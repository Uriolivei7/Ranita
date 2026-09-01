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

        val pageText = res.text
        if (pageText.contains("altcha-widget") || pageText.contains("Confirm you&#039;re human")) {
            Log.w("SoloLatino", "[Voe] CAPTCHA page detected, skipping voe.sx mirror")
            return
        }

        var encodedString: String? = null

        encodedString = res.document.selectFirst("script[type=application/json]")
            ?.data()?.trim()
            ?.substringAfter("[\"")
            ?.substringBeforeLast("\"]")

        if (encodedString == null) {
            encodedString = res.document.select("script").mapNotNull { script ->
                val html = script.html()
                Regex("""["']([A-Za-z0-9+/=]{100,})["']""").find(html)?.groupValues?.get(1)
            }.firstOrNull()
        }

        if (encodedString == null) {
            Log.w("SoloLatino", "[Voe] encoded string not found after ${5 - maxRedirects} redirect(s)")
            return
        }

        val decryptedJson = decryptVoeF7(encodedString)
        val m3u8 = decryptedJson?.source
        val mp4 = decryptedJson?.directAccessUrl
        if (m3u8 != null) {
            Log.d("SoloLatino", "[Voe] Found M3U8: ${m3u8.take(100)}")
            M3u8Helper.generateM3u8(
                name,
                m3u8,
                "$mainUrl/",
                headers = mapOf("Origin" to "$mainUrl/"),
            ).forEach(callback)
        }
        if (mp4 != null) {
            Log.d("SoloLatino", "[Voe] Found MP4: ${mp4.take(100)}")
            callback.invoke(
                newExtractorLink("$name MP4", "$name MP4", mp4, INFER_TYPE) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
        }
        if (m3u8 == null && mp4 == null) {
            Log.e("SoloLatino", "[Voe] No source found after decryption")
        }
    }

    private fun decryptVoeF7(p8: String): VoeDecrypted? {
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
            Log.e("SoloLatino", "[Voe] decrypt error: ${e.message}")
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
            Log.d("SoloLatino", "[SW] HTTP ${resp.code} len=${resp.text.length} snippet=${resp.text.take(1000).replace("\n"," ")}")
            val m3u8Regex = Regex("""(https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)""")
            val mp4Regex = Regex("""(https?://[^"'\s<>]+\.(?:mp4|m4v)[^"'\s<>]*)""")
            val fileRegex = Regex("""file\s*:\s*["'](https?://[^"']+)["']""")
            var found = false
            for (m in m3u8Regex.findAll(resp.text)) {
                Log.d("SoloLatino", "[SW] M3U8: ${m.value.take(120)}")
                callback.invoke(newExtractorLink(name, name, m.value) { this.referer = mainUrl })
                found = true
            }
            if (!found) {
                for (m in fileRegex.findAll(resp.text)) {
                    val f = m.groupValues[1]
                    if (f.contains(".m3u8") || f.contains(".mp4")) {
                        Log.d("SoloLatino", "[SW] file: $f")
                        callback.invoke(newExtractorLink(name, name, f) { this.referer = mainUrl })
                        found = true
                    }
                }
            }
            if (!found) {
                for (m in mp4Regex.findAll(resp.text)) {
                    Log.d("SoloLatino", "[SW] MP4: ${m.value.take(120)}")
                    callback.invoke(newExtractorLink(name, name, m.value) { this.referer = mainUrl })
                    found = true
                }
            }
            if (!found) {
                val evalRegex = Regex("""eval\s*\(([^)]+)\)""")
                for (em in evalRegex.findAll(resp.text)) {
                    val params = em.groupValues[1].split(",")
                    if (params.size >= 6) {
                        try {
                            val p = params[0].trim().removeSurrounding("'", "'").removeSurrounding("\"", "\"")
                            val a = params[1].trim().toIntOrNull() ?: 36
                            val c = params[2].trim().toIntOrNull() ?: 0
                            val kList = params.drop(3).map { it.trim().removeSurrounding("'", "'").removeSurrounding("\"", "\"") }.take(c)
                            var decoded = p
                            for (i in kList.indices.reversed()) {
                                if (kList[i].isBlank()) continue
                                decoded = decoded.replace(Regex("\\b${i.toString(a)}\\b"), kList[i])
                            }
                            for (m in m3u8Regex.findAll(decoded)) {
                                Log.d("SoloLatino", "[SW] M3U8 (eval): ${m.value.take(120)}")
                                callback.invoke(newExtractorLink(name, name, m.value) { this.referer = mainUrl })
                                found = true
                            }
                            if (!found) for (m in fileRegex.findAll(decoded)) {
                                val f = m.groupValues[1]
                                if (f.contains(".m3u8") || f.contains(".mp4")) {
                                    Log.d("SoloLatino", "[SW] file(eval): $f")
                                    callback.invoke(newExtractorLink(name, name, f) { this.referer = mainUrl })
                                    found = true
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
            if (!found) Log.w("SoloLatino", "[SW] No M3U8/MP4 found pageHasJW=${resp.text.contains("jwplayer")} hasSources=${resp.text.contains("sources")} hasEval=${resp.text.contains("eval(")}")
        } catch (e: Exception) {
            Log.e("SoloLatino", "[SW] Error: ${e.message}", e)
        }
    }
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
                // eval packer: eval(function(p,a,c,k,e,d){...})
                val evalRegex = Regex("""eval\s*\(function\(p,a,c,k,e,d\).*?\)\)""", RegexOption.DOT_MATCHES_ALL)
                for (em in evalRegex.findAll(resp.text)) {
                    val evalBlock = em.value
                    // Quick unpack: find p,a,c,k parts like }('...',36,XX,'...'.split('|'))
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