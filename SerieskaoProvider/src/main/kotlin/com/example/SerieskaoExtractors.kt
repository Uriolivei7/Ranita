package com.example

import android.util.Base64
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlinx.coroutines.*
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val KAO_TAG = "SeriesKao"

private val HEX_CHARS = "0123456789abcdef".toCharArray()

private fun ByteArray.toHexFast(): String {
    val c = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        c[i * 2] = HEX_CHARS[v ushr 4]
        c[i * 2 + 1] = HEX_CHARS[v and 0x0F]
    }
    return String(c)
}

internal suspend fun solveEmbed69PoW(challenge: String, salt: String): ByteArray? {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    var nonce = 0L
    val maxAttempts = 500000L
    while (nonce < maxAttempts) {
        val input = "$challenge$nonce".toByteArray(Charsets.UTF_8)
        val hash = md.digest(input).toHexFast()
        if (hash.startsWith("000")) {
            Log.d(KAO_TAG, "PoW nonce=$nonce hash=${hash.take(8)} attempts=$nonce")
            return java.security.MessageDigest.getInstance("SHA-256")
                .digest("$challenge$nonce$salt".toByteArray(Charsets.UTF_8))
        }
        nonce++
    }
    Log.e(KAO_TAG, "PoW no solution after $maxAttempts attempts")
    return null
}

internal fun decryptAESLocal(encryptedBase64: String, aesKey: ByteArray): String? {
    return try {
        val raw = Base64.decode(encryptedBase64, Base64.NO_WRAP)
        if (raw.size < 17) return null
        val iv = raw.copyOfRange(0, 16)
        val ciphertext = raw.copyOfRange(16, raw.size)
        if (ciphertext.size % 16 != 0) return null
        val keySpec = SecretKeySpec(aesKey.copyOfRange(0, 32), "AES")
        try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e1: Exception) {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv))
            val decrypted = cipher.doFinal(ciphertext)
            val padByte = decrypted.last().toInt() and 0xFF
            var padLen = if (padByte in 1..16) padByte else 0
            if (padLen > 0) {
                for (i in decrypted.size - padLen until decrypted.size) {
                    if ((decrypted[i].toInt() and 0xFF) != padByte) {
                        padLen = 0
                        break
                    }
                }
            }
            String(decrypted.copyOfRange(0, decrypted.size - padLen), Charsets.UTF_8)
        }
    } catch (e: Exception) {
        Log.e(KAO_TAG, "AES error: ${e.message}")
        null
    }
}

fun fixHostsLinks(url: String): String {
    return url
        .replaceFirst("https://morencius.com", "https://vidhidepro.com")
        .replaceFirst("https://minochinos.com", "https://vidhidepro.com")
        .replaceFirst("https://hglink.to", "https://streamwish.to")
        .replaceFirst("https://swdyu.com", "https://streamwish.to")
        .replaceFirst("https://cybervynx.com", "https://streamwish.to")
        .replaceFirst("https://dumbalag.com", "https://streamwish.to")
        .replaceFirst("https://awish.pro", "https://streamwish.to")
        .replaceFirst("https://mivalyo.com", "https://vidhidepro.com")
        .replaceFirst("https://dinisglows.com", "https://vidhidepro.com")
        .replaceFirst("https://dhtpre.com", "https://vidhidepro.com")
        .replaceFirst("https://filemoon.link", "https://filemoon.sx")
        .replaceFirst("https://sblona.com", "https://watchsb.com")
        .replaceFirst("https://lulu.st", "https://lulustream.com")
        .replaceFirst("https://uqload.io", "https://uqload.com")
        .replaceFirst("https://do7go.com", "https://dood.la")
        .replaceFirst("https://doodstream.com", "https://dood.la")
        .replaceFirst("https://streamtape.com", "https://streamtape.cc")
}

data class KaoServer(
    @JsonProperty("servername") val servername: String? = null,
    @JsonProperty("link") val link: String? = null,
)

data class KaoServersByLang(
    @JsonProperty("file_id") val fileId: String? = null,
    @JsonProperty("video_language") val videoLanguage: String? = null,
    @JsonProperty("sortedEmbeds") val sortedEmbeds: List<KaoServer> = emptyList(),
)

data class KaoVoeDecrypted(
    @JsonProperty("source") val source: String? = null,
    @JsonProperty("direct_access_url") val directAccessUrl: String? = null,
)

class KaoVoeExtractor {
    private val voeHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
    )

    suspend fun parseHtml(
        html: String,
        pageUrl: String,
        sourceName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        cookies: Map<String, String>? = null,
    ): Boolean {
        var workHtml = html
        if (html.contains("altcha-widget") || html.contains("Confirm you&#039;re human")) {
            Log.d(KAO_TAG, "[Voe] ALTCHA detectado, resolviendo PoW: ${pageUrl.take(100)}")
            workHtml = solveAltcha(html, pageUrl, cookies) ?: run {
                Log.w(KAO_TAG, "[Voe] ALTCHA no resuelto, sin links")
                return false
            }
        }
        val pageOrigin = try {
            val u = java.net.URL(pageUrl)
            "${u.protocol}://${u.host}"
        } catch (_: Exception) { pageUrl }

        var encodedString: String? = null

        encodedString = Regex("""<script[^>]*type=["']application/json["'][^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(workHtml)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.contains("[\"") }
            ?.substringAfter("[\"")
            ?.substringBeforeLast("\"]")

        if (encodedString == null) {

            val scripts = Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(workHtml).map { it.groupValues[1] }
            outer@ for (body in scripts) {
                for (m in Regex("""["']([A-Za-z0-9+/=]{100,})["']""").findAll(body)) {
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
            Log.w(KAO_TAG, "[Voe] encoded string not found: ${pageUrl.take(100)}")
            return false
        }

        val decryptedJson = decryptVoeF7(encodedString)
        val m3u8 = decryptedJson?.source
        val mp4 = decryptedJson?.directAccessUrl
        var emitted = false
        if (m3u8 != null) {
            Log.d(KAO_TAG, "[Voe] Found M3U8: ${m3u8.take(100)}")
            M3u8Helper.generateM3u8(
                sourceName,
                m3u8,
                "$pageOrigin/",
                headers = mapOf("Origin" to "$pageOrigin/"),
            ).forEach(callback)
            emitted = true
        }
        if (mp4 != null) {
            Log.d(KAO_TAG, "[Voe] Found MP4: ${mp4.take(100)}")
            callback.invoke(
                newExtractorLink("$sourceName MP4", "$sourceName MP4", mp4, INFER_TYPE) {
                    this.referer = pageUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            emitted = true
        }
        if (!emitted) Log.w(KAO_TAG, "[Voe] No source found after decryption")
        return emitted
    }


    private suspend fun solveAltcha(
        html: String,
        pageUrl: String,
        cookies: Map<String, String>?,
    ): String? {
        return try {
            val inputTag = Regex("""<input[^>]*name=["']?_token["']?[^>]*>""").find(html)?.value
            if (inputTag == null) {
                Log.w(KAO_TAG, "[Voe] ALTCHA sin input _token")
                return null
            }
            val token = Regex("""value=["']?([^"'\s>]+)""").find(inputTag)?.groupValues?.get(1)
                ?.takeIf { it.isNotBlank() }
            if (token == null) {
                Log.w(KAO_TAG, "[Voe] ALTCHA sin token")
                return null
            }
            val challengeUrl = Regex("""<altcha-widget[^>]*challenge=["']?([^"'\s>]+)""").find(html)
                ?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            if (challengeUrl == null) {
                Log.w(KAO_TAG, "[Voe] ALTCHA sin challenge URL")
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
            Log.d(KAO_TAG, "[Voe] challenge -> code=${chalResp.code} len=${chalText.length} cookies=${jar.keys}")
            if (!chalText.trimStart().startsWith("{")) {
                Log.d(KAO_TAG, "[Voe] challenge sin JSON, reintento sin cookies: ${chalText.take(120)}")
                chalResp = app.get(
                    challengeUrl,
                    headers = voeHeaders + ("Referer" to pageUrl),
                    timeout = 30L,
                )
                chalText = try { chalResp.text } catch (_: Exception) { "" }
                Log.d(KAO_TAG, "[Voe] challenge retry -> code=${chalResp.code} len=${chalText.length}")
                if (!chalText.trimStart().startsWith("{")) {
                    Log.w(KAO_TAG, "[Voe] challenge no-JSON: ${chalText.take(160)}")
                    return null
                }
            }
            val solution = solveAltchaPow(chalText) ?: return null
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
            Log.d(KAO_TAG, "[Voe] POST solucion -> code=${postResp.code} len=${postBody.length} gate=${postBody.contains("altcha-widget")}")
            postBody.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(KAO_TAG, "[Voe] ALTCHA error: ${e.message}")
            null
        }
    }

    private suspend fun solveAltchaPow(challengeText: String, maxAttempts: Int = 1024): Pair<Long, String>? {
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
            Log.w(KAO_TAG, "[Voe] ALTCHA params incompletos (nonce=${nonceHex != null} salt=${saltHex != null} prefix=$prefix cost=$cost): ${challengeText.take(160)}")
            return null
        }
        val nonce = hexToBytes(nonceHex) ?: run {
            Log.w(KAO_TAG, "[Voe] ALTCHA nonce inválido")
            return null
        }
        val salt = hexToBytes(saltHex) ?: run {
            Log.w(KAO_TAG, "[Voe] ALTCHA salt inválida")
            return null
        }
        if (prefix.isEmpty() || cost <= 0) return null

        val t0 = System.currentTimeMillis()
        val nThreads = 4
        val winner = AtomicLong(-1)
        val winnerHex = AtomicReference<String?>(null)
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
                        val derivedHex = pbkdf2Sha256(password, salt, cost, keyLength, mac).toHex()
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
        val w = winner.get()
        val wh = winnerHex.get()
        if (w >= 0 && wh != null) {
            Log.d(KAO_TAG, "[Voe] ALTCHA resuelto en $w intentos (${System.currentTimeMillis() - t0}ms, ${nThreads}h)")
            return w to wh
        }
        Log.w(KAO_TAG, "[Voe] ALTCHA PoW agotado ($maxAttempts)")
        return null
    }

    private fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, keyLength: Int, mac: Mac): ByteArray {
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

    private fun decryptVoeF7(p8: String, quiet: Boolean = false): KaoVoeDecrypted? {
        return try {
            val vF = rot13(p8)
            val vF2 = replacePatterns(vF)
            val vF3 = removeUnderscores(vF2)
            val vF4 = base64Decode(vF3)
            val vF5 = charShift(vF4, 3)
            val vF6 = vF5.reversed()
            val vAtob = base64Decode(vF6)
            parseJson<KaoVoeDecrypted>(vAtob)
        } catch (e: Exception) {
            if (!quiet) Log.e(KAO_TAG, "[Voe] decrypt error: ${e.message}")
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
}

class KaoStreamWish {
    suspend fun parseHtml(
        html: String,
        pageUrl: String,
        linkReferer: String,
        sourceName: String,
        callback: (ExtractorLink) -> Unit,
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
            suspend fun emitFile(raw: String, tag: String) {
                val f = resolveUrl(raw)
                if (f.contains(".m3u8") || f.contains(".mp4") || f.contains(".m4v")) {
                    Log.d(KAO_TAG, "[SW] $tag: ${f.take(120)}")
                    callback.invoke(newExtractorLink(sourceName, sourceName, f) { this.referer = linkReferer })
                    found = true
                }
            }
            for (m in m3u8Regex.findAll(html)) {
                Log.d(KAO_TAG, "[SW] M3U8: ${m.value.take(120)}")
                callback.invoke(newExtractorLink(sourceName, sourceName, m.value) { this.referer = linkReferer })
                found = true
            }
            if (!found) {
                for (m in fileRegex.findAll(html)) emitFile(m.groupValues[1], "file")
            }
            if (!found) {
                for (m in mp4Regex.findAll(html)) {
                    Log.d(KAO_TAG, "[SW] MP4: ${m.value.take(120)}")
                    callback.invoke(newExtractorLink(sourceName, sourceName, m.value) { this.referer = linkReferer })
                    found = true
                }
            }
            if (!found) {
                val packerRegex = Regex("""\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)
                for (pm in packerRegex.findAll(html)) {
                    try {
                        val decoded = unpackDeanEdwards(
                            pm.groupValues[1],
                            pm.groupValues[2].toIntOrNull() ?: 36,
                            pm.groupValues[3].toIntOrNull() ?: 0,
                            pm.groupValues[4]
                        ) ?: continue
                        for (m in m3u8Regex.findAll(decoded)) {
                            Log.d(KAO_TAG, "[SW] M3U8 (eval): ${m.value.take(120)}")
                            callback.invoke(newExtractorLink(sourceName, sourceName, m.value) { this.referer = linkReferer })
                            found = true
                        }
                        if (!found) for (m in fileRegex.findAll(decoded)) emitFile(m.groupValues[1], "file(eval)")
                    } catch (_: Exception) {}
                    if (found) break
                }
            }
            if (!found) {
                val iframes = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .findAll(html).map { it.groupValues[1] }
                    .filter { it.contains("streamwish") || it.contains("/e/") }.take(2).toList()
                if (iframes.isNotEmpty()) Log.d(KAO_TAG, "[SW] iframes: $iframes")
                for (src in iframes) {
                    try {
                        val ihtml = app.get(resolveUrl(src), headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                            "Referer" to pageUrl,
                        ), timeout = 15L).text
                        for (m in m3u8Regex.findAll(ihtml)) {
                            Log.d(KAO_TAG, "[SW] M3U8 (iframe): ${m.value.take(120)}")
                            callback.invoke(newExtractorLink(sourceName, sourceName, m.value) { this.referer = linkReferer })
                            found = true
                        }
                        if (!found) for (m in fileRegex.findAll(ihtml)) emitFile(m.groupValues[1], "file(iframe)")
                    } catch (_: Exception) {}
                    if (found) break
                }
            }
            if (!found) {
                val fctx = Regex("""(file|sources)\s*:.{0,200}""").find(html)?.value
                Log.w(KAO_TAG, "[SW] No M3U8/MP4 pageHasJW=${html.contains("jwplayer")} hasSources=${html.contains("sources")} hasEval=${html.contains("eval(")} ctx=${fctx?.take(200)}")
            }
            found
        } catch (e: Exception) {
            Log.e(KAO_TAG, "[SW] Error: ${e.message}", e)
            false
        }
    }
}

private fun unpackDeanEdwards(packed: String, base: Int, count: Int, dictRaw: String): String? {
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

private fun unpackPackedJS(html: String): String? {
    val evalHeaderRegex = Regex("""eval\(function\(p,a,c,k,e,d\)\{.+?\}\('""", RegexOption.DOT_MATCHES_ALL)
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

private suspend fun scanHtmlForSubs(html: String, baseUrl: String, subtitleCallback: (SubtitleFile) -> Unit, seen: MutableSet<String> = mutableSetOf()) {
    Regex("""["']([^"']*\.(?:vtt|srt)(?:\?[^"']*)?)["']""", RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
        val subUrl = match.groupValues[1]
        val cleanSubUrl = if (subUrl.startsWith("http")) subUrl else "$baseUrl/$subUrl"
        if (seen.add(cleanSubUrl)) {
            Log.d(KAO_TAG, "[PageSubs] Subtítulo: $cleanSubUrl")
            subtitleCallback.invoke(newSubtitleFile("Español", cleanSubUrl))
        }
    }
}

private suspend fun scanPageForSubs(pageUrl: String, subtitleCallback: (SubtitleFile) -> Unit) {
    try {
        val scanHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        )
        val html = app.get(pageUrl, headers = scanHeaders, timeout = 20L).text
        scanHtmlForSubs(html, pageUrl.substringBeforeLast("/"), subtitleCallback)
    } catch (e: Exception) {
        Log.d(KAO_TAG, "[PageSubs] Error al escanear $pageUrl: ${e.message}")
    }
}

private suspend fun tryExtractSubsFromM3u8(
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
            val lang = Regex("""LANGUAGE\s*=\s*"([^"]*)""", RegexOption.IGNORE_CASE).find(mediaBlock.value)?.groupValues?.get(1) ?: "Español"
            val uri = Regex("""URI\s*=\s*"([^"]*)""", RegexOption.IGNORE_CASE).find(mediaBlock.value)?.groupValues?.get(1)
            if (uri != null) {
                val subUrl = if (uri.startsWith("http")) uri else "$baseUrl/$uri"
                Log.d(KAO_TAG, "[M3u8Subs] lang=$lang, url=${subUrl.take(120)}")
                subtitleCallback.invoke(newSubtitleFile(lang, subUrl))
                count++
            }
        }
    } catch (e: Exception) {
        Log.d(KAO_TAG, "[M3u8Subs] Error manifest: ${e.message}")
    }
}

private const val SW_READY_JS = "h.includes('.m3u8')||h.includes('jwplayer')"
private const val VOE_READY_JS = "(!h.includes('altcha-widget'))&&(h.includes('.m3u8')||h.includes('application/json'))"
private const val DUMP_JS = "(function(){try{NativeBridge.onHtml(document.documentElement.outerHTML);}catch(e){NativeBridge.onHtml('ERR:'+e);}})()"

private suspend fun renderViaWebView(pageUrl: String, referer: String?, waitMs: Long = 12000L, readyJs: String? = null): String? {
    return withContext(Dispatchers.Main) {
        val appCtx = SerieskaoProvider.pluginContext?.applicationContext ?: run {
            Log.w(KAO_TAG, "[WebView] sin context")
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
                    webView?.evaluateJavascript(DUMP_JS, null)
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
                            Log.d(KAO_TAG, "[WebView] listo antes de tiempo, dumpeando")
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
            Log.d(KAO_TAG, "[WebView] renderizando ${pageUrl.take(100)}")
            withTimeoutOrNull(waitMs + 15000L) { deferred.await() }
        } catch (e: Exception) {
            Log.w(KAO_TAG, "[WebView] error: ${e.message}")
            null
        } finally {
            try { mainHandler.removeCallbacksAndMessages(null) } catch (_: Exception) {}
            try { webView?.destroy() } catch (_: Exception) {}
        }
    }
}

private suspend fun tryVidHideProExtraction(
    url: String,
    referer: String,
    languageName: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    return try {
        Log.d(KAO_TAG, "[VH-Pro] trying $url")
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
            "Accept" to "*/*",
            "Referer" to referer,
        )
        val res = app.get(url, headers = headers, timeout = 20L)
        if (!res.isSuccessful) {
            Log.w(KAO_TAG, "[VH-Pro] HTTP ${res.code}")
            return false
        }
        val html = res.text
        Log.d(KAO_TAG, "[VH-Pro] HTTP 200 len=${html.length}")

        val unpacked = unpackPackedJS(html)
        if (unpacked.isNullOrBlank()) {
            Log.w(KAO_TAG, "[VH-Pro] packer unpack failed")
            return false
        }
        Log.d(KAO_TAG, "[VH-Pro] unpacked len=${unpacked.length} snippet=${unpacked.take(200)}")

        val linksMap = mutableMapOf<String, String>()
        Regex("""links\s*[=:]\s*\{([^}]+)\}""", RegexOption.DOT_MATCHES_ALL).find(unpacked)?.let { block ->
            val inner = block.groupValues[1]
            Regex(""""(hls\d)"\s*:\s*"([^"]+)"""").findAll(inner).forEach { m ->
                linksMap[m.groupValues[1]] = m.groupValues[2]
            }
        }

        if (linksMap.isEmpty()) {
            Regex(""""(hls\d)"\s*:\s*"([^"]+)"""").findAll(unpacked).forEach { m ->
                linksMap[m.groupValues[1]] = m.groupValues[2]
            }
        }

        if (linksMap.isEmpty()) {
            Log.w(KAO_TAG, "[VH-Pro] no hls links in unpacked JS")
            return false
        }

        val preferOrder = listOf("hls2", "hls3", "hls4")
        val orderedKeys = preferOrder.filter { linksMap.containsKey(it) } + linksMap.keys.filter { it !in preferOrder }
        if (orderedKeys.isEmpty()) {
            Log.w(KAO_TAG, "[VH-Pro] no hls links in unpacked JS")
            return false
        }

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
            if (u.startsWith("/")) {
                u = "https://vidhidepro.com$u"
                Log.d(KAO_TAG, "[VH-Pro] relative URL, prepended base: ${u.take(120)}")
            }
            Variant(key, u)
        }
        val reachable = java.util.Collections.synchronizedSet(mutableSetOf<Variant>())
        resolved.amap { v ->
            try {

                val code = withTimeoutOrNull(20000L) {
                    app.get(v.url, headers = probeHeaders, timeout = 20L).code
                } ?: -1
                Log.d(KAO_TAG, "[VH-Pro] probe ${v.key} -> $code")
                if (code in 200..299) reachable.add(v)
            } catch (_: Exception) {}
        }
        val toEmit = if (reachable.isNotEmpty()) {
            orderedKeys.mapNotNull { k -> reachable.firstOrNull { it.key == k } }
        } else {
            Log.w(KAO_TAG, "[VH-Pro] ningún master responde, emitiendo todos igual")
            resolved
        }

        var firstM3u8: String? = null
        for (v in toEmit) {
            if (firstM3u8 == null) firstM3u8 = v.url
            Log.d(KAO_TAG, "[VH-Pro] emit ${v.key} url=${v.url.take(120)}")
            callback(newExtractorLink("SeriesKao", "VidHidePro - ${v.key} [$languageName]", v.url, ExtractorLinkType.M3U8) {
                this.referer = url
                this.headers = vidHeaders
            })
        }
        Log.d(KAO_TAG, "[VH-Pro] emitted ${toEmit.size} variants")

        val seenSubs = mutableSetOf<String>()
        scanHtmlForSubs(html, url.substringBeforeLast("/"), subtitleCallback, seenSubs)
        scanHtmlForSubs(unpacked, url.substringBeforeLast("/"), subtitleCallback, seenSubs)
        firstM3u8?.let { tryExtractSubsFromM3u8(it, url, subtitleCallback) }
        true
    } catch (e: Exception) {
        Log.e(KAO_TAG, "[VH-Pro] error: ${e.message}")
        false
    }
}

private fun webViewCookies(url: String): Map<String, String> {
    return try {
        val raw = android.webkit.CookieManager.getInstance().getCookie(url) ?: return emptyMap()
        raw.split(";").mapNotNull { part ->
            val kv = part.trim().split("=", limit = 2)
            if (kv.size == 2 && kv[0].isNotBlank()) kv[0].trim() to kv[1] else null
        }.toMap()
    } catch (_: Exception) {
        emptyMap()
    }
}

private suspend fun tryVoeExtraction(    url: String,
    referer: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    return try {
        Log.d(KAO_TAG, "[Voe] trying $url")
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer" to referer,
        )
        suspend fun tryMirrors(): Boolean {
            val hashPath = try { java.net.URL(url).path } catch (_: Exception) { "" }
            if (!hashPath.startsWith("/e/")) return false

            val mirrors = listOf("yip.su", "tubelessceliolymph.com")
            val mirrorOk = java.util.concurrent.atomic.AtomicBoolean(false)
            withTimeoutOrNull(20000L) {
                mirrors.amap { mirror ->
                    if (mirrorOk.get()) return@amap
                    try {
                        val mUrl = "https://$mirror$hashPath"
                        Log.d(KAO_TAG, "[Voe] probando mirror: $mUrl")
                        val mResp = app.get(mUrl, headers = headers + ("Referer" to url), timeout = 10L)
                        val mHtml = mResp.text
                        if (KaoVoeExtractor().parseHtml(mHtml, mUrl, "SeriesKao", subtitleCallback, callback, mResp.cookies)) {
                            Log.d(KAO_TAG, "[Voe] mirror $mirror OK")
                            mirrorOk.set(true)
                        }
                    } catch (_: Exception) {}
                }
            }
            return mirrorOk.get()
        }
        val res = app.get(url, headers = headers, timeout = 15L, allowRedirects = false)
        val redirectUrl = res.headers["Location"] ?: res.url
        Log.d(KAO_TAG, "[Voe] status=${res.code} redirect=$redirectUrl")

        val finalUrl = if (res.code in 301..303) {
            val h2 = headers + ("Referer" to url)
            val res2 = app.get(redirectUrl, headers = h2, timeout = 15L)
            res2.url
        } else {
            redirectUrl
        }
        Log.d(KAO_TAG, "[Voe] finalUrl=$finalUrl")

        val finalResp = app.get(finalUrl, headers = headers, timeout = 15L)
        val finalHtml = finalResp.text

        val finalCookies = finalResp.cookies

        if (finalHtml.contains("captcha") || finalHtml.contains("CAPTCHA") || finalHtml.contains("cf-challenge") || finalHtml.contains("altcha-widget")) {
            Log.w(KAO_TAG, "[Voe] challenge detectado en $finalUrl")

            if (KaoVoeExtractor().parseHtml(finalHtml, finalUrl, "SeriesKao", subtitleCallback, callback, finalCookies)) return true
            if (tryMirrors()) return true
            Log.d(KAO_TAG, "[Voe] probando WebView (Altcha se auto-resuelve): $finalUrl")
            val rendered = renderViaWebView(finalUrl, url, readyJs = VOE_READY_JS)
            if (rendered != null && KaoVoeExtractor().parseHtml(rendered, finalUrl, "SeriesKao", subtitleCallback, callback, webViewCookies(finalUrl))) {
                Log.d(KAO_TAG, "[Voe] WebView fallback emitió links")
                return true
            }
            return false
        }

        val m3u8 = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(finalHtml)?.groupValues?.get(1)
        val mp4 = Regex("""(https?://[^"'\s]+\.mp4[^"'\s]*)""").find(finalHtml)?.groupValues?.get(1)

        val videoUrl = m3u8 ?: mp4
        if (videoUrl == null) {
            Log.w(KAO_TAG, "[Voe] no m3u8/mp4 found in $finalUrl")
            if (KaoVoeExtractor().parseHtml(finalHtml, finalUrl, "SeriesKao", subtitleCallback, callback, finalCookies)) return true
            if (tryMirrors()) return true
            Log.d(KAO_TAG, "[Voe] probando WebView: $finalUrl")
            val rendered = renderViaWebView(finalUrl, url, readyJs = VOE_READY_JS)
            if (rendered != null && KaoVoeExtractor().parseHtml(rendered, finalUrl, "SeriesKao", subtitleCallback, callback, webViewCookies(finalUrl))) {
                Log.d(KAO_TAG, "[Voe] WebView fallback emitió links")
                return true
            }
            return false
        }

        val type = ExtractorLinkType.M3U8
        Log.d(KAO_TAG, "[Voe] found ${if (m3u8 != null) "m3u8" else "mp4"}: ${videoUrl.take(120)}")

        callback(newExtractorLink("SeriesKao", "Voe", videoUrl, type) {
            this.referer = finalUrl
            this.headers = headers
        })
        true
    } catch (e: Exception) {
        Log.e(KAO_TAG, "[Voe] error: ${e.message}")
        false
    }
}

suspend fun loadKaoSourceExtractor(
    source: String,
    url: String,
    referer: String? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) = kotlinx.coroutines.coroutineScope {
    var count = 0
    val outerScope = this

    launch { scanPageForSubs(url, subtitleCallback) }

    fun emitWrapped(link: ExtractorLink) {
        count++
        outerScope.launch {
            callback.invoke(
                newExtractorLink("SeriesKao", "$source[${link.source}]", link.url) {
                    this.quality = link.quality
                    this.type = link.type
                    this.referer = link.referer
                    this.headers = link.headers
                    this.extractorData = link.extractorData
                }
            )
        }
    }

    val domain = try { java.net.URL(url).host } catch (_: Exception) { "" }
    val customHandled = when {
        domain.contains("vidhidepro") -> tryVidHideProExtraction(url, referer ?: url, source, subtitleCallback) { link ->
            count++
            outerScope.launch { callback.invoke(link) }
        }
        domain.contains("voe.sx") -> tryVoeExtraction(url, referer ?: url, subtitleCallback) { link ->
            count++
            outerScope.launch { callback.invoke(link) }
        }
        domain.contains("streamwish") -> {

            var directOk = false
            try {
                val staticHtml = app.get(
                    url,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
                        "Referer" to (referer ?: url),
                    ),
                    timeout = 15L
                ).text
                if (KaoStreamWish().parseHtml(staticHtml, url, referer ?: url, "SeriesKao") { link ->
                        count++
                        outerScope.launch {
                            callback.invoke(
                                newExtractorLink("SeriesKao", "$source[StreamWish]", link.url) {
                                    this.quality = link.quality
                                    this.type = link.type
                                    this.referer = link.referer
                                    this.headers = link.headers
                                    this.extractorData = link.extractorData
                                }
                            )
                        }
                    }) {
                    Log.d(KAO_TAG, "[SW] parseo estático OK: $url")
                    directOk = true
                }
            } catch (e: Exception) {
                Log.d(KAO_TAG, "[SW] parseo estático falló: ${e.message}")
            }
            if (!directOk) {
                loadExtractor(url, referer, subtitleCallback) { link -> emitWrapped(link) }
            }
            if (count == 0) {
                Log.d(KAO_TAG, "[SW] extractor 0 links, probando WebView: $url")
                val rendered = renderViaWebView(url, referer, readyJs = SW_READY_JS)
                if (rendered != null) {
                    KaoStreamWish().parseHtml(rendered, url, referer ?: url, "SeriesKao") { link ->
                        count++
                        outerScope.launch {
                            callback.invoke(
                                newExtractorLink("SeriesKao", "$source[StreamWish]", link.url) {
                                    this.quality = link.quality
                                    this.type = link.type
                                    this.referer = link.referer
                                    this.headers = link.headers
                                    this.extractorData = link.extractorData
                                }
                            )
                        }
                    }
                }
                if (count == 0) Log.w(KAO_TAG, "[SW] WebView sin links: $url")
            }
            true
        }
        else -> false
    }

    if (!customHandled || count == 0) {
        Log.d(KAO_TAG, "loadKaoSourceExtractor [$source] fallback a loadExtractor: $url")
        loadExtractor(url, referer, subtitleCallback) { link ->
            count++
            Log.d(KAO_TAG, "loadKaoSourceExtractor [$source] -> ${link.source} ${link.url.take(80)} q=${link.quality} type=${link.type}")
            outerScope.launch {
                callback.invoke(
                    newExtractorLink("SeriesKao", "$source[${link.source}]", link.url) {
                        this.quality = link.quality
                        this.type = link.type
                        this.referer = link.referer
                        this.headers = link.headers
                        this.extractorData = link.extractorData
                    }
                )
            }
        }
    }

    launch {
        if (count == 0) Log.w(KAO_TAG, "loadKaoSourceExtractor [$source] 0 links para $url")
        else Log.d(KAO_TAG, "loadKaoSourceExtractor [$source] $count links para $url")
    }
}

suspend fun extractKaoEmbed69(
    embedUrl: String,
    referer: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    try {
        Log.d(KAO_TAG, "embed69 - procesando $embedUrl")
        val embed69Headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
            "Accept" to "*/*",
            "Referer" to referer,
        )
        val embedResp = app.get(embedUrl, headers = embed69Headers, timeout = 30L)
        Log.d(KAO_TAG, "embed69 - HTTP ${embedResp.code}, length=${embedResp.text.length}")
        val embedDoc = embedResp.document
        val embedPageHtml = embedResp.text
        val dataLinkScript = embedDoc.select("script")
            .firstOrNull { it.html().contains("dataLink =") }
        if (dataLinkScript == null) {
            Log.d(KAO_TAG, "embed69 - sin dataLink, probando playServerVast")
            val serverUrls = Regex("""playServerVast\(\s*'([^']+)'\s*\)""")
                .findAll(embedPageHtml)
                .map { it.groupValues[1] }
                .distinct()
                .toList()
            if (serverUrls.isEmpty()) {
                Log.e(KAO_TAG, "embed69 - tampoco hay playServerVast: ${embedPageHtml.take(300)}")
                return
            }
            Log.d(KAO_TAG, "embed69 - servers=${serverUrls.size}: $serverUrls")
            serverUrls.amap { serverUrl ->
                loadKaoSourceExtractor("embed69", fixHostsLinks(serverUrl), embedUrl, subtitleCallback, callback)
            }
            return
        }
        Log.d(KAO_TAG, "embed69 - script con dataLink encontrado")
        val dataLinkJson = dataLinkScript.html()
            .substringAfter("dataLink =")
            .substringBefore(";")
            .trim()
        Log.d(KAO_TAG, "embed69 - dataLink JSON: ${dataLinkJson.take(300)}")

        val pageHtml = embedResp.text
        val embedChallenge = Regex("""POW_CHALLENGE\s*=\s*'([^']+)'""").find(pageHtml)?.groupValues?.get(1)
        val embedSalt = Regex("""POW_SALT\s*=\s*'([^']+)'""").find(pageHtml)?.groupValues?.get(1)
        if (embedChallenge == null || embedSalt == null) {
            Log.e(KAO_TAG, "embed69 - No se pudo extraer POW_CHALLENGE/SALT")
            return
        }
        Log.d(KAO_TAG, "embed69 - challenge=$embedChallenge salt=$embedSalt")

        tryParseJson<List<KaoServersByLang>>(dataLinkJson)?.amap { lang ->
            val encryptedLinks = lang.sortedEmbeds.mapNotNull { it.link }
            if (encryptedLinks.isEmpty()) return@amap
            Log.d(KAO_TAG, "embed69 - ${encryptedLinks.size} enlaces encriptados para ${lang.videoLanguage}")

            val aesKey = withContext(Dispatchers.Default) { solveEmbed69PoW(embedChallenge, embedSalt) }
            if (aesKey == null) {
                Log.e(KAO_TAG, "embed69 - PoW failed")
                return@amap
            }
            Log.d(KAO_TAG, "embed69 - PoW solved, decrypting ${encryptedLinks.size} links")
            val langTag = when (lang.videoLanguage?.uppercase()) {
                "LAT" -> "LATINO"
                "SUB", "ENGLISH" -> "SUBTITULADO"
                "ESP", "SPANISH" -> "CASTELLANO"
                "ENG", "VOSE" -> "VOSE"
                "JAP", "JAPANESE" -> "JAPONES"
                else -> lang.videoLanguage ?: "??"
            }
            encryptedLinks.amap { encrypted ->
                val decryptedUrl = decryptAESLocal(encrypted, aesKey)
                if (decryptedUrl != null) {
                    Log.d(KAO_TAG, "embed69 - decrypted: ${decryptedUrl.take(100)}")
                    loadKaoSourceExtractor(langTag, fixHostsLinks(decryptedUrl), embedUrl, subtitleCallback, callback)
                } else {
                    Log.w(KAO_TAG, "embed69 - decrypt null para $encrypted")
                }
            }
        } ?: Log.e(KAO_TAG, "embed69 - No se pudo parsear dataLink JSON")
    } catch (e: Exception) {
        Log.e(KAO_TAG, "embed69 - error: ${e.message}")
    }
}