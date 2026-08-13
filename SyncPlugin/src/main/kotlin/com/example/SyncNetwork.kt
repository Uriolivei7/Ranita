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
        return graphql(token, query)?.data?.viewer?.projectV2?.id
    }

    suspend fun fetchDevices(token: String, projectNum: Int): List<SyncDevice> {
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
        val nodes = resp?.data?.viewer?.projectV2?.items?.nodes ?: return emptyList()
        return nodes.mapNotNull { node ->
            val content = node.content ?: return@mapNotNull null
            val body = content.bodyText ?: ""
            SyncDevice(
                name = content.title ?: "",
                deviceId = content.title ?: "",
                itemId = node.id ?: "",
                updatedAt = parseIsoTime(content.updatedAt),
                syncedData = if (body.isNotBlank()) decompressData(body) else null,
                itemContentId = content.id,
            )
        }.filter { it.itemId.isNotEmpty() }
    }

    suspend fun registerDevice(token: String, projectId: String, deviceName: String, data: String): Pair<String?, String?> {
        val compressed = compressData(data)
        val query = """
            mutation { addProjectV2DraftIssue(input: {
                projectId: "$projectId"
                title: "$deviceName"
                body: "$compressed"
            }) { projectItem { id content { ... on DraftIssue { id } } } } }
        """.trimIndent()
        val resp = graphql(token, query)
        val itemId = resp?.data?.addDraft?.projectItem?.id
        val contentId = resp?.data?.addDraft?.projectItem?.content?.id
        if (itemId == null) err("register failed: ${resp?.errors?.joinToString() { it.message ?: "" }}")
        return itemId to contentId
    }

    suspend fun updateDevice(token: String, contentId: String, deviceName: String, data: String): Boolean {
        val compressed = compressData(data)
        val query = """
            mutation { updateProjectV2DraftIssue(input: {
                draftIssueId: "$contentId"
                title: "$deviceName"
                body: "$compressed"
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
        android.util.Log.e(TAG, msg)
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