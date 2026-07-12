package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
enum class DocumentAccessLevel { PUBLIC_MEMBERS, BOARD_ONLY, ADMIN_ONLY }

@Serializable
data class DocumentFolderDto(
    val id: String,
    val name: String,
    val parentFolderId: String?,
)

@Serializable
data class DocumentDto(
    val id: String,
    val folderId: String,
    val title: String,
    val currentVersionId: String?,
    val createdBy: String,
    val createdByDisplayName: String,
    val createdAt: LocalDateTime,
    val accessLevel: DocumentAccessLevel,
    val isDeleted: Boolean,
)

@Serializable
data class DocumentVersionDto(
    val id: String,
    val documentId: String,
    val versionNumber: Int,
    val fileName: String,
    val mimeType: String,
    val fileSizeBytes: Long,
    val checksumSha256: String,
    val uploadedBy: String,
    val uploadedByDisplayName: String,
    val uploadedAt: LocalDateTime,
    val changeNote: String?,
)
