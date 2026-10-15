package com.example

import android.util.Log
import android.util.Base64
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.json.JSONObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val DANI_TAG = "Danimados"

private fun unpackPackedJS(html: String): String? {
    val evalHeaderRegex = Regex("""eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*d\s*\)\s*\{[\s\S]*?\}\s*\(\s*'""")
    val evalHeader = evalHeaderRegex.find(html) ?: return null
    val argStart = evalHeader.range.last + 1

    val argsRegex = Regex("""',(\d+),(\d+),'([^']+)'\.split\('\|'\)""")
    val argsMatch = argsRegex.find(html, argStart) ?: return null

    val packedP = html.substring(argStart, argsMatch.range.first)
    val base = argsMatch.groupValues[1].toIntOrNull() ?: return null
    val count = argsMatch.groupValues[2].toIntOrNull() ?: return null
    val kRaw = argsMatch.groupValues[3]

    val k = kRaw.split("|").toTypedArray()

    val result = StringBuilder(packedP)
    for (idx in count - 1 downTo 0) {
        val key = idx.toString(base)
        val value = k.getOrElse(idx) { "" }
        if (key.isNotEmpty() && value.isNotEmpty()) {
            val pattern = Regex("\\b${Regex.escape(key)}\\b")
            val replacement = Regex.escapeReplacement(value)
            val replaced = pattern.replace(result, replacement)
            result.clear()
            result.append(replaced)
        }
    }

    return result.toString().replace("\\'", "'")
}

private suspend fun scanDaniHtmlForSubs(html: String, baseUrl: String, subtitleCallback: (SubtitleFile) -> Unit, seen: MutableSet<String> = mutableSetOf()) {
    Regex("""["']([^"']*\.(?:vtt|srt)(?:\?[^"']*)?)["']""", RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
        val subUrl = match.groupValues[1]
        val cleanSubUrl = if (subUrl.startsWith("http")) subUrl else "$baseUrl/$subUrl"
        if (seen.add(cleanSubUrl)) {
            Log.d(DANI_TAG, "[PageSubs] SubtÃ­tulo: $cleanSubUrl")
            subtitleCallback.invoke(newSubtitleFile("EspaÃ±ol", cleanSubUrl))
        }
    }
}

private suspend fun tryExtractDaniSubsFromM3u8(
    m3u8Url: String,
    referer: String?,
    subtitleCallback: (SubtitleFile) -> Unit,
) {
    try {
        val subHeaders = mutableMapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        )
        if (referer != null) subHeaders["Referer"] = referer
        val manifest = app.get(m3u8Url, headers = subHeaders, timeout = 20L).text
        val baseUrl = m3u8Url.substringBeforeLast("/")
        var count = 0
        Regex("""#EXT-X-MEDIA:TYPE=SUBTITLES[^#]*""", RegexOption.IGNORE_CASE).findAll(manifest).forEach { mediaBlock ->
            val lang = Regex("""LANGUAGE\s*=\s*"([^"]*)""", RegexOption.IGNORE_CASE).find(mediaBlock.value)?.groupValues?.get(1) ?: "EspaÃ±ol"
            val uri = Regex("""URI\s*=\s*"([^"]*)""", RegexOption.IGNORE_CASE).find(mediaBlock.value)?.groupValues?.get(1)
            if (uri != null) {
                val subUrl = if (uri.startsWith("http")) uri else "$baseUrl/$uri"
                Log.d(DANI_TAG, "[M3u8Subs] lang=$lang, url=${subUrl.take(120)}")
                subtitleCallback.invoke(newSubtitleFile(lang, subUrl))
                count++
            }
        }
        Log.d(DANI_TAG, "[M3u8Subs] $count subs")
    } catch (e: Exception) {
        Log.d(DANI_TAG, "[M3u8Subs] Error manifest: ${e.message}")
    }
}

internal suspend fun tryVidHideProExtractDanimados(
    url: String,
    referer: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    return try {
        Log.d(DANI_TAG, "[VH-Pro] trying $url")
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
            "Accept" to "*/*",
            "Referer" to referer,
        )
        val res = app.get(url, headers = headers, timeout = 20L)
        if (!res.isSuccessful) {
            Log.w(DANI_TAG, "[VH-Pro] HTTP ${res.code}")
            return false
        }
        val html = res.text
        Log.d(DANI_TAG, "[VH-Pro] HTTP 200 len=${html.length}")

        val unpacked = unpackPackedJS(html)
        if (unpacked.isNullOrBlank()) {
            Log.w(DANI_TAG, "[VH-Pro] packer unpack failed")
            return false
        }

        val linksMap = mutableMapOf<String, String>()
        Regex("""links\s*[=:]\s*\{([^}]+)\}""", RegexOption.DOT_MATCHES_ALL).find(unpacked)?.let { block ->
            val inner = block.groupValues[1]
            Regex("""(?:["'])?([A-Za-z0-9]+)(?:["'])?\s*:\s*"([^"]+)""").findAll(inner).forEach { m ->
                if (m.groupValues[1].startsWith("hls")) linksMap[m.groupValues[1]] = m.groupValues[2]
            }
        }
        if (linksMap.isEmpty()) {
            Regex(""""(hls\d)"\s*:\s*"([^"]+)""").findAll(unpacked).forEach { m ->
                linksMap[m.groupValues[1]] = m.groupValues[2]
            }
        }

        if (linksMap.isEmpty()) {
            Log.w(DANI_TAG, "[VH-Pro] no hls links in unpacked JS")
            return false
        }

        val preferOrder = listOf("hls2", "hls3", "hls4")
        val orderedKeys = preferOrder.filter { linksMap.containsKey(it) } + linksMap.keys.filter { it !in preferOrder }

        val vidHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
            "Referer" to url,
            "Origin" to url.substringBeforeLast("/"),
        )

        data class Variant(val key: String, val url: String)
        val probeHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
            "Referer" to url,
        )
        val resolved = orderedKeys.mapNotNull { key ->
            var u = linksMap[key] ?: return@mapNotNull null
            if (u.startsWith("//")) u = "https:$u"
            if (u.startsWith("/")) {
                u = "https://vidhidepro.com$u"
                Log.d(DANI_TAG, "[VH-Pro] relative URL, prepended base: ${u.take(120)}")
            }
            Variant(key, u)
        }
        val reachable = java.util.Collections.synchronizedSet(mutableSetOf<Variant>())
        resolved.amap { v ->
            try {
                val code = withTimeoutOrNull(10000L) {
                    app.get(v.url, headers = probeHeaders, timeout = 8L, allowRedirects = false).code
                } ?: -1
                Log.d(DANI_TAG, "[VH-Pro] probe ${v.key} -> $code")
                if (code in 200..399) reachable.add(v)
            } catch (_: Exception) {}
        }
        val toEmit = if (reachable.isNotEmpty()) {
            orderedKeys.mapNotNull { k -> reachable.firstOrNull { it.key == k } }
        } else {
            Log.w(DANI_TAG, "[VH-Pro] ningÃºn master responde, emitiendo todos igual")
            resolved
        }

        var firstM3u8: String? = null
        for (v in toEmit) {
            if (firstM3u8 == null) firstM3u8 = v.url
            Log.d(DANI_TAG, "[VH-Pro] emit ${v.key} url=${v.url.take(120)}")
            callback(newExtractorLink("Danimados", "Danimados - VidHidePro - ${v.key}", v.url, ExtractorLinkType.M3U8) {
                this.referer = url
                this.headers = vidHeaders
            })
        }
        Log.d(DANI_TAG, "[VH-Pro] emitted ${toEmit.size} variants")

        val seenSubs = mutableSetOf<String>()
        scanDaniHtmlForSubs(html, url.substringBeforeLast("/"), subtitleCallback, seenSubs)
        scanDaniHtmlForSubs(unpacked, url.substringBeforeLast("/"), subtitleCallback, seenSubs)
        firstM3u8?.let { tryExtractDaniSubsFromM3u8(it, url, subtitleCallback) }
        true
    } catch (e: Exception) {
        Log.e(DANI_TAG, "[VH-Pro] error: ${e.message}")
        false
    }
}

// ================================ VOE ================================

data class DamiVoeDecrypted(
    @JsonProperty("source") val source: String? = null,
    @JsonProperty("direct_access_url") val directAccessUrl: String? = null,
)

private fun decryptVoeF7(p8: String, quiet: Boolean = false): DamiVoeDecrypted? {
    return try {
        val vF = rot13(p8)
        val vF2 = replacePatterns(vF)
        val vF3 = removeUnderscores(vF2)
        val vF4 = base64Decode(vF3)
        val vF5 = charShift(vF4, 3)
        val vF6 = vF5.reversed()
        val vAtob = base64Decode(vF6)
        parseJson<DamiVoeDecrypted>(vAtob)
    } catch (e: Exception) {
        if (!quiet) Log.e(DANI_TAG, "[Voe] decrypt error: ${e.message}")
        null
    }
}

private fun rot13(input: String): String {
    return input.map { c ->
        when (c) {
            in 'A'..'Z' -> ((c - 'A' + 13) % 26 + 'A'.code).toChar()
            in 'a'..'z' -> ((c - 'a' + 13) % 26 + 'a'.code).toChar()
            else -> c
        }
    }.joinToString("")
}

private fun replacePatterns(input: String): String {
    val patterns = listOf("@\$", "^^", "~@", "%?", "*~", "!!", "#&")
    return patterns.fold(input) { result, pattern ->
        result.replace(Regex(Regex.escape(pattern)), "_")
    }
}

private fun removeUnderscores(input: String): String = input.replace("_", "")

private fun charShift(input: String, shift: Int): String {
    return input.map { (it.code - shift).toChar() }.joinToString("")
}

private fun base64Decode(input: String): String {
    return String(android.util.Base64.decode(input, android.util.Base64.DEFAULT))
}

private suspend fun parseVoeHtml(
    html: String,
    pageUrl: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
    cookies: Map<String, String>? = null,
): Boolean {
    var workHtml = html
    if (html.contains("altcha-widget") || html.contains("Confirm you&#039;re human")) {
        Log.d(DANI_TAG, "[Voe] ALTCHA detectado, resolviendo PoW: ${pageUrl.take(100)}")
        workHtml = solveDaniAltcha(html, pageUrl, cookies) ?: run {
            Log.w(DANI_TAG, "[Voe] ALTCHA no resuelto, sin links")
            return false
        }
    }
    val pageOrigin = try {
        val u = java.net.URL(pageUrl)
        "${u.protocol}://${u.host}"
    } catch (_: Exception) { pageUrl }

    var encodedString: String? = Regex(
        """<script[^>]*type=["']application/json["'][^>]*>(.*?)</script>""",
        RegexOption.DOT_MATCHES_ALL
    ).find(workHtml)?.groupValues?.get(1)?.trim()
        ?.takeIf { it.contains("[\"") }
        ?.substringAfter("[\"")
        ?.substringBeforeLast("\"]")

    if (encodedString == null) {
        val scripts = Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(workHtml).map { it.groupValues[1] }
        outer@ for (body in scripts) {
            for (m in Regex("""["']([A-Za-z0-9+/=@\^~\*\?&#!%$]{100,})["']""").findAll(body)) {
                val cand = m.groupValues[1]
                val test = decryptVoeF7(cand, quiet = true)
                if (test?.source != null || test?.directAccessUrl != null) {
                    encodedString = cand
                    break@outer
                }
            }
        }
    }

    if (encodedString == null) {
        Log.w(DANI_TAG, "[Voe] encoded string not found: ${pageUrl.take(100)}")
        return false
    }

    val decryptedJson = decryptVoeF7(encodedString)
    val m3u8 = decryptedJson?.source
    val mp4 = decryptedJson?.directAccessUrl
    var emitted = false
    if (m3u8 != null) {
        Log.d(DANI_TAG, "[Voe] Found M3U8: ${m3u8.take(100)}")
        M3u8Helper.generateM3u8(
            "Danimados",
            m3u8,
            "$pageOrigin/",
            headers = mapOf("Origin" to "$pageOrigin/"),
            name = "Danimados [Voe]",
        ).forEach(callback)
        emitted = true
    }
    if (mp4 != null) {
        Log.d(DANI_TAG, "[Voe] Found MP4: ${mp4.take(100)}")
        callback.invoke(
            newExtractorLink("Danimados", "Danimados [Voe (MP4)]", mp4, INFER_TYPE) {
                this.referer = pageUrl
                this.quality = Qualities.Unknown.value
            }
        )
        emitted = true
    }
    if (!emitted) Log.w(DANI_TAG, "[Voe] No source found after decryption")
    return emitted
}

internal suspend fun tryVoeExtractDanimados(
    url: String,
    referer: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    return try {
        Log.d(DANI_TAG, "[Voe] trying $url")
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer" to referer,
        )
        suspend fun tryMirrors(): Boolean {
            val hashPath = try { java.net.URL(url).path } catch (_: Exception) { "" }
            if (!hashPath.startsWith("/e/")) return false

            val mirrors = listOf("teresapoliticallearn.com", "yip.su", "tubelessceliolymph.com")
            val mirrorOk = java.util.concurrent.atomic.AtomicBoolean(false)
            withTimeoutOrNull(20000L) {
                mirrors.amap { mirror ->
                    if (mirrorOk.get()) return@amap
                    try {
                        val mUrl = "https://$mirror$hashPath"
                        if (mUrl == url) return@amap
                        Log.d(DANI_TAG, "[Voe] probando mirror: $mUrl")
                        val mResp = app.get(mUrl, headers = headers + ("Referer" to url), timeout = 10L)
                        if (parseVoeHtml(mResp.text, mUrl, subtitleCallback, callback, mResp.cookies)) {
                            Log.d(DANI_TAG, "[Voe] mirror $mirror OK")
                            mirrorOk.set(true)
                        }
                    } catch (_: Exception) {}
                }
            }
            return mirrorOk.get()
        }
        val res = app.get(url, headers = headers, timeout = 15L, allowRedirects = false)
        val redirectUrl = res.headers["Location"] ?: res.url
        Log.d(DANI_TAG, "[Voe] status=${res.code} redirect=$redirectUrl")

        var finalUrl = if (res.code in 301..399) {
            val h2 = headers + ("Referer" to url)
            try { app.get(redirectUrl, headers = h2, timeout = 15L).url } catch (_: Exception) { redirectUrl }
        } else {
            redirectUrl
        }

        var finalResp = app.get(finalUrl, headers = headers, timeout = 15L)
        var finalHtml = finalResp.text
        var finalCookies = finalResp.cookies


        val jsRedirectRegex = Regex("""window\.location\.href\s*=\s*['\"](https?://[^'\"]+)['\"]""")
        var voeHops = 0
        while (voeHops++ < 3) {
            val target = jsRedirectRegex.find(finalHtml)?.groupValues?.get(1)?.trim()
                ?.replace("\\/", "/")?.replace("\\u002F", "/")?.replace("\\u002f", "/")
            if (target.isNullOrBlank() || target == finalUrl) break
            Log.d(DANI_TAG, "[Voe] JS redirect ($voeHops) -> $target")
            val rr = app.get(target, headers = headers + ("Referer" to finalUrl), timeout = 15L)
            finalCookies = finalCookies + rr.cookies
            finalUrl = rr.url
            finalHtml = rr.text
        }
        Log.d(DANI_TAG, "[Voe] finalUrl=$finalUrl")

        if (finalHtml.contains("captcha") || finalHtml.contains("CAPTCHA") || finalHtml.contains("cf-challenge") || finalHtml.contains("altcha-widget")) {
            Log.w(DANI_TAG, "[Voe] challenge detectado en $finalUrl")
            if (parseVoeHtml(finalHtml, finalUrl, subtitleCallback, callback, finalCookies)) return true
            return tryMirrors()
        }

        val m3u8 = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(finalHtml)?.groupValues?.get(1)
        val mp4 = Regex("""(https?://[^"'\s]+\.mp4[^"'\s]*)""").find(finalHtml)?.groupValues?.get(1)
        val videoUrl = m3u8 ?: mp4
        if (videoUrl == null) {
            Log.w(DANI_TAG, "[Voe] no m3u8/mp4 found in $finalUrl")
            if (parseVoeHtml(finalHtml, finalUrl, subtitleCallback, callback, finalCookies)) return true
            return tryMirrors()
        }
        Log.d(DANI_TAG, "[Voe] found ${if (m3u8 != null) "m3u8" else "mp4"}: ${videoUrl.take(120)}")
        callback(newExtractorLink("Danimados", "Danimados [Voe]", videoUrl, ExtractorLinkType.M3U8) {
            this.referer = finalUrl
            this.headers = headers
        })
        return true
    } catch (e: Exception) {
        Log.e(DANI_TAG, "[Voe] error: ${e.message}")
        false
    }
}

// ================================ VOE ALTCHA (port anti-freeze: mutex + 2 hilos) ================================

private val daniPowMutex = Mutex()

private suspend fun solveDaniAltcha(
    html: String,
    pageUrl: String,
    cookies: Map<String, String>?,
): String? {
    return try {
        val voeHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
        )
        val inputTag = Regex("""<input[^>]*name=["']?_token["']?[^>]*>""").find(html)?.value
        if (inputTag == null) {
            Log.w(DANI_TAG, "[Voe] ALTCHA sin input _token")
            return null
        }
        val token = Regex("""value=["']?([^"'\s>]+)""").find(inputTag)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() }
        if (token == null) {
            Log.w(DANI_TAG, "[Voe] ALTCHA sin token")
            return null
        }
        val challengeUrl = Regex("""<altcha-widget[^>]*challenge=["']?([^"'\s>]+)""").find(html)
            ?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        if (challengeUrl == null) {
            Log.w(DANI_TAG, "[Voe] ALTCHA sin challenge URL")
            return null
        }

        var jar = cookies ?: emptyMap()
        if (jar.isEmpty()) {
            val fresh = app.get(pageUrl, headers = voeHeaders, referer = pageUrl, timeout = 30L)
            jar = fresh.cookies
        }
        fun cookieHeader(): String = jar.entries.joinToString("; ") { "${it.key}=${it.value}" }

        var chalResp = app.get(
            challengeUrl,
            headers = voeHeaders + ("Referer" to pageUrl) + ("Cookie" to cookieHeader()),
            timeout = 30L,
        )
        var chalText = try { chalResp.text } catch (_: Exception) { "" }
        Log.d(DANI_TAG, "[Voe] challenge -> code=${chalResp.code} len=${chalText.length} cookies=${jar.keys}")
        if (!chalText.trimStart().startsWith("{")) {
            Log.d(DANI_TAG, "[Voe] challenge sin JSON, reintento sin cookies: ${chalText.take(120)}")
            chalResp = app.get(
                challengeUrl,
                headers = voeHeaders + ("Referer" to pageUrl),
                timeout = 30L,
            )
            chalText = try { chalResp.text } catch (_: Exception) { "" }
            Log.d(DANI_TAG, "[Voe] challenge retry -> code=${chalResp.code} len=${chalText.length}")
            if (!chalText.trimStart().startsWith("{")) {
                Log.w(DANI_TAG, "[Voe] challenge no-JSON: ${chalText.take(160)}")
                return null
            }
        }
        val solution = solveDaniAltchaPow(chalText) ?: return null
        val params = Regex(""""parameters"\s*:\s*(\{[^{}]*\})""").find(chalText)
            ?.groupValues?.get(1) ?: return null
        val sig = Regex(""""signature"\s*:\s*"([^"]+)"""").find(chalText)
            ?.groupValues?.get(1) ?: return null
        val payload = "{\"challenge\":{\"parameters\":$params,\"signature\":\"$sig\"}," +
            "\"solution\":{\"counter\":${solution.first},\"derivedKey\":\"${solution.second}\",\"time\":1}}"
        val encoded = URLEncoder.encode(
            Base64.encodeToString(payload.toByteArray(Charsets.UTF_8), Base64.NO_WRAP), "UTF-8"
        )
        val origin = try {
            java.net.URL(pageUrl).let { "${it.protocol}://${it.host}" }
        } catch (_: Exception) { pageUrl }
        val postResp = app.post(
            pageUrl,
            headers = voeHeaders + ("Origin" to origin) + ("Referer" to pageUrl) + ("Cookie" to cookieHeader()),
            data = mapOf("_token" to token, "access" to "0", "altcha" to encoded),
            timeout = 30L,
        )
        val postBody = try { postResp.text } catch (_: Exception) { "" }
        Log.d(DANI_TAG, "[Voe] POST solucion -> code=${postResp.code} len=${postBody.length} gate=${postBody.contains("altcha-widget")}")
        postBody.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        Log.w(DANI_TAG, "[Voe] ALTCHA error: ${e.message}")
        null
    }
}

private suspend fun solveDaniAltchaPow(challengeText: String, maxAttempts: Int = 1024): Pair<Long, String>? {
    fun hexToBytes(hex: String): ByteArray? {
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        return try {
            ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } catch (_: Exception) { null }
    }
    fun ByteArray.toHex(): String {
        val hexChars = "0123456789abcdef".toCharArray()
        val out = CharArray(size * 2)
        for (i in indices) {
            val v = this[i].toInt() and 0xFF
            out[i * 2] = hexChars[v ushr 4]
            out[i * 2 + 1] = hexChars[v and 0x0F]
        }
        return String(out)
    }
    val nonceHex = Regex(""""nonce"\s*:\s*"([0-9a-fA-F]+)"""").find(challengeText)?.groupValues?.get(1)
    val saltHex = Regex(""""salt"\s*:\s*"([0-9a-fA-F]+)"""").find(challengeText)?.groupValues?.get(1)
    val prefix = Regex(""""keyPrefix"\s*:\s*"([0-9a-fA-F]+)"""").find(challengeText)?.groupValues?.get(1)?.lowercase()
    val cost = Regex(""""cost"\s*:\s*(\d+)""").find(challengeText)?.groupValues?.get(1)?.toIntOrNull()
    val keyLength = Regex(""""keyLength"\s*:\s*(\d+)""").find(challengeText)?.groupValues?.get(1)?.toIntOrNull() ?: 32
    if (nonceHex == null || saltHex == null || prefix == null || cost == null) {
        Log.w(DANI_TAG, "[Voe] ALTCHA params incompletos (nonce=${nonceHex != null} salt=${saltHex != null} prefix=$prefix cost=$cost): ${challengeText.take(160)}")
        return null
    }
    val nonce = hexToBytes(nonceHex) ?: run {
        Log.w(DANI_TAG, "[Voe] ALTCHA nonce inválido")
        return null
    }
    val salt = hexToBytes(saltHex) ?: run {
        Log.w(DANI_TAG, "[Voe] ALTCHA salt inválida")
        return null
    }
    if (prefix.isEmpty() || cost <= 0) return null

    val t0 = System.currentTimeMillis()
    val nThreads = 2
    val winner = AtomicLong(-1)
    val winnerHex = AtomicReference<String?>(null)
    daniPowMutex.withLock {
    coroutineScope {
        (0 until nThreads).map { t ->
            async(Dispatchers.Default) {
                val mac = Mac.getInstance("HmacSHA256")
                var counter = t
                while (counter < maxAttempts && winner.get() < 0) {
                    ensureActive()
                    val password = ByteArray(nonce.size + 4)
                    System.arraycopy(nonce, 0, password, 0, nonce.size)
                    password[nonce.size] = (counter ushr 24).toByte()
                    password[nonce.size + 1] = (counter ushr 16).toByte()
                    password[nonce.size + 2] = (counter ushr 8).toByte()
                    password[nonce.size + 3] = counter.toByte()
                    val derivedHex = pbkdf2Sha256Dani(password, salt, cost, keyLength, mac).toHex()
                    if (derivedHex.startsWith(prefix)) {
                        if (winner.compareAndSet(-1, counter.toLong())) {
                            winnerHex.set(derivedHex)
                        }
                        return@async
                    }
                    counter += nThreads
                }
            }
        }.awaitAll()
    }
    }
    val w = winner.get()
    val wh = winnerHex.get()
    if (w >= 0 && wh != null) {
        Log.d(DANI_TAG, "[Voe] ALTCHA resuelto en $w intentos (${System.currentTimeMillis() - t0}ms, ${nThreads}h)")
        return w to wh
    }
    Log.w(DANI_TAG, "[Voe] ALTCHA PoW agotado ($maxAttempts)")
    return null
}

private fun pbkdf2Sha256Dani(password: ByteArray, salt: ByteArray, iterations: Int, keyLength: Int, mac: Mac): ByteArray {
    mac.init(SecretKeySpec(password, "HmacSHA256"))
    val hashLength = 32
    val output = ByteArray(keyLength)
    val mixed = ByteArray(hashLength)
    val blockIndex = ByteArray(4)
    var current = ByteArray(hashLength)
    var next = ByteArray(hashLength)
    var offset = 0
    var block = 1
    while (offset < keyLength) {
        blockIndex[0] = (block ushr 24).toByte()
        blockIndex[1] = (block ushr 16).toByte()
        blockIndex[2] = (block ushr 8).toByte()
        blockIndex[3] = block.toByte()
        mac.reset()
        mac.update(salt)
        mac.update(blockIndex)
        mac.doFinal(current, 0)
        System.arraycopy(current, 0, mixed, 0, hashLength)
        for (i in 1 until iterations) {
            mac.update(current)
            mac.doFinal(next, 0)
            for (j in 0 until hashLength) mixed[j] = (mixed[j].toInt() xor next[j].toInt()).toByte()
            val swap = current
            current = next
            next = swap
        }
        val length = minOf(hashLength, keyLength - offset)
        System.arraycopy(mixed, 0, output, offset, length)
        offset += length
        block++
    }
    return output
}

// ================================ StreamWish ================================

private const val DANI_SW_READY_JS = "h.includes('.m3u8')||h.includes('jwplayer')"
private const val DANI_DUMP_JS = "(function(){try{NativeBridge.onHtml(document.documentElement.outerHTML);}catch(e){NativeBridge.onHtml('ERR:'+e);}})()"

private val daniWebViewMutex = Mutex()

private suspend fun renderDaniWebView(pageUrl: String, referer: String?, waitMs: Long = 12000L, readyJs: String? = null): String? {
    daniWebViewMutex.withLock {
        return withContext(Dispatchers.Main) {
        val appCtx = DanimadosProvider.pluginContext?.applicationContext ?: run {
            Log.w(DANI_TAG, "[WebView] sin context")
            return@withContext null
        }
        var webView: WebView? = null
        val mainHandler = Handler(Looper.getMainLooper())
        try {
            webView = WebView(appCtx)
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
                userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"
            }
            val deferred = CompletableDeferred<String?>()
            val polls = java.util.concurrent.atomic.AtomicInteger(0)
            val maxPolls = (waitMs / 2000L).toInt().coerceAtLeast(1)
            fun dump() {
                if (deferred.isCompleted) return
                try {
                    webView?.evaluateJavascript(DANI_DUMP_JS, null)
                } catch (_: Exception) {
                    if (!deferred.isCompleted) deferred.complete(null)
                }
            }
            fun pollOnce() {
                if (deferred.isCompleted) return
                try {
                    webView?.evaluateJavascript(
                        "(function(){try{var h=document.documentElement.outerHTML;NativeBridge.onPoll(($readyJs));}catch(e){NativeBridge.onPoll(false);}})()",
                        null
                    )
                } catch (_: Exception) {
                    if (!deferred.isCompleted) deferred.complete(null)
                }
            }
            webView.addJavascriptInterface(object {
                @JavascriptInterface
                fun onHtml(html: String) {
                    if (!deferred.isCompleted) deferred.complete(html)
                }

                @JavascriptInterface
                fun onPoll(ready: Boolean) {
                    mainHandler.post {
                        if (deferred.isCompleted) return@post
                        if (ready) {
                            Log.d(DANI_TAG, "[WebView] listo antes de tiempo, dumpeando")
                            dump()
                            return@post
                        }
                        if (polls.incrementAndGet() >= maxPolls) {
                            dump()
                        } else {
                            mainHandler.postDelayed({ pollOnce() }, 2000L)
                        }
                    }
                }
            }, "NativeBridge")
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    polls.set(0)
                    if (readyJs != null) {
                        mainHandler.postDelayed({ pollOnce() }, 2000L)
                    } else {
                        mainHandler.postDelayed({ dump() }, waitMs)
                    }
                }

                override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                    if (!deferred.isCompleted) deferred.complete(null)
                }
            }
            if (!referer.isNullOrBlank()) webView.loadUrl(pageUrl, mapOf("Referer" to referer))
            else webView.loadUrl(pageUrl)
            Log.d(DANI_TAG, "[WebView] renderizando ${pageUrl.take(100)}")
            withTimeoutOrNull(waitMs + 15000L) { deferred.await() }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(DANI_TAG, "[WebView] error grave: ${t::class.simpleName}: ${t.message}")
            null
        } finally {
            try { mainHandler.removeCallbacksAndMessages(null) } catch (_: Exception) {}
            try { webView?.destroy() } catch (_: Exception) {}
        }
        }
    }
}

private fun unpackDeanEdwardsDani(packed: String, base: Int, count: Int, dictRaw: String): String? {
    return try {
        val k = dictRaw.split("|").toTypedArray()
        val result = StringBuilder(packed)
        for (idx in count - 1 downTo 0) {
            val key = idx.toString(base)
            val value = k.getOrElse(idx) { "" }
            if (key.isNotEmpty() && value.isNotEmpty()) {
                val replaced = Regex("\\b${Regex.escape(key)}\\b").replace(result, Regex.escapeReplacement(value))
                result.clear()
                result.append(replaced)
            }
        }
        result.toString().replace("\\'", "'")
    } catch (_: Exception) { null }
}

private suspend fun parseDaniStreamwishHtml(
    html: String,
    pageUrl: String,
    linkReferer: String,
    emit: suspend (String) -> Unit,
): Boolean {
    return try {
        val m3u8Regex = Regex("""(https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)""")
        val mp4Regex = Regex("""(https?://[^"'\s<>]+\.(?:mp4|m4v)[^"'\s<>]*)""")
        val fileRegex = Regex("""(?:file|src)\s*:\s*["']((?:https?:)?//[^"']+)["']""")
        var found = false
        fun resolveUrl(u: String): String {
            var r = u.replace("\\/", "/").trim()
            if (r.startsWith("//")) r = "https:$r"
            if (r.startsWith("/")) {
                val base = try {
                    val uu = java.net.URL(pageUrl)
                    "${uu.protocol}://${uu.host}"
                } catch (_: Exception) { "" }
                r = base + r
            }
            return r
        }
        suspend fun emitFile(raw: String) {
            val f = resolveUrl(raw)
            if (f.contains(".m3u8") || f.contains(".mp4") || f.contains(".m4v")) {
                Log.d(DANI_TAG, "[SW] file: ${f.take(120)}")
                emit(f)
                found = true
            }
        }
        for (m in m3u8Regex.findAll(html)) {
            Log.d(DANI_TAG, "[SW] M3U8: ${m.value.take(120)}")
            emit(m.value)
            found = true
        }
        if (!found) {
            for (m in fileRegex.findAll(html)) emitFile(m.groupValues[1])
        }
        if (!found) {
            for (m in mp4Regex.findAll(html)) {
                Log.d(DANI_TAG, "[SW] MP4: ${m.value.take(120)}")
                emit(m.value)
                found = true
            }
        }
        if (!found) {
            val packerRegex = Regex("""\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)
            for (pm in packerRegex.findAll(html)) {
                try {
                    val decoded = unpackDeanEdwardsDani(
                        pm.groupValues[1],
                        pm.groupValues[2].toIntOrNull() ?: 36,
                        pm.groupValues[3].toIntOrNull() ?: 0,
                        pm.groupValues[4]
                    ) ?: continue
                    for (m in m3u8Regex.findAll(decoded)) {
                        Log.d(DANI_TAG, "[SW] M3U8 (eval): ${m.value.take(120)}")
                        emit(m.value)
                        found = true
                    }
                    if (!found) for (m in fileRegex.findAll(decoded)) emitFile(m.groupValues[1])
                } catch (_: Exception) {}
                if (found) break
            }
        }
        if (!found) {
            Log.w(DANI_TAG, "[SW] No M3U8/MP4 pageHasJW=${html.contains("jwplayer")} hasSources=${html.contains("sources")} hasEval=${html.contains("eval(")}")
        }
        found
    } catch (e: Exception) {
        Log.e(DANI_TAG, "[SW] Error: ${e.message}")
        false
    }
}

internal suspend fun tryStreamwishDanimados(
    url: String,
    referer: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean {
    var count = 0
    val collected = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())
    suspend fun wrapped(rawUrl: String) {
        count++
        val type = if (rawUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE
        callback(newExtractorLink("Danimados", "Danimados [StreamWish]", rawUrl, type) {
            this.referer = referer
            this.headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
                "Referer" to referer,
            )
        })
    }

    val fixed = url
        .replaceFirst("https://hglink.to", "https://streamwish.to")
        .replaceFirst("http://hglink.to", "https://streamwish.to")
        .replaceFirst("https://swdyu.com", "https://streamwish.to")
        .replaceFirst("https://cybervynx.com", "https://streamwish.to")
        .replaceFirst("https://dumbalag.com", "https://streamwish.to")
        .replaceFirst("https://awish.pro", "https://streamwish.to")
    return try {
        Log.d(DANI_TAG, "[SW] trying $fixed")
        try {
            val staticHtml = app.get(
                fixed,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
                    "Referer" to referer,
                ),
                timeout = 15L
            ).text
            if (parseDaniStreamwishHtml(staticHtml, fixed, referer) { wrapped(it) }) {
                Log.d(DANI_TAG, "[SW] parseo estático OK: $fixed")
                return true
            }
        } catch (e: Exception) {
            Log.d(DANI_TAG, "[SW] parseo estático falló: ${e.message}")
        }
        if (loadExtractor(fixed, referer, subtitleCallback) { collected.add(it) }) {
            for (link in collected) {
                count++
                Log.d(DANI_TAG, "[SW] loadExtractor -> ${link.source} ${link.url.take(80)}")
                callback(newExtractorLink("Danimados", "Danimados [StreamWish]", link.url) {
                    this.quality = link.quality
                    this.type = link.type
                    this.referer = link.referer
                    this.headers = link.headers
                    this.extractorData = link.extractorData
                })
            }
        }
        if (count == 0) {
            Log.d(DANI_TAG, "[SW] probando WebView: $fixed")
            val rendered = renderDaniWebView(fixed, referer, readyJs = DANI_SW_READY_JS)
            if (rendered != null && parseDaniStreamwishHtml(rendered, fixed, referer) { wrapped(it) }) {
                Log.d(DANI_TAG, "[SW] WebView fallback emitió links")
                return true
            }
        }
        count > 0
    } catch (e: Exception) {
        Log.e(DANI_TAG, "[SW] error: ${e.message}")
        count > 0
    }
}

// ================================ Ok.ru (port Donghualife: data-options -> HLS) ================================

internal suspend fun tryOkruDanimados(
    url: String,
    referer: String,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val videoId = Regex("""ok\.ru/(?:videoembed|video)/(\d+)""").find(url)?.groupValues?.getOrNull(1)
        ?: return false
    val embedUrl = "https://ok.ru/videoembed/$videoId"
    val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
        "Referer" to "https://ok.ru/",
    )
    val metadata = try {
        val doc = app.get(embedUrl, headers = headers, timeout = 30L).document
        val raw = doc.selectFirst("[data-options]")?.attr("data-options")
        if (raw.isNullOrBlank()) null
        else JSONObject(raw).optJSONObject("flashvars")?.optJSONObject("metadata")
    } catch (e: Exception) {
        Log.w(DANI_TAG, "[Okru] $videoId falló: ${e.message}")
        null
    }
    var emitted = false
    val hls = metadata?.optString("hlsManifestUrl")?.takeIf { it.isNotBlank() }
    if (hls != null) {
        Log.d(DANI_TAG, "[Okru] $videoId -> HLS")
        callback(newExtractorLink("Danimados", "Danimados [ok.ru]", hls, ExtractorLinkType.M3U8) {
            this.referer = embedUrl
            this.quality = Qualities.P1080.value
        })
        emitted = true
    }
    if (!emitted) {
        val videos = metadata?.optJSONArray("videos")
        if (videos != null) {
            for (i in videos.length() - 1 downTo 0) {
                val v = videos.optJSONObject(i) ?: continue
                val u = v.optString("url").takeIf { it.isNotBlank() } ?: continue
                val vn = v.optString("name")
                callback(newExtractorLink("Danimados", "Danimados [ok.ru $vn]", u, ExtractorLinkType.VIDEO) {
                    this.referer = embedUrl
                    this.quality = when (vn.lowercase()) {
                        "full" -> Qualities.P1080.value
                        "hd" -> Qualities.P720.value
                        "sd" -> Qualities.P480.value
                        "low" -> Qualities.P360.value
                        "lowest", "mobile" -> Qualities.P240.value
                        else -> Qualities.Unknown.value
                    }
                })
                emitted = true
            }
        }
    }
    if (!emitted) Log.w(DANI_TAG, "[Okru] $videoId: sin URLs (video borrado/privado?)")
    return emitted
}

// ================================ Byse ================================

internal suspend fun tryByseHttpDanimados(
    embedUrl: String,
    parent: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    return try {
        val site = runCatching { "https://${java.net.URI(parent).host}" }.getOrDefault("https://danimados.cc")
        Log.d(DANI_TAG, "[Byse] flujo HTTP (site=$site) $embedUrl")
        val sources = ByseHttpExtractor().extract(embedUrl, parent, site)
        var found = false
        for (s in sources) {
            Log.d(DANI_TAG, "[Byse] source: ${s.url.take(140)} label=${s.label}")
            for (sub in s.subtitles) subtitleCallback(sub)
            val type = if (s.url.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE
            callback(
                newExtractorLink("Danimados", "Danimados - Byse${s.label?.let { " - $it" } ?: ""}", s.url, type) {
                    this.referer = site
                    this.headers = mapOf("Origin" to site)
                }
            )
            found = true
        }
        if (found) Log.d(DANI_TAG, "[Byse] done vÃ­a HTTP")
        found
    } catch (e: Exception) {
        Log.w(DANI_TAG, "[Byse] HTTP error: ${e.message}")
        false
    }
}
