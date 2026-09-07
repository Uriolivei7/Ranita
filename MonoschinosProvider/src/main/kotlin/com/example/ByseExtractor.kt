package com.example

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class ByseHttpExtractor {

    class ByseSource(val url: String, val label: String?, val subtitles: List<SubtitleFile>)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun extract(embedUrl: String, embedParent: String, embedOrigin: String): List<ByseSource> {
        val host = runCatching { java.net.URI(embedUrl).host }.getOrNull() ?: embedUrl.substringAfter("://").substringBefore("/")
        val origin = "https://$host"
        val mediaId = runCatching {
            java.net.URI(embedUrl).path?.split("/")?.filter { it.isNotBlank() }?.getOrNull(1)
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: throw Exception("Byse: could not read media id from $embedUrl")
        Log.d("MonosChinos", "[Byse] origin=$origin mediaId=$mediaId")

        val challenge = parseJson<ByteChallenge>(
            postJson("$origin/api/videos/access/challenge", apiHeaders(), "{}")
        )

        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        }.generateKeyPair()
        val publicKey = keyPair.public as ECPublicKey
        fun padded32(v: ByteArray): ByteArray = when {
            v.size > 32 -> v.copyOfRange(v.size - 32, v.size)
            v.size < 32 -> ByteArray(32 - v.size) + v
            else -> v
        }
        val signer = Signature.getInstance("SHA256withECDSA").apply {
            initSign(keyPair.private)
            update(challenge.nonce.toByteArray(StandardCharsets.UTF_8))
        }
        val signature = signer.sign()

        val attestBody = ByteAttestRequest(
            viewerId = "",
            deviceId = "",
            challengeId = challenge.challengeId,
            nonce = challenge.nonce,
            signature = urlSafeBase64(signature),
            publicKey = ByteEcJwk(
                alg = "ES256", crv = "P-256", ext = true,
                keyOps = listOf("verify"), kty = "EC",
                x = urlSafeBase64(padded32(publicKey.w.affineX.toByteArray())),
                y = urlSafeBase64(padded32(publicKey.w.affineY.toByteArray())),
            ),
            client = buildFingerprint(),
            storage = emptyMap(),
            attributes = mapOf("entropy" to "low"),
        )
        val attest = parseJson<ByteAttestResponse>(
            postJson("$origin/api/videos/access/attest", apiHeaders(), attestBody.toJson())
        )
        Log.d("MonosChinos", "[Byse] attest ok: token=${attest.token.take(16)}... viewer=${attest.viewerId} confidence=${attest.confidence}")

        val gateHeaders = mapOf(
            "Cookie" to "byse_viewer_id=${attest.viewerId}; byse_device_id=${attest.deviceId}",
            "X-Embed-Origin" to (runCatching { java.net.URI(embedOrigin).host }.getOrNull() ?: embedOrigin),
            "X-Embed-Referer" to embedParent,
            "X-Embed-Parent" to embedParent,
        )

        val fingerprint = ByteFingerprint(attest.token, attest.viewerId, attest.deviceId, attest.confidence)
        val fpBody = ByteFingerprintPayload(fingerprint).toJson()

        val captcha = parseJson<ByteCaptchaChallenge>(
            postJson("$origin/api/videos/$mediaId/embed/captcha", apiHeaders(gateHeaders), fpBody)
        )
        Log.d("MonosChinos", "[Byse] captcha difficulty=${captcha.powDifficulty}")

        val solution = solvePow(captcha.powNonce, captcha.powDifficulty)
        val verify = parseJson<ByteVerifyResponse>(
            postJson(
                "$origin/api/videos/$mediaId/embed/captcha/verify",
                apiHeaders(gateHeaders),
                ByteVerifyRequest(captcha.powToken, solution, fingerprint).toJson()
            )
        )
        if (verify.status != "ok") throw Exception("Byse: PoW verification failed (${verify.status})")
        val captchaToken = verify.token?.takeIf { it.isNotBlank() }
            ?: throw Exception("Byse: missing captcha token")
        Log.d("MonosChinos", "[Byse] captcha ok (token ${captchaToken.take(12)})")

        val playback = parseJson<BytePlaybackResponse>(
            postJson(
                "$origin/api/videos/$mediaId/embed/playback",
                apiHeaders(gateHeaders + ("X-Captcha-Token" to captchaToken)),
                fpBody
            )
        )
        val encrypted = playback.playback ?: throw Exception("Byse: no playback payload")
        val decryptedJson = decrypt(encrypted)
        Log.d("MonosChinos", "[Byse] decrypted len=${decryptedJson.length}")

        val decrypted = parseJson<ByteDecryptedPlayback>(decryptedJson)
        val subtitles = decrypted.tracks.orEmpty().mapNotNull { track ->
            val file = track.file ?: track.url ?: return@mapNotNull null
            if (!file.startsWith("http")) return@mapNotNull null
            SubtitleFile(track.label ?: track.language ?: "Subtitle", file)
        }.distinctBy { it.lang + it.url }

        return decrypted.sources.orEmpty().mapNotNull { source ->
            val url = source.url ?: source.file ?: return@mapNotNull null
            if (!url.startsWith("http")) return@mapNotNull null
            ByseSource(url, source.label, subtitles)
        }
    }

    // ============================ HTTP helpers ============================

    private fun postJson(url: String, headers: Map<String, String>, jsonBody: String): String {
        val builder = Request.Builder().url(url)
        for ((k, v) in headers) builder.header(k, v)
        builder.post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
        val resp = client.newCall(builder.build()).execute()
        resp.use {
            val code = it.code
            val body = it.body?.string().orEmpty()
            if (code != 200) throw Exception("Byse POST $code for $url: ${body.take(200)}")
            return body
        }
    }

    private fun apiHeaders(extra: Map<String, String>? = null): Map<String, String> = buildMap {
        put("Accept", "*/*")
        put("Accept-Language", "en-US,en;q=0.9")
        put("Cache-Control", "no-cache")
        put("Pragma", "no-cache")
        put("User-Agent", constAndroidChromeUA)
        if (extra != null) putAll(extra)
    }

    companion object {
        /** Byse hates Android user agents, so yeah */
        const val constAndroidChromeUA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"
    }

    // =============================== Crypto ===============================

    private fun decrypt(input: ByteEncryptedPlayback): String {
        fun List<String>.concatDecoded(): ByteArray = fold(ByteArray(0)) { acc, part -> acc + urlSafeBase64Decode(part) }

        val keyBytes = when {
            input.version == null -> input.keyParts.concatDecoded()
            else -> {
                val version = input.version.toIntOrNull() ?: 1
                if (version in 1..input.keyParts.size) {
                    listOf(input.keyParts[version - 1], input.keyParts[input.keyParts.size - version]).concatDecoded()
                } else {
                    input.keyParts.concatDecoded()
                }
            }
        }

        val payloadBytes = urlSafeBase64Decode(input.payload)
        if (payloadBytes.size < 16) throw Exception("Byse: payload too short")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            GCMParameterSpec(128, urlSafeBase64Decode(input.iv)),
        )
        return String(cipher.doFinal(payloadBytes), StandardCharsets.UTF_8)
    }

    private fun urlSafeBase64Decode(input: String): ByteArray {
        val base64 = input.replace('-', '+').replace('_', '/')
        val padding = when (base64.length % 4) {
            2 -> "=="
            3 -> "="
            else -> ""
        }
        return Base64.decode(base64 + padding, Base64.DEFAULT)
    }

    private fun urlSafeBase64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun buildFingerprint() = ByteClientFingerprint(
        userAgent = constAndroidChromeUA,
        pixelRatio = 1,
        screenWidth = 1920,
        screenHeight = 1080,
        colorDepth = 24,
        languages = listOf("en-US", "en"),
        timezone = "America/New_York",
        hardwareConcurrency = 8,
        touchPoints = 0,
        webglVendor = "Google Inc. (Intel)",
        webglRenderer = "ANGLE (Intel, Intel(R) UHD Graphics 630, OpenGL 4.5)",
        canvasHash = randomHash(),
        audioHash = randomHash(),
        webglParamsHash = randomHash(),
        fontsHash = randomHash(),
        codecsHash = randomHash(),
        mediaDevices = "ai0ao0vi0",
        pointerType = "fine,hover",
        extra = mapOf("vendor" to "", "appVersion" to "5.0 (X11)"),
    )

    private fun randomHash(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return urlSafeBase64(bytes)
    }

    // ================================ PoW =================================

    private fun solvePow(nonce: String, difficulty: Int, maxIterations: Int = 2_000_000): String {
        val prefix = "$nonce:".toByteArray(Charsets.ISO_8859_1)
        val bufferSize = 512
        val bufferMask = 511
        val initConst = 2654435761L
        val finalConst = 2246822519L
        val mask32 = 0xFFFFFFFFL

        fun rotl(value: Long, shift: Int): Long = ((value shl shift) or (value ushr (32 - shift))) and mask32

        val buf = LongArray(bufferSize)
        for (counter in 0..maxIterations) {
            val input = prefix + counter.toString().toByteArray(Charsets.ISO_8859_1)

            var s0 = 1779033703L
            var s1 = 3144134277L
            var s2 = 1013904242L
            var s3 = 2773480762L

            fun mix() {
                s0 = (s0 + s1) and mask32
                s3 = rotl(s3 xor s0, 16)
                s2 = (s2 + s3) and mask32
                s1 = rotl(s1 xor s2, 12)
                s0 = (s0 + s1) and mask32
                s3 = rotl(s3 xor s0, 8)
                s2 = (s2 + s3) and mask32
                s1 = rotl(s1 xor s2, 7)
            }

            for (b in input) {
                s0 = (s0 + (b.toInt() and 0xFF)) and mask32
                s0 = rotl(s0, 7)
                mix()
            }
            repeat(8) { mix() }

            for (i in 0 until bufferSize) {
                mix()
                buf[i] = (s0 xor s2) and mask32
            }

            repeat(2) {
                for (si in 0 until bufferSize) {
                    val a = (buf[si] and bufferMask.toLong()).toInt()
                    var c = (buf[si] + buf[a]) and mask32
                    c = rotl(c, 13)
                    c = (c xor ((buf[(si + 1) and bufferMask] * initConst) and mask32)) and mask32
                    buf[si] = c
                    s0 = (s0 xor c) and mask32
                    mix()
                }
            }

            mix()

            var outVal = s0
            for (ci in 0 until 64) {
                val d = buf[ci]
                outVal = (outVal + d) and mask32
                outVal = rotl(outVal, 5)
                outVal = (outVal xor ((d * finalConst) and mask32)) and mask32
            }
            outVal = (outVal xor s2) and mask32

            if (outVal.toInt().countLeadingZeroBits() >= difficulty) {
                return counter.toString()
            }
        }
        throw Exception("Byse: PoW exhausted ($maxIterations iterations, difficulty=$difficulty)")
    }

    // ================================ DTOs ================================

    class ByteChallenge(
        @JsonProperty("challenge_id") val challengeId: String,
        @JsonProperty("nonce") val nonce: String,
    )

    class ByteEcJwk(
        @JsonProperty("alg") val alg: String,
        @JsonProperty("crv") val crv: String,
        @JsonProperty("ext") val ext: Boolean,
        @JsonProperty("key_ops") val keyOps: List<String>,
        @JsonProperty("kty") val kty: String,
        @JsonProperty("x") val x: String,
        @JsonProperty("y") val y: String,
    )

    class ByteClientFingerprint(
        @JsonProperty("user_agent") val userAgent: String,
        @JsonProperty("pixel_ratio") val pixelRatio: Int,
        @JsonProperty("screen_width") val screenWidth: Int,
        @JsonProperty("screen_height") val screenHeight: Int,
        @JsonProperty("color_depth") val colorDepth: Int,
        @JsonProperty("languages") val languages: List<String>,
        @JsonProperty("timezone") val timezone: String,
        @JsonProperty("hardware_concurrency") val hardwareConcurrency: Int,
        @JsonProperty("touch_points") val touchPoints: Int,
        @JsonProperty("webgl_vendor") val webglVendor: String,
        @JsonProperty("webgl_renderer") val webglRenderer: String,
        @JsonProperty("canvas_hash") val canvasHash: String,
        @JsonProperty("audio_hash") val audioHash: String,
        @JsonProperty("webgl_params_hash") val webglParamsHash: String,
        @JsonProperty("fonts_hash") val fontsHash: String,
        @JsonProperty("codecs_hash") val codecsHash: String,
        @JsonProperty("media_devices") val mediaDevices: String,
        @JsonProperty("pointer_type") val pointerType: String,
        @JsonProperty("extra") val extra: Map<String, String>,
    )

    class ByteAttestRequest(
        @JsonProperty("viewer_id") val viewerId: String,
        @JsonProperty("device_id") val deviceId: String,
        @JsonProperty("challenge_id") val challengeId: String,
        @JsonProperty("nonce") val nonce: String,
        @JsonProperty("signature") val signature: String,
        @JsonProperty("public_key") val publicKey: ByteEcJwk,
        @JsonProperty("client") val client: ByteClientFingerprint,
        @JsonProperty("storage") val storage: Map<String, String>,
        @JsonProperty("attributes") val attributes: Map<String, String>,
    )

    class ByteAttestResponse(
        @JsonProperty("token") val token: String,
        @JsonProperty("viewer_id") val viewerId: String,
        @JsonProperty("device_id") val deviceId: String,
        @JsonProperty("confidence") val confidence: Double,
    )

    class ByteFingerprint(
        @JsonProperty("token") val token: String,
        @JsonProperty("viewer_id") val viewerId: String,
        @JsonProperty("device_id") val deviceId: String,
        @JsonProperty("confidence") val confidence: Double,
    )

    class ByteFingerprintPayload(val fingerprint: ByteFingerprint)

    class ByteCaptchaChallenge(
        @JsonProperty("pow_nonce") val powNonce: String,
        @JsonProperty("pow_difficulty") val powDifficulty: Int,
        @JsonProperty("pow_token") val powToken: String,
    )

    class ByteVerifyRequest(
        @JsonProperty("pow_token") val powToken: String,
        @JsonProperty("solution") val solution: String,
        @JsonProperty("fingerprint") val fingerprint: ByteFingerprint,
    )

    class ByteVerifyResponse(
        @JsonProperty("status") val status: String,
        @JsonProperty("token") val token: String? = null,
    )

    class BytePlaybackResponse(val playback: ByteEncryptedPlayback? = null)

    class ByteEncryptedPlayback(
        @JsonProperty("iv") val iv: String,
        @JsonProperty("key_parts") val keyParts: List<String>,
        @JsonProperty("payload") val payload: String,
        @JsonProperty("version") val version: String? = null,
    )

    class ByteDecryptedPlayback(
        @JsonProperty("sources") val sources: List<ByteVideoSource>? = null,
        @JsonProperty("tracks") val tracks: List<ByteSubtitle>? = null,
    )

    class ByteVideoSource(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
    )

    class ByteSubtitle(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("language") val language: String? = null,
    )
}