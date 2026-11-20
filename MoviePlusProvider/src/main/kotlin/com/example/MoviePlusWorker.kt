package com.example

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

object MoviePlusWorker {
    private const val BASE = "https://api.appsxploit.dev"
    private const val FB_API_KEY = "AIzaSyCVuJ2EQpizrj_dPJqfNUIMXOt_m8Tl9MM"
    private const val RESOLVE_CONCURRENCY = 6
    private const val VERSION_CODE = 90354
    private const val ID_TOKEN_TTL_MS = 720_000L

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaTypeOrNull()
    private val ANDROID_HEADERS = mapOf(
        "X-Android-Package" to "com.dvex.movp",
        "X-Android-Cert" to "BA89250E17180469CBB5CC9DFA087AE59FD7E008"
    )
    private val seasonKeyRe = Regex("(?i)temporada\\s*0*(\\d+)")
    private val sxeRe = Regex("(?i)S0*(\\d+)\\s*E0*(\\d+)")
    private val fbLock = Mutex()
    private val availCache = ConcurrentHashMap<String, Boolean>()

    @Volatile private var firebaseIdToken: String? = null
    @Volatile private var idTokenAtMs: Long = 0L

    class Session(
        val keyPair: KeyPair,
        val spkiB64: String,
        val deviceId: String
    ) {
        var sessionToken: String? = null
        var grants: Map<String, String> = emptyMap()
    }

    data class ResolvedLink(val quality: String, val size: String, val url: String)
    data class EpisodeLink(val season: Int, val episode: Int, val quality: String, val envelope: String)

    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)

    private fun sha256Hex(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun signB64(session: Session, msg: String): String {
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(session.keyPair.private)
        sig.update(msg.toByteArray(Charsets.UTF_8))
        return b64(sig.sign())
    }

    private fun esc(s: String): String {
        val out = StringBuilder()
        for (c in s) {
            when (c) {
                '\t' -> out.append("\\t")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    private fun json(vararg pairs: Pair<String, Any?>): String =
        pairs.joinToString(",", "{", "}") { (k, v) ->
            val value = when (v) {
                is String -> "\"" + esc(v) + "\""
                is Boolean, is Int, is Long -> v.toString()
                null -> "null"
                else -> "\"" + esc(v.toString()) + "\""
            }
            "\"" + esc(k) + "\":" + value
        }

    private suspend fun post(path: String, bodyBytes: ByteArray, headers: Map<String, String>): Pair<Int, String> {
        val rb = bodyBytes.toRequestBody(JSON_MEDIA)
        val h = mapOf("User-Agent" to "okhttp/4.12.0") + headers
        val r = app.post(BASE + path, headers = h, requestBody = rb, timeout = 30L)
        return r.code to r.text
    }

    private fun hybridHeaders(session: Session, path: String, body: ByteArray): Map<String, String> {
        val token = session.sessionToken ?: ""
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val bodyHash = sha256Hex(body)
        val tokenHash = sha256Hex(token.toByteArray(Charsets.UTF_8))
        val canon = "MOVP-HYBRID-REQUEST-V1\nPOST\n$path\n$bodyHash\n$nonce\n$tokenHash"
        return mapOf(
            "X-MOVP-Hybrid-Session" to token,
            "X-MOVP-Hybrid-Nonce" to nonce,
            "X-MOVP-Hybrid-Signature" to signB64(session, canon)
        )
    }

    private suspend fun postRetry(
        session: Session?, path: String, body: ByteArray,
        hybrid: Boolean = false, tries: Int = RESOLVE_CONCURRENCY,
        noRetry: Set<Int> = emptySet()
    ): Pair<Int, String> {
        var last = -1 to ""
        for (i in 0 until tries) {
            val headers = if (hybrid && session != null) hybridHeaders(session, path, body) else emptyMap()
            last = post(path, body, headers)
            if (last.first == 200 || last.first in noRetry) return last
            delay(2500L)
        }
        return last
    }

    private fun generateIdentity(): Session {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val kp = gen.generateKeyPair()
        return Session(kp, b64(kp.public.encoded), UUID.randomUUID().toString())
    }

    private suspend fun connect(session: Session): Boolean {
        Log.d("MoviePlusWorker", "connect start")
        for (attempt in 0 until 8) {
            try {
                val challengeBody = json("schemaVersion" to 1, "versionCode" to VERSION_CODE).toByteArray(Charsets.UTF_8)
                val (code1, text1) = post("/v2/client/challenge", challengeBody, emptyMap())
                Log.d("MoviePlusWorker", "challenge attempt=$attempt code=$code1 len=${text1.length}")
                if (code1 != 200) { delay(2500L); continue }
                val challenge = JSONObject(text1).getString("challenge")
                val signature = signB64(session, "MOVP-HYBRID-SESSION-V1\n$challenge\n${session.deviceId}")
                val sessionBody = json(
                    "schemaVersion" to 1,
                    "publicKeySpki" to session.spkiB64,
                    "challenge" to challenge,
                    "deviceId" to session.deviceId,
                    "signature" to signature
                ).toByteArray(Charsets.UTF_8)
                val (code2, text2) = post("/v2/client/session", sessionBody, emptyMap())
                Log.d("MoviePlusWorker", "session attempt=$attempt code=$code2 len=${text2.length}")
                if (code2 == 200) {
                    session.sessionToken = JSONObject(text2).getString("sessionToken")
                    return true
                }
                Log.w("MoviePlusWorker", "session inesperado code=$code2: ${text2.take(120)}")
            } catch (e: Exception) { }
            delay(2500L)
        }
        return false
    }

    private suspend fun bootstrap(session: Session): Boolean {
        val body = json("schemaVersion" to 1, "deviceId" to session.deviceId, "channel" to "prod", "settingsProfile" to 7)
            .toByteArray(Charsets.UTF_8)
        val (code, text) = postRetry(session, "/v2/client/bootstrap", body, hybrid = true)
        Log.d("MoviePlusWorker", "bootstrap code=$code len=${text.length}")
        if (code != 200) return false
        return try {
            val grants = JSONObject(text).optJSONObject("admission")?.optJSONObject("grants") ?: return false
            val map = HashMap<String, String>()
            val keys = grants.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = grants.optString(k, "")
            }
            session.grants = map
            map.containsKey("catalog") && map.containsKey("catalog-value")
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun newSession(): Session? = try {
        Log.d("MoviePlusWorker", "newSession start")
        val s = generateIdentity()
        if (!connect(s)) null
        else if (!bootstrap(s)) null
        else s
    } catch (e: Exception) {
        null
    }

    private suspend fun playback(session: Session, envelope: String): String? {
        val body = json(
            "schemaVersion" to 1,
            "deviceId" to session.deviceId,
            "grantId" to (session.grants["catalog-value"] ?: ""),
            "token" to envelope,
            "issuedAtMs" to (System.currentTimeMillis() - 1000),
            "resolveProvider" to true
        ).toByteArray(Charsets.UTF_8)
        val (code, text) = postRetry(session, "/v2/client/playback", body, hybrid = true, tries = 3, noRetry = setOf(503, 429))
        if (code != 200) return null
        return try {
            val o = JSONObject(text)
            if (o.optString("status") == "resolved" && o.optBoolean("ok")) {
                val v = o.optString("value")
                if (v.startsWith("http")) v else null
            } else null
        } catch (e: Exception) {
            null
        }
    }

    suspend fun resolveFresh(envelope: String): String? = try {
        val s = newSession() ?: return null
        playback(s, envelope)
    } catch (t: Throwable) {
        null
    }

    suspend fun resolveEnvelope(envelope: String): String? = try {
        resolveFresh(envelope)
    } catch (t: Throwable) {
        null
    }

    suspend fun resolveMovie(
        movieId: Int,
        fallback: List<String> = emptyList(),
        onLink: (suspend (ResolvedLink) -> Unit)? = null
    ): List<ResolvedLink> = try {
        val s = newSession() ?: return emptyList()
        val body = json(
            "schemaVersion" to 1,
            "deviceId" to s.deviceId,
            "grantId" to (s.grants["catalog"] ?: ""),
            "movieId" to movieId
        ).toByteArray(Charsets.UTF_8)
        val (code, text) = postRetry(s, "/v2/client/movie-variants", body, hybrid = true, tries = 3)
        if (code != 200) return emptyList()
        val variants = mutableListOf<String>()
        val arr = JSONObject(text).optJSONObject("movieLinks")?.optJSONArray("variants")
        if (arr != null) for (i in 0 until arr.length()) arr.optString(i)?.let { variants.add(it) }
        val effective = if (variants.isEmpty()) fallback else variants
        val byQuality = LinkedHashMap<String, Triple<String, String, String>>()
        for (v in effective) {
            val parts = v.split(",_,", limit = 3)
            if (parts.size != 3) continue
            val q = parts[0].trim()
            if (!byQuality.containsKey(q)) byQuality[q] = Triple(parts[0].trim(), parts[1].trim(), parts[2].trim())
        }
        val out = mutableListOf<ResolvedLink>()
        val seen = java.util.Collections.synchronizedSet(HashSet<String>())
        val results = kotlinx.coroutines.coroutineScope {
            val jobs = byQuality.values.map { (quality, size, envelope) ->
                async(Dispatchers.IO) {
                    try {
                        val url = resolveFresh(envelope) ?: return@async null
                        if (!seen.add(url)) return@async null
                        val link = ResolvedLink(quality, size, url)
                        onLink?.invoke(link)
                        link
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            jobs.awaitAll()
        }
        results.filterNotNull().distinctBy { it.url }
    } catch (e: Exception) {
        emptyList()
    }

    suspend fun ensureFirebaseToken(): String? = fbLock.withLock {
        val now = System.currentTimeMillis()
        if (firebaseIdToken != null && now - idTokenAtMs < ID_TOKEN_TTL_MS) return@withLock firebaseIdToken
        val s = newSession() ?: return@withLock null
        try {
            val outer = json("schemaVersion" to 1, "deviceId" to s.deviceId, "action" to "guest", "payload" to "__PAYLOAD__")
            val inner = json("deviceKey" to s.deviceId, "appVersion" to "17.3", "platform" to "android")
            val authBody = outer.replace("\"__PAYLOAD__\"", inner).toByteArray(Charsets.UTF_8)
            val (code, text) = postRetry(s, "/v2/client/auth", authBody, hybrid = true, tries = 4)
            if (code != 200) return@withLock null
            val customToken = JSONObject(text).optString("token")
            if (customToken.isBlank()) return@withLock null
            val url = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=$FB_API_KEY"
            val body = json("token" to customToken, "returnSecureToken" to true)
                .toRequestBody(JSON_MEDIA)
            val r = app.post(url, headers = ANDROID_HEADERS, requestBody = body, timeout = 25L)
            if (r.code != 200) return@withLock null
            val idToken = JSONObject(r.text).optString("idToken")
            if (idToken.isBlank()) return@withLock null
            firebaseIdToken = idToken
            idTokenAtMs = System.currentTimeMillis()
            idToken
        } catch (e: Exception) {
            null
        }
    }

    suspend fun episodeEnvelopes(seriesId: Int): List<EpisodeLink> = try {
        val token = ensureFirebaseToken() ?: return emptyList()
        val url = "https://firestore.googleapis.com/v1/projects/dev-movieplus/databases/(default)/documents/tv/$seriesId"
        val r = app.get(url, headers = ANDROID_HEADERS + ("Authorization" to "Bearer $token"), timeout = 25L)
        if (r.code != 200) return emptyList()
        val fields = JSONObject(r.text).optJSONObject("fields") ?: return emptyList()
        val out = mutableListOf<EpisodeLink>()
        val keys = fields.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val season = seasonKeyRe.find(key)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val quality = key.substringAfter('/', "").trim()
            val values = fields.optJSONObject(key)?.optJSONObject("arrayValue")?.optJSONArray("values") ?: continue
            for (i in 0 until values.length()) {
                val raw = values.optJSONObject(i)?.optString("stringValue") ?: continue
                val entry = raw.trim().trim('"')
                val parts = entry.split("_SPLIT_")
                if (parts.size < 2) continue
                val envelope = parts[1].trim()
                if (!envelope.startsWith("Movp2")) continue
                val ep = sxeRe.find(parts[0])?.groupValues?.get(2)?.toIntOrNull() ?: (i + 1)
                out.add(EpisodeLink(season, ep, quality, envelope))
            }
        }
        out
    } catch (e: Exception) {
        emptyList()
    }

    suspend fun currentCatalogUrls(): List<String> = try {
        val s = newSession() ?: return emptyList()
        val body = json(
            "schemaVersion" to 1,
            "deviceId" to s.deviceId,
            "grantId" to (s.grants["catalog"] ?: ""),
            "collection" to "app",
            "documentId" to "db"
        ).toByteArray(Charsets.UTF_8)
        val (code, text) = postRetry(s, "/v2/client/document", body, hybrid = true, tries = 4)
        if (code != 200) return emptyList()
        val urls = JSONObject(text)
            .optJSONObject("data")?.optJSONObject("config_gz")?.optJSONObject("prod")?.optJSONObject("urls")
            ?: return emptyList()
        listOf("github", "server1", "server2")
            .mapNotNull { urls.optString(it)?.takeIf { v -> v.startsWith("http") } }
    } catch (e: Exception) {
        emptyList()
    }

    private fun availKey(id: Int, isMovie: Boolean) = "${if (isMovie) "movie" else "series"}_$id"

    suspend fun filterAvailable(items: List<Pair<Int, Boolean>>): Set<Int> {
        Log.d("MoviePlusWorker", "filterAvailable items=${items.size}")
        val result = HashSet<Int>()
        val need = ArrayList<Pair<Int, Boolean>>()
        for ((id, isMovie) in items) {
            when (availCache[availKey(id, isMovie)]) {
                true -> result.add(id)
                false -> {}
                null -> need.add(id to isMovie)
            }
        }
        if (need.isEmpty()) return result
        val token = withTimeoutOrNull(20000L) { ensureFirebaseToken() } ?: run {
            Log.w("MoviePlusWorker", "filterAvailable token timeout/null -> fail-open ${items.size} ids")
            return items.map { it.first }.toSet()
        }
        for (chunk in need.chunked(100)) {
            try {
                val docs = chunk.joinToString(",") {
                    "\"projects/dev-movieplus/databases/(default)/documents/contentLinkVersions/${availKey(it.first, it.second)}\""
                }
                val body = "{\"documents\":[$docs]}".toRequestBody(JSON_MEDIA)
                val url = "https://firestore.googleapis.com/v1/projects/dev-movieplus/databases/(default)/documents:batchGet"
                val r = app.post(url, headers = ANDROID_HEADERS + ("Authorization" to "Bearer $token"), requestBody = body, timeout = 25L)
                if (r.code != 200) {
                    chunk.forEach { result.add(it.first) }
                    continue
                }
                val arr = JSONArray(r.text)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val found = o.optJSONObject("found")
                    if (found != null) {
                        val name = found.optString("name").substringAfterLast('/')
                        val deleted = found.optJSONObject("fields")?.optJSONObject("deleted")?.optBoolean("booleanValue", false) ?: false
                        availCache[name] = !deleted
                        if (!deleted) name.substringAfterLast('_').toIntOrNull()?.let { result.add(it) }
                    } else {
                        val missing = o.optString("missing").substringAfterLast('/')
                        if (missing.isNotBlank()) availCache[missing] = false
                    }
                }
            } catch (e: Exception) {
                chunk.forEach { result.add(it.first) }
            }
        }
        return result
    }
}
