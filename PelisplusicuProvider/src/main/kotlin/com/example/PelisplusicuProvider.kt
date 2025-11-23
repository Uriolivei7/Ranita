package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import org.json.JSONObject
import java.util.Calendar

class PelisplusicuProvider : MainAPI() {
    override var mainUrl = "https://v4.pelis-plus.icu"
    override var name = "PelisPlusIcu"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
        TvType.Cartoon,
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val urls = listOf(
            Pair("Series de Estreno", "$mainUrl/series#Series de Estreno"),
            Pair("Series Actualizadas", "$mainUrl/series#Series Actualizadas"),
//            Pair("Series Recomendadas", "$mainUrl/series#Series Recomendadas"),
            Pair("Animes Actualizados", "$mainUrl/animes#Animes Actualizados"),
            Pair("Animes Recomendados", "$mainUrl/animes#Animes Recomendados"),
            Pair("Películas de Estreno", "$mainUrl/peliculas#Películas de Estreno"),
            Pair("Películas Actualizadas", "$mainUrl/peliculas#Películas Actualizadas"),
//            Pair("Películas Recomendadas", "$mainUrl/peliculas#Películas Recomendadas"),
            //Pair("Doramas de Estreno", "$mainUrl/doramas#Doramas de Estreno"),
            Pair("Doramas Actualizadas", "$mainUrl/doramas#Doramas Actualizadas"),
//            Pair("Doramas Recomendadas", "$mainUrl/doramas#Doramas Recomendadas"),
        )

        urls.amap { (name, url) ->
            val doc = app.get(url, referer = "$mainUrl/").document
            val home =
                doc.selectXpath("//*[@id=\"$name\"]")?.select("a")?.map {
                    val title = it.selectFirst("span.overflow-hidden")?.text()
                    val link = it.attr("href")
                    val img =
                        it.selectFirst("div.w-full img.w-full")?.attr("src")
                    val year = it.selectFirst("div:nth-child(1) > div:nth-child(1) > div:nth-child(2) > div:nth-child(1) > span:nth-child(1)")?.text()
                        ?.toIntOrNull()
                    newTvSeriesSearchResponse(title!!, link!!, TvType.TvSeries){
                        this.posterUrl = fixUrl(img!!)
                        this.year = year
                    }
                }
            if(!home.isNullOrEmpty()){
                items.add(HomePageList(name, home))
            }
        }
        return newHomePageResponse(items)
    }


    data class ApiResponse(
        val videos: List<Video?>?
    )

    data class Video(
        val slug: String? = null,
        val titulo: String? = null,
        val modo: Int? = null,
        val imagen: String? = null,
        val release_date: String? = null,
        val id: Int? = null,
        val original: String? = null,
        val tipo: Int? = null,
        val score: Int? = null,
        val votes: String? = null,
        val calidad: String? = null,
        val descripcion: String? = null,
        val portada: String? = null,
        val tmdb_id: String? = null,
        val trailer: String? = null,
        val duracion: String? = null,
        val status: String? = null,
        val created_at: String? = null,
        val updated_at: String? = null
    )

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(name, "SEARCH LOGS --- Inicio de búsqueda para query='$query'")

        val bodyMap = mapOf(
            "query" to query,
            "genre" to "",
            "year" to "",
            "format" to "",
            "page" to 1,
            "pageSize" to 42
        )

        val jsonBody = JSONObject(bodyMap).toString()
        val requestBodyString = "[$jsonBody]"
        //Log.d(name, "SEARCH LOGS --- Request Body JSON: $requestBodyString")

        val mediaType = "text/plain; charset=UTF-8".toMediaType()

        val headers = mapOf(
            "Referer" to "$mainUrl/directorio?search=$query",
            "Next-Action" to "8c9b580858b66ac9488313744447683fd72f2644",
            "Next-Router-State-Tree" to "%5B%22%22%2C%7B%22children%22%3A%5B%22directorio%22%2C%7B%22children%22%3A%5B%5B%22options%22%2C%22%22%2C%22oc%22%5D%2C%7B%22children%22%3A%5B%22__PAGE__%3F%7B%5C%22search%5C%22%3A%5C%22calamar%5C%22%7D%22%2C%7B%7D%2C%22%2Fdirectorio%3Fsearch%3Dcalamar%22%2C%22refresh%22%5D%7D%5D%7D%5D%7D%2Cnull%2Cnull%2Ctrue%5D",
        )

        val requestUrl = "$mainUrl/directorio?search=${query}r"
        //Log.d(name, "SEARCH LOGS --- Request URL: $requestUrl")

        val requestBody = RequestBody.create(mediaType, requestBodyString)

        try {
            val response = app.post(
                url = requestUrl,
                headers = headers,
                requestBody = requestBody
            )

            val responseText = response.body.string()

            Log.d(name, "SEARCH LOGS --- Response Status Code: ${response.code}")

            if (response.code != 200) {
                Log.e(name, "SEARCH LOGS --- Petición fallida. Revisar headers o URL.")
                return emptyList()
            }

            Log.d(name, "SEARCH LOGS --- Response Length: ${responseText.length}")

            val jsonToParse = responseText.substringAfter("1:")

            if (jsonToParse.length < 5) {
                Log.e(name, "SEARCH LOGS --- Fallo: El JSON extraído es demasiado corto o inválido.")
                Log.d(name, "SEARCH LOGS --- Respuesta completa (Inicio): ${responseText.take(200)}")
                return emptyList()
            }

            //Log.d(name, "SEARCH LOGS --- JSON a parsear (Inicio): ${jsonToParse.take(200)}")

            val result = AppUtils.tryParseJson<ApiResponse>(jsonToParse)

            if (result == null) {
                //Log.e(name, "SEARCH LOGS --- Fallo: AppUtils.tryParseJson devolvió NULL. Revisar estructura de ApiResponse.")
                return emptyList()
            }

            //Log.d(name, "SEARCH LOGS --- Videos encontrados: ${result.videos?.size ?: 0}")

            return result.videos?.filterNotNull()?.amap { video ->
                val title = video.titulo
                val typeName = if(video.modo == 1) "pelicula" else "serie"
                val link = video.slug
                val img = video.imagen

                if (title.isNullOrBlank() || link.isNullOrBlank() || img.isNullOrBlank()) {
                    //Log.w(name, "SEARCH LOGS --- Ítem omitido por campos nulos: $video")
                    null
                } else {
                    val year = video.release_date?.substringBefore("-")?.toIntOrNull()
                    newTvSeriesSearchResponse(title, fixUrl("$mainUrl/$typeName/$link"), TvType.TvSeries){
                        this.posterUrl = fixUrl("https://image.tmdb.org/t/p/w185/$img.jpg")
                        this.year = year
                    }
                }
            }?.filterNotNull().orEmpty()
        } catch (e: Exception) {
            Log.e(name, "SEARCH LOGS --- Excepción crítica: ${e.message}", e)
            return emptyList()
        }
    }

    data class Capitulo(
        val titulo: String? = null,
        val descripcion: String? = null,
        val temporada: Int? = null,
        val capitulo: Int? = null,
        val imagen: String? = null,
    )

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val tvType = if (url.contains("pelicula")) TvType.Movie else TvType.TvSeries
        if(tvType.equals(TvType.Movie)){
            val info = doc.select("div.flex.w-full.h-fit.flex-col")
            val title = info.select("div div.flex div.flex span.font-semibold").first()?.text() ?: ""
            val year = title.substringAfter("(").substringBefore(")").toIntOrNull()
            val poster = info.select("div div.object-cover img").first()?.attr("src")
            val backimage = doc.selectXpath("/html/body/div/div/main/div[1]/div[1]/div/div[2]/div[1]/img").attr("src")
            val description = info.select("span.w-full.text-textsec.text-sm").first()?.text() ?: ""
            val tags = info.select("div.flex-wrap.gap-3.mt-4.hidden a").map{ it?.text().orEmpty()}
            return newMovieLoadResponse(title, url, tvType, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backimage ?: poster
                this.plot = description
                this.tags = tags
                this.year = year
            }
        }else{
            val title = doc.select(".detailsTop_title___bVic").first()?.text() ?: ""
            val year = title.substringAfter("(").substringBefore(")").toIntOrNull()
            val poster = doc.selectFirst(".detailsTop_detailsimage__oA30g")?.attr("src")
            val backimage = doc.selectFirst(".detailsTop_detailsbgimage__Yhf6Y")?.attr("style")?.substringAfter("url(")?.substringBefore(")")
            val description = doc.selectFirst(".detailsBottom_descriptioncontent__eOros > p:nth-child(1)")?.text() ?: ""
            val tags = doc.select("div.detailsBottom_singlecontent__vAIRR:nth-child(4) > span:nth-child(2) span").map{ it?.text().orEmpty().replace(",", "")}
            val json = doc.select("script").firstOrNull {
                it.html().startsWith("self.__next_f.push(") && it.html().contains("\\\"capitulos\\\":[")
            }?.html()?.substringAfter("\\\"capitulos\\\":")?.substringBefore("}],")
                ?.replace("\\\"", "\"")
            val capitulos = AppUtils.tryParseJson<List<Capitulo>>(json)
            var episodes = if (!capitulos.isNullOrEmpty()) {
                capitulos.amap {
                    val titulo = it.titulo?.replace("$title ", "")
                    val epurl = "$url/$titulo"
                    newEpisode(epurl){
                        this.name = titulo
                        this.season = it.temporada
                        this.episode = it.capitulo
                        this.posterUrl = "https://image.tmdb.org/t/p/w185/${it.imagen}.jpg"
                    }
                }
            } else listOf()
            return newTvSeriesLoadResponse(
                title,
                url, tvType, episodes,
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backimage ?: poster
                this.plot = description
                this.tags = tags
                this.year = year
            }
        }
    }

    suspend fun customLoadExtractor(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit)
    {
        loadExtractor(url
            .replaceFirst("https://hglink.to", "https://streamwish.to")
            .replaceFirst("https://swdyu.com","https://streamwish.to")
            .replaceFirst("https://mivalyo.com", "https://vidhidepro.com")
            .replaceFirst("https://filemoon.link", "https://filemoon.sx")
            .replaceFirst("https://sblona.com", "https://watchsb.com")
            , referer, subtitleCallback, callback)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        //Log.d(name, "LOADLINKS LOGS --- 1. Iniciando extracción de links para URL: $data")

        try {
            val doc = app.get(data).document

            val scriptChunks = doc.select("script").filter {
                it.html().trim().startsWith("self.__next_f.push([1")
            }

            val text = scriptChunks.joinToString("") {
                it.html().replaceFirst("self.__next_f.push([1,\"", "")
                    .replace(""""]\)$""".toRegex(), "")
            }

            //Log.d(name, "LOADLINKS LOGS --- 2. Longitud del texto extraído: ${text.length}")

            if (text.isEmpty()) {
                Log.e(name, "LOADLINKS LOGS --- 3. Fallo: Texto de script (Next.js chunk) está vacío.")
                return false
            }

            val linksRaw = fetchLinks(text.replace("\\\"", "\""))

            //Log.d(name, "LOADLINKS LOGS --- 4. Enlaces brutos (Raw Links) encontrados: ${linksRaw.size}")

            if (linksRaw.isEmpty()) {
                Log.w(name, "LOADLINKS LOGS --- 5. Advertencia: No se encontraron enlaces válidos en el texto.")
            }

            linksRaw.amap { link ->
                val fixedUrl = fixHostsLinks(link.url!!)
                //Log.d(name, "LOADLINKS LOGS --- 6. Procesando link: [${link.lang}] URL original: ${link.url} -> URL fija: $fixedUrl")

                loadSourceNameExtractor(link.lang!!, fixedUrl, data, subtitleCallback, callback)
            }

            Log.d(name, "LOADLINKS LOGS --- 7. Finalizada la carga de links.")
            return true
        } catch (e: Exception) {
            Log.e(name, "LOADLINKS LOGS --- 8. Excepción crítica en loadLinks: ${e.message}", e)
            return false
        }
    }

    data class Link(
        val lang: String?,
        val url: String?
    )

    fun fetchLinks(text: String?): List<Link> {
        if (text.isNullOrEmpty()) {
            return listOf()
        }
        val linkRegex =
            Regex(""""enlace":"(https?://(www\.)?[-a-zA-Z0-9@:%._+~#=]{1,256}\.[a-zA-Z0-9()]{1,6}\b([-a-zA-Z0-9()@:%_+.~#?&/=]*))","tipo":\d,"idioma":(\d)""")
        return linkRegex.findAll(text).map { Link(getLang(it.groupValues[4]), it.groupValues[1]) }.toList()
    }

    fun getLang(str: String): String {
        return when (str) {
            "1" -> "Latino"
            "2" -> "Español"
            "3" -> "Subtitulado"
            else -> ""
        }
    }
}

suspend fun loadSourceNameExtractor(
    source: String,
    url: String,
    referer: String? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    loadExtractor(url, referer, subtitleCallback) { link ->
        CoroutineScope(Dispatchers.IO).launch {
            callback.invoke(
                newExtractorLink(
                    "$source[${link.source}]",
                    "$source[${link.source}]",
                    link.url,
                ) {
                    this.quality = link.quality
                    this.type = link.type
                    this.referer = link.referer
                    this.headers = link.headers
                    this.extractorData = link.extractorData
                }
            )
        }
    }
}

fun fixHostsLinks(url: String): String {
    return url
        .replaceFirst("https://doodstream.com", "https://dood.la")
        .replaceFirst("https://streamtape.com", "https://streamtape.cc")
        .replaceFirst("https://hglink.to", "https://streamwish.to")
        .replaceFirst("https://swdyu.com", "https://streamwish.to")
        .replaceFirst("https://cybervynx.com", "https://streamwish.to")
        .replaceFirst("https://dumbalag.com", "https://streamwish.to")
        .replaceFirst("https://mivalyo.com", "https://vidhidepro.com")
        .replaceFirst("https://dinisglows.com", "https://vidhidepro.com")
        .replaceFirst("https://dhtpre.com", "https://vidhidepro.com")
        .replaceFirst("https://filemoon.link", "https://filemoon.sx")
        .replaceFirst("https://sblona.com", "https://watchsb.com")
        .replaceFirst("https://lulu.st", "https://lulustream.com")
        .replaceFirst("https://uqload.io", "https://uqload.com")
        .replaceFirst("https://do7go.com", "https://dood.la")
}