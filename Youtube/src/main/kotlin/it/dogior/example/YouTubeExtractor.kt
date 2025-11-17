package it.dogior.example

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.schemaStripRegex
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory

open class YouTubeExtractor() : ExtractorApi() {
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false
    override val name = "YouTube"

    private fun mapResolutionToQuality(resolution: String): Qualities {
        return when (resolution.lowercase().trim()) {
            "2160p" -> Qualities.P2160
            "1440p" -> Qualities.P1440
            "1080p" -> Qualities.P1080
            "720p" -> Qualities.P720
            "480p" -> Qualities.P480
            "360p" -> Qualities.P360
            "240p" -> Qualities.P240
            "144p" -> Qualities.P144
            else -> Qualities.Unknown
        }
    }

    @Suppress("DEPRECATION")
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val link =
            YoutubeStreamLinkHandlerFactory.getInstance().fromUrl(
                url.replace(schemaStripRegex, "")
            )

        val extractor = object : YoutubeStreamExtractor(
            ServiceList.YouTube,
            link
        ) {}

        extractor.fetchPage()

        val videoStreams = extractor.videoStreams?.filterNotNull() ?: emptyList()
        val audioStreams = extractor.audioStreams?.filterNotNull() ?: emptyList()

        videoStreams.forEach { stream ->
            val quality = mapResolutionToQuality(stream.resolution)

            if (quality != Qualities.Unknown) {
                callback.invoke(
                    newExtractorLink(
                        this.name,
                        this.name,
                        stream.url!!,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = url
                        this.quality = quality.value
                    }
                )
            }
        }

        audioStreams.forEach { stream ->
            callback.invoke(
                newExtractorLink(
                    this.name,
                    this.name,
                    stream.url!!,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        val subtitles = try {
            extractor.subtitlesDefault.filterNotNull()
        } catch (e: Exception) {
            logError(e)
            emptyList()
        }
        subtitles.mapNotNull {
            SubtitleFile(it.languageTag ?: return@mapNotNull null, it.content ?: return@mapNotNull null)
        }.forEach(subtitleCallback)
    }
}