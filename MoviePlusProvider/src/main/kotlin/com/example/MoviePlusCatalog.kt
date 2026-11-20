package com.example

import android.util.Log
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayInputStream
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

object MoviePlusCatalog {
    const val IMAGE_BASE = "https://image.tmdb.org/t/p"
    const val TYPE_MOVIE = 1
    const val TYPE_SERIES = 2
    private const val CACHE_TTL_MS = 300_000L

    private val MIRRORS = listOf(
        "https://raw.githubusercontent.com/dev-mplus01/mplus01T/main/db/items_up.json.gz",
        "https://api.appsdevxp.com/db/items_up.json.gz",
        "https://developerxploit.com/app/db/items_up.json.gz"
    )

    val GENRE_NAMES: Map<Int, String> = mapOf(
        1 to "Acción", 2 to "Aventura", 3 to "Animación", 4 to "Comedia",
        5 to "Crimen", 6 to "Documental", 7 to "Drama", 8 to "Familia",
        9 to "Fantasía", 10 to "Historia", 11 to "Terror", 12 to "Música",
        13 to "Misterio", 14 to "Romance", 15 to "Ciencia ficción",
        16 to "Película de TV", 17 to "Suspense", 18 to "Bélica", 19 to "Western",
        20 to "Acción y aventura", 21 to "Niños", 22 to "Noticias", 23 to "Reality",
        24 to "Sci-Fi & Fantasy", 25 to "Soap", 26 to "Talk show", 27 to "Bélico y político"
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CatalogItem(
        val id: Int = 0,
        val name: String = "",
        val otherNames: String = "",
        val date: String = "",
        val genres: List<Int> = emptyList(),
        val type: Int = TYPE_MOVIE,
        val image: String = "",
        val resolutions: String = "",
        val urls: List<String> = emptyList()
    )

    private val mutex = Mutex()
    @Volatile private var cacheMovies: List<CatalogItem> = emptyList()
    @Volatile private var cacheSeries: List<CatalogItem> = emptyList()
    @Volatile private var cacheTime: Long = 0L
    private val ACCENTS = Regex("\\p{Mn}+")
    private val PUNCT = Regex("[^\\p{L}\\p{N}]+")

    suspend fun loadCatalog(): Pair<List<CatalogItem>, List<CatalogItem>> {
        Log.d("MoviePlus", "loadCatalog start cache=${cacheMovies.size}/${cacheSeries.size}")
        val now = System.currentTimeMillis()
        if (cacheMovies.isNotEmpty() && now - cacheTime < CACHE_TTL_MS) return cacheMovies to cacheSeries
        return mutex.withLock {
            val now2 = System.currentTimeMillis()
            if (cacheMovies.isNotEmpty() && now2 - cacheTime < CACHE_TTL_MS) return@withLock cacheMovies to cacheSeries

            val live = try {
                kotlinx.coroutines.withTimeoutOrNull(8000L) { MoviePlusWorker.currentCatalogUrls() } ?: emptyList()
            } catch (e: Exception) {
                Log.w("MoviePlus", "currentCatalogUrls fallo: ${e.message}")
                emptyList()
            }
            Log.d("MoviePlus", "loadCatalog liveUrls=${live.size}")
            val urls = (live + MIRRORS).distinct()
            for (u in urls) {
                try {
                    Log.d("MoviePlus", "catalog fetch $u")
                    val resp = app.get(u, timeout = 60L)
                    val bytes = resp.body.bytes()
                    val json = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes().decodeToString() }
                    val (m, s) = parseCatalog(json)
                    Log.d("MoviePlus", "catalog $u -> movies=${m.size} series=${s.size}")
                    if (m.isEmpty() && s.isEmpty()) continue
                    cacheMovies = m
                    cacheSeries = s
                    cacheTime = System.currentTimeMillis()
                    return@withLock m to s
                } catch (e: Exception) {
                    continue
                }
            }
            cacheMovies to cacheSeries
        }
    }

    private fun parseCatalog(json: String): Pair<List<CatalogItem>, List<CatalogItem>> {
        val root = parseJson<Map<String, Any?>>(json) ?: return emptyList<CatalogItem>() to emptyList()
        return parseList(root["movies"]) to parseList(root["series"])
    }

    private fun parseList(raw: Any?): List<CatalogItem> {
        if (raw !is List<*>) return emptyList()
        return raw.mapNotNull { el ->
            if (el !is Map<*, *>) return@mapNotNull null
            val id = (el["id"] as? Number)?.toInt() ?: return@mapNotNull null
            val genreRaw = el["generos"]
            val genres = when (genreRaw) {
                is List<*> -> genreRaw.mapNotNull { (it as? Number)?.toInt() }
                is String -> genreRaw.split(",", " ").mapNotNull { it.trim().toIntOrNull() }
                else -> emptyList()
            }
            val urls = (el["urls"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
            CatalogItem(
                id = id,
                name = (el["nameFile"] as? String) ?: "",
                otherNames = (el["othersNames"] as? String) ?: "",
                date = (el["date"] as? String) ?: "",
                genres = genres,
                type = (el["type"] as? Number)?.toInt() ?: TYPE_MOVIE,
                image = (el["urlImage"] as? String) ?: "",
                resolutions = (el["resolutions"] as? String) ?: "",
                urls = urls
            )
        }
    }

    fun normalize(text: String): String =
        PUNCT.replace(
            ACCENTS.replace(Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD), ""),
            " "
        ).trim()

    fun findMovie(id: Int): CatalogItem? = cacheMovies.firstOrNull { it.id == id }
    fun findSeries(id: Int): CatalogItem? = cacheSeries.firstOrNull { it.id == id }
}
