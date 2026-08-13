package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import com.lagradost.cloudstream3.app
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import kotlinx.serialization.decodeFromString
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object SyncNetwork {

    private const val TAG = "SyncStream"
    private const val API_URL = "https://api.github.com/graphql"
    val json = Json { ignoreUnknownKeys = true }

    private const val CHUNK_PREFIX = "CS1"
    private const val CHUNK_SIZE = 60000

    @Volatile var lastError: String? = null

    fun compressData(data: String): String {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { gz -> gz.write(data.toByteArray(Charsets.UTF_8)) }
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    fun decompressData(data: String): String {
        return try {
            val compressed = Base64.decode(data, Base64.NO_WRAP)
            GZIPInputStream(ByteArrayInputStream(compressed))
                .bufferedReader(Charsets.UTF_8).readText()
        } catch (_: Exception) {
            data
        }
    }

    fun splitChunks(data: String): List<String> =
        if (data.length <= CHUNK_SIZE) listOf(data)
        else data.chunked(CHUNK_SIZE)

    fun makeChunkBody(index: Int, total: Int, chunkData: String): String =
        "$CHUNK_PREFIX|$index/$total|$chunkData"

    fun mainDrafts(devices: List<SyncDevice>): List<SyncDevice> =
        devices.filter { it.chunkIndex == 0 }

    fun assemblePayload(devices: List<SyncDevice>, deviceId: String): String? {
        val drafts = devices.filter { it.deviceId == deviceId && it.rawChunkData != null }
        if (drafts.isEmpty()) return null
        val main = drafts.firstOrNull { it.chunkIndex == 0 } ?: return null
        val total = main.totalChunks
        if (total < 1) return null
        val byIndex = drafts.associateBy { it.chunkIndex }
        if ((0 until total).any { byIndex[it] == null }) return null
        return (0 until total).map { byIndex[it]!!.rawChunkData.orEmpty() }.joinToString("")
    }

    @SuppressLint("HardwareIds")
    fun getDeviceId(packageName: String, context: Context): String {
        val androidId =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        if (!androidId.isNullOrEmpty()) return md5(packageName + androidId)
        val deviceInfo = "${Build.BRAND}_${Build.MODEL}_${Build.DEVICE}"
        return md5(packageName + UUID.nameUUIDFromBytes(deviceInfo.toByteArray()).toString())
    }

    fun md5(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private suspend fun graphql(token: String, query: String): GitHubGraphQLResponse? {
        val headers = mapOf(
            "Content-Type" to "application/json",
            "Authorization" to "Bearer $token",
            "Accept" to "application/vnd.github+json",
        )
        return try {
            val res = app.post(API_URL, headers = headers, json = mapOf("query" to query))
            if (res.isSuccessful) {
                json.decodeFromString<GitHubGraphQLResponse>(res.text)
            } else {
                err("graphql HTTP ${res.code}")
                null
            }
        } catch (e: Exception) {
            err("graphql failed: ${e.message}")
            null
        }
    }

    suspend fun fetchProjectId(token: String, projectNum: Int): String? {
        val query = "query { viewer { projectV2(number: $projectNum) { id } } }"
        val resp = graphql(token, query)
        val id = resp?.data?.viewer?.projectV2?.id
        if (id != null) log("Proyecto $projectNum -> $id")
        else err("fetchProjectId: proyecto no accesible: ${resp?.errors?.joinToString() { it.message ?: "" }}")
        return id
    }

    suspend fun fetchDevices(token: String, projectNum: Int): List<SyncDevice>? {
        val query = """
            query { viewer { projectV2(number: $projectNum) {
                id
                items(first: 100) { nodes { id content {
                    __typename
                    ... on DraftIssue { id title bodyText updatedAt }
                } } }
            } } }
        """.trimIndent()
        val resp = graphql(token, query)
        if (resp == null) {
            err("fetchDevices: falló la consulta del proyecto")
            return null
        }
        if (resp.data?.viewer?.projectV2 == null) {
            err("fetchDevices: proyecto no accesible con este token: ${resp.errors?.joinToString() { it.message ?: "" }}")
            return null
        }
        val nodes = resp.data.viewer.projectV2.items?.nodes ?: return emptyList()
        return nodes.mapNotNull { node ->
            val content = node.content ?: return@mapNotNull null
            val title = content.title ?: return@mapNotNull null
            val body = content.bodyText ?: ""
            val baseId = stripChunkSuffix(title)
            val titleChunk = title.removePrefix(baseId).removePrefix("#").toIntOrNull()
            var chunkIndex = 0
            var totalChunks = 1
            var chunkData: String? = null
            if (body.startsWith(CHUNK_PREFIX)) {
                val parts = body.split("|", limit = 3)
                if (parts.size == 3) {
                    val nums = parts[1].split("/")
                    chunkIndex = nums.getOrNull(0)?.toIntOrNull() ?: 0
                    totalChunks = nums.getOrNull(1)?.toIntOrNull() ?: 1
                    chunkData = parts[2].ifEmpty { null }
                }
            } else {
                chunkIndex = titleChunk ?: 0
                chunkData = body.ifEmpty { null }
            }
            SyncDevice(
                name = title,
                deviceId = baseId,
                itemId = node.id ?: "",
                updatedAt = parseIsoTime(content.updatedAt),
                rawChunkData = chunkData,
                chunkIndex = chunkIndex,
                totalChunks = totalChunks,
                itemContentId = content.id,
            )
        }.filter { it.itemId.isNotEmpty() }.also {
            log("fetchDevices: ${it.size} item(s), ${mainDrafts(it).size} dispositivo(s)")
        }
    }

    private fun stripChunkSuffix(title: String): String {
        val idx = title.lastIndexOf('#')
        if (idx > 0 && title.substring(idx + 1).toIntOrNull() != null) return title.substring(0, idx)
        return title
    }

    suspend fun registerDevice(
        token: String,
        projectId: String,
        deviceName: String,
        chunks: List<String>
    ): List<String>? {
        val ids = mutableListOf<String>()
        for ((i, chunk) in chunks.withIndex()) {
            val title = if (i == 0) deviceName else "$deviceName#$i"
            val body = makeChunkBody(i, chunks.size, chunk)
            val (itemId, contentId) = registerSingle(token, projectId, title, body)
            if (itemId == null || contentId == null) {
                err("register chunk $i falló: ${SyncNetwork.lastError}")
                return null
            }
            ids.add(contentId)
        }
        log("registerDevice: ${chunks.size} trozo(s) creados")
        return ids
    }

    private suspend fun registerSingle(
        token: String,
        projectId: String,
        title: String,
        body: String
    ): Pair<String?, String?> {
        val query = """
            mutation { addProjectV2DraftIssue(input: {
                projectId: "$projectId"
                title: "$title"
                body: "$body"
            }) { projectItem { id content { ... on DraftIssue { id } } } } }
        """.trimIndent()
        val resp = graphql(token, query)
        val itemId = resp?.data?.addDraft?.projectItem?.id
        val contentId = resp?.data?.addDraft?.projectItem?.content?.id
        if (itemId == null) err("register failed: ${resp?.errors?.joinToString() { it.message ?: "" }}")
        else log("register: draft $contentId creado")
        return itemId to contentId
    }

    suspend fun updateDevice(
        token: String,
        projectId: String,
        deviceName: String,
        chunks: List<String>,
        existing: Map<Int, String>
    ): Map<Int, String>? {
        val result = existing.toMutableMap()
        for ((i, chunk) in chunks.withIndex()) {
            val title = if (i == 0) deviceName else "$deviceName#$i"
            val body = makeChunkBody(i, chunks.size, chunk)
            val contentId = result[i]
            if (contentId != null) {
                if (!updateSingle(token, contentId, title, body)) return null
            } else {
                val (itemId, newId) = registerSingle(token, projectId, title, body)
                if (itemId == null || newId == null) return null
                result[i] = newId
            }
        }
        log("updateDevice: ${chunks.size} trozo(s) sincronizados")
        return result
    }

    private suspend fun updateSingle(
        token: String,
        contentId: String,
        title: String,
        body: String
    ): Boolean {
        val query = """
            mutation { updateProjectV2DraftIssue(input: {
                draftIssueId: "$contentId"
                title: "$title"
                body: "$body"
            }) { draftIssue { id } } }
        """.trimIndent()
        val resp = graphql(token, query)
        if (resp?.data?.updateDraft?.draftIssue?.id == null) {
            err("update failed: ${resp?.errors?.joinToString() { it.message ?: "" }}")
            return false
        }
        return true
    }

    private fun err(msg: String) {
        lastError = msg
        android.util.Log.e(TAG, msg)
    }

    private fun log(msg: String) {
        android.util.Log.i(TAG, msg)
    }

    // ISO8601 -> epoch seconds
    fun parseIsoTime(iso: String?): Long {
        if (iso.isNullOrEmpty()) return 0L
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.parse(iso)?.time?.div(1_000L) ?: 0L
        } catch (_: Exception) {
            0L
        }
    }
}