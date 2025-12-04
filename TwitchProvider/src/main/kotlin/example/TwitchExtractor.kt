package example

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import android.util.Log
import java.lang.RuntimeException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class TwitchExtractor : ExtractorApi() {
    private val EXTRACTOR_TAG = "TwitchExtractor"

    override val mainUrl = "https://twitch.tv/"
    override val name = "Twitch"
    override val requiresReferer = false

    private val CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"

    data class PlaybackTokenResponse(
        @JsonProperty("data") val data: Data?,
    )

    data class Data(
        @JsonProperty("streamPlaybackAccessToken") val streamPlaybackAccessToken: StreamPlaybackAccessToken?,
    )

    data class StreamPlaybackAccessToken(
        @JsonProperty("signature") val signature: String,
        @JsonProperty("value") val value: String,
    )

    data class M3U8Link(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("name") val name: String? = null,
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.e(EXTRACTOR_TAG, "getUrl - URL de Stream recibida: $url")
        val channelName = url.substringAfterLast("/")

        val twitchApiUrl = "https://gql.twitch.tv/gql"

        val query = """
            query PlaybackAccessToken(${"$"}channelName: String!) {
              streamPlaybackAccessToken(
                channelName: ${"$"}channelName
                params: {
                  platform: "web",
                  playerType: "site" 
                }
              ) {
                value
                signature
              }
            }
        """.trimIndent()

        val requestBodyMap = mapOf(
            "query" to query,
            "variables" to mapOf("channelName" to channelName)
        )

        val jsonBodyString = mapper.writeValueAsString(requestBodyMap)

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBodyObject = jsonBodyString.toRequestBody(mediaType)

        Log.e(EXTRACTOR_TAG, "getUrl - Solicitando token de Playback para $channelName")
        Log.e(EXTRACTOR_TAG, "GraphQL Payload: $jsonBodyString")

        val rawTokenResponse = app.post(
            twitchApiUrl,
            requestBody = requestBodyObject,
            headers = mapOf(
                "Client-Id" to CLIENT_ID,
                "Content-Type" to "application/json"
            )
        )

        val rawResponseText = rawTokenResponse.text
        Log.e(EXTRACTOR_TAG, "GraphQL Raw Response: $rawResponseText")

        val tokenResponse = rawTokenResponse.parsed<PlaybackTokenResponse>()

        val accessToken = tokenResponse.data?.streamPlaybackAccessToken

        if (accessToken?.value.isNullOrBlank() || accessToken?.signature.isNullOrBlank()) {
            Log.e(EXTRACTOR_TAG, "getUrl - FALLO: No se pudo obtener el token/signature de Twitch. El canal podría estar offline o la respuesta de GraphQL no contiene los datos esperados.")
            throw RuntimeException("Failed to get Twitch playback token. Channel may be offline.")
        }

        Log.e(EXTRACTOR_TAG, "getUrl - Token y Signature obtenidos.")

        val token = accessToken.value
        val signature = accessToken.signature

        val usherUrl = "https://usher.ttvnw.net/api/channel/hls/$channelName.m3u8"
        val m3u8Url = "$usherUrl?sig=$signature&token=$token"

        Log.e(EXTRACTOR_TAG, "getUrl - URL del Manifiesto M3U8: $m3u8Url")

        val m3u8Text = app.get(
            m3u8Url,
            headers = mapOf("Client-Id" to CLIENT_ID)
        ).text

        val links = m3u8Text.lines().mapNotNull { line ->
            if (!line.startsWith("#") && line.isNotBlank()) {
                val prevLineIndex = m3u8Text.lines().indexOf(line) - 1
                val resolutionLine = if (prevLineIndex >= 0) m3u8Text.lines()[prevLineIndex] else null

                val resolutionMatch = Regex("RESOLUTION=\\d+x(\\d+),?").find(resolutionLine ?: "")
                val resolution = resolutionMatch?.groupValues?.get(1)?.plus("p") ?: "Source"

                M3U8Link(url = line, name = resolution)
            } else {
                null
            }
        }

        links.forEach { link ->
            val linkUrl = link.url ?: return@forEach
            val name = link.name ?: "Source"
            val quality = getQualityFromName(name.substringBefore('p'))

            Log.e(EXTRACTOR_TAG, "getUrl - Encontrado enlace. Nombre: $name, Calidad: $quality")

            callback.invoke(
                newExtractorLink(
                    this.name,
                    "${this.name} ${name}",
                    linkUrl
                ) {
                    this.type = ExtractorLinkType.M3U8
                    this.quality = quality
                    this.referer = m3u8Url
                }
            )
        }
        Log.e(EXTRACTOR_TAG, "getUrl - Finalizado el procesamiento de enlaces. Total links: ${links.size}")
    }
}