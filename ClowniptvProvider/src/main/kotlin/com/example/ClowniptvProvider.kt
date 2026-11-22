package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

class ClowniptvProvider(
    private val prefs: android.content.SharedPreferences? = null
) : MainAPI() {
    override var mainUrl = "https://sxcce.dpdns.org"
    override var name = "IPTVGlobal"
    override var lang = "en"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    companion object {
        private const val TAG = "IPTVGlobal"
        private const val PLAYLIST_TTL_MS = 1_800_000L
        private const val ROW_PAGE_SIZE = 60

        private val PREFIX_ROWS: List<Pair<String, String>> = buildList {
            fun addRow(row: String, vararg codes: String) = codes.forEach { add(it.uppercase(Locale.ROOT) to row) }
            addRow("Latin America", "MX", "ARG", "CO", "LATINO", "BR", "LATIN-BR", "UY", "DO", "CR", "CAR")
            addRow("India", "IN", "ASIA-IN", "UK-IN", "IND", "INDIA")
            addRow("PPV & Events", "PPV", "VIP")
            addRow("United States", "US", "USA")
            addRow("United Kingdom", "UK")
            addRow("Canada", "CA")
            addRow("Pakistan", "PK", "VIP - PK", "VIP-PK")
            addRow("Bangladesh", "BD")
            addRow("Sri Lanka", "SRI LANKA", "LK", "SL")
            addRow("Arabic", "AR", "AR-SP", "AR-KIDS", "AR-VIP", "AR_NS", "SH", "ISLAM", "BEIN", "OSN", "MBC", "MYHD", "ROT", "ART", "EGY", "IRAQ", "KSA", "UAE", "KUW", "KU", "QATAR", "BAHR", "OMAN", "YEMEN", "JOR", "LEB", "SYR", "PALES", "LBY", "TUN", "DZ", "MA", "SUDAN")
            addRow("France", "FR", "FR-CAR", "FR-REU")
            addRow("Germany", "DE")
            addRow("Spain", "ES")
            addRow("Italy", "IT")
            addRow("Portugal", "PT")
            addRow("Turkey", "TR")
            addRow("Netherlands & Belgium", "NL", "BE")
            addRow("Nordic", "SW", "SE", "DK", "NO", "FI", "IC")
            addRow("Albania", "AL")
            addRow("Ex-Yugoslavia", "EXYU", "SRB")
            addRow("Greece", "GR")
            addRow("Poland", "PL")
            addRow("Romania", "RO")
            addRow("Russia", "RU")
            addRow("Iran & Afghanistan", "IR", "AF")
            addRow("Africa", "DSTV")
            addRow("Australia", "AU")
        }

        private val PINNED = listOf("India", "PPV & Events", "United States", "United Kingdom", "Canada")
        private val ROW_ORDER: List<String> =
            PINNED + (PREFIX_ROWS.map { it.second }.distinct() - PINNED.toSet()).sorted() +
                listOf("24/7", "Other")

        private val LOGO = Regex("""tvg-logo="([^"]*)"""")
        private val BLOCK = Regex("""XXX|ADULT|18[+]|[+]18|PORN|EROTIC|NSFW""", RegexOption.IGNORE_CASE)
        private val ALLOW = Regex("""ADULT\s*SWIM""", RegexOption.IGNORE_CASE)

        private fun isBlocked(name: String, section: String): Boolean {
            if (ALLOW.containsMatchIn(name)) return false
            if (ALLOW.containsMatchIn(section)) return false
            return BLOCK.containsMatchIn(name) || BLOCK.containsMatchIn(section)
        }

        data class Channel(
            val name: String = "",
            val url: String = "",
            val logo: String = "",
            val section: String = "",
        )

        private fun isSeparator(name: String) = name.contains("✦●✦") || name.contains("◆◆◆")

        private fun rowOf(ch: Channel): String {
            if (!ch.name.contains('|')) return "Other"
            val prefix = ch.name.substringBefore('|').trim().uppercase(Locale.ROOT)
            if (prefix.startsWith("24/7")) return "24/7"
            return PREFIX_ROWS.firstOrNull { it.first == prefix }?.second
                ?: PREFIX_ROWS.firstOrNull { it.first == prefix.substringBefore('-').trim() }?.second
                ?: "Other"
        }

        private fun parse(text: String): List<Channel> {
            val out = ArrayList<Channel>(30000)
            var info: String? = null
            var section = ""
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.startsWith("#EXTINF")) { info = line; continue }
                if (line.isEmpty() || line.startsWith("#") || info == null) continue
                val meta = info!!
                info = null
                val name = meta.substringAfterLast(',', "").trim()
                val logo = LOGO.find(meta)?.groupValues?.get(1) ?: ""
                if (isSeparator(name)) {
                    section = name.replace("✦●✦", "").replace("◆◆◆", "").trim(' ', '|')
                    continue
                }
                if (name.isNotEmpty() && !isBlocked(name, section)) {
                    out.add(Channel(name, line, logo, section))
                }
            }
            return out
        }
    }

    private val lock = Mutex()
    @Volatile private var channels: List<Channel> = emptyList()
    @Volatile private var rows: Map<String, List<Channel>> = emptyMap()
    @Volatile private var loadedAt: Long = 0L

    override val mainPage get() = mainPageOf(*ROW_ORDER.map { it to it }.toTypedArray())

    private suspend fun catalog(): Map<String, List<Channel>> = lock.withLock {
        if (rows.isNotEmpty() && System.currentTimeMillis() - loadedAt < PLAYLIST_TTL_MS) return@withLock rows
        val parsed = try {
            val r = app.get("$mainUrl/playlist.m3u", timeout = 60L)
            parse(r.text)
        } catch (e: Exception) {
            Log.w(TAG, "catalog fetch fallo: ${e.message}")
            emptyList()
        }
        if (parsed.isNotEmpty()) {
            channels = parsed
            rows = parsed.groupBy { rowOf(it) }
            loadedAt = System.currentTimeMillis()
            Log.d(TAG, "catalog loaded: ${parsed.size} canales, ${rows.size} filas")
        }
        rows
    }

    private fun toSearchResponse(ch: Channel) = newLiveSearchResponse(ch.name, ch.toJson(), TvType.Live) {
        this.posterUrl = ch.logo.takeIf { it.isNotBlank() }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        catalog()
        val list = rows[request.data] ?: emptyList()
        val start = (page - 1) * ROW_PAGE_SIZE
        val slice = if (start < list.size) list.subList(start, minOf(start + ROW_PAGE_SIZE, list.size)) else emptyList()
        val items = slice.map { toSearchResponse(it) }
        return newHomePageResponse(HomePageList(request.name, items, true), start + ROW_PAGE_SIZE < list.size)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        catalog()
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return channels.asSequence()
            .filter { it.name.contains(q, ignoreCase = true) || it.section.contains(q, ignoreCase = true) }
            .take(200)
            .map { toSearchResponse(it) }
            .toList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val ch = try {
            parseJson<Channel>(url)
        } catch (e: Exception) {
            Log.e(TAG, "load parse fallo: ${e.message}")
            return null
        } ?: return null
        return newLiveStreamLoadResponse(ch.name, url, url) {
            this.posterUrl = ch.logo.takeIf { it.isNotBlank() }
            this.plot = ch.section.takeIf { it.isNotBlank() }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ch = try {
            parseJson<Channel>(data)
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks parse fallo: ${e.message}")
            return false
        } ?: return false
        callback(newExtractorLink(name, ch.name, ch.url, ExtractorLinkType.M3U8) {
            this.quality = Qualities.Unknown.value
        })
        return true
    }
}
