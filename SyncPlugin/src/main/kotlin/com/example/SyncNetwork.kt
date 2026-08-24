package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
        devices.groupBy { it.deviceId }.mapNotNull { (_, ds) ->
            val ptrs = ds.filter { it.isPointer }
            if (ptrs.isNotEmpty()) {
                ptrs.maxByOrNull { it.gen ?: 0L }
            } else {
                ds.filter { !it.isPointer && !it.isDelta && it.chunkIndex == 0 }
                    .maxByOrNull { it.gen ?: it.updatedAt }
            }
        }

    private val POINTER_REGEX = Regex("""^(.+)#P(\d+)$""")
    private val DELTA_PTR_REGEX = Regex("""^(.+)#D(\d+)$""")
    private val DELTA_CHUNK_REGEX = Regex("""^(.+)#D(\d+)\.(\d+)$""")

    fun findDeltaPointer(devices: List<SyncDevice>, deviceId: String): SyncDevice? =
        devices.filter { it.deviceId == deviceId && it.isDelta && it.chunkIndex == -1 }
            .maxByOrNull { it.gen ?: 0L }

    suspend fun assembleDeltaPayload(token: String, devices: List<SyncDevice>, deviceId: String): String? {
        val ptr = findDeltaPointer(devices, deviceId) ?: return null
        val gen = ptr.gen ?: return null
        val group = devices.filter {
            it.deviceId == deviceId && it.isDelta && it.chunkIndex >= 0 && it.gen == gen
        }
        if (group.isEmpty()) return null
        return assembleCommitted(token, group, deviceId, gen)
    }

    suspend fun assemblePayload(token: String, devices: List<SyncDevice>, deviceId: String): String? {
        val mine = devices.filter { it.deviceId == deviceId }
        if (mine.isEmpty()) return null
        val ptr = mine.filter { it.isPointer }.maxByOrNull { it.gen ?: 0L }
        if (ptr != null && ptr.gen != null) {
            val gen = ptr.gen!!
            val group = mine.filter { !it.isPointer && it.gen == gen }
            return assembleCommitted(token, group, deviceId, gen)
        }
        log("[restore] $deviceId: sin puntero, modo legacy")
        return legacyAssemble(token, mine, deviceId)
    }

    private suspend fun assembleCommitted(
        token: String,
        group: List<SyncDevice>,
        deviceId: String,
        gen: Long
    ): String? {
        val byIndex = HashMap<Int, SyncDevice>()
        for (d in group) {
            val prev = byIndex[d.chunkIndex]
            if (prev == null || d.updatedAt > prev.updatedAt) byIndex[d.chunkIndex] = d
        }
        val draft0 = byIndex[0]
        if (draft0 == null) {
            log("[restore] $deviceId: gen $gen sin chunk 0")
            return null
        }
        val body0 = fetchChunkBody(token, draft0.itemContentId ?: draft0.itemId)
            ?: return null
        val total = parseChunkTotal(body0)
        if (total == null) {
            log("[restore] $deviceId: chunk 0/$gen sin header de total")
            return null
        }
        val data0 = stripChunkHeader(body0, 0, total)
        if (data0 == null) {
            log("[restore] $deviceId: chunk 0/$total formato inválido")
            return null
        }
        val missing = (1 until total).filter { byIndex[it] == null }
        if (missing.isNotEmpty()) {
            log("[restore] $deviceId: gen $gen incompleta, faltan ${missing.size} chunk(s)")
            return null
        }
        val rest: List<String?> = coroutineScope {
            val sem = Semaphore(6)
            (1 until total).map { i ->
                async {
                    sem.withPermit {
                        val d = byIndex[i]!!
                        val body = fetchChunkBody(token, d.itemContentId ?: d.itemId)
                        body?.let { stripChunkHeader(it, i, total) }
                    }
                }
            }.awaitAll()
        }
        if (rest.any { it == null }) {
            log("[restore] $deviceId: gen $gen con chunks inválidos al descargar")
            return null
        }
        return buildString {
            append(data0)
            for (piece in rest) append(piece!!)
        }
    }

    private suspend fun legacyAssemble(
        token: String,
        drafts: List<SyncDevice>,
        deviceId: String
    ): String? {
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

    @SuppressLint("HardwareIds", "MissingPermission")
    fun getDeviceId(packageName: String, context: Context): String {
        val androidId =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        if (!androidId.isNullOrEmpty()) return md5(packageName + androidId)

        val serial: String? = try {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Build.getSerial() else Build.SERIAL
        } catch (_: Exception) {
            null
        }
        if (!serial.isNullOrEmpty() && serial != "unknown") return md5(packageName + serial)
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
                val itemId = node.id ?: continue
                if (itemId.isEmpty()) continue
                val ptrMatch = POINTER_REGEX.matchEntire(title)
                if (ptrMatch != null) {
                    all.add(
                        SyncDevice(
                            name = title,
                            deviceId = ptrMatch.groupValues[1],
                            itemId = itemId,
                            updatedAt = parseIsoTime(content.updatedAt),
                            chunkIndex = -1,
                            itemContentId = content.id,
                            gen = ptrMatch.groupValues[2].toLongOrNull(),
                            isPointer = true,
                        )
                    )
                    continue
                }
                val dPtrMatch = DELTA_PTR_REGEX.matchEntire(title)
                if (dPtrMatch != null) {
                    all.add(
                        SyncDevice(
                            name = title,
                            deviceId = dPtrMatch.groupValues[1],
                            itemId = itemId,
                            updatedAt = parseIsoTime(content.updatedAt),
                            chunkIndex = -1,
                            itemContentId = content.id,
                            gen = dPtrMatch.groupValues[2].toLongOrNull(),
                            isDelta = true,
                        )
                    )
                    continue
                }
                val dChunkMatch = DELTA_CHUNK_REGEX.matchEntire(title)
                if (dChunkMatch != null) {
                    all.add(
                        SyncDevice(
                            name = title,
                            deviceId = dChunkMatch.groupValues[1],
                            itemId = itemId,
                            updatedAt = parseIsoTime(content.updatedAt),
                            chunkIndex = dChunkMatch.groupValues[3].toIntOrNull() ?: 0,
                            itemContentId = content.id,
                            gen = dChunkMatch.groupValues[2].toLongOrNull(),
                            isDelta = true,
                        )
                    )
                    continue
                }
                val baseId = parseDraftTitle(title).first
                val (gen, chunk) = parseDraftTitle(title).second
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

    data class AtomicPushResult(
        val gen: Long,
        val stagedIds: Map<Int, String>,
        val pointerItemId: String,
        val pointerContentId: String,
    )

    /**
     * Publicación atómica: crea todos los chunks en drafts nuevos (staging),
     * y al final actualiza/crea el draft-puntero con el gen comprometido.
     * Si el proceso muere antes del flip, el puntero sigue apuntando a la
     * generación anterior completa — los lectores nunca ven datos rotos.
     */
    suspend fun pushAtomic(
        token: String,
        projectId: String,
        deviceId: String,
        chunks: List<String>,
        gen: Long,
        pointerItemId: String?,
        pointerContentId: String?,
        kind: Char = 'P'
    ): AtomicPushResult? {
        val staged = createStagedChunks(token, projectId, deviceId, chunks, gen, kind)
        if (staged == null) return null

        val ptrTitle = "$deviceId#$kind$gen"
        val ptr: Pair<String, String>? = if (pointerItemId != null && pointerContentId != null) {
            if (updateSingle(token, pointerContentId, ptrTitle, "sync-$kind $gen")) {
                pointerItemId to pointerContentId
            } else null
        } else {
            val (itemId, contentId) = registerSingle(token, projectId, ptrTitle, "sync-$kind $gen")
            if (itemId == null || contentId == null) null else itemId to contentId
        }
        if (ptr == null) {
            err("pushAtomic: no se pudo confirmar el puntero")
            return null
        }
        log("[push] commit: gen $gen (${chunks.size} chunks)")
        return AtomicPushResult(gen, staged, ptr.first, ptr.second)
    }

    private suspend fun createStagedChunks(
        token: String,
        projectId: String,
        deviceId: String,
        chunks: List<String>,
        gen: Long,
        kind: Char
    ): Map<Int, String>? {
        val results: List<Pair<Int, Pair<String, String>>?> = coroutineScope {
            val sem = Semaphore(5)
            chunks.indices.map { i ->
                async {
                    sem.withPermit {
                        // Sin letra de canal: los chunks SIEMPRE son "#<gen>.<i>".
                        // Solo el título del puntero lleva la letra (P/D).
                        val title = "$deviceId#$gen.$i"
                        val body = makeChunkBody(i, chunks.size, chunks[i])
                        val (itemId, contentId) = registerSingle(token, projectId, title, body)
                        if (itemId == null || contentId == null) null else i to (itemId to contentId)
                    }
                }
            }.awaitAll()
        }
        if (results.any { it == null }) {
            err("pushAtomic: fallo creando chunks staging, revirtiendo")
            coroutineScope {
                val sem = Semaphore(5)
                results.mapNotNull { it?.second?.first }.map { itemId ->
                    async { sem.withPermit { deleteDraft(token, projectId, itemId) } }
                }.awaitAll()
            }
            return null
        }
        return results.mapNotNull { it }.associate { it.first to it.second.second }
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
        val ptr = drafts.filter { it.isPointer }.maxByOrNull { it.gen ?: 0L }
        val dPtr = drafts.filter { it.isDelta && it.chunkIndex == -1 }.maxByOrNull { it.gen ?: 0L }
        val freshest = listOfNotNull(ptr, dPtr).maxByOrNull { it.updatedAt }
        if (freshest != null && System.currentTimeMillis() / 1000L - freshest.updatedAt < 45L) {
            log("[push] cleanup omitido: puntero reciente (<45s), esperando consistencia")
            return
        }
        val keep = if (removeAll) {
            emptySet<String>()
        } else {
            val keepIds = mutableSetOf<String>()
            if (ptr != null && ptr.gen != null) {
                val g = ptr.gen!!
                drafts.filter { it.isPointer || (!it.isDelta && it.gen == g) }
                    .forEach { keepIds.add(it.itemId) }
            }
            if (dPtr != null && dPtr.gen != null) {
                val dg = dPtr.gen!!
                drafts.filter { (it.isDelta && it.chunkIndex == -1) || (it.isDelta && it.gen == dg) }
                    .forEach { keepIds.add(it.itemId) }
            }
            if (keepIds.isEmpty()) {
                val anchor = drafts.filter { !it.isPointer && !it.isDelta && it.chunkIndex == 0 }
                    .maxByOrNull { it.updatedAt }
                if (anchor != null) {
                    val anchorGen = anchor.gen
                    if (anchorGen != null) {
                        drafts.filter { it.gen == anchorGen }.forEach { keepIds.add(it.itemId) }
                    } else {
                        drafts.filter { kotlin.math.abs(anchor.updatedAt - it.updatedAt) <= 120L }
                            .forEach { keepIds.add(it.itemId) }
                    }
                }
            }
            keepIds
        }
        var stale = drafts.filter { it.itemId !in keep }
        val maxCommitted = listOfNotNull(ptr?.gen, dPtr?.gen).maxOrNull()
        if (maxCommitted != null) {
            val future = stale.filter { (it.gen ?: 0L) > maxCommitted }
            if (future.isNotEmpty()) {
                log("[push] cleanup: se omiten ${future.size} draft(s) de gens futuras")
                stale = stale.filterNot { (it.gen ?: 0L) > maxCommitted }
            }
        }
        if (stale.isEmpty()) return
        log("[push] cleanup: ${stale.size} stale drafts de $deviceId")
        coroutineScope {
            val sem = Semaphore(5)
            stale.map { draft ->
                async { sem.withPermit { deleteDraft(token, projectId, draft.itemId) } }
            }.awaitAll()
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