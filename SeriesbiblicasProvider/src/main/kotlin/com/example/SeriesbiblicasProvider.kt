package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.loadExtractor
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element

data class SeriesInfo(val title: String, val url: String, val posterUrl: String?)
data class VideoEntry(val name: String, val urls: List<String>, val isNewSeason: Boolean = false)

class SeriesbiblicasProvider : MainAPI() {
    override var mainUrl = "https://seriesbiblicas.net"
    override var name = "SeriesBiblicas"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Cartoon)

    override val mainPage = mainPageOf("/" to "Todas las Series")

    private val excludedSlugs = setOf(
        "portada", "peliculas", "biblias", "ensenanzas", "alabanzas",
        "devocionales", "donar", "aviso-legal", "politica-de-privacidad",
        "category", "series"
    )

    private fun parsePageTitle(page: JSONObject): String {
        return page.getJSONObject("title").getString("rendered")
            .replace("&#8211;", "-")
            .replace("&#8220;", "\"")
            .replace("&#8221;", "\"")
            .replace(Regex("""\s*\u25b6\s*$"""), "")
            .trim()
    }

    private fun isRedirected(title: String): Boolean {
        return title.contains("(REDIRECTED)", ignoreCase = true)
    }

    private suspend fun fetchMediaUrlMap(ids: Collection<Int>): Map<Int, String> {
        if (ids.isEmpty()) return emptyMap()
        val map = mutableMapOf<Int, String>()
        try {
            val resp = app.get("$mainUrl/wp-json/wp/v2/media?include=${ids.joinToString(",")}&per_page=100&_fields=id,source_url")
            val arr = JSONArray(resp.text)
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                map[m.getInt("id")] = m.getString("source_url")
            }
        } catch (e: Exception) {
            Log.e("SeriesBiblicas", "fetchMediaUrlMap: ${e.message}")
        }
        return map
    }

    private suspend fun fetchSeriesWithPosters(): List<SeriesInfo> {
        val resp = app.get("$mainUrl/wp-json/wp/v2/pages?per_page=100&_fields=featured_media,id,slug,link,title")
        val pages = JSONArray(resp.text)
        val series = mutableListOf<SeriesInfo>()
        val seen = mutableSetOf<String>()
        val mediaIds = mutableSetOf<Int>()
        val mediaToIndices = mutableMapOf<Int, MutableList<Int>>()

        for (i in 0 until pages.length()) {
            val page = pages.getJSONObject(i)
            val slug = page.getString("slug")
            if (slug.isBlank() || slug in excludedSlugs || !seen.add(slug)) continue
            val title = parsePageTitle(page)
            if (isRedirected(title)) continue
            val link = page.getString("link")
            val mediaId = page.optInt("featured_media", 0)
            val idx = series.size
            series.add(SeriesInfo(title, link, null))
            if (mediaId > 0) {
                mediaIds.add(mediaId)
                mediaToIndices.getOrPut(mediaId) { mutableListOf() }.add(idx)
            }
        }

        val mediaMap = fetchMediaUrlMap(mediaIds)
        for ((mediaId, indices) in mediaToIndices) {
            val url = mediaMap[mediaId]
            if (url != null) {
                for (idx in indices) {
                    series[idx] = series[idx].copy(posterUrl = url)
                }
            }
        }

        return series
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d("SeriesBiblicas", "getMainPage: fetching via REST API")
        val series = fetchSeriesWithPosters()
        Log.d("SeriesBiblicas", "getMainPage: got ${series.size} series from API")

        val home = series.map { info ->
            newTvSeriesSearchResponse(info.title, info.url, TvType.TvSeries) {
                this.posterUrl = info.posterUrl
            }
        }

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = home, isHorizontalImages = false),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d("SeriesBiblicas", "search: query=$query via REST API")
        val result = mutableListOf<SearchResponse>()

        try {
            val resp = app.get("$mainUrl/wp-json/wp/v2/pages?search=$query&per_page=30&_fields=featured_media,id,slug,link,title")
            val pages = JSONArray(resp.text)
            Log.d("SeriesBiblicas", "search: API returned ${pages.length()} results")

            val series = mutableListOf<SeriesInfo>()
            val mediaIds = mutableSetOf<Int>()
            val mediaToIndices = mutableMapOf<Int, MutableList<Int>>()

            for (i in 0 until pages.length()) {
                val page = pages.getJSONObject(i)
                val slug = page.getString("slug")
                if (slug.isBlank() || slug in excludedSlugs) continue
                val title = parsePageTitle(page)
                if (isRedirected(title)) continue
                val link = page.getString("link")
                val mediaId = page.optInt("featured_media", 0)
                val idx = series.size
                series.add(SeriesInfo(title, link, null))
                if (mediaId > 0) {
                    mediaIds.add(mediaId)
                    mediaToIndices.getOrPut(mediaId) { mutableListOf() }.add(idx)
                }
            }

            val mediaMap = fetchMediaUrlMap(mediaIds)
            for ((mediaId, indices) in mediaToIndices) {
                val url = mediaMap[mediaId]
                if (url != null) {
                    for (idx in indices) {
                        series[idx] = series[idx].copy(posterUrl = url)
                    }
                }
            }

            for (info in series) {
                Log.d("SeriesBiblicas", "search: ${info.title} -> ${info.url}")
                result.add(newTvSeriesSearchResponse(info.title, info.url, TvType.TvSeries) {
                    this.posterUrl = info.posterUrl
                })
            }
        } catch (e: Exception) {
            Log.e("SeriesBiblicas", "search: API failed, fallback to HTML. ${e.message}")
            val doc = app.get("$mainUrl/?s=$query").document
            doc.select("a[href^='$mainUrl/']").forEach { link ->
                val url = link.attr("abs:href")
                if (url.isBlank()) return@forEach
                val slug = url.removeSuffix("/").substringAfterLast("/")
                if (slug.isBlank() || slug in excludedSlugs) return@forEach
                val title = link.ownText().trim().ifBlank { link.text().trim() }
                if (title.isBlank() || title.length < 3 || isRedirected(title)) return@forEach
                result.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries))
            }
        }

        Log.d("SeriesBiblicas", "search: total=${result.size}")
        return result
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d("SeriesBiblicas", "load: $url")
        val document = app.get(url).document

        val title = document.selectFirst("title")?.text()
            ?.replace(Regex("""\s*[-–|]\s*SERIESBIBLICAS\.NET"""), "")
            ?.replace(Regex("""\s*[-–|]\s*Series\s*Bíblicas"""), "")
            ?.replace(Regex("""\s*[-–|]\s*Series\s*Biblicas"""), "")
            ?.replace(Regex("""\s*[-–|.]\s*NET\s*$"""), "")
            ?.trim()
            ?: document.selectFirst(".entry-title")?.text()
            ?: slugToTitle(url.removeSuffix("/").substringAfterLast("/"))
        Log.d("SeriesBiblicas", "load: title=$title")

        val poster = fixUrl(document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: document.selectFirst("img.aligncenter.size-full")?.attr("src"))
        Log.d("SeriesBiblicas", "load: poster=$poster")

        val slugLang = languageFromSlug(url.removeSuffix("/").substringAfterLast("/"))
        val description = document.select(".su-tabs-pane").getOrNull(1)?.let { pane ->
            val txt = pane.text()
            val markers = listOf("SINOPSIS", "SIPNOSIS", "sinopsis", "sipnosis", "Argumento")
            markers.firstNotNullOfOrNull { marker ->
                val idx = txt.indexOf(marker)
                if (idx >= 0) txt.substring(idx + marker.length).trim().takeIf { it.length > 20 } else null
            }
        } ?: document.selectFirst("meta[name='description']")?.attr("content")
            ?.let { if (slugLang != null) "$slugLang — $it" else it }
            ?: slugLang
        Log.d("SeriesBiblicas", "load: description=${description?.take(80) ?: "null"}")

        val episodes = ArrayList<Episode>()
        val allVideoEntries = extractAllVideoEntries(document)
        Log.d("SeriesBiblicas", "load: total video entries=${allVideoEntries.size}")

        var globalIndex = 0
        var currentSeason = 1
        var epInSeason = 0
        for ((index, entry) in allVideoEntries.withIndex()) {
            val (name, urls, isNewSeason) = entry
            if (isNewSeason && globalIndex > 0) {
                currentSeason++
                epInSeason = 0
            }
            epInSeason++
            Log.d("SeriesBiblicas", "load: ep$globalIndex title=$name urls=${urls.size} season=$currentSeason")
            episodes.add(newEpisode("$url?ep=$globalIndex") {
                this.name = name
                this.episode = epInSeason
                this.season = currentSeason
                this.posterUrl = poster
            })
            globalIndex++
        }

        Log.d("SeriesBiblicas", "load: returning ${episodes.size} episodes for $title")
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epIndex = Regex("""[?&]ep=(\d+)""").find(data)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val seriesUrl = data.substringBefore("?ep=").substringBefore("&ep=")
        Log.d("SeriesBiblicas", "loadLinks: epIndex=$epIndex seriesUrl=$seriesUrl")

        val document = app.get(seriesUrl).document
        val allEntries = extractAllVideoEntries(document)
        Log.d("SeriesBiblicas", "loadLinks: total entries=${allEntries.size}, index=$epIndex")

        val entry = allEntries.getOrNull(epIndex)
        if (entry == null) {
            Log.d("SeriesBiblicas", "loadLinks: entry $epIndex not found!")
            return false
        }

        val videoUrls = entry.urls
        Log.d("SeriesBiblicas", "loadLinks: found ${videoUrls.size} video URLs")

        var found = false
        videoUrls.forEach { videoUrl ->
            Log.d("SeriesBiblicas", "loadLinks: videoUrl=$videoUrl")
            if (videoUrl.isNotBlank()) {
                loadExtractor(videoUrl, seriesUrl, subtitleCallback, callback)
                found = true
            }
        }

        Log.d("SeriesBiblicas", "loadLinks: found=$found")
        return found
    }

    private fun extractVideoUrls(parent: Element): List<String> {
        val urls = mutableListOf<String>()

        parent.select(".su-lightbox[data-mfp-src]").forEach {
            var src = it.attr("data-mfp-src")
            if (src.startsWith("//")) src = "https:$src"
            urls.add(src)
        }

        parent.select("iframe[src]").forEach {
            val src = it.attr("abs:src")
            if (src.isNotBlank()) urls.add(src)
        }

        parent.select("a[href]").forEach {
            val href = it.attr("abs:href")
            if (href.contains("ok.ru") || href.contains("youtube") || href.contains("sendvid") ||
                href.contains("filemoon") || href.contains("vk.com") || href.contains("tokyvideo") ||
                href.contains("drive.google") || href.contains("mega.nz") || href.contains("vimeo") ||
                href.contains("dailymotion")) {
                urls.add(href)
            }
        }

        return urls.distinct()
    }

    private fun extractAllVideoEntries(document: org.jsoup.nodes.Document): List<VideoEntry> {
        val spoilers = document.select(".su-accordion .su-spoiler")
        if (spoilers.isNotEmpty()) {
            var spoilerIsNewSeason = false
            val entries = spoilers.mapNotNull { spoiler ->
                val videoUrls = extractVideoUrls(spoiler)
                if (videoUrls.isEmpty()) return@mapNotNull null
                val title = spoiler.selectFirst(".su-spoiler-title")?.text() ?: "Capítulo"
                VideoEntry(title, videoUrls)
            }
            if (entries.isNotEmpty()) return entries
        }

        val entries = mutableListOf<VideoEntry>()
        val carousels = document.select(".su-tabs-pane .owl-carousel")
        Log.d("SeriesBiblicas", "extractAllVideoEntries: found ${carousels.size} carousels")

        carousels.forEachIndexed { carouselIdx, carousel ->
            val containers = carousel.select(".sa_hover_container")
            Log.d("SeriesBiblicas", "extractAllVideoEntries: carousel $carouselIdx has ${containers.size} items")

            containers.forEachIndexed { epIdx, container ->
                val urls = extractVideoUrls(container)
                if (urls.isEmpty()) return@forEachIndexed
                val rawText = container.wholeText()
                val epName = Regex("""CAP\s*[#:]?\s*(\d+)""").find(rawText)
                    ?.let { "Capítulo ${it.groupValues[1]}" }
                    ?: "Capítulo ${epIdx + 1}"
                Log.d("SeriesBiblicas", "extractAllVideoEntries: ep=${epName} urls=${urls.size}")
                entries.add(VideoEntry(epName, urls, isNewSeason = epIdx == 0))
            }
        }

        return entries
    }

    private fun languageFromSlug(slug: String): String? {
        return when {
            slug.contains("audio-latino") -> "Audio Latino"
            slug.contains("subtitulada") -> "Subtitulada"
            slug.contains("sub") && !slug.contains("super") -> "Subtitulada"
            slug.contains("latino") -> "Latino"
            slug.contains("espanol") || slug.contains("español") -> "Español"
            slug.contains("portugues") || slug.contains("português") -> "Português"
            slug.contains("english") || slug.contains("subtitled") -> "English Subtitled"
            else -> null
        }
    }

    private fun slugToTitle(slug: String): String {
        val clean = slug
            .replace("-hd", " HD")
            .replace("-google-drive", "")
            .replace("-ok-ru", "")
            .replace("-imagentv", " ImagenTV")
            .replace("-unife-google-drive", "")
            .replace("-unife", "")
            .replace("-audio-latino", " (Audio Latino)")
            .replace("-latino", " (Latino)")
            .replace("-subtitulada", " (Subtitulada)")
            .replace("-sub", " (Sub)")
            .replace("-espanol", " (Español)")
            .replace("-portugues", " (PT)")
        return clean.split("-").joinToString(" ") { word ->
            if (word.all { it.isUpperCase() }) word else word.replaceFirstChar { it.uppercase() }
        }.trim()
    }

    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return if (url.startsWith("//")) "https:$url" else url
    }
}
