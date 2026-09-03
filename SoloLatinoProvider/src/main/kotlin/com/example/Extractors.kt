package com.example

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

open class VoeExtractor : ExtractorApi() {
    override val name = "Voe"
    override val mainUrl = "https://voe.sx"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        Log.d("SoloLatino", "[Voe] URL: $url")
        val voeHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
        )
        val redirectRegex = Regex("""(?:window\.)?location(?:\.href)?\s*=\s*'([^']+)'""")
        var currentUrl = url
        var currentReferer = referer ?: url
        var res = app.get(currentUrl, headers = voeHeaders, referer = currentReferer)
        var maxRedirects = 5
        var redirectUrl = redirectRegex.find(res.text)?.groupValues?.get(1)
        while (redirectUrl != null && maxRedirects > 0) {
            Log.d("SoloLatino", "[Voe] Redirect to: $redirectUrl")
            currentReferer = currentUrl
            currentUrl = redirectUrl
            res = app.get(currentUrl, headers = voeHeaders, referer = currentReferer)
            maxRedirects--
            redirectUrl = redirectRegex.find(res.text)?.groupValues?.get(1)
        }

        if (maxRedirects == 0 && redirectUrl != null) {
            Log.e("SoloLatino", "[Voe] Too many redirects, giving up")
        }

        parseHtml(res.text, currentUrl, name, subtitleCallback, callback)
    }

    suspend fun parseHtml(
        html: String,
        pageUrl: String,
        sourceName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (html.contains("altcha-widget") || html.contains("Confirm you&#039;re human")) {
            Log.w("SoloLatino", "[Voe] CAPTCHA page, no parse: ${pageUrl.take(100)}")
            return false
        }
        val pageOrigin = try {
            val u = java.net.URL(pageUrl)
            "${u.protocol}://${u.host}"
        } catch (_: Exception) { pageUrl }

        var encodedString: String? = null

        encodedString = Regex("""<script[^>]*type=["']application/json["'][^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.contains("[\"") }
            ?.substringAfter("[\"")
            ?.substringBeforeLast("\"]")

        if (encodedString == null) {

            val scripts = Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(html).map { it.groupValues[1] }
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
            Log.w("SoloLatino", "[Voe] encoded string not found: ${pageUrl.take(100)}")
            return false
        }

        val decryptedJson = decryptVoeF7(encodedString)
        val m3u8 = decryptedJson?.source
        val mp4 = decryptedJson?.directAccessUrl
        var emitted = false
        if (m3u8 != null) {
            Log.d("SoloLatino", "[Voe] Found M3U8: ${m3u8.take(100)}")
            M3u8Helper.generateM3u8(
                sourceName,
                m3u8,
                "$pageOrigin/",
                headers = mapOf("Origin" to "$pageOrigin/"),
            ).forEach(callback)
            emitted = true
        }
        if (mp4 != null) {
            Log.d("SoloLatino", "[Voe] Found MP4: ${mp4.take(100)}")
            callback.invoke(
                newExtractorLink("$sourceName MP4", "$sourceName MP4", mp4, INFER_TYPE) {
                    this.referer = pageUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            emitted = true
        }
        if (!emitted) Log.w("SoloLatino", "[Voe] No source found after decryption")
        return emitted
    }

    private fun decryptVoeF7(p8: String, quiet: Boolean = false): VoeDecrypted? {
        return try {
            val vF = rot13(p8)
            val vF2 = replacePatterns(vF)
            val vF3 = removeUnderscores(vF2)
            val vF4 = base64Decode(vF3)
            val vF5 = charShift(vF4, 3)
            val vF6 = vF5.reversed()
            val vAtob = base64Decode(vF6)
            parseJson<VoeDecrypted>(vAtob)
        } catch (e: Exception) {
            if (!quiet) Log.e("SoloLatino", "[Voe] decrypt error: ${e.message}")
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

class VoeYipsu : VoeExtractor() {
    override val mainUrl = "https://yip.su"
}

class VoeDonaldlineelse : VoeExtractor() {
    override val mainUrl = "https://donaldlineelse.com"
}

class VoeCharlestoughrace : VoeExtractor() {
    override val mainUrl = "https://charlestoughrace.com"
}

class VoeTubeless : VoeExtractor() {
    override val mainUrl = "https://tubelessceliolymph.com"
}

class VoeSimplum : VoeExtractor() {
    override val mainUrl = "https://simpulumlamerop.com"
}

class VoeUroch : VoeExtractor() {
    override val mainUrl = "https://urochsunloath.com"
}

class VoeNathan : VoeExtractor() {
    override val mainUrl = "https://nathanfromsubject.com"
}

class VoeMetagnath : VoeExtractor() {
    override val mainUrl = "https://metagnathtuggers.com"
}

class VoePamelachangemission : VoeExtractor() {
    override val mainUrl = "https://pamelachangemission.com"
}

data class VoeDecrypted(
    @JsonProperty("source") val source: String? = null,
    @JsonProperty("direct_access_url") val directAccessUrl: String? = null,
)

class SoloStreamWish : ExtractorApi() {
    override var mainUrl = "https://streamwish.to"
    override var name = "SoloStreamWish"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        Log.d("SoloLatino", "[SW] URL: $url referer=$referer")
        try {
            val resp = app.get(url, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Referer" to (referer ?: url),
            ), timeout = 20000L)
            Log.d("SoloLatino", "[SW] HTTP ${resp.code} len=${resp.text.length}")
            parseHtml(resp.text, url, referer ?: url, name, callback)
        } catch (e: Exception) {
            Log.e("SoloLatino", "[SW] Error: ${e.message}", e)
        }
    }

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
                    Log.d("SoloLatino", "[SW] $tag: ${f.take(120)}")
                    callback.invoke(newExtractorLink(sourceName, sourceName, f) { this.referer = linkReferer })
                    found = true
                }
            }
            for (m in m3u8Regex.findAll(html)) {
                Log.d("SoloLatino", "[SW] M3U8: ${m.value.take(120)}")
                callback.invoke(newExtractorLink(sourceName, sourceName, m.value) { this.referer = linkReferer })
                found = true
            }
            if (!found) {
                for (m in fileRegex.findAll(html)) emitFile(m.groupValues[1], "file")
            }
            if (!found) {
                for (m in mp4Regex.findAll(html)) {
                    Log.d("SoloLatino", "[SW] MP4: ${m.value.take(120)}")
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
                            Log.d("SoloLatino", "[SW] M3U8 (eval): ${m.value.take(120)}")
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
                if (iframes.isNotEmpty()) Log.d("SoloLatino", "[SW] iframes: $iframes")
                for (src in iframes) {
                    try {
                        val ihtml = app.get(resolveUrl(src), headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                            "Referer" to pageUrl,
                        ), timeout = 15000L).text
                        for (m in m3u8Regex.findAll(ihtml)) {
                            Log.d("SoloLatino", "[SW] M3U8 (iframe): ${m.value.take(120)}")
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
                Log.w("SoloLatino", "[SW] No M3U8/MP4 pageHasJW=${html.contains("jwplayer")} hasSources=${html.contains("sources")} hasEval=${html.contains("eval(")} ctx=${fctx?.take(200)}")
            }
            found
        } catch (e: Exception) {
            Log.e("SoloLatino", "[SW] Error: ${e.message}", e)
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

class SoloVidHide : ExtractorApi() {
    override var mainUrl = "https://vidhidepro.com"
    override var name = "SoloVidHide"
    override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        Log.d("SoloLatino", "[VH] URL: $url referer=$referer")
        try {
            val resp = app.get(url, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Referer" to (referer ?: url),
            ), timeout = 20000L)
            Log.d("SoloLatino", "[VH] HTTP ${resp.code} len=${resp.text.length} snippet=${resp.text.take(600).replace("\n"," ")}")
            val m3u8Regex = Regex("""(https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)""")
            val mp4Regex = Regex("""(https?://[^"'\s<>]+\.(?:mp4|m4v)[^"'\s<>]*)""")
            val fileRegex = Regex("""file\s*:\s*["'](https?://[^"']+)["']""")
            var found = false
            suspend fun tryExtract(text: String, tag: String) {
                for (m in m3u8Regex.findAll(text)) {
                    Log.d("SoloLatino", "[VH] $tag M3U8: ${m.value.take(120)}")
                    callback.invoke(newExtractorLink(name, name, m.value) { this.referer = mainUrl })
                    found = true
                }
                if (!found) for (m in fileRegex.findAll(text)) {
                    val f = m.groupValues[1]
                    if (f.contains(".m3u8") || f.contains(".mp4") || f.contains(".ts")) {
                        Log.d("SoloLatino", "[VH] $tag file: $f")
                        callback.invoke(newExtractorLink(name, name, f) { this.referer = mainUrl })
                        found = true
                    }
                }
                if (!found) for (m in mp4Regex.findAll(text)) {
                    Log.d("SoloLatino", "[VH] $tag MP4: ${m.value.take(120)}")
                    callback.invoke(newExtractorLink(name, name, m.value) { this.referer = mainUrl })
                    found = true
                }
            }
            tryExtract(resp.text, "direct")
            if (!found) {

                val evalRegex = Regex("""eval\s*\(function\(p,a,c,k,e,d\).*?\)\)""", RegexOption.DOT_MATCHES_ALL)
                for (em in evalRegex.findAll(resp.text)) {
                    val evalBlock = em.value

                    val argsMatch = Regex("""\}\('(.*)',(\d+),(\d+),'(.*)'\.split""", RegexOption.DOT_MATCHES_ALL).find(evalBlock)
                    if (argsMatch != null) {
                        try {
                            val p = argsMatch.groupValues[1]
                            val a = argsMatch.groupValues[2].toIntOrNull() ?: 36
                            val c = argsMatch.groupValues[3].toIntOrNull() ?: 0
                            val kRaw = argsMatch.groupValues[4]
                            val kList = kRaw.split('|')
                            var decoded = p
                            for (i in kList.indices.reversed()) {
                                if (kList[i].isBlank()) continue
                                decoded = decoded.replace(Regex("\\b${i.toString(a)}\\b"), kList[i])
                            }
                            Log.d("SoloLatino", "[VH] eval decoded snippet=${decoded.take(400).replace("\n"," ")}")
                            tryExtract(decoded, "eval")
                            if (found) break
                        } catch (_: Exception) {}
                    }
                }
            }
            if (!found) Log.w("SoloLatino", "[VH] No video found hasEval=${resp.text.contains("eval(")} hasPacker=${resp.text.contains("function(p,a,c,k")}")
        } catch (e: Exception) {
            Log.e("SoloLatino", "[VH] Error: ${e.message}", e)
        }
    }
}

class SoloFileMoon : ExtractorApi() {
    override var mainUrl = "https://filemoon.sx"
    override var name = "SoloFileMoon"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        Log.d("SoloLatino", "[FM] URL: $url")
        try {
            val resp = app.get(url, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Referer" to url,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            ), timeout = 20000L)
            val m3u8Regex = Regex("""(https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)""")
            val mp4Regex = Regex("""(https?://[^"'\s<>]+\.(?:mp4|ts)[^"'\s<>]*)""")
            var found = false
            for (m in m3u8Regex.findAll(resp.text)) {
                Log.d("SoloLatino", "[FM] M3U8: ${m.value.take(100)}")
                callback.invoke(newExtractorLink(name, name, m.value) { this.referer = mainUrl })
                found = true
            }
            if (!found) {
                for (m in mp4Regex.findAll(resp.text)) {
                    Log.d("SoloLatino", "[FM] MP4: ${m.value.take(100)}")
                    callback.invoke(newExtractorLink(name, name, m.value) { this.referer = mainUrl })
                    found = true
                }
            }
            if (!found) Log.w("SoloLatino", "[FM] No video found in page")
        } catch (e: Exception) {
            Log.e("SoloLatino", "[FM] Error: ${e.message}")
        }
    }
}