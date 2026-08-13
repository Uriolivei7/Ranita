package com.example

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class SyncCategory(val key: String) {
    EXTENSIONS("extensions"),
    SETTINGS("settings"),
    BOOKMARKS("bookmarks"),
    RESUME_WATCHING("resume_watching"),
    SEARCH_HISTORY("search_history")
}

@Serializable
data class BackupVars(
    val bool: Map<String, Boolean>? = null,
    val int: Map<String, Int>? = null,
    val string: Map<String, String>? = null,
    val float: Map<String, Float>? = null,
    val long: Map<String, Long>? = null,
    val stringSet: Map<String, Set<String>?>? = null
)

@Serializable
data class BackupFile(
    val datastore: BackupVars = BackupVars(),
    val settings: BackupVars = BackupVars()
)

@Serializable
data class GitHubGraphQLResponse(
    val data: GitHubGraphQLData? = null,
    val errors: List<GitHubGraphQLError>? = null
)

@Serializable
data class GitHubGraphQLError(
    val message: String? = null
)

@Serializable
data class GitHubGraphQLData(
    val viewer: GitHubViewer? = null,
    @SerialName("addProjectV2DraftIssue") val addDraft: AddDraftResult? = null,
    @SerialName("updateProjectV2DraftIssue") val updateDraft: UpdateDraftResult? = null
)

@Serializable
data class GitHubViewer(
    val projectV2: GitHubProject? = null
)

@Serializable
data class GitHubProject(
    val id: String? = null,
    val items: GitHubItems? = null
)

@Serializable
data class GitHubItems(
    val nodes: List<GitHubNode>? = null
)

@Serializable
data class GitHubNode(
    val id: String? = null,
    val content: GitHubContent? = null
)

@Serializable
data class GitHubContent(
    val id: String? = null,
    val title: String? = null,
    val bodyText: String? = null,
    val updatedAt: String? = null,
    @SerialName("__typename") val typeName: String? = null
)

@Serializable
data class AddDraftResult(
    val projectItem: GitHubProjectItem? = null
)

@Serializable
data class GitHubProjectItem(
    val id: String? = null,
    val content: GitHubContent? = null
)

@Serializable
data class UpdateDraftResult(
    val draftIssue: GitHubContent? = null
)

data class SyncDevice(
    val name: String,
    val deviceId: String,
    val itemId: String,
    val updatedAt: Long,
    val syncedData: String? = null,
    val itemContentId: String? = null,
)