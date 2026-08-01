package com.example

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink


class MediaCacheExtractor : ExtractorApi() {
    override val name = "MediaCache"
    override val mainUrl = "https://get.mediacache.cc"
    override val requiresReferer = true

    private val TAG = "MediaCacheExtractor"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "Extrayendo: $url")
        Log.d(TAG, "Referer: $referer")

        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Accept" to "*/*",
                "Origin" to (referer?.substringBefore("/watch") ?: "https://anime.uniquestream.net"),
                "Referer" to (referer ?: "https://anime.uniquestream.net/")
            )

            val m3u8Response = app.get(url, headers = headers, timeout = 20L)

            if (m3u8Response.code != 200) {
                Log.e(TAG, "Error al descargar m3u8: ${m3u8Response.code}")
                return
            }

            val m3u8Content = m3u8Response.text
            Log.d(TAG, "M3U8 descargado, parseando variantes...")

            val variants = parseM3U8Variants(m3u8Content, url)

            Log.d(TAG, "Encontradas ${variants.size} variantes")

            variants.forEach { variant ->

                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "$name - ${variant.quality}",
                        url = variant.url,
                        type = ExtractorLinkType.M3U8,
                    ) {
                        this.referer = referer ?: "https://anime.uniquestream.net/"
                        this.quality = variant.qualityValue
                        this.headers = headers
                    }
                )

                Log.d(TAG, "✓ Agregado: ${variant.quality}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error en extractor: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun parseM3U8Variants(content: String, baseUrl: String): List<M3U8Variant> {
        val variants = mutableListOf<M3U8Variant>()
        val lines = content.lines()
        var currentQuality = ""
        var currentResolution = ""
        var currentBandwidth = 0

        for (i in lines.indices) {
            val line = lines[i].trim()

            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val resMatch = Regex("RESOLUTION=(\\d+)x(\\d+)").find(line)
                if (resMatch != null) {
                    val width = resMatch.groupValues[1].toIntOrNull() ?: 0
                    val height = resMatch.groupValues[2].toIntOrNull() ?: 0
                    currentResolution = "${width}x${height}"

                    currentQuality = when {
                        height >= 1080 -> "1080p"
                        height >= 720 -> "720p"
                        height >= 480 -> "480p"
                        height >= 360 -> "360p"
                        else -> "${height}p"
                    }
                }

                val bwMatch = Regex("BANDWIDTH=(\\d+)").find(line)
                currentBandwidth = bwMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

            } else if (!line.startsWith("#") && line.isNotEmpty() && currentQuality.isNotEmpty()) {
                val variantUrl = if (line.startsWith("http")) {
                    line
                } else {
                    val base = baseUrl.substringBeforeLast("/")
                    "$base/$line"
                }

                val qualityValue = when (currentQuality) {
                    "1080p" -> Qualities.P1080.value
                    "720p" -> Qualities.P720.value
                    "480p" -> Qualities.P480.value
                    "360p" -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }

                variants.add(
                    M3U8Variant(
                        url = variantUrl,
                        quality = currentQuality,
                        qualityValue = qualityValue,
                        resolution = currentResolution,
                        bandwidth = currentBandwidth
                    )
                )

                currentQuality = ""
            }
        }

        return variants
    }

    data class M3U8Variant(
        val url: String,
        val quality: String,
        val qualityValue: Int,
        val resolution: String,
        val bandwidth: Int
    )
}