package com.example

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.fixUrl
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

open class OkRuExtractor : ExtractorApi() {
    override val name            = "OkRU"
    override val mainUrl         = "https://ok.ru"
    override val requiresReferer = false

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:135.0) Gecko/20100101 Firefox/135.0"

    private fun mapQuality(quality: String): Int {
        return when (quality.lowercase()) {
            "full"   -> Qualities.P1080.value
            "hd"     -> Qualities.P720.value
            "sd"     -> Qualities.P480.value
            "low"    -> Qualities.P360.value
            "lowest" -> Qualities.P240.value
            "mobile" -> Qualities.P144.value
            else     -> Qualities.Unknown.value
        }
    }

    private suspend fun invokeLink(
        quality: String,
        videoUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val mappedQuality = mapQuality(quality)
        Log.d(name, "$quality -> $videoUrl")
        callback(
            newExtractorLink(
                name,
                "$name MP4",
                fixUrl(videoUrl),
                ExtractorLinkType.VIDEO
            ) {
                headers = mutableMapOf(
                    "Referer"    to "$mainUrl/",
                    "Origin"     to mainUrl,
                    "User-Agent" to userAgent
                )
                this.quality = mappedQuality
            }
        )
    }

    private suspend fun invokeStreamLink(
        label: String,
        streamUrl: String,
        type: ExtractorLinkType,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(name, "$label -> $streamUrl")
        callback(
            newExtractorLink(
                name,
                label,
                streamUrl,
                type
            ) {
                headers = mutableMapOf(
                    "Referer"    to "$mainUrl/",
                    "Origin"     to mainUrl,
                    "User-Agent" to userAgent
                )
                this.quality = Qualities.Unknown.value
            }
        )
    }

    private fun jsonUrl(dataOptions: String, key: String): String? {
        return Regex(""""$key":"([^"]+)"""")
            .find(dataOptions)?.groupValues?.get(1)
            ?.replace("\\u0026", "&")
            ?.replace("\\/", "/")
            ?.replace("&amp;", "&")
            ?.takeIf { it.startsWith("http") }
    }

    private fun optionKeys(dataOptions: String): String {
        return Regex(""""([A-Za-z0-9_]+)":""").findAll(dataOptions)
            .map { it.groupValues[1] }
            .distinct()
            .joinToString(",")
    }

    private suspend fun probeStream(streamUrl: String, type: ExtractorLinkType): Boolean? {
        val label = if (type == ExtractorLinkType.DASH) "DASH" else "HLS"
        return try {
            val response = app.get(
                streamUrl,
                headers = mapOf(
                    "Referer"    to "$mainUrl/",
                    "Origin"     to mainUrl,
                    "User-Agent" to userAgent
                ),
                timeout = 10L
            )
            val body = response.text
            val marker = if (type == ExtractorLinkType.DASH) "<MPD" else "#EXTM3U"
            val ok = (response.code == 200 || response.code == 206) &&
                body.contains(marker, ignoreCase = true)
            Log.d(name, "probe $label -> code=${response.code} len=${body.length} ok=$ok")
            ok
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w(name, "probe $label fallo: ${t.message}")
            null
        }
    }

    private val probeClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private suspend fun probeProgressive(videoUrl: String): Boolean? {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(videoUrl)
                    .header("Range", "bytes=0-1023")
                    .header("Referer", "$mainUrl/")
                    .header("Origin", mainUrl)
                    .header("User-Agent", userAgent)
                    .build()
                probeClient.newCall(request).execute().use { response ->
                    val read = response.body?.byteStream()?.use { it.read(ByteArray(1024)) } ?: -1
                    val ok = (response.code == 200 || response.code == 206) && read > 0
                    Log.d(name, "probe MP4 -> code=${response.code} read=$read ok=$ok")
                    ok
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.w(name, "probe MP4 fallo: ${t.message}")
                null
            }
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(name, url)

        val id =
            Regex("""(?:videoembed|video)/(\d+)""").find(url.trim())?.groupValues?.get(1)
                ?: return
        val embedUrl = fixUrl("/videoembed/$id")

        val response = app.get(
            embedUrl,
            headers = mapOf(
                "User-Agent" to userAgent,
                "Accept"     to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
        )

        val dataOptions =
            response.document.selectFirst("[data-module=OKVideo]")?.attr("data-options")
                ?.ifEmpty { null }
                ?: Regex("""data-options="([^"]+)"""").find(response.text)?.groupValues?.get(1)
                    ?.replace("&quot;", "\"")
                ?: return

        val originalUrl =
            Regex(""""originalUrl":"([^"]+)"""").find(dataOptions)?.groupValues?.get(1)
                ?.replace("\\/", "/")
        if (originalUrl != null && (originalUrl.contains("youtube.com") || originalUrl.contains("youtu.be"))) {
            Log.d(name, "YouTube redirect: $originalUrl")
            loadExtractor(originalUrl, subtitleCallback, callback)
            return
        }

        val hlsUrl = jsonUrl(dataOptions, "ondemandHls") ?: jsonUrl(dataOptions, "hlsManifestUrl")
        val dashUrl = jsonUrl(dataOptions, "ondemandDash") ?: jsonUrl(dataOptions, "metadataUrl")

        Log.d(
            name,
            "id=$id hls=${hlsUrl != null} dash=${dashUrl != null} videos=${dataOptions.contains("\"videos\"")}"
        )
        if (hlsUrl == null && dashUrl == null) {
            Log.w(name, "sin ondemandHls/ondemandDash; claves=${optionKeys(dataOptions)}")
        }

        var emitted = 0

        if (hlsUrl != null) {
            if (probeStream(hlsUrl, ExtractorLinkType.M3U8) != false) {
                Log.d(name, "HLS ondemand")
                invokeStreamLink("$name HLS", hlsUrl, ExtractorLinkType.M3U8, callback)
                emitted++
            } else {
                Log.w(name, "HLS ondemand no responde, se omite")
            }
        }

        if (dashUrl != null) {
            if (probeStream(dashUrl, ExtractorLinkType.DASH) != false) {
                Log.d(name, "DASH ondemand")
                invokeStreamLink("$name DASH", dashUrl, ExtractorLinkType.DASH, callback)
                emitted++
            } else {
                Log.w(name, "DASH ondemand no responde, se omite")
            }
        }

        if (emitted > 0) {
            Log.d(name, "Emitidos $emitted (HLS/DASH) id=$id; agregando MP4 progresivo")
        } else if (hlsUrl != null || dashUrl != null) {
            Log.w(name, "HLS/DASH descartados por el probe; probando MP4 progresivo")
        }

        val videosJson =
            Regex(""""videos":(\[.*?\])""").find(dataOptions)?.groupValues?.get(1)
                ?: return

        val videoLinks =
            Regex("""\{"name":"([^"]+)","url":"([^"]+)"""").findAll(videosJson)
                .map { it.groupValues[1] to it.groupValues[2] }
                .sortedByDescending { mapQuality(it.first) }
                .toList()

        if (videoLinks.isEmpty()) {
            Log.d(name, "No video links found")
            return
        }

        Log.d(name, "MP4 progresivos: ${videoLinks.size}")

        videoLinks.forEach { (quality, videoUrl) ->
            val cleanUrl = videoUrl
                .replace("\\u0026", "&")
                .replace("\\/", "/")
            if (cleanUrl.contains("youtube.com") || cleanUrl.contains("youtu.be")) {
                return@forEach
            }
            val mp4Ok = probeProgressive(cleanUrl)
            when {
                mp4Ok == true -> invokeLink(quality, cleanUrl, callback)
                mp4Ok == false -> Log.w(name, "MP4 $quality no responde, se omite")
                emitted > 0 -> Log.w(name, "MP4 $quality sin verificar; hay HLS/DASH, se omite")
                else -> invokeLink(quality, cleanUrl, callback)
            }
        }
    }
}

class Odnoklassniki : OkRuExtractor() {
    override val name    = "OkRu"
    override val mainUrl = "https://odnoklassniki.ru"
}
