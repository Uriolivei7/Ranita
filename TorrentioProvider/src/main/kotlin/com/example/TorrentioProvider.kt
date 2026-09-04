package com.example

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import java.net.URLEncoder

class TorrentioProvider : MainAPI() {
    override var mainUrl = "https://torrentio.strem.fun"
    override var name = "Torrentio"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override var lang = "mx"
    override val hasMainPage = true

    companion object {
        const val CINEMETA = "https://v3-cinemeta.strem.io"
        const val TRACKERS_URL = "https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_best.txt"

        const val TIO_CONFIG = "qualityfilter=cam,scr|sort=seeders"

        const val OPENSUBS = "https://opensubtitles-v3.strem.io"

        @Volatile var cachedTrackers: List<String>? = null
        @Volatile var cachedTrackersAt: Long = 0L
        const val TRACKERS_TTL_MS = 3600000L
    }

    data class CineMeta(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("background") val background: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("releaseInfo") val releaseInfo: String? = null,
        @JsonProperty("imdbRating") val imdbRating: String? = null,
    )

    data class CineCatalog(@JsonProperty("metas") val metas: List<CineMeta>? = null)

    data class CineVideo(
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("thumbnail") val thumbnail: String? = null,
    )

    data class CineDetail(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("background") val background: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("releaseInfo") val releaseInfo: String? = null,
        @JsonProperty("imdbRating") val imdbRating: String? = null,
        @JsonProperty("videos") val videos: List<CineVideo>? = null,
    )

    data class CineMetaResp(@JsonProperty("meta") val meta: CineDetail? = null)

    data class TioBehavior(@JsonProperty("filename") val filename: String? = null)

    data class TioStream(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("infoHash") val infoHash: String? = null,
        @JsonProperty("fileIdx") val fileIdx: Int? = null,
        @JsonProperty("behaviorHints") val behaviorHints: TioBehavior? = null,
        @JsonProperty("sources") val sources: List<String>? = null,
    )

    data class TioResp(@JsonProperty("streams") val streams: List<TioStream>? = null)

    data class TioLoad(
        val imdb: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val isMovie: Boolean = true,
    )

    data class OsSub(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("lang") val lang: String? = null,
    )

    data class OsResp(@JsonProperty("subtitles") val subtitles: List<OsSub>? = null)

    private fun metaToSearch(m: CineMeta, type: TvType): SearchResponse? {
        val title = m.name ?: return null
        val id = m.id ?: return null
        val metaUrl = "$CINEMETA/meta/${if (type == TvType.Movie) "movie" else "series"}/$id.json"
        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, metaUrl, type) { this.posterUrl = m.poster }
        } else {
            newTvSeriesSearchResponse(title, metaUrl, type) { this.posterUrl = m.poster }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val lists = listOf(
            "Películas Top" to ("$CINEMETA/catalog/movie/top.json" to TvType.Movie),
            "Series Top" to ("$CINEMETA/catalog/series/top.json" to TvType.TvSeries),
        ).amap { (name, pair) ->
            val (url, type) = pair
            try {
                val items = parseJson<CineCatalog>(app.get(url, timeout = 15000L).text)
                    ?.metas?.mapNotNull { metaToSearch(it, type) } ?: emptyList()
                Log.d("Torrentio", "mainPage $name: ${items.size}")
                HomePageList(name, items)
            } catch (e: Exception) {
                Log.w("Torrentio", "mainPage $name error: ${e.message}")
                HomePageList(name, emptyList())
            }
        }
        return newHomePageResponse(lists, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val q = URLEncoder.encode(query, "UTF-8")
        return listOf(
            "$CINEMETA/catalog/movie/top/search=$q.json" to TvType.Movie,
            "$CINEMETA/catalog/series/top/search=$q.json" to TvType.TvSeries,
        ).amap { (url, type) ->
            try {
                parseJson<CineCatalog>(app.get(url, timeout = 15000L).text)
                    ?.metas?.mapNotNull { metaToSearch(it, type) } ?: emptyList()
            } catch (e: Exception) {
                Log.w("Torrentio", "search error: ${e.message}")
                emptyList()
            }
        }.flatten().distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val res = parseJson<CineMetaResp>(app.get(url, timeout = 15000L).text)?.meta
                ?: return null
            val title = res.name ?: return null
            val imdb = res.id?.takeIf { it.startsWith("tt") } ?: res.id
            val year = res.releaseInfo?.take(4)?.toIntOrNull()
            val score = res.imdbRating?.toDoubleOrNull()?.let { Score.from10(it) }
            if (res.type == "series" && !res.videos.isNullOrEmpty()) {
                val episodes = res.videos.mapNotNull { v ->
                    val s = v.season ?: return@mapNotNull null
                    val e = v.episode ?: return@mapNotNull null
                    newEpisode(TioLoad(imdb, s, e, false).toJson()) {
                        this.name = v.title
                        this.season = s
                        this.episode = e
                        this.posterUrl = v.thumbnail
                        this.description = v.overview
                    }
                }
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = res.poster
                    this.backgroundPosterUrl = res.background
                    this.plot = res.description
                    this.year = year
                    this.score = score
                }
            } else {
                newMovieLoadResponse(title, url, TvType.Movie, TioLoad(imdb, null, null, true).toJson()) {
                    this.posterUrl = res.poster
                    this.backgroundPosterUrl = res.background
                    this.plot = res.description
                    this.year = year
                    this.score = score
                }
            }
        } catch (e: Exception) {
            Log.e("Torrentio", "load error: ${e.message}")
            null
        }
    }

    private suspend fun getTrackers(): List<String> {
        val now = System.currentTimeMillis()
        cachedTrackers?.let { if (now - cachedTrackersAt < TRACKERS_TTL_MS) return it }
        return try {
            val list = app.get(TRACKERS_URL, timeout = 15000L).text
                .lineSequence().map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .take(20).toList()
            cachedTrackers = list
            cachedTrackersAt = now
            list
        } catch (e: Exception) {
            Log.w("Torrentio", "trackers error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val ld = parseJson<TioLoad>(data) ?: return false
            val imdb = ld.imdb?.takeIf { it.startsWith("tt") } ?: run {
                Log.w("Torrentio", "sin imdb id: $data")
                return false
            }
            val streamUrl = if (ld.isMovie || ld.season == null) {
                "$mainUrl/$TIO_CONFIG/stream/movie/$imdb.json"
            } else {
                "$mainUrl/$TIO_CONFIG/stream/series/$imdb:${ld.season}:${ld.episode}.json"
            }
            Log.d("Torrentio", "loadLinks: $streamUrl")
            val streams = parseJson<TioResp>(app.get(streamUrl, timeout = 30000L).text)
                ?.streams?.filter { !it.infoHash.isNullOrBlank() } ?: emptyList()
            Log.d("Torrentio", "loadLinks: ${streams.size} torrents")
            if (streams.isEmpty()) return false
            val globalTrackers = getTrackers()
            var count = 0
            for (s in streams.take(50)) {
                val hash = s.infoHash ?: continue
                val perStream = s.sources
                    ?.filter { it.startsWith("tracker:") }
                    ?.map { it.removePrefix("tracker:") }
                    ?.filter { it.isNotBlank() } ?: emptyList()
                val trackers = (perStream + globalTrackers).distinct().take(15)
                val magnet = buildString {
                    append("magnet:?xt=urn:btih:").append(hash)
                    s.behaviorHints?.filename?.takeIf { it.isNotBlank() }?.let {
                        append("&dn=").append(URLEncoder.encode(it, "UTF-8"))
                    }
                    trackers.forEach {
                        append("&tr=").append(URLEncoder.encode(it, "UTF-8"))
                    }

                    s.fileIdx?.let { append("&index=").append(it) }
                }
                val blob = listOf(s.name, s.title).joinToString(" ")
                val q = Regex("""(\d{3,4}[pP])""").find(blob)?.groupValues?.get(1) ?: ""
                val seeds = Regex("""👤\s*(\d+)""").find(s.title ?: "")?.groupValues?.get(1)
                val size = Regex("""💾\s*([\d.]+\s*\wB)""").find(s.title ?: "")?.groupValues?.get(1)
                var label = "Torrentio"
                if (q.isNotBlank()) label += " $q"
                val group = Regex("""^\[([^\]]+)\]""")
                    .find(s.behaviorHints?.filename ?: "")?.groupValues?.get(1)
                    ?: Regex("""^\[([^\]]+)\]""").find(s.title ?: "")?.groupValues?.get(1)
                if (!group.isNullOrBlank()) label += " [$group]"
                if (seeds != null) label += " 👤$seeds"
                if (size != null) label += " $size"
                if ((s.fileIdx ?: 0) != 0) label += " [${s.fileIdx}]"
                callback(newExtractorLink("Torrentio", label, magnet) {
                    this.quality = getQualityFromName(if (q.isNotBlank()) q else null)
                })
                count++
            }

            try {
                val subUrl = if (ld.isMovie || ld.season == null) {
                    "$OPENSUBS/subtitles/movie/$imdb.json"
                } else {
                    "$OPENSUBS/subtitles/series/$imdb:${ld.season}:${ld.episode}.json"
                }
                val subs = parseJson<OsResp>(app.get(subUrl, timeout = 15000L).text)
                    ?.subtitles?.filter { !it.url.isNullOrBlank() } ?: emptyList()
                val wanted = subs.filter { it.lang in setOf("spa", "esp", "eng") }
                    .sortedBy { if (it.lang == "eng") 1 else 0 }.take(6)
                for (sub in wanted) {
                    val langName = when (sub.lang) {
                        "spa", "esp" -> "Español"
                        "eng" -> "English"
                        else -> sub.lang ?: "Sub"
                    }
                    Log.d("Torrentio", "sub: $langName ${sub.url?.take(80)}")
                    subtitleCallback.invoke(newSubtitleFile(langName, sub.url!!))
                }
                Log.d("Torrentio", "subs emitidos: ${wanted.size}/${subs.size}")
            } catch (e: Exception) {
                Log.w("Torrentio", "subs error: ${e.message}")
            }
            count > 0
        } catch (e: Exception) {
            Log.e("Torrentio", "loadLinks error: ${e.message}")
            false
        }
    }
}