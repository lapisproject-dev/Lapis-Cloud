package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.DocumentFolderTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.DocumentVersionTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.ESCALATED_ROLES
import network.lapis.cloud.server.security.canAccessDocumentAtLevel
import network.lapis.cloud.server.security.resolveCurrentMember
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
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Metadata-only. File bytes are handled by
 * [network.lapis.cloud.server.routes.registerDocumentRoutes] over plain Ktor HTTP, not here —
 * see [IDocumentService] KDoc for why. Access-level filtering is applied on every read here,
 * mirrored again on the HTTP download route (never only in the UI).
 *
 * **Welle "Treasurer Document Upload"**: the three write gates ([createFolder], [createDocument],
 * [deleteDocument]) use [ESCALATED_ROLES] (BOARD/TREASURER/ADMIN), not the narrower BOARD/ADMIN
 * pair — TREASURER manages the document area (Belege, Jahresabschlüsse etc.) as part of the
 * role's normal duties, same as [network.lapis.cloud.server.routes.registerDocumentRoutes]'
 * upload route and [network.lapis.cloud.server.security.canAccessDocumentAtLevel]'s BOARD_ONLY
 * branch, which must stay in lockstep with these three gates — otherwise a TREASURER could
 * create/delete a document but not open the very BOARD_ONLY folder it lives in.
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
     *
     * **[DocumentFolderDto.documentCount]** (added alongside the download-counter wave) DOES
     * respect the caller's [DocumentAccessLevel] visibility, unlike the folder listing itself: the
     * count is computed with the exact same predicates [listDocuments] uses (`isDeleted eq false`
     * plus the caller's `canAccessDocumentAtLevel`-derived allowed levels), so a folder never
     * reports a count that includes documents the caller could not actually open — that would be
     * an information leak through a number, not just through the accessLevel-gated listing.
     */
    override suspend fun listFolders(): List<DocumentFolderDto> {
        val current = resolveCurrentMember(call)
        return transaction {
            val allowedLevels = DocumentAccessLevel.entries.filter { current.canAccessDocumentAtLevel(it) }
            val documentCountColumn = DocumentTable.id.count()
            val countByFolderId: Map<Uuid, Long> =
                DocumentTable
                    .select(DocumentTable.folderId, documentCountColumn)
                    .where { (DocumentTable.isDeleted eq false) and (DocumentTable.accessLevel inList allowedLevels) }
                    .groupBy(DocumentTable.folderId)
                    .associate { it[DocumentTable.folderId] to it[documentCountColumn] }
            DocumentFolderTable.selectAll().map { it.toDocumentFolderDto(countByFolderId) }
        }
    }

    override suspend fun createFolder(
        name: String,
        parentFolderId: String?,
    ): DocumentFolderDto {
        val current = resolveCurrentMember(call)
        if (current.role !in ESCALATED_ROLES) throw ForbiddenException()
        return transaction {
            val id = Uuid.random()
            DocumentFolderTable.insert {
                it[DocumentFolderTable.id] = id
                it[DocumentFolderTable.name] = name
                it[DocumentFolderTable.parentFolderId] = parentFolderId?.let(Uuid::parse)
            }
            // Freshly created folder -- documentCount is always 0 by construction, no query needed.
            DocumentFolderTable
                .selectAll()
                .where { DocumentFolderTable.id eq id }
                .single()
                .toDocumentFolderDto(emptyMap())
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

    /**
     * Review finding fix (Welle "Treasurer Document Upload", Runde 4): this previously checked
     * ONLY [ESCALATED_ROLES] role membership and accepted the caller-supplied [accessLevel]
     * unchecked -- unlike [listVersions], [deleteDocument] and the upload/download routes, all of
     * which additionally call [canAccessDocumentAtLevel]. A TREASURER or BOARD member could
     * therefore create a document at `ADMIN_ONLY`, which [listDocuments] then filters out of their
     * own view, the upload route (403) can never fill, and [deleteDocument] (403) can never clean
     * up again -- a permanently orphaned, empty document row only an ADMIN can fix, created via a
     * client that offers all [DocumentAccessLevel] entries regardless of role (`DocumentsScreen.kt`).
     * Now mirrors [deleteDocument]'s shape exactly: ForbiddenException when the caller's
     * role/access-level combination cannot itself read the requested level.
     */
    override suspend fun createDocument(
        folderId: String,
        title: String,
        accessLevel: DocumentAccessLevel,
    ): DocumentDto {
        val current = resolveCurrentMember(call)
        if (current.role !in ESCALATED_ROLES) throw ForbiddenException()
        if (!current.canAccessDocumentAtLevel(accessLevel)) {
            throw ForbiddenException("Not authorized to create a document at this access level")
        }
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

    /**
     * Review finding fix (Welle "Treasurer Document Upload"): this previously checked ONLY
     * [ESCALATED_ROLES] role membership and never the document's own [DocumentAccessLevel] --
     * unlike [listVersions] and the download route, both of which additionally call
     * [canAccessDocumentAtLevel]. A TREASURER (role-only ESCALATED_ROLES member) could therefore
     * soft-delete an `ADMIN_ONLY` document -- e.g. the archived Serienbrief PDFs
     * [network.lapis.cloud.server.routes.registerMailmergeRoutes] files away as `ADMIN_ONLY`
     * (Beitragsrechnungen, Spendenbescheinigungen) -- despite never being able to read it. Now
     * mirrors [listVersions]'s row-lookup + access-level shape exactly: NotFoundException for a
     * missing or already-deleted row (was previously a silent no-op 200 on
     * [DocumentTable.update]), ForbiddenException when the caller's role/access-level combination
     * cannot read this document's level.
     */
    override suspend fun deleteDocument(documentId: String) {
        val current = resolveCurrentMember(call)
        if (current.role !in ESCALATED_ROLES) throw ForbiddenException()
        val docId = Uuid.parse(documentId)
        transaction {
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
                throw ForbiddenException("Not authorized to delete this document")
            }
            DocumentTable.update({ DocumentTable.id eq docId }) {
                it[isDeleted] = true
            }
        }
    }
}

private fun ResultRow.toDocumentFolderDto(countByFolderId: Map<Uuid, Long>): DocumentFolderDto =
    DocumentFolderDto(
        id = this[DocumentFolderTable.id].toString(),
        name = this[DocumentFolderTable.name],
        parentFolderId = this[DocumentFolderTable.parentFolderId]?.toString(),
        documentCount = (countByFolderId[this[DocumentFolderTable.id]] ?: 0L).toInt(),
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
        downloadCount = this[DocumentVersionTable.downloadCount],
    )
