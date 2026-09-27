package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.DocumentDto
import network.lapis.cloud.shared.domain.DocumentFolderDto
import network.lapis.cloud.shared.domain.DocumentVersionDto
import network.lapis.cloud.shared.domain.FolderAccessLevelChangeDto

/**
 * Metadata-only RPC surface. File bytes travel over dedicated Ktor HTTP routes
 * (`POST /api/documents/{id}/versions`, `GET /api/documents/{id}/download`) — see
 * `network.lapis.cloud.server.routes.DocumentRoutes` — not through Kilua RPC, which is
 * inefficient for large byte arrays. Access-level filtering happens server-side on every one of
 * these methods (and again on the HTTP download route) — never only in the UI.
 *
 * Welle V1.9.1 "Zugriffsrechte für Dokumente und Ordner sichtbar und editierbar": folders now carry
 * their own [DocumentAccessLevel] ([DocumentFolderDto.accessLevel]), and the invariant "a document
 * is never more visible than the folder it lives in" holds everywhere -- [createFolder]/
 * [createDocument] enforce it on write, [setFolderAccessLevel] materializes a tightening into every
 * document of the affected subtree (a loosening never cascades). See
 * `docs/architecture/document-access.adoc` for the full model.
 */
@RpcService
interface IDocumentService {
    /** Filtered server-side: only folders whose EFFECTIVE level (own level, or a stricter ancestor's) the caller may read. */
    suspend fun listFolders(): List<DocumentFolderDto>

    /**
     * Role: BOARD/TREASURER/ADMIN. [accessLevel] must be readable by the caller AND at least as
     * restrictive as the effective level of [parentFolderId] (`ConflictException` otherwise). A
     * [parentFolderId] the caller may not read is reported as `NotFoundException`, never `Forbidden`
     * (existence of an invisible parent must not leak).
     */
    suspend fun createFolder(
        name: String,
        parentFolderId: String? = null,
        accessLevel: DocumentAccessLevel = DocumentAccessLevel.PUBLIC_MEMBERS,
    ): DocumentFolderDto

    /**
     * Filtered server-side to documents the caller's role may see. A [folderId] the caller may not
     * read is reported as `NotFoundException`, never `Forbidden`.
     */
    suspend fun listDocuments(folderId: String? = null): List<DocumentDto>

    /**
     * Creates the [DocumentDto] shell; the first version is added via the HTTP upload route.
     * [accessLevel] must be at least as restrictive as the folder's effective level
     * (`ConflictException` otherwise).
     */
    suspend fun createDocument(
        folderId: String,
        title: String,
        accessLevel: DocumentAccessLevel,
    ): DocumentDto

    suspend fun listVersions(documentId: String): List<DocumentVersionDto>

    /** Soft-delete: sets `isDeleted = true`, keeps versions for audit. Role: Board/Treasurer/Admin. */
    suspend fun deleteDocument(documentId: String)

    /**
     * Welle V1.9.1. Role: BOARD/TREASURER/ADMIN, and the caller must be able to read both the OLD
     * and the NEW level (`ForbiddenException` otherwise -- nobody may set a level they could not
     * themselves read afterwards, nor change a document they cannot currently read). Rejects
     * (`ConflictException`) a level less restrictive than the folder's effective level -- the
     * document side never stutzt automatically, only the folder side does (see
     * [setFolderAccessLevel]).
     */
    suspend fun setDocumentAccessLevel(
        documentId: String,
        accessLevel: DocumentAccessLevel,
    ): DocumentDto

    /**
     * Welle V1.9.1. Role: BOARD/TREASURER/ADMIN. A tightening (more restrictive than the folder's
     * current own level) cascades into every document of the folder's whole subtree that is
     * currently less restrictive than its own folder's effective level, clamping it up -- never
     * down. A loosening changes only the folder's own level and NEVER cascades. Rejects
     * (`ConflictException`) a level less restrictive than the effective level of the PARENT folder.
     */
    suspend fun setFolderAccessLevel(
        folderId: String,
        accessLevel: DocumentAccessLevel,
    ): FolderAccessLevelChangeDto
}
