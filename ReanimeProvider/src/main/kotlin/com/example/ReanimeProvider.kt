package com.example

import android.content.Context
import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class ReanimeProvider : MainAPI() {
    companion object {
        private const val MAIN_URL = "https://reanime.to"
        private const val FLIX_BASE = "https://flixcloud.cc"
        private const val FLIX_REFERER = "https://flixcloud.cc/"

        private val browserHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36",
            "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8",
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site",
        )

        private fun sha256Hex(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        private fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(password, "HmacSHA256"))
            val blocks = (dkLen + 31) / 32
            val out = ByteArray(blocks * 32)
            var offset = 0
            for (block in 1..blocks) {
                mac.update(salt)
                val intBlock = byteArrayOf(
                    (block ushr 24).toByte(), (block ushr 16).toByte(),
                    (block ushr 8).toByte(), block.toByte()
                )
                var u = mac.doFinal(intBlock)
                val t = u.copyOf()
                for (i in 2..iterations) {
                    u = mac.doFinal(u)
                    for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
                }
                System.arraycopy(t, 0, out, offset, 32)
                offset += 32
            }
            return out.copyOf(dkLen)
        }

        private fun b64decode(s: String): ByteArray =
            Base64.decode(s.trim(), Base64.DEFAULT)

        class MiniWasm(data: ByteArray) {
            val mem = ByteArray(65536)
            private var global0 = 0
            private val funcs = mutableListOf<Triple<Int, Int, ByteArray>>()
            private val endsList = mutableListOf<Map<Int, Int>>()
            private val exports = HashMap<String, Int>()

            private fun lebU(buf: ByteArray, p: Int): Pair<Int, Int> {
                var result = 0
                var shift = 0
                var pos = p
                while (true) {
                    val b = buf[pos].toInt() and 0xFF
                    pos++
                    result = result or ((b and 0x7F) shl shift)
                    if (b and 0x80 == 0) break
                    shift += 7
                }
                return result to pos
            }

            private fun lebS(buf: ByteArray, p: Int): Pair<Int, Int> {
                var result = 0
                var shift = 0
                var pos = p
                while (true) {
                    val b = buf[pos].toInt() and 0xFF
                    pos++
                    result = result or ((b and 0x7F) shl shift)
                    shift += 7
                    if (b and 0x80 == 0) {
                        if (shift < 64 && b and 0x40 != 0) result -= (1 shl shift)
                        break
                    }
                }
                return result to pos
            }

            init {
                var pos = 8
                while (pos < data.size) {
                    val secId = data[pos].toInt() and 0xFF
                    pos++
                    val (size, np) = lebU(data, pos)
                    pos = np
                    val bodyEnd = minOf(pos + size, data.size)
                    when (secId) {
                        7 -> {
                            var p = pos
                            val (cnt, p1) = lebU(data, p); p = p1
                            repeat(cnt) {
                                val (nl, pa) = lebU(data, p); p = pa
                                val name = String(data, p, nl, Charsets.UTF_8); p += nl
                                val kind = data[p].toInt() and 0xFF; p += 1
                                val (idx, pb) = lebU(data, p); p = pb
                                if (kind == 0) exports[name] = idx
                            }
                        }
                        11 -> {
                            var p = pos
                            val (cnt, p1) = lebU(data, p); p = p1
                            repeat(cnt) {
                                val (_, pf) = lebU(data, p); p = pf
                                require(data[p].toInt() == 0x41) { "data offset expr" }
                                p += 1
                                val (off, po) = lebS(data, p); p = po
                                require(data[p].toInt() == 0x0b) { "offset end" }
                                p += 1
                                val (dsz, pd) = lebU(data, p); p = pd
                                System.arraycopy(data, p, mem, off, dsz)
                                p += dsz
                            }
                        }
                        10 -> {
                            var p = pos
                            val (cnt, p1) = lebU(data, p); p = p1
                            repeat(cnt) {
                                val (bs, pa) = lebU(data, p); p = pa
                                val fb = data.copyOfRange(p, p + bs); p += bs
                                var fp = 0
                                val (ng, pb) = lebU(fb, fp); fp = pb
                                var totalLocals = 0
                                repeat(ng) {
                                    val (c, pc1) = lebU(fb, fp); fp = pc1
                                    fp += 1
                                    totalLocals += c
                                }

                                val ends = HashMap<Int, Int>()
                                val stack = ArrayList<Int>()
                                var q = fp
                                while (q < fb.size) {
                                    val op = fb[q].toInt() and 0xFF
                                    q++
                                    when (op) {
                                        0x02, 0x03, 0x04 -> { q++; stack.add(q - 1) }
                                        0x05 -> {}
                                        0x0b -> {
                                            if (stack.isNotEmpty()) ends[stack.removeAt(stack.size - 1)] = q
                                            else break
                                        }
                                        0x0c, 0x0d, 0x10 -> { val (_, q1) = lebU(fb, q); q = q1 }
                                        0x11 -> { val (_, q1) = lebU(fb, q); q = q1; val (_, q2) = lebU(fb, q); q = q2 }
                                        0x20, 0x21, 0x22, 0x23, 0x24 -> { val (_, q1) = lebU(fb, q); q = q1 }
                                        0x41 -> { val (_, q1) = lebS(fb, q); q = q1 }
                                        in 0x28..0x3e -> { val (_, q1) = lebU(fb, q); q = q1; val (_, q2) = lebU(fb, q); q = q2 }
                                    }
                                }
                                funcs.add(Triple(totalLocals, fp, fb))
                                endsList.add(ends)
                            }
                        }
                    }
                    pos += size
                }
            }

            fun call(name: String, args: IntArray): List<Int> {
                val idx = exports[name] ?: throw Exception("funcion $name no exportada")
                val (nlocals, start, code) = funcs[idx]
                val ends = endsList[idx]
                val loc = IntArray(args.size + nlocals)
                System.arraycopy(args, 0, loc, 0, args.size)
                val stack = ArrayList<Int>()
                val labels = ArrayList<Triple<Boolean, Int, Int>>()
                var pc = start

                while (true) {
                    val op = code[pc].toInt() and 0xFF
                    pc++
                    when (op) {
                        0x20 -> { val (v, np) = lebU(code, pc); pc = np; stack.add(loc[v]) }
                        0x21 -> { val (v, np) = lebU(code, pc); pc = np; loc[v] = stack.removeAt(stack.size - 1) }
                        0x22 -> { val (v, np) = lebU(code, pc); pc = np; loc[v] = stack[stack.size - 1] }
                        0x23 -> { val (_, np) = lebU(code, pc); pc = np; stack.add(global0) }
                        0x24 -> { val (_, np) = lebU(code, pc); pc = np; global0 = stack.removeAt(stack.size - 1) }
                        0x41 -> { val (v, np) = lebS(code, pc); pc = np; stack.add(v) }
                        0x2d -> {
                            var np = pc
                            val (_, na) = lebU(code, np); np = na
                            val (_, nb) = lebU(code, np); pc = nb
                            val addr = stack.removeAt(stack.size - 1) and 0xFFFFFFFF.toInt()
                            stack.add(mem[addr].toInt() and 0xFF)
                        }
                        0x3a -> {
                            var np = pc
                            val (_, na) = lebU(code, np); np = na
                            val (_, nb) = lebU(code, np); pc = nb
                            val value = stack.removeAt(stack.size - 1) and 0xFF
                            val addr = stack.removeAt(stack.size - 1) and 0xFFFFFFFF.toInt()
                            mem[addr] = value.toByte()
                        }
                        0x6a -> { val b = stack.removeAt(stack.size - 1); val a = stack.removeAt(stack.size - 1); stack.add(a + b) }
                        0x6b -> { val b = stack.removeAt(stack.size - 1); val a = stack.removeAt(stack.size - 1); stack.add(a - b) }
                        0x6c -> { val b = stack.removeAt(stack.size - 1); val a = stack.removeAt(stack.size - 1); stack.add(a * b) }
                        0x71 -> { val b = stack.removeAt(stack.size - 1); val a = stack.removeAt(stack.size - 1); stack.add(a and b) }
                        0x72 -> { val b = stack.removeAt(stack.size - 1); val a = stack.removeAt(stack.size - 1); stack.add(a or b) }
                        0x73 -> { val b = stack.removeAt(stack.size - 1); val a = stack.removeAt(stack.size - 1); stack.add(a xor b) }
                        0x74 -> { val b = stack.removeAt(stack.size - 1); val a = stack.removeAt(stack.size - 1); stack.add(a shl (b and 31)) }
                        0x75 -> { val b = stack.removeAt(stack.size - 1); val a = stack.removeAt(stack.size - 1); stack.add(a shr (b and 31)) }
                        0x76 -> { val b = stack.removeAt(stack.size - 1); val a = stack.removeAt(stack.size - 1); stack.add(a ushr (b and 31)) }
                        0x77 -> { // rotl
                            val b = stack.removeAt(stack.size - 1) and 31
                            val a = stack.removeAt(stack.size - 1)
                            stack.add(if (b == 0) a else (a shl b) or (a ushr (32 - b)))
                        }
                        0x78 -> {
                            val b = stack.removeAt(stack.size - 1) and 31
                            val a = stack.removeAt(stack.size - 1)
                            stack.add(if (b == 0) a else (a ushr b) or (a shl (32 - b)))
                        }
                        0x45 -> stack.add(if (stack.removeAt(stack.size - 1) == 0) 1 else 0)
                        0x46 -> { val b = stack.removeAt(stack.size - 1); val a = stack.removeAt(stack.size - 1); stack.add(if (a == b) 1 else 0) }
                        0x47 -> { val b = stack.removeAt(stack.size - 1); val a = stack.removeAt(stack.size - 1); stack.add(if (a != b) 1 else 0) }
                        0x4e -> { val b = stack.removeAt(stack.size - 1); val a = stack.removeAt(stack.size - 1); stack.add(if (a >= b) 1 else 0) }
                        0x4f -> {
                            val b = stack.removeAt(stack.size - 1).toLong() and 0xFFFFFFFFL
                            val a = stack.removeAt(stack.size - 1).toLong() and 0xFFFFFFFFL
                            stack.add(if (a >= b) 1 else 0)
                        }
                        0x02, 0x03, 0x04 -> {
                            val hdr = pc - 1
                            val btIdx = pc
                            pc++
                            labels.add(Triple(op == 0x03, hdr, ends[btIdx] ?: -1))
                        }
                        0x0b -> {
                            if (labels.isNotEmpty()) labels.removeAt(labels.size - 1) else return stack
                        }
                        0x0c -> {
                            val (d, np) = lebU(code, pc); pc = np
                            val target = labels[labels.size - 1 - d]
                            for (k in labels.size - 1 downTo labels.size - 1 - d) labels.removeAt(k)
                            if (target.first) pc = target.second
                            else pc = target.third
                        }
                        0x0d -> {
                            val (d, np) = lebU(code, pc); pc = np
                            val cond = stack.removeAt(stack.size - 1)
                            if (cond != 0) {
                                val target = labels[labels.size - 1 - d]
                                for (k in labels.size - 1 downTo labels.size - 1 - d) labels.removeAt(k)
                                if (target.first) pc = target.second
                                else pc = target.third
                            }
                        }
                        0x00 -> throw Exception("wasm unreachable")
                        else -> throw Exception("wasm opcode no soportado 0x${op.toString(16)}")
                    }
                }
            }
        }
    }

    override var mainUrl = MAIN_URL
    override var name = "Re:ANIME"
    override var lang = "en"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)

    override val mainPage = mainPageOf("/home" to "Descubrir")

    // ------------------------------------------------------------------
    // Main page / Search
    // ------------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val resp = app.get("$mainUrl/home", headers = browserHeaders)
            val doc = resp.document
            val anchors = doc.select("a[href*='/watch/']")
            Log.d("Reanime", "getMainPage: code=${resp.code} title='${doc.title()}' anchors=${anchors.size}")

            val items = anchors.mapNotNull { el ->
                val href = el.attr("href") ?: return@mapNotNull null
                val slug = Regex("""/watch/([a-z0-9\-_]+)""").find(href)?.groupValues?.get(1)
                    ?: return@mapNotNull null
                val img = el.selectFirst("img")
                val title = img?.attr("alt")?.trim().takeUnless { it.isNullOrBlank() }
                    ?: img?.attr("title")?.trim().takeUnless { it.isNullOrBlank() }
                    ?: el.attr("title")?.trim().takeUnless { it.isNullOrBlank() }
                    ?: el.attr("aria-label")?.trim().takeUnless { it.isNullOrBlank() }
                    ?: el.text().trim().takeUnless { it.isNullOrBlank() }
                    ?: return@mapNotNull null
                newAnimeSearchResponse(title, "$mainUrl/anime/$slug", TvType.Anime) {
                    this.posterUrl = img?.attr("src")?.takeIf { it.isNotBlank() }
                        ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }
                }
            }.distinctBy { it.url }
            if (items.isEmpty()) {

                val sample = anchors.take(3).mapIndexed { i, el ->
                    "[$i] href=${el.attr("href").take(40)} text='${el.text().take(30)}' imgAlt='${el.selectFirst("img")?.attr("alt")?.take(30)}'"
                }
                Log.w("Reanime", "getMainPage: 0 items. sample=$sample")
            }
            newHomePageResponse(listOf(HomePageList(request.name, items)), hasNext = false)
        } catch (e: Exception) {
            Log.e("Reanime", "getMainPage fallo: ${e.message}")
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val text = app.get(
                "$mainUrl/api/v1/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}",
                headers = browserHeaders
            ).text
            val json = JSONObject(text)
            val results = mutableListOf<SearchResponse>()
            val arr = json.optJSONArray("results") ?: return emptyList()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val slug = o.optString("anime_id") ?: continue
                val title = o.optJSONObject("title")?.optString("english")
                    ?.takeIf { it.isNotBlank() } ?: continue
                results.add(newAnimeSearchResponse(title, "$mainUrl/anime/$slug", TvType.Anime) {
                    this.posterUrl = o.optJSONObject("cover_image")?.optString("large")
                })
            }
            if (results.isEmpty()) Log.w("Reanime", "search '$query': sin resultados")
            results
        } catch (e: Exception) {
            Log.e("Reanime", "search '$query' fallo: ${e.message}")
            null
        }
    }

    // ------------------------------------------------------------------
    // Load (detalle + episodios)
    // ------------------------------------------------------------------

    private data class AnimeRelation(
        val slug: String,
        val title: String,
        val poster: String?,
        val year: Int?,
        val isMovie: Boolean,
    )

    private data class AnimeMeta(
        val slug: String,
        val anilistId: Int,
        val title: String,
        val description: String?,
        val poster: String?,
        val banner: String?,
        val genres: List<String>,
        val year: Int?,
        val score: Int?,
        val status: String?,
        val relations: List<AnimeRelation>,
    )

    private fun relationTitle(o: JSONObject): String {
        val titleObj = o.optJSONObject("title")
        return titleObj?.optString("english")?.takeIf { it.isNotBlank() }
            ?: titleObj?.optString("romaji")?.takeIf { it.isNotBlank() }
            ?: o.optString("anime_id")
    }

    private suspend fun fetchMeta(slug: String): AnimeMeta? {
        return try {
            val json = JSONObject(app.get("$mainUrl/api/v1/anime/$slug", headers = browserHeaders).text)
            val titleObj = json.optJSONObject("title")
            val title = titleObj?.optString("english")?.takeIf { it.isNotBlank() }
                ?: titleObj?.optString("romaji") ?: slug
            AnimeMeta(
                slug = slug,
                anilistId = json.optInt("anilist_id", 0),
                title = title,
                description = json.optString("description").takeIf { it.isNotBlank() }?.let { stripHtml(it) },
                poster = json.optJSONObject("cover_image")?.optString("large"),
                banner = json.optString("banner_image").takeIf { it.isNotBlank() },
                genres = json.optJSONArray("genres")?.let { a ->
                    (0 until a.length()).mapNotNull { a.optString(it) }
                } ?: emptyList(),
                year = json.optInt("season_year", 0).takeIf { it > 0 },
                score = json.optInt("average_score", 0).takeIf { it > 0 },
                status = json.optString("status").takeIf { it.isNotBlank() },
                relations = json.optJSONArray("relations")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val relSlug = o.optString("anime_id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        if (relSlug == slug) return@mapNotNull null
                        AnimeRelation(
                            slug = relSlug,
                            title = relationTitle(o),
                            poster = o.optJSONObject("cover_image")?.optString("large")?.takeIf { it.isNotBlank() },
                            year = o.optInt("season_year", 0).takeIf { it > 0 },
                            isMovie = o.optString("format") == "MOVIE",
                        )
                    }
                } ?: emptyList(),
            )
        } catch (e: Exception) {
            Log.e("Reanime", "fetchMeta $slug fallo: ${e.message}")
            null
        }
    }

    private fun stripHtml(s: String): String =
        s.replace(Regex("<br\\s*/?>"), "\n").replace(Regex("<[^>]*>"), "").trim()

    override suspend fun load(url: String): LoadResponse? {
        val slug = Regex("""/anime/([a-z0-9\-_]+)""").find(url)?.groupValues?.get(1)
            ?: url.takeIf { !it.contains("/") && !it.contains(":") }
            ?: return null

        val meta = fetchMeta(slug) ?: return null

        val episodes = mutableListOf<Episode>()
        try {
            val json = JSONObject(
                app.get("$mainUrl/api/v1/anime/$slug/episodes?limit=2000", headers = browserHeaders).text
            )
            val arr = json.optJSONArray("data")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val num = o.optInt("episode_number", i + 1)
                    episodes.add(newEpisode("{\"aid\":${meta.anilistId},\"n\":$num}") {
                        this.name = o.optString("title").takeIf { it.isNotBlank() } ?: "Episodio $num"
                        this.episode = num
                        this.season = 1
                        this.description = o.optString("description").takeIf { it.isNotBlank() }
                    })
                }
            }
        } catch (e: Exception) {
            Log.e("Reanime", "load episodios $slug fallo: ${e.message}")
        }
        Log.d("Reanime", "load '${meta.title}': ${episodes.size} eps")

        return newTvSeriesLoadResponse(meta.title, "$mainUrl/anime/$slug", TvType.Anime, episodes) {
            this.posterUrl = meta.poster
            this.backgroundPosterUrl = meta.banner
            this.plot = meta.description
            this.tags = meta.genres
            this.year = meta.year
            this.score = meta.score?.let { Score.from10(it / 10f) }
            this.showStatus = when (meta.status) {
                "Releasing" -> ShowStatus.Ongoing
                "Finished" -> ShowStatus.Completed
                else -> null
            }
            this.recommendations = meta.relations.map { rel ->
                newAnimeSearchResponse(rel.title, "$mainUrl/anime/${rel.slug}",
                    if (rel.isMovie) TvType.AnimeMovie else TvType.Anime) {
                    this.posterUrl = rel.poster
                    this.year = rel.year
                }
            }.takeIf { it.isNotEmpty() }
        }
    }

    // ------------------------------------------------------------------
    // loadLinks — cadena flixcloud descifrada
    // ------------------------------------------------------------------

    private data class FlixResolve(val masterUrl: String, val subtitles: List<Pair<String, String>>)

        private val activePks = mutableListOf<ByteArray>()

        private val SEG_XOR_KEY = byteArrayOf(
            0x9d.toByte(), 0x2a.toByte(), 0xf1.toByte(), 0x47,
            0xb3.toByte(), 0x8e.toByte(), 0x5c.toByte(), 0x70.toByte(),
            0xa6.toByte(), 0x19.toByte(), 0xe4.toByte(), 0x3b.toByte(),
            0xd8.toByte(), 0x62.toByte(), 0x0f.toByte(), 0xc5.toByte(),
        )

    private fun extractField(html: String, name: String): String? =
        Regex("\"$name\":\"([^\"]*)\"").find(html)?.groupValues?.get(1)
            ?: Regex("$name:\"([^\"]*)\"").find(html)?.groupValues?.get(1)


    private suspend fun resolveFlix(embedUrl: String): FlixResolve? {

        repeat(3) { attempt ->
            try {
                val html = app.get(embedUrl, headers = browserHeaders).text

                val seed = Regex("""obfuscation_seed:"([^"]+)"""").find(html)?.groupValues?.get(1)
                    ?: return@repeat
                val wPayloadB64 = Regex("""w_payload:"([A-Za-z0-9+/=]+)"""").find(html)?.groupValues?.get(1)
                    ?: return@repeat
                val odBlock = Regex(
                    """obfuscated_crypto_data:\{cd_[0-9a-f]+:\{ad_[0-9a-f]+:\[\{od_[0-9a-f]+:\{(.{50,600}?)\}\}\]""",
                    RegexOption.DOT_MATCHES_ALL
                ).find(html)?.groupValues?.get(1) ?: return@repeat
                val kfB64 = Regex("""kf_[0-9a-f]+:"([^"]+)"""").find(odBlock)?.groupValues?.get(1)
                    ?: return@repeat
                val ivB64 = Regex("""ivf_[0-9a-f]+:"([^"]+)"""").find(odBlock)?.groupValues?.get(1)
                    ?: return@repeat

                var eS = seed
                for (o in 0..2) eS = sha256Hex(eS + o.toString())
                var sS = eS
                for (o in 0..2) sS = sha256Hex(sS + o.toString())
                val tokenField = eS.substring(48) + "_" + eS.substring(56)
                val frag2Field = sS.substring(0, 16) + "_" + sS.substring(16, 24)

                val token = extractField(html, tokenField) ?: return@repeat
                val frag2B64 = extractField(html, frag2Field) ?: return@repeat

                val apiJson = JSONObject(app.get("$FLIX_BASE/api/m3u8/$token", headers = browserHeaders).text)
                val vidKey = sha256Hex(token + "vid").take(10)
                val keyKey = sha256Hex(token + "key").take(10)
                val encUrlB64 = apiJson.optString(vidKey).takeIf { it.isNotEmpty() } ?: return@repeat
                val keyMatB64 = apiJson.optString(keyKey).takeIf { it.isNotEmpty() } ?: return@repeat

                val wasm = MiniWasm(b64decode(wPayloadB64))
                val a = b64decode(kfB64)
                val b = b64decode(frag2B64)
                val c = b64decode(keyMatB64)
                val k = minOf(a.size, b.size, c.size)
                val seedInt = seed.substring(0, 8).toLong(16).toInt()

                System.arraycopy(a, 0, wasm.mem, 1000, a.size)
                System.arraycopy(b, 0, wasm.mem, 1000 + k, b.size)
                System.arraycopy(c, 0, wasm.mem, 1000 + 2 * k, c.size)

                wasm.call("_s", intArrayOf(seedInt))
                wasm.call("_r", intArrayOf(1000, 1000 + k, 1000 + 2 * k, 1000 + 3 * k, k))

                val p = wasm.mem.copyOfRange(1000 + 3 * k, 1000 + 4 * k)

                val pk = ByteArray(32)
                for (i in 0 until 32) {
                    pk[i] = (wasm.mem[2000 + i].toInt() xor wasm.mem[2032 + i].toInt()).toByte()
                }
                synchronized(activePks) {
                    if (activePks.size > 6) activePks.clear()
                    activePks.add(pk)
                }

                val derived = pbkdf2Sha256(p, seed.toByteArray(Charsets.UTF_8), 1000, 32)
                val seedBytes = seed.toByteArray(Charsets.UTF_8)
                val j = ByteArray(32)
                for (i in 0 until 32) {
                    j[i] = (derived[i].toInt() xor seedBytes[i % seedBytes.size].toInt()).toByte()
                }
                val aesKey = MessageDigest.getInstance("SHA-256").digest(j)

                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(b64decode(ivB64)))
                val masterUrl = String(cipher.doFinal(b64decode(encUrlB64)), Charsets.UTF_8).trim()
                if (!masterUrl.startsWith("http")) return@repeat

                val subs = mutableListOf<Pair<String, String>>()
                Regex("""subtitles:\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)?.let { subArr ->
                    Regex("""\{url:"([^"]+)",language:"([^"]*)",format:"([^"]*)"""").findAll(subArr).forEach { m ->
                        subs.add(m.groupValues[2] to m.groupValues[1])
                    }
                }

                if (attempt > 0) Log.w("Reanime", "resolveFlix OK en intento ${attempt + 1}")
                return FlixResolve(masterUrl, subs)
            } catch (e: Exception) {
                Log.e("Reanime", "resolveFlix intento fallo: ${e.message}")
            }
        }
        Log.w("Reanime", "resolveFlix: 3 intentos fallidos para $embedUrl")
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val objData = try {
            JSONObject(data)
        } catch (_: Exception) {
            Log.e("Reanime", "loadLinks data inválido")
            return false
        }
        val aid = objData.optInt("aid", 0)
        val ep = objData.optInt("n", 0)
        if (aid <= 0 || ep <= 0) return false

        val serversJson = try {
            JSONObject(app.get("$mainUrl/api/flix/$aid/$ep", headers = browserHeaders).text)
        } catch (e: Exception) {
            Log.e("Reanime", "loadLinks api/flix fallo: ${e.message}")
            return false
        }
        if (!serversJson.optBoolean("success")) {
            Log.w("Reanime", "loadLinks ep $ep: sin fuentes")
            return false
        }

        val seen = LinkedHashSet<String>()
        val preferred = mutableListOf<Pair<String, String>>()
        val arr = serversJson.optJSONArray("servers") ?: return false
        for (pass in listOf("HD-2", "HD-1", null)) {
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val srvName = s.optString("serverName")
                val link = s.optString("dataLink")
                if (link.isBlank()) continue
                if (pass != null && srvName != pass) continue
                if (seen.add(link)) preferred.add(srvName to link)
            }
        }

            val emittedSubs = mutableSetOf<String>()
        var any = false
        var subsEmitted = false
        for ((srvName, embedLink) in preferred) {
            val resolved = resolveFlix(embedLink) ?: continue
            any = true
            if (subsEmitted) continue
            val label = "Re:ANIME $srvName"
            Log.d("Reanime", "loadLinks $label OK")

            callback(newExtractorLink(name, label, resolved.masterUrl, ExtractorLinkType.M3U8) {
                this.referer = FLIX_REFERER
                this.headers = browserHeaders + mapOf(
                    "Referer" to FLIX_REFERER,
                    "Origin" to FLIX_BASE,
                    "Accept" to "*/*",
                )
                this.quality = Qualities.P1080.value
            })

            for ((lang, subUrl) in resolved.subtitles) {
                if (!emittedSubs.add(subUrl)) continue
                val ext = subUrl.substringAfterLast(".", "").substringBefore("?").uppercase()
                    .takeIf { it in listOf("ASS", "SSA", "SRT", "VTT") }
                val subName = if (ext != null) "$lang ($ext)" else lang
                subtitleCallback(newSubtitleFile(subName, subUrl))
            }
            subsEmitted = true
            Log.d("Reanime", "loadLinks $label: ${resolved.subtitles.size} subs emitidos")
        }

        if (!any) Log.w("Reanime", "loadLinks ep $ep: ningún servidor resolvió")
        return any
    }

    // ------------------------------------------------------------------
    // Interceptor: los playlists de flixcloud responden en Base64
    // ------------------------------------------------------------------

    @Suppress("ObjectLiteralToLambda")
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                val url = request.url.toString()

                if (url.contains("slopnet.site")) {
                    val newReq = request.newBuilder()
                        .header("User-Agent", browserHeaders["User-Agent"]!!)
                        .header("Referer", FLIX_REFERER)
                        .build()
                    return chain.proceed(newReq)
                }

                if (url.contains("flixcloud.cc") && url.contains(".m3u8")) {
                    val response = chain.proceed(request)
                    return try {
                    val bodyText = response.peekBody(2L * 1024 * 1024).string()
                    if (bodyText.trimStart().startsWith("#EXTM3U")) return response

                    val clean = bodyText.trim().replace(Regex("[^A-Za-z0-9+/=]"), "")
                    if (clean.length < 16) {
                        Log.w("Reanime", "interceptor m3u8: no b64 (code=${response.code})")
                        return response
                    }
                    val raw = Base64.decode(clean, Base64.DEFAULT)

                    var decodedBody: String? = null
                    val pks = synchronized(activePks) { activePks.toList() }
                    for (pk in pks) {
                        val dec = ByteArray(raw.size) { i ->
                            (raw[i].toInt() xor pk[i % pk.size].toInt()).toByte()
                        }
                        if (String(dec, 0, 7, Charsets.UTF_8) == "#EXTM3U") {
                            decodedBody = String(dec, Charsets.UTF_8)
                            break
                        }
                    }
                    if (decodedBody == null) {
                        Log.w("Reanime", "interceptor m3u8: ningun PK valido (pks=${pks.size})")
                        return response
                    }

                    val contentType = response.body?.contentType()
                    response.newBuilder()
                        .body(okhttp3.ResponseBody.create(contentType, decodedBody))
                        .build()
                    } catch (e: Exception) {
                        Log.w("Reanime", "interceptor m3u8 error: ${e.message}")
                        response
                    }
                }

                val response = chain.proceed(request)
                return try {

                    val head = try {
                        response.peekBody(12).bytes()
                    } catch (_: Exception) {
                        return response
                    }

                    val isPng = head.size >= 4 &&
                        head[0] == 0x89.toByte() && head[1] == 0x50.toByte() &&
                        head[2] == 0x4e.toByte() && head[3] == 0x47.toByte()
                    val isRiffWebp = head.size >= 12 &&
                        head[0] == 0x52.toByte() && head[1] == 0x49.toByte() &&
                        head[2] == 0x46.toByte() && head[3] == 0x46.toByte() &&
                        head[8] == 0x57.toByte() && head[9] == 0x45.toByte() &&
                        head[10] == 0x42.toByte() && head[11] == 0x50.toByte()

                    if (!isPng && !isRiffWebp) return response

                    val bytes = response.body?.bytes() ?: return response

                    val skip = if (isRiffWebp) 12 else 8
                    val payload = bytes.copyOfRange(skip, bytes.size)

                    val transformed = if (payload.isNotEmpty() && payload[0] == 0x47.toByte()) {
                        payload
                    } else {
                        ByteArray(payload.size) { i ->
                            (payload[i].toInt() xor SEG_XOR_KEY[i and 15].toInt()).toByte()
                        }
                    }

                    response.newBuilder()
                        .body(okhttp3.ResponseBody.create(response.body?.contentType(), transformed))
                        .build()
                } catch (e: Exception) {
                    Log.w("Reanime", "interceptor segmento error: ${e.message}")
                    response
                }
            }
        }
    }
}