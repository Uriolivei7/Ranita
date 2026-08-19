package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
            .groupBy { it.deviceId }
            .mapNotNull { (_, gens) -> gens.maxByOrNull { it.gen ?: it.updatedAt } }

    suspend fun assemblePayload(token: String, devices: List<SyncDevice>, deviceId: String): String? {
        val drafts = devices.filter { it.deviceId == deviceId }
        if (drafts.isEmpty()) return null
        val byGen = drafts.groupBy { it.gen }
        val gens: MutableList<Long?> = byGen.keys.filterNotNull().sortedDescending().toMutableList()
        if (byGen.containsKey(null)) gens += null
        for (gen in gens) {
            val group = byGen[gen] ?: continue
            val chosen = if (gen == null) {
                val anchor = group.filter { it.chunkIndex == 0 }.maxByOrNull { it.updatedAt } ?: continue
                group.filter { kotlin.math.abs(anchor.updatedAt - it.updatedAt) <= 120L }
            } else group
            val byIndex = HashMap<Int, SyncDevice>()
            for (d in chosen) {
                val prev = byIndex[d.chunkIndex]
                if (prev == null || d.updatedAt > prev.updatedAt) byIndex[d.chunkIndex] = d
            }
            val maxChunk = byIndex.keys.maxOrNull() ?: -1
            val listedTotal = maxChunk + 1
            if ((0 until listedTotal).any { byIndex[it] == null }) {
                log("[restore] $deviceId: gen ${gen ?: "?"} incompleta, chunks=${byIndex.keys.sorted()}")
                continue
            }
            val draft0 = byIndex[0] ?: continue
            val chunkId0 = draft0.itemContentId ?: draft0.itemId
            val body0 = fetchChunkBody(token, chunkId0)
            if (body0 == null) {
                log("[restore] $deviceId: chunk 0 fetch falló")
                return null
            }
            val realTotal = parseChunkTotal(body0)
            if (realTotal != null && realTotal != listedTotal) {
                log("[restore] $deviceId: gen ${gen ?: "?"} mismatch listed=$listedTotal real=$realTotal")
                continue
            }
            val total = realTotal ?: listedTotal
            val sb = StringBuilder()
            val data0 = stripChunkHeader(body0, 0, total)
            if (data0 == null) {
                log("[restore] $deviceId: chunk 0/$total formato inválido")
                return null
            }
            sb.append(data0)
            for (i in 1 until total) {
                val draft = byIndex[i]
                if (draft == null) {
                    log("[restore] $deviceId: chunk $i/$total no existe")
                    return null
                }
                val chunkId = draft.itemContentId ?: draft.itemId
                val body = fetchChunkBody(token, chunkId)
                if (body == null) {
                    log("[restore] $deviceId: chunk $i/$total fetch falló")
                    return null
                }
                val data = stripChunkHeader(body, i, total)
                if (data == null) {
                    log("[restore] $deviceId: chunk $i/$total formato inválido")
                    return null
                }
                sb.append(data)
            }
            return sb.toString()
        }
        log("[restore] $deviceId: ninguna gen completa, intentando best-effort")
        return assembleBestEffort(token, drafts, deviceId)
    }

    private suspend fun assembleBestEffort(
        token: String,
        drafts: List<SyncDevice>,
        deviceId: String
    ): String? {
        val latestChunk0 = drafts.filter { it.chunkIndex == 0 }
            .maxByOrNull { it.gen ?: it.updatedAt } ?: return null
        val body0 = fetchChunkBody(token, latestChunk0.itemContentId ?: latestChunk0.itemId)
            ?: return null
        val realTotal = parseChunkTotal(body0) ?: return null

        val allByIndex = HashMap<Int, SyncDevice>()
        for (d in drafts) {
            val prev = allByIndex[d.chunkIndex]
            if (prev == null) {
                allByIndex[d.chunkIndex] = d
            } else {
                val dGen = d.gen ?: 0L
                val prevGen = prev.gen ?: 0L
                if (dGen > prevGen || (dGen == prevGen && d.updatedAt > prev.updatedAt)) {
                    allByIndex[d.chunkIndex] = d
                }
            }
        }

        for (i in 0 until realTotal) {
            if (allByIndex[i] == null) {
                log("[restore] $deviceId: best-effort falta chunk $i/$realTotal")
                return null
            }
        }

        val sb = StringBuilder()
        val data0 = stripChunkHeader(body0, 0, realTotal) ?: return null
        sb.append(data0)
        for (i in 1 until realTotal) {
            val draft = allByIndex[i]!!
            val chunkId = draft.itemContentId ?: draft.itemId
            val body = fetchChunkBody(token, chunkId) ?: return null
            val data = stripChunkHeader(body, i, realTotal) ?: return null
            sb.append(data)
        }

        val result = sb.toString()
        log("[restore] $deviceId: best-effort armado ok (${result.length} chars)")
        return result
    }

    private fun parseChunkTotal(body: String): Int? {
        if (!body.startsWith(CHUNK_PREFIX)) return null
        val parts = body.split("|", limit = 3)
        if (parts.size != 3) return null
        val nums = parts[1].split("/")
        return nums.getOrNull(1)?.toIntOrNull()
    }

    private suspend fun fetchChunkBody(token: String, itemId: String): String? {
        val query = """query { node(id: "$itemId") { ... on DraftIssue { id title bodyText updatedAt } } }"""
        val resp = graphql(token, query)
        val body = resp?.data?.node?.bodyText
        if (body == null) err("fetchChunkBody($itemId): ${resp?.errors?.joinToString() { it.message ?: "" }}")
        return body
    }

    private fun stripChunkHeader(body: String, index: Int, total: Int): String? {
        if (!body.startsWith(CHUNK_PREFIX)) return body
        val parts = body.split("|", limit = 3)
        if (parts.size != 3) return null
        val nums = parts[1].split("/")
        val i = nums.getOrNull(0)?.toIntOrNull() ?: -1
        val t = nums.getOrNull(1)?.toIntOrNull() ?: -1
        if (i != index || t != total) return null
        return parts[2]
    }

    @SuppressLint("HardwareIds")
    fun getDeviceId(packageName: String, context: Context): String {
        val androidId =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        if (!androidId.isNullOrEmpty()) return md5(packageName + androidId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val serial = Build.getSerial()
                if (!serial.isNullOrEmpty() && serial != "unknown") return md5(packageName + serial)
            } catch (_: SecurityException) {
            }
        } else {
            val serial = Build.SERIAL
            if (!serial.isNullOrEmpty() && serial != "unknown") return md5(packageName + serial)
        }
        val deviceInfo = "${Build.BRAND}_${Build.MODEL}_${Build.DEVICE}"
        return md5(packageName + UUID.nameUUIDFromBytes(deviceInfo.toByteArray()).toString())
    }

    fun md5(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private suspend fun graphql(
        token: String,
        query: String,
        maxRetries: Int = 3
    ): GitHubGraphQLResponse? {
        val headers = mapOf(
            "Content-Type" to "application/json",
            "Authorization" to "Bearer $token",
            "Accept" to "application/vnd.github+json",
        )
        for (attempt in 0..maxRetries) {
            val resp = try {
                val res = app.post(API_URL, headers = headers, json = mapOf("query" to query))
                if (res.isSuccessful) {
                    json.decodeFromString<GitHubGraphQLResponse>(res.text)
                } else {
                    if (res.code == 503 && attempt < maxRetries) {
                        log("[sync] HTTP 503, retry ${attempt + 1}/$maxRetries")
                        delay(3000L * (1 shl attempt))
                        continue
                    }
                    err("graphql HTTP ${res.code}")
                    null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt < maxRetries) {
                    log("[sync] graphql error, retry ${attempt + 1}/$maxRetries")
                    delay(3000L * (1 shl attempt))
                    continue
                }
                err("graphql failed: ${e.message}")
                null
            }
            return resp
        }
        return null
    }

    suspend fun fetchProjectId(token: String, projectNum: Int): String? {
        val query = "query { viewer { projectV2(number: $projectNum) { id } } }"
        val resp = graphql(token, query)
        val id = resp?.data?.viewer?.projectV2?.id
        if (id != null) log("[sync] project #$projectNum ok")
        else err("fetchProjectId: no accesible")
        return id
    }

    suspend fun fetchDevices(token: String, projectNum: Int): List<SyncDevice>? {
        val all = mutableListOf<SyncDevice>()
        var cursor: String? = null
        while (true) {
            val after = if (cursor == null) "" else ", after: \"$cursor\""
            val query = """
                query { viewer { projectV2(number: $projectNum) {
                    id
                    items(first: 100$after) { nodes { id content {
                        __typename
                        ... on DraftIssue { id title updatedAt }
                    } } pageInfo { endCursor hasNextPage } }
                } } }
            """.trimIndent()
            val resp = graphql(token, query)
            if (resp == null) {
                err("fetchDevices: falló la consulta del proyecto")
                return null
            }
            val project = resp.data?.viewer?.projectV2
            if (project == null) {
                err("fetchDevices: proyecto no accesible con este token: ${resp.errors?.joinToString() { it.message ?: "" }}")
                return null
            }
            val nodes = project.items?.nodes ?: emptyList()
            for (node in nodes) {
                val content = node.content ?: continue
                val title = content.title ?: continue
                val baseId = parseDraftTitle(title).first
                val (gen, chunk) = parseDraftTitle(title).second
                val itemId = node.id ?: continue
                if (itemId.isEmpty()) continue
                all.add(
                    SyncDevice(
                        name = title,
                        deviceId = baseId,
                        itemId = itemId,
                        updatedAt = parseIsoTime(content.updatedAt),
                        chunkIndex = chunk,
                        itemContentId = content.id,
                        gen = gen,
                    )
                )
            }
            val pageInfo = project.items?.pageInfo
            if (pageInfo?.hasNextPage != true || pageInfo.endCursor.isNullOrEmpty()) break
            cursor = pageInfo.endCursor
        }
        log("fetchDevices: ${all.size} item(s), ${mainDrafts(all).size} dispositivo(s)")
        return all
    }

    // Título -> (deviceId base, (gen, chunkIndex)); retrocompatible:
    //  "deviceId"            -> (deviceId, (null, 0))
    //  "deviceId#3"          -> (deviceId, (null, 3))
    //  "deviceId#123.0"      -> (deviceId, (123, 0))
    private fun parseDraftTitle(title: String): Pair<String, Pair<Long?, Int>> {
        val idx = title.lastIndexOf('#')
        if (idx <= 0) return title to (null to 0)
        val suffix = title.substring(idx + 1)
        val baseId = title.substring(0, idx)
        val dot = suffix.indexOf('.')
        val gen: Long?
        val chunk: Int?
        if (dot >= 0) {
            gen = suffix.substring(0, dot).toLongOrNull()
            chunk = suffix.substring(dot + 1).toIntOrNull()
        } else {
            gen = null
            chunk = suffix.toIntOrNull()
        }
        if (chunk == null) return title to (null to 0)
        return baseId to (gen to chunk)
    }

    suspend fun registerDevice(
        token: String,
        projectId: String,
        deviceName: String,
        chunks: List<String>,
        gen: Long
    ): List<String>? {
        val ids = mutableListOf<String>()
        for ((i, chunk) in chunks.withIndex()) {
            val title = "$deviceName#$gen.$i"
            val body = makeChunkBody(i, chunks.size, chunk)
            val (itemId, contentId) = registerSingle(token, projectId, title, body)
            if (itemId == null || contentId == null) {
                err("register chunk $i falló: ${SyncNetwork.lastError}")
                return null
            }
            ids.add(contentId)
        }
        log("[push] register: ${chunks.size} chunks")
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
        return itemId to contentId
    }

    suspend fun updateDevice(
        token: String,
        projectId: String,
        deviceName: String,
        chunks: List<String>,
        existing: Map<Int, String>,
        gen: Long
    ): Map<Int, String>? {
        val result = existing.toMutableMap()
        for ((i, chunk) in chunks.withIndex()) {
            val title = "$deviceName#$gen.$i"
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
        log("[push] update: ${chunks.size} chunks")
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

    suspend fun deleteDraft(token: String, projectId: String, itemId: String): Boolean {
        val query = """
            mutation { deleteProjectV2Item(input: {
                projectId: "$projectId"
                itemId: "$itemId"
            }) { deletedItemId } }
        """.trimIndent()
        val resp = graphql(token, query)
        val ok = resp?.data?.deleteItem?.deletedItemId != null
        if (!ok) err("deleteDraft($itemId) fallo")
        else log("[push] draft eliminado")
        return ok
    }

    suspend fun cleanupStaleDrafts(
        token: String,
        projectId: String,
        deviceId: String,
        devices: List<SyncDevice>,
        removeAll: Boolean = false
    ) {
        val drafts = devices.filter { it.deviceId == deviceId }
        if (drafts.isEmpty()) return
        val keep = if (removeAll) {
            emptySet<String>()
        } else {
            val anchor = drafts.filter { it.chunkIndex == 0 }.maxByOrNull { it.updatedAt } ?: return
            val anchorGen = anchor.gen
            if (anchorGen != null) {
                drafts.filter { it.gen == anchorGen }.map { it.itemId }.toSet()
            } else {
                drafts.filter { kotlin.math.abs(anchor.updatedAt - it.updatedAt) <= 120L }
                    .map { it.itemId }
                    .toSet()
            }
        }
        val stale = drafts.filter { it.itemId !in keep }
        if (stale.isEmpty()) return
        log("[push] cleanup: ${stale.size} stale drafts de $deviceId")
        for (draft in stale) {
            deleteDraft(token, projectId, draft.itemId)
        }
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