package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.DocumentDto
import network.lapis.cloud.shared.domain.DocumentFolderDto
import network.lapis.cloud.shared.domain.DocumentVersionDto

/**
 * Metadata-only RPC surface. File bytes travel over dedicated Ktor HTTP routes
 * (`POST /api/documents/{id}/versions`, `GET /api/documents/{id}/download`) — see
 * `network.lapis.cloud.server.routes.DocumentRoutes` — not through Kilua RPC, which is
 * inefficient for large byte arrays. Access-level filtering happens server-side on every one of
 * these methods (and again on the HTTP download route) — never only in the UI.
 */
@RpcService
interface IDocumentService {
    suspend fun listFolders(): List<DocumentFolderDto>

    /** Role: Board/Admin. */
    suspend fun createFolder(
        name: String,
        parentFolderId: String? = null,
    ): DocumentFolderDto

    /** Filtered server-side to documents the caller's role may see. */
    suspend fun listDocuments(folderId: String? = null): List<DocumentDto>

    /** Creates the [DocumentDto] shell; the first version is added via the HTTP upload route. */
    suspend fun createDocument(
        folderId: String,
        title: String,
        accessLevel: DocumentAccessLevel,
    ): DocumentDto

    suspend fun listVersions(documentId: String): List<DocumentVersionDto>

    /** Soft-delete: sets `isDeleted = true`, keeps versions for audit. Role: Board/Admin. */
    suspend fun deleteDocument(documentId: String)
}
