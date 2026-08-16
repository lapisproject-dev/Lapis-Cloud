package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.DocumentFolderTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.DocumentVersionTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.canAccessDocumentAtLevel
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.DocumentDto
import network.lapis.cloud.shared.domain.DocumentFolderDto
import network.lapis.cloud.shared.domain.DocumentVersionDto
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IDocumentService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Metadata-only. File bytes are handled by
 * [network.lapis.cloud.server.routes.registerDocumentRoutes] over plain Ktor HTTP, not here —
 * see [IDocumentService] KDoc for why. Access-level filtering is applied on every read here,
 * mirrored again on the HTTP download route (never only in the UI).
 */
class DocumentService(
    private val call: ApplicationCall,
) : IDocumentService {
    /**
     * **V0.11.0 security fix (pre-existing, found while auditing the FRIEND-wave blast radius)**:
     * this method previously did not call [resolveCurrentMember] AT ALL -- the full folder tree
     * (names only) was readable with no session, by anyone on the internet. Now requires at least
     * an authenticated caller. Deliberately does NOT additionally filter by
     * [network.lapis.cloud.server.security.canAccessDocumentAtLevel]: [DocumentFolderTable] carries
     * no [DocumentAccessLevel] of its own (only individual [DocumentTable] rows do -- a folder is a
     * navigational container, not itself access-controlled content), so per-level folder filtering
     * would need a schema change out of scope for this wave. The actual content-access boundary
     * remains fully intact at [listDocuments]/[createDocument]/the document download route, which
     * already gate on [DocumentAccessLevel] per document. A [network.lapis.cloud.shared.domain
     * .MemberStatus.FRIEND] can therefore see folder NAMES (like any other authenticated caller
     * could before this fix) but not folder CONTENTS.
     */
    override suspend fun listFolders(): List<DocumentFolderDto> {
        resolveCurrentMember(call)
        return transaction {
            DocumentFolderTable.selectAll().map { it.toDocumentFolderDto() }
        }
    }

    override suspend fun createFolder(
        name: String,
        parentFolderId: String?,
    ): DocumentFolderDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.BOARD, AccountRole.ADMIN)
        return transaction {
            val id = Uuid.random()
            DocumentFolderTable.insert {
                it[DocumentFolderTable.id] = id
                it[DocumentFolderTable.name] = name
                it[DocumentFolderTable.parentFolderId] = parentFolderId?.let(Uuid::parse)
            }
            DocumentFolderTable
                .selectAll()
                .where { DocumentFolderTable.id eq id }
                .single()
                .toDocumentFolderDto()
        }
    }

    override suspend fun listDocuments(folderId: String?): List<DocumentDto> {
        val current = resolveCurrentMember(call)
        return transaction {
            val conditions = mutableListOf<Op<Boolean>>(DocumentTable.isDeleted eq false)
            if (folderId != null) conditions += (DocumentTable.folderId eq Uuid.parse(folderId))
            val allowedLevels = DocumentAccessLevel.entries.filter { current.canAccessDocumentAtLevel(it) }
            conditions += (DocumentTable.accessLevel inList allowedLevels)
            (DocumentTable innerJoin MemberTable)
                .selectAll()
                .where { conditions.reduce { a, b -> a and b } }
                .map { it.toDocumentDto() }
        }
    }

    override suspend fun createDocument(
        folderId: String,
        title: String,
        accessLevel: DocumentAccessLevel,
    ): DocumentDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.BOARD, AccountRole.ADMIN)
        val now = DbClock.nowLocalDateTime()
        return transaction {
            val id = Uuid.random()
            DocumentTable.insert {
                it[DocumentTable.id] = id
                it[DocumentTable.folderId] = Uuid.parse(folderId)
                it[DocumentTable.title] = title
                it[createdBy] = current.memberId
                it[createdAt] = now
                it[DocumentTable.accessLevel] = accessLevel
                it[isDeleted] = false
            }
            (DocumentTable innerJoin MemberTable)
                .selectAll()
                .where { DocumentTable.id eq id }
                .single()
                .toDocumentDto()
        }
    }

    override suspend fun listVersions(documentId: String): List<DocumentVersionDto> {
        val current = resolveCurrentMember(call)
        val docId = Uuid.parse(documentId)
        return transaction {
            val documentRow =
                DocumentTable
                    .selectAll()
                    .where { DocumentTable.id eq docId }
                    .singleOrNull()
                    ?: throw NotFoundException("Document $documentId not found")
            if (documentRow[DocumentTable.isDeleted]) {
                throw NotFoundException("Document $documentId not found")
            }
            if (!current.canAccessDocumentAtLevel(documentRow[DocumentTable.accessLevel])) {
                throw ForbiddenException("Not authorized to view versions of this document")
            }
            (DocumentVersionTable innerJoin MemberTable)
                .selectAll()
                .where { DocumentVersionTable.documentId eq docId }
                .map { it.toDocumentVersionDto() }
        }
    }

    override suspend fun deleteDocument(documentId: String) {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.BOARD, AccountRole.ADMIN)
        transaction {
            DocumentTable.update({ DocumentTable.id eq Uuid.parse(documentId) }) {
                it[isDeleted] = true
            }
        }
    }
}

private fun ResultRow.toDocumentFolderDto(): DocumentFolderDto =
    DocumentFolderDto(
        id = this[DocumentFolderTable.id].toString(),
        name = this[DocumentFolderTable.name],
        parentFolderId = this[DocumentFolderTable.parentFolderId]?.toString(),
    )

private fun ResultRow.toDocumentDto(): DocumentDto =
    DocumentDto(
        id = this[DocumentTable.id].toString(),
        folderId = this[DocumentTable.folderId].toString(),
        title = this[DocumentTable.title],
        currentVersionId = this[DocumentTable.currentVersionId]?.toString(),
        createdBy = this[DocumentTable.createdBy].toString(),
        createdByDisplayName = this[MemberTable.displayName],
        createdAt = this[DocumentTable.createdAt],
        accessLevel = this[DocumentTable.accessLevel],
        isDeleted = this[DocumentTable.isDeleted],
    )

private fun ResultRow.toDocumentVersionDto(): DocumentVersionDto =
    DocumentVersionDto(
        id = this[DocumentVersionTable.id].toString(),
        documentId = this[DocumentVersionTable.documentId].toString(),
        versionNumber = this[DocumentVersionTable.versionNumber],
        fileName = this[DocumentVersionTable.fileName],
        mimeType = this[DocumentVersionTable.mimeType],
        fileSizeBytes = this[DocumentVersionTable.fileSizeBytes],
        checksumSha256 = this[DocumentVersionTable.checksumSha256],
        uploadedBy = this[DocumentVersionTable.uploadedBy].toString(),
        uploadedByDisplayName = this[MemberTable.displayName],
        uploadedAt = this[DocumentVersionTable.uploadedAt],
        changeNote = this[DocumentVersionTable.changeNote],
    )
