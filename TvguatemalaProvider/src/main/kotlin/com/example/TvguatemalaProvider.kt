package com.example

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class TvguatemalaProvider : MainAPI() {
    override var mainUrl = "https://www.tvguatemalaenvivo.com"
    override var name = "TVGuatemala"
    override var lang = "mx"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = false

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null
        val doc = fetchDoc(mainUrl) ?: return null
        val sections = mutableListOf<HomePageList>()
        var currentSection = "Canales en vivo"
        var currentItems = mutableListOf<SearchResponse>()
        for (el in doc.select("h2, div.single-video")) {
            if (el.tagName().equals("h2", ignoreCase = true)) {
                if (currentItems.isNotEmpty()) {
                    sections.add(HomePageList(currentSection, currentItems))
                    currentItems = mutableListOf()
                }
                currentSection = el.text().trim().ifBlank { currentSection }
            } else {
                val a = el.selectFirst("a[href*=/livetv/details/]") ?: continue
                val url = a.attr("abs:href")
                val title = cleanChannelTitle(a.attr("title").ifBlank {
                    a.selectFirst("span.video-item-content")?.text()
                        ?: a.selectFirst("img")?.attr("alt")
                        ?: ""
                })
                val logo = a.selectFirst("img")?.attr("abs:src") ?: ""
                if (url.isNotBlank() && title.isNotBlank()) {
                    currentItems.add(newLiveSearchResponse(title, url, TvType.Live) {
                        this.posterUrl = posterFor(title, logo.takeIf { it.isNotBlank() })
                    })
                }
            }
        }
        if (currentItems.isNotEmpty()) {
            sections.add(HomePageList(currentSection, currentItems))
        }
        if (sections.isEmpty()) {
            val cards = parseCards(doc)
            if (cards.isEmpty()) return null
            sections.add(HomePageList("Canales en vivo", cards.map { channelItem(it) }))
        }
        return newHomePageResponse(sections, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val qn = fold(q)
        return try {
            val doc = fetchDoc(mainUrl) ?: return emptyList()
            val cards = parseCards(doc)
            val matched = cards.asSequence()
                .filter { fold(it.name).contains(qn) }
                .distinctBy { it.url }
                .take(40)
                .toList()
            matched.map { channelItem(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "search '$q' fallo: ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val u = url.trim()
        if (u.isBlank()) return null
        return try {
            val doc = fetchDoc(u) ?: return null
            val rawTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: doc.selectFirst("h1")?.text()
                ?: u.substringAfterLast("/")
                    .takeIf { it.contains("-") }
                    ?.replace("-", " ")
                    ?.capitalize()
                    ?: "En Vivo"
            val title = cleanChannelTitle(rawTitle)
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
            newTvSeriesLoadResponse(title, u, TvType.Live, listOf(newEpisode(u) {
                this.name = title
                this.posterUrl = posterFor(title, poster.takeIf { it.isNotBlank() })
            })) {
                this.plot = title
                this.posterUrl = posterFor(title, poster.takeIf { it.isNotBlank() })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "load '$u' fallo: ${e.message}")
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val u = data.trim()
        if (u.isBlank() || !u.startsWith("http")) return false
        return try {
            val doc = fetchDoc(u) ?: return false
            val m3u8 = extractM3u8(doc, u) ?: return false
            val ok = try {
                val txt = app.get(m3u8, timeout = 8L).text
                txt.contains("#EXTM3U")
            } catch (e: Exception) {
                false
            }
            if (!ok) return false
            val title = cleanChannelTitle(doc.selectFirst("meta[property=og:title]")?.attr("content") ?: name)
            callback(newExtractorLink(name, title, m3u8, ExtractorLinkType.M3U8) {
                this.quality = Qualities.Unknown.value
            })
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks '$u' fallo: ${e.message}")
            false
        }
    }

    private suspend fun fetchDoc(url: String): Document? {
        return try {
            val txt = app.get(url, timeout = 15L).text
            Jsoup.parse(txt, url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "fetchDoc '$url' fallo: ${e.message}")
            null
        }
    }

    private fun parseCards(doc: Document): List<ChannelInfo> {
        val out = ArrayList<ChannelInfo>()
        for (div in doc.select("div.single-video")) {
            val a = div.selectFirst("a[href*=/livetv/details/]") ?: continue
            val url = a.attr("abs:href")
            val title = cleanChannelTitle(a.attr("title").ifBlank {
                a.selectFirst("span.video-item-content")?.text()
                    ?: a.selectFirst("img")?.attr("alt")
                    ?: ""
            })
            val logo = a.selectFirst("img")?.attr("abs:src") ?: ""
            if (url.isNotBlank() && title.isNotBlank()) {
                out.add(ChannelInfo(title, url, logo.takeIf { it.isNotBlank() }, null))
            }
        }
        return out
    }

    private fun channelItem(ch: ChannelInfo): SearchResponse =
        newLiveSearchResponse(ch.name, ch.url, TvType.Live) {
            this.posterUrl = posterFor(ch.name, ch.logo)
        }

    private fun extractM3u8(doc: Document, baseUrl: String): String? {
        val video = doc.selectFirst("video[src], source[src]")
        val src = video?.attr("abs:src") ?: null
        if (src?.contains(".m3u8") == true) return src
        val text = doc.body()?.html() ?: doc.toString()
        val rx = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
        return rx.find(text)?.value
    }

    private fun posterFor(name: String, logo: String?): String {
        val placeholder = "https://placehold.co/320x320/1e1e2e/e0e0e0/png?text=" + enc(name)
        val l = logo?.trim()
        if (l.isNullOrBlank()) return placeholder
        return "https://wsrv.nl/?url=${enc(l)}&w=320&h=320&fit=contain&default=${enc(placeholder)}"
    }

    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun fold(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()

    private fun cleanChannelTitle(raw: String): String {
        var title = raw.substringBefore('|').trim()
        title = title.replace(
            Regex("(?i)\\s*(?:tv\\s+guatemala|ver\\s+tv\\s+online|tv\\s+online).*$"),
            ""
        )
        title = title.replace(
            Regex("(?i)\\s+(?:en\\s+vivo|gratis|online|hd|24\\s*/\\s*7|24\\s+horas|ver)(?:\\b.*)?$"),
            ""
        )
        return title.trim().ifBlank { raw.substringBefore('|').trim() }
    }

    private data class ChannelInfo(
        val name: String,
        val url: String,
        val logo: String?,
        val group: String?,
    )

    companion object {
        const val TAG = "TvguatemalaEnvivo"

        @JvmStatic
        var pluginContext: Context? = null
    }
}
