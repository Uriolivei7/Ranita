package com.example

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import okhttp3.FormBody
import java.net.URL
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.withTimeoutOrNull

class ByseExtractor : ExtractorApi() {
    override var mainUrl = "https://bysedikamoum.com"
    override var name = "Byse"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        Log.d("Byse", "[Byse] URL: $url")

        val videoId = Regex("""/e/(\w+)""").find(url)?.groupValues?.get(1) ?: return
        val baseUrl = try {
            val parsed = java.net.URL(url)
            "${parsed.protocol}://${parsed.host}"
        } catch (e: Exception) { mainUrl }

        try {
            val apiRes = app.post(
                "$baseUrl/api/source",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to url,
                    "Origin" to baseUrl,
                ),
                referer = url,
                requestBody = FormBody.Builder()
                    .add("r", "")
                    .add("s", videoId)
                    .build()
            )
            Log.d("Byse", "[Byse] API response: ${apiRes.text.take(300)}")

            val fileUrl = Regex("""file["']\s*:\s*["']([^"']+)["']""")
                .find(apiRes.text)?.groupValues?.get(1)
                ?: Regex("""url["']\s*:\s*["']([^"']+)["']""")
                .find(apiRes.text)?.groupValues?.get(1)
                ?: return

            callback.invoke(
                newExtractorLink(name, name, fileUrl) {
                    this.referer = baseUrl
                    this.quality = Qualities.Unknown.value
                    this.type = if (fileUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                }
            )
        } catch (e: Exception) {
            Log.e("Byse", "[Byse] Error: ${e.message}")
        }
    }
}

class MhdflixStreamWish : ExtractorApi() {
    override var name = "MhdflixStreamWish"
    override var mainUrl = "https://streamwish.to"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Referer" to url,
            "Accept-Language" to "es",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        )
        val html = app.get(url, headers = headers).text
        val m3u8Regex = Regex("""(https?://[^"'<>\s]+\.m3u8[^"'<>\s]*)""")

        val directM3u8 = m3u8Regex.find(html)
        if (directM3u8 != null) {
            callback.invoke(newExtractorLink("StreamWish", "StreamWish", directM3u8.value, ExtractorLinkType.M3U8) {
                this.referer = mainUrl
            })
            return
        }

        val evalFn = "eval(function(p,a,c,k,e,d){"
        val evalStart = html.indexOf(evalFn)
        if (evalStart >= 0) {
            val callStart = html.indexOf("}('", evalStart)
            if (callStart >= 0) {
                var argIdx = callStart + 2
                if (argIdx < html.length && html[argIdx] == '\'') argIdx++ else return
                val pStart = argIdx
                while (argIdx < html.length && html[argIdx] != '\'') argIdx++
                if (argIdx >= html.length) return
                val p = html.substring(pStart, argIdx); argIdx++
                if (argIdx < html.length && html[argIdx] == ',') argIdx++
                while (argIdx < html.length && html[argIdx] == ' ') argIdx++
                val aStart = argIdx
                while (argIdx < html.length && html[argIdx].isDigit()) argIdx++
                val a = html.substring(aStart, argIdx).toIntOrNull() ?: 36
                if (argIdx < html.length && html[argIdx] == ',') argIdx++
                while (argIdx < html.length && html[argIdx] == ' ') argIdx++
                val cStart = argIdx
                while (argIdx < html.length && html[argIdx].isDigit()) argIdx++
                val c = html.substring(cStart, argIdx).toIntOrNull() ?: 0
                if (argIdx < html.length && html[argIdx] == ',') argIdx++
                while (argIdx < html.length && html[argIdx] == ' ') argIdx++
                if (argIdx < html.length && html[argIdx] == '\'') argIdx++ else return
                val kStart = argIdx
                while (argIdx < html.length && html[argIdx] != '\'') argIdx++
                if (argIdx >= html.length) return
                val k = html.substring(kStart, argIdx).split("|")
                var decoded = p
                for (idx in k.indices.reversed()) {
                    if (k[idx].isBlank()) continue
                    decoded = decoded.replace(Regex("\\b${idx.toString(a)}\\b"), k[idx])
                }
                val decodedM3u8 = m3u8Regex.find(decoded)?.value
                if (decodedM3u8 != null) {
                    callback.invoke(newExtractorLink("StreamWish", "StreamWish", decodedM3u8, ExtractorLinkType.M3U8) {
                        this.referer = mainUrl
                    })
                    return
                }
            }
        }
    }
}

class MhdflixVidHide : ExtractorApi() {
    override var name = "MhdflixVidHide"
    override var mainUrl = "https://vidhidepro.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Referer" to url,
            "Accept-Language" to "es",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        )

        val html = app.get(url, headers = headers).text
        val m3u8Regex = Regex("""(https?://[^"'<>\s]+\.m3u8[^"'<>\s]*)""")

        val directM3u8 = m3u8Regex.find(html)
        if (directM3u8 != null) {
            callback.invoke(newExtractorLink("VidHide", "VidHide", directM3u8.value, ExtractorLinkType.M3U8) {
                this.referer = mainUrl
            })
            return
        }

        val evalMarker = "eval(function(p,a,c,k,e,d){"
        val evalStart = html.indexOf(evalMarker)
        Log.d("MhdflixVidHide", "evalStart=$evalStart")
        if (evalStart < 0) { Log.d("MhdflixVidHide", "marker not found"); return }

        val callStart = html.indexOf("}('", evalStart)
        Log.d("MhdflixVidHide", "callStart='}' index=$callStart")
        if (callStart < 0) { Log.d("MhdflixVidHide", "}(' not found"); return }

        var argIdx = callStart + 2
        if (argIdx >= html.length || html[argIdx] != '\'') { Log.d("MhdflixVidHide", "no opening ' at argIdx=$argIdx"); return }
        argIdx++
        val pStart = argIdx

        while (argIdx < html.length && html[argIdx] != '\'') argIdx++
        if (argIdx >= html.length) return
        val p = html.substring(pStart, argIdx)
        Log.d("MhdflixVidHide", "p len=${p.length} start='${p.take(50)}'")
        argIdx++

        if (argIdx < html.length && html[argIdx] == ',') argIdx++
        while (argIdx < html.length && html[argIdx] == ' ') argIdx++

        val aStart = argIdx
        while (argIdx < html.length && html[argIdx].isDigit()) argIdx++
        val a = html.substring(aStart, argIdx).toIntOrNull() ?: 36
        if (argIdx < html.length && html[argIdx] == ',') argIdx++
        while (argIdx < html.length && html[argIdx] == ' ') argIdx++

        // read c
        val cStart = argIdx
        while (argIdx < html.length && html[argIdx].isDigit()) argIdx++
        val c = html.substring(cStart, argIdx).toIntOrNull() ?: 0
        if (argIdx < html.length && html[argIdx] == ',') argIdx++
        while (argIdx < html.length && html[argIdx] == ' ') argIdx++

        if (argIdx >= html.length || html[argIdx] != '\'') { Log.d("MhdflixVidHide", "no opening ' for k"); return }
        argIdx++
        val kStart = argIdx
        while (argIdx < html.length && html[argIdx] != '\'') argIdx++
        if (argIdx >= html.length) return
        val k = html.substring(kStart, argIdx).split("|")
        Log.d("MhdflixVidHide", "a=$a c=$c k size=${k.size} first5=${k.take(5)}")

        var decoded = p
        for (idx in k.indices.reversed()) {
            if (k[idx].isBlank()) continue
            decoded = decoded.replace(Regex("\\b${idx.toString(a)}\\b"), k[idx])
        }
        Log.d("MhdflixVidHide", "decoded len=${decoded.length}")

        val decodedM3u8 = m3u8Regex.find(decoded)?.value
        if (decodedM3u8 != null) {
            Log.d("MhdflixVidHide", "M3U8: ${decodedM3u8.take(100)}")
            callback.invoke(newExtractorLink("VidHide", "VidHide", decodedM3u8, ExtractorLinkType.M3U8) {
                this.referer = mainUrl
            })
            return
        }
        val anyUrl = Regex("""https?://[^"'\s,;<>]+""").findAll(decoded).take(3).map { it.value }.toList()
        Log.d("MhdflixVidHide", "No M3U8. URLs in decoded: $anyUrl")
    }
}

class MhdflixCubeembed : ExtractorApi() {
    override var name = "Cubeembed"
    override var mainUrl = "https://cubeembed.rpmvid.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val hash = url.substringAfterLast("#").substringAfter("/")
        val baseUrl = getBaseUrl(url)
        Log.d("MhdflixCubeembed", "hash=$hash baseUrl=$baseUrl")

        val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0")
        val encrypted = app.get("$baseUrl/api/v1/video?id=$hash", headers = headers).text.trim()
        Log.d("MhdflixCubeembed", "API response length=${encrypted.length}")

        val key = "kiemtienmua911ca"
        val ivList = listOf("1234567890oiuytr", "0123456789abcdef")

        val decryptedText = ivList.firstNotNullOfOrNull { iv ->
            try {
                decryptAES(encrypted, key, iv)
            } catch (e: Exception) {
                Log.w("MhdflixCubeembed", "Decrypt failed with IV=$iv: ${e.message}")
                null
            }
        } ?: throw Exception("Failed to decrypt with all IVs")

        Log.d("MhdflixCubeembed", "Decrypted: $decryptedText")

        val source = Regex("\"source\":\"(.*?)\"").find(decryptedText)
            ?.groupValues?.get(1)
            ?.replace("\\/", "/") ?: ""
        val cfUrl = Regex("\"cf\":\"(.*?)\"").find(decryptedText)
            ?.groupValues?.get(1)
            ?.replace("\\/", "/") ?: ""

        val subtitleSection = Regex("\"subtitle\":\\{(.*?)\\}").find(decryptedText)?.groupValues?.get(1)
        subtitleSection?.let { section ->
            Regex("\"([^\"]+)\":\\s*\"([^\"]+)\"").findAll(section).forEach { match ->
                val lang = match.groupValues[1]
                val rawPath = match.groupValues[2].split("#")[0]
                if (rawPath.isNotEmpty()) {
                    val path = rawPath.replace("\\/", "/")
                    val subUrl = "$baseUrl$path"
                    subtitleCallback(newSubtitleFile(lang, fixUrl(subUrl)))
                }
            }
        }

        if (cfUrl.isNotEmpty()) {
            Log.d("MhdflixCubeembed", "CF M3U8: $cfUrl")
            callback.invoke(
                newExtractorLink(this.name, "$name (CF)", cfUrl, ExtractorLinkType.M3U8) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
        }
        if (source.isNotEmpty()) {
            val sourceHttp = source.replaceFirst("https://", "http://")
            Log.d("MhdflixCubeembed", "Source M3U8 (HTTP): $sourceHttp")
            callback.invoke(
                newExtractorLink(this.name, "$name (Source)", sourceHttp, ExtractorLinkType.M3U8) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
        }
        val hlsTiktok = Regex("\"hlsVideoTiktok\":\"(.*?)\"").find(decryptedText)
            ?.groupValues?.get(1)
            ?.replace("\\/", "/") ?: ""
        if (hlsTiktok.isNotEmpty()) {
            val tiktokUrl = "$baseUrl$hlsTiktok"
            Log.d("MhdflixCubeembed", "TikTok M3U8: $tiktokUrl")
            callback.invoke(
                newExtractorLink(this.name, "$name (TikTok)", tiktokUrl, ExtractorLinkType.M3U8) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }

    private fun getBaseUrl(url: String): String {
        return try {
            val parsed = URL(url)
            "${parsed.protocol}://${parsed.host}"
        } catch (e: Exception) {
            Log.e("MhdflixCubeembed", "getBaseUrl fallback: ${e.message}")
            mainUrl
        }
    }

    private fun decryptAES(inputHex: String, key: String, iv: String): String {
        val keySpec = SecretKeySpec(key.encodeToByteArray(), "AES")
        val ivSpec = IvParameterSpec(iv.encodeToByteArray())
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        val decrypted = cipher.doFinal(hexToBytes(inputHex))
        return String(decrypted, Charsets.UTF_8)
    }

    private fun hexToBytes(hex: String): ByteArray {
        check(hex.length % 2 == 0) { "Hex string must have an even length" }
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

class Sendvid : ExtractorApi() {
    override var name = "Sendvid"
    override val mainUrl = "https://sendvid.com"
    override val requiresReferer = false
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url).document
        val urlString = doc.select("head meta[property=og:video:secure_url]").attr("content")
        if (urlString.contains("m3u8")) {
            generateM3u8(
                name,
                urlString,
                mainUrl,
            ).forEach(callback)
        } else {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = urlString,
                    type = INFER_TYPE
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}

open class MhdflixVoe : ExtractorApi() {
    override val name = "MhdflixVoe"
    override val mainUrl = "https://voe.sx"
    override val requiresReferer = true

    companion object {
        val voeMirrors = listOf(
            "https://yip.su",
            "https://tubelessceliolymph.com",
            "https://charlestoughrace.com",
            "https://simpulumlamerop.com",
            "https://urochsunloath.com",
            "https://nathanfromsubject.com",
            "https://metagnathtuggers.com",
            "https://pamelachangemission.com",
            "https://eugenemakedraw.com",
        )
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        Log.d("MhdflixVoe", "[Voe] URL: $url")
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
            Log.d("MhdflixVoe", "[Voe] Redirect to: $redirectUrl")
            currentReferer = currentUrl
            currentUrl = redirectUrl
            res = app.get(currentUrl, headers = voeHeaders, referer = currentReferer)
            maxRedirects--
            redirectUrl = redirectRegex.find(res.text)?.groupValues?.get(1)
        }

        if (maxRedirects == 0 && redirectUrl != null) {
            Log.e("MhdflixVoe", "[Voe] Too many redirects")
        }

        val pageText = res.text
        if (parseHtml(pageText, currentUrl, subtitleCallback, callback)) return

        val id = Regex("""/e/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)
        if (!id.isNullOrBlank()) {
            Log.d("MhdflixVoe", "[Voe] probando mirrors (id=$id)")
            withTimeoutOrNull(25000L) {
                for (mirror in voeMirrors) {
                    val mUrl = "$mirror/e/$id"
                    if (mUrl == currentUrl) continue
                    try {
                        val mHtml = app.get(mUrl, headers = voeHeaders + ("Referer" to currentUrl), timeout = 10000L).text
                        if (parseHtml(mHtml, mUrl, subtitleCallback, callback)) {
                            Log.d("MhdflixVoe", "[Voe] mirror OK: $mirror")
                            return@withTimeoutOrNull
                        }
                    } catch (_: Exception) {}
                }
            }
        }
        Log.w("MhdflixVoe", "[Voe] sin fuentes para $url")
    }

    suspend fun parseHtml(
        html: String,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (html.contains("altcha-widget") || html.contains("Confirm you&#039;re human")) {
            Log.w("MhdflixVoe", "[Voe] CAPTCHA page, no parse: ${pageUrl.take(100)}")
            return false
        }
        val pageOrigin = try {
            val u = java.net.URL(pageUrl)
            "${u.protocol}://${u.host}"
        } catch (_: Exception) { pageUrl }

        val candidates = mutableListOf<String>()
        Regex(
            """<script[^>]*type=["']application/json["'][^>]*>(.*?)</script>""",
            RegexOption.DOT_MATCHES_ALL
        ).find(html)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.contains("[\"") }
            ?.substringAfter("[\"")
            ?.substringBeforeLast("\"]")
            ?.let { candidates.add(it) }
        val scripts = Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html).map { it.groupValues[1] }
        for (body in scripts) {
            for (m in Regex("""["']([A-Za-z0-9+/=@\^~\*\?&#!%$]{100,})["']""").findAll(body)) {
                val cand = m.groupValues[1]
                if (!candidates.contains(cand)) candidates.add(cand)
            }
        }
        Log.d("MhdflixVoe", "[Voe] ${candidates.size} candidatos en $pageUrl")

        var encodedString: String? = null
        for (cand in candidates) {
            val test = decryptVoeF7(cand, quiet = true)
            if (test?.source != null || test?.directAccessUrl != null) {
                encodedString = cand
                break
            }
        }

        if (encodedString == null) {
            Log.w("MhdflixVoe", "[Voe] encoded string not found: ${pageUrl.take(100)}")
            return false
        }

        val decryptedJson = decryptVoeF7(encodedString)
        val m3u8 = decryptedJson?.source
        val mp4 = decryptedJson?.directAccessUrl
        var emitted = false
        if (m3u8 != null) {
            Log.d("MhdflixVoe", "[Voe] Found M3U8: ${m3u8.take(100)}")
            M3u8Helper.generateM3u8(name, m3u8, "$pageOrigin/", headers = mapOf("Origin" to "$pageOrigin/")).forEach(callback)
            emitted = true
        }
        if (mp4 != null) {
            Log.d("MhdflixVoe", "[Voe] Found MP4: ${mp4.take(100)}")
            callback.invoke(newExtractorLink("$name MP4", "$name MP4", mp4, INFER_TYPE) {
                this.referer = pageUrl
                this.quality = Qualities.Unknown.value
            })
            emitted = true
        }
        if (!emitted) Log.w("MhdflixVoe", "[Voe] No source found after decryption")
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
            if (!quiet) Log.e("MhdflixVoe", "[Voe] decrypt error: ${e.message}")
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

class MhdflixVoeYipsu : MhdflixVoe() {
    override val mainUrl = "https://yip.su"
}

class MhdflixVoeDonald : MhdflixVoe() {
    override val mainUrl = "https://donaldlineelse.com"
}

class MhdflixVoeCharles : MhdflixVoe() {
    override val mainUrl = "https://charlestoughrace.com"
}

class MhdflixVoeTubeless : MhdflixVoe() {
    override val mainUrl = "https://tubelessceliolymph.com"
}

class MhdflixVoeSimplum : MhdflixVoe() {
    override val mainUrl = "https://simpulumlamerop.com"
}

class MhdflixVoeUroch : MhdflixVoe() {
    override val mainUrl = "https://urochsunloath.com"
}

class MhdflixVoeNathan : MhdflixVoe() {
    override val mainUrl = "https://nathanfromsubject.com"
}

class MhdflixVoeMetagnath : MhdflixVoe() {
    override val mainUrl = "https://metagnathtuggers.com"
}

class MhdflixVoePamela : MhdflixVoe() {
    override val mainUrl = "https://pamelachangemission.com"
}

data class VoeDecrypted(
    @JsonProperty("source") val source: String? = null,
    @JsonProperty("direct_access_url") val directAccessUrl: String? = null,
)