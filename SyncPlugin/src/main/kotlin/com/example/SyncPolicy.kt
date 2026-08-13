package com.example

import kotlin.math.absoluteValue

object SyncTime {
    private const val MILLIS_THRESHOLD = 100_000_000_000L

    fun toEpochSeconds(timestamp: Long): Long =
        if (timestamp.absoluteValue >= MILLIS_THRESHOLD) timestamp / 1_000L else timestamp

    fun nowEpochSeconds(nowMillis: Long = System.currentTimeMillis()): Long =
        toEpochSeconds(nowMillis)

    fun shouldRestore(cloudTimestamp: Long, localTimestamp: Long): Boolean =
        (cloudTimestamp == 0L && localTimestamp == 0L) ||
            toEpochSeconds(cloudTimestamp) > toEpochSeconds(localTimestamp)
}

object SyncKeyPath {
    fun itemTimestamp(
        key: String,
        category: SyncCategory,
        stringMap: Map<String, String>?,
    ): Long {
        if (stringMap == null) return 0L
        extractTimestamp(stringMap[key]).takeIf { it > 0L }?.let { return SyncTime.toEpochSeconds(it) }
        for (relatedKey in relatedTimestampKeys(key, category)) {
            extractTimestamp(stringMap[relatedKey]).takeIf { it > 0L }?.let {
                return SyncTime.toEpochSeconds(it)
            }
        }
        return 0L
    }

    fun relatedTimestampKeys(key: String, category: SyncCategory): List<String> {
        val parts = key.split('/')
        if (parts.size < 2) return emptyList()
        val id = parts.last().toIntOrNull() ?: return emptyList()
        val type = parts[parts.lastIndex - 1].lowercase()
        val prefix = parts.dropLast(2)

        fun sibling(name: String): String = (prefix + name + id.toString()).joinToString("/")

        return when (category) {
            SyncCategory.BOOKMARKS -> when (type) {
                "result_watch_state", "result_watch_state_data" ->
                    listOf(sibling("result_watch_state_data"))
                else -> emptyList()
            }
            SyncCategory.RESUME_WATCHING -> when (type) {
                "result_resume_watching", "result_resume_watching_2",
                "result_season", "result_dub", "result_episode" ->
                    listOf(sibling("result_resume_watching_2"))
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    fun extractTimestamp(json: String?): Long {
        if (json == null) return 0L
        return try {
            "\"updateTime\":\\s*(\\d+)".toRegex().find(json)?.groupValues?.get(1)?.toLong()
                ?: "\"latestUpdatedTime\":\\s*(\\d+)".toRegex()
                    .find(json)?.groupValues?.get(1)?.toLong()
                ?: "\"searchedAt\":\\s*(\\d+)".toRegex().find(json)?.groupValues?.get(1)?.toLong()
                ?: 0L
        } catch (_: Exception) {
            0L
        }
    }
}