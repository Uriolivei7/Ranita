package com.example

/*
    CEO: Ranita
    Empresa: RANITA TECH
    Versión software: 1.0     fecha: 09/08/2025
*/

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.fixUrl
import org.json.JSONObject
import java.net.URL
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

open class Cubeembed : ExtractorApi() {
    override var name = "Cubeembed"
    override var mainUrl = "https://cubeembed.rpmvid.com"
    override val requiresReferer = true

    companion object {
        private const val TAG = "Cubeembed"
        // Claves extraidas del bundle de Turbovio (codepoints de keyChars/saltChars)
        private const val KEYBASE = "1d54a1d53c1d56e1d6811d53c1d6831d4da1d4d41d4e81d7d91d7da1d7db"
        private const val IVBASE = "1f5101f5dd1f5111f6aa1f6e126a1"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"
        private val HEX = "0123456789abcdef".toCharArray()
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "resolve start host=${try { URL(url).host } catch (_: Exception) { "?" }}")
        val res = try {
            resolveTurbovio(url, subtitleCallback)
        } catch (e: Exception) {
            Log.w(TAG, "resolve err ${e::class.simpleName}: ${e.message}")
            null
        }
        if (res == null) {
            Log.w(TAG, "Turbovio sin fuente")
            return
        }
        val isHls = res.streamUrl.contains(".m3u8") || res.streamUrl.contains(".txt")
        callback(newExtractorLink(this.name, this.name, res.streamUrl, if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
            this.referer = res.referer
            this.headers = mapOf(
                "User-Agent" to UA,
                "Referer" to res.referer,
                "Origin" to res.origin,
                "Accept" to "*/*",
            )
            this.quality = Qualities.Unknown.value
        })
        Log.d(TAG, "emitted ${res.streamUrl.take(120)}")
    }

    private suspend fun resolveTurbovio(inputUrl: String, subtitleCallback: (SubtitleFile) -> Unit): TurbovioResolved? {
        val parsed = URL(inputUrl)
        val inHost = parsed.host.lowercase()
        val pageUrl: String
        val slug: String
        val host: String

        if (inHost.contains("cubeembed")) {
            val file1 = inputUrl.substringAfter("#", "").substringBefore("?").trim()
            if (file1.isEmpty()) {
                Log.w(TAG, "cubeembed sin hash en fragmento")
                return null
            }
            val r = app.get(
                "https://cubeembed.rpmvid.com/api/public/resolve?file1=$file1",
                headers = mapOf("User-Agent" to UA, "Referer" to "https://cubeembed.rpmvid.com/"),
                timeout = 20L,
            )
            val j = JSONObject(r.text)
            pageUrl = j.optString("url", "")
            if (pageUrl.isEmpty()) {
                Log.w(TAG, "resolve sin url (${r.text.take(120)})")
                return null
            }
            val pu = URL(pageUrl)
            host = pu.host.lowercase()
            slug = pu.path.trimEnd('/').substringAfterLast('/')
        } else if (inHost.contains("turboembed")) {
            pageUrl = inputUrl
            host = inHost
            slug = parsed.path.trimEnd('/').substringAfterLast('/')
        } else {
            return null
        }

        if (slug.isEmpty()) {
            Log.w(TAG, "slug vacío")
            return null
        }

        val c = aesEncrypt(slug, host)
        val trackingUrl = "https://$host/api/stream/tracking?c=$c"
        val trackHeaders = mapOf(
            "User-Agent" to UA,
            "Referer" to pageUrl,
            "Origin" to "https://$host",
            "Accept" to "*/*",
        )
        val resp = app.get(trackingUrl, headers = trackHeaders, timeout = 25L)
        val xt = resp.headers["x-tracking"] ?: resp.headers["X-Tracking"]
        if (xt.isNullOrBlank()) {
            Log.w(TAG, "sin x-tracking (http ${resp.code})")
            return null
        }
        val decrypted = aesDecrypt(xt, host)
        val obj = JSONObject(decrypted)
        val stream = obj.optJSONObject("stream") ?: obj
        val src = listOf("hls", "tiktok", "direct")
            .map { stream.optString(it, "") }
            .firstOrNull { it.isNotBlank() }
        if (src.isNullOrBlank()) {
            Log.w(TAG, "stream sin hls/tiktok/direct")
            return null
        }
        Log.d(TAG, "Turbovio src host=${try { URL(src).host } catch (_: Exception) { "?" }}")

        // Subtitles (si vienen)
        val subs = stream.optJSONArray("subtitles")
        if (subs != null) {
            for (i in 0 until subs.length()) {
                val s = subs.optJSONObject(i) ?: continue
                val subUrl = s.optString("url", "")
                if (subUrl.isBlank()) continue
                val lang = s.optString("language", s.optString("lang", "es"))
                subtitleCallback(com.lagradost.cloudstream3.SubtitleFile(lang, subUrl))
            }
        }

        return TurbovioResolved(src, referer = "https://$host/", origin = "https://$host")
    }

    private fun dayHex(): String = (System.currentTimeMillis() / 86_400_000L).toString(16)

    private fun buildKey(): String {
        val raw = KEYBASE + dayHex()
        return if (raw.length >= 32) raw.substring(0, 32) else raw.padEnd(32, '0')
    }

    private fun buildIv(host: String): String {
        val raw = IVBASE + toHex(host.toByteArray(Charsets.UTF_8))
        return if (raw.length >= 32) raw.substring(0, 32) else raw.padEnd(32, '0')
    }

    private fun aesEncrypt(text: String, host: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(fromHex(buildKey()), "AES"),
            IvParameterSpec(fromHex(buildIv(host))),
        )
        return toHex(cipher.doFinal(text.toByteArray(Charsets.UTF_8)))
    }

    private fun aesDecrypt(hex: String, host: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(fromHex(buildKey()), "AES"),
            IvParameterSpec(fromHex(buildIv(host))),
        )
        return String(cipher.doFinal(fromHex(hex)), Charsets.UTF_8)
    }

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private fun fromHex(hex: String): ByteArray {
        val clean = hex.trim()
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "hex invalido" }
            out[i] = ((hi shl 4) + lo).toByte()
        }
        return out
    }

    private data class TurbovioResolved(val streamUrl: String, val referer: String, val origin: String)
}

class Turboembed : Cubeembed() {
    override var name = "TurboEmbed"
    override var mainUrl = "https://turboembed.com"
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
            generateM3u8(name, urlString, mainUrl).forEach(callback)
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
