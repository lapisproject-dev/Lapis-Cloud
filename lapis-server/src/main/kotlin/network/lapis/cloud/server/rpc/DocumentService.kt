package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.ai.kb.KnowledgeReleaseStore
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.DocumentFolderTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.DocumentVersionTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.documents.FolderAccessLevels
import network.lapis.cloud.server.security.ESCALATED_ROLES
import network.lapis.cloud.server.security.canAccessDocumentAtLevel
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.DocumentAccessLevelSnapshot
import network.lapis.cloud.shared.domain.DocumentDto
import network.lapis.cloud.shared.domain.DocumentFolderAccessLevelSnapshot
import network.lapis.cloud.shared.domain.DocumentFolderDto
import network.lapis.cloud.shared.domain.DocumentVersionDto
import network.lapis.cloud.shared.domain.FolderAccessLevelChangeDto
import network.lapis.cloud.shared.domain.restrictiveness
import network.lapis.cloud.shared.rpc.ConflictException
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

private val logger = KotlinLogging.logger {}

/**
 * How many `document_folder` ids [DocumentService.setFolderAccessLevel]'s cascade puts into one
 * `folder_id IN (...)` query (polish round, P3). PostgreSQL's wire protocol caps a statement at
 * **65535 bind parameters**, so an unchunked `inList` over a very wide subtree would not merely be
 * slow -- it would make the whole tightening fail, which is precisely the outcome the deliberate
 * decision NOT to cap a tighten by size exists to avoid. 1000 keeps every statement three orders of
 * magnitude below the limit while staying one round trip for any realistic folder tree.
 */
private const val FOLDER_ID_CHUNK_SIZE = 1_000

/**
 * How often [DocumentService.setFolderAccessLevel] re-runs its whole transaction after
 * [FolderAccessLevels.SubtreeGrewUnderLockException] (polish round, P1). Each retry can only lose to
 * a `createFolder` that commits inside the window between the pre-lock read and the lock acquisition,
 * so three consecutive losses are already implausible; the point of the cap is that a pathological
 * insert loop cannot turn one RPC call into an unbounded one.
 */
private const val MAX_TIGHTEN_ATTEMPTS = 3

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
 *
 * **Welle V1.9.1 "Zugriffsrechte für Dokumente und Ordner sichtbar und editierbar"**: folders now
 * carry their own [DocumentAccessLevel] ([DocumentFolderDto.accessLevel]), and the invariant "a
 * document is never more visible than the folder it lives in" is enforced everywhere a document or
 * folder level is written ([createFolder], [createDocument], [setDocumentAccessLevel],
 * [setFolderAccessLevel] — and, outside this class, the archiving pipeline, see
 * `network.lapis.cloud.server.routes.DocumentArchiving` KDoc). The wirksame (effective) level of a
 * folder is the most restrictive level along its own ancestor chain
 * ([network.lapis.cloud.server.documents.FolderAccessLevels]); an invisible folder is reported as
 * [NotFoundException], never [ForbiddenException] (a 403 would confirm its existence). See
 * `docs/architecture/document-access.adoc` for the full model.
 *
 * **Fix round**: a folder tighten now materializes into descendant FOLDERS' own levels too, not only
 * into documents ([setFolderAccessLevel], B5), and every write path here takes a `FOR UPDATE` row
 * lock on the folder's ancestor chain before it reads a folder level (B2) — the invariant is
 * enforced in application code, not by a database constraint, so without those locks a concurrent
 * tighten and insert could interleave into a permanently too-visible document. The uniform lock
 * order across this whole file is `document_folder` rows in ascending UUID order, then `document`
 * rows, then [AuditLogRecorder]'s global chain-state row (which must always be last) — see
 * [FolderAccessLevels]' own locking contract.
 */
class DocumentService(
    private val call: ApplicationCall,
) : IDocumentService {
    /**
     * **V0.11.0 security fix (pre-existing, found while auditing the FRIEND-wave blast radius)**:
     * this method previously did not call [resolveCurrentMember] AT ALL -- the full folder tree
     * (names only) was readable with no session, by anyone on the internet. Now requires at least
     * an authenticated caller.
     *
     * **Welle V1.9.1**: now ALSO filters by each folder's EFFECTIVE [DocumentAccessLevel]
     * (`FolderAccessLevels.Snapshot.effectiveLevelsForAll`, one pass over one read of the whole table
     * -- never one ancestor-climb query per folder). A sub-folder less restrictive than an ADMIN_ONLY/BOARD_ONLY
     * ancestor is unsichtbar even though its OWN level would otherwise qualify -- see
     * [FolderAccessLevels] KDoc for why (the hierarchy itself must not leak what the ancestor
     * hides). This client only ever creates top-level folders, but the schema (and this filter)
     * support nesting. **Behavior change from the V0.11.0 KDoc this method used to carry**: a
     * [network.lapis.cloud.shared.domain.MemberStatus.FRIEND]/GUEST caller is not in
     * [network.lapis.cloud.server.security.MemberStatusSets.ORGANIZATION_MEMBER], so
     * `canAccessDocumentAtLevel(PUBLIC_MEMBERS)` is now `false` for them -- they no longer see ANY
     * folder name, not even a `PUBLIC_MEMBERS` one (previously every authenticated caller, FRIEND/
     * GUEST included, saw every folder NAME regardless of level, just not its documents). A
     * strictly tighter, deliberate side effect of this wave, not a separate decision.
     *
     * **[DocumentFolderDto.documentCount]** DOES respect the caller's [DocumentAccessLevel]
     * visibility, unlike the folder listing itself before V1.9.1: the count is computed with the
     * exact same predicates [listDocuments] uses (`isDeleted eq false` plus the caller's
     * `canAccessDocumentAtLevel`-derived allowed levels), so a folder never reports a count that
     * includes documents the caller could not actually open. Since V1.9.1, this count is ALSO
     * ordner-lokal (not per-subtree) — the [FolderAccessLevels] invariant established by
     * [createDocument]/[createFolder]/[setDocumentAccessLevel]/[setFolderAccessLevel] guarantees
     * every document in a folder is at least as restrictive as that folder's own effective level,
     * so a caller who can see the folder at all learns nothing about a hidden document beyond "it
     * exists at a level I could also read" — no additional leak through the number.
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
            val effectiveLevels = FolderAccessLevels.snapshot().effectiveLevelsForAll()
            DocumentFolderTable
                .selectAll()
                .filter { row ->
                    val folderId = row[DocumentFolderTable.id]
                    // Fix round (W4): `getValue` here used to throw (500) when a folder row appeared
                    // between the effective-level pass and this listing query -- the two are separate
                    // reads and, under READ COMMITTED, a concurrent `createFolder` can commit in
                    // between. A missing entry is "I don't know", which must fail CLOSED (hide the
                    // folder) rather than fail the whole listing: an operator loses one row from one
                    // refresh, not the screen.
                    val effective = effectiveLevels[folderId]
                    if (effective == null) {
                        logger.warn {
                            "listFolders: no effective access level computed for folder $folderId " +
                                "(row committed concurrently?) -- omitting it from this listing"
                        }
                        false
                    } else {
                        current.canAccessDocumentAtLevel(effective)
                    }
                }.map { it.toDocumentFolderDto(countByFolderId) }
        }
    }

    /**
     * Welle V1.9.1: [accessLevel] must be readable by the caller AND at least as restrictive as the
     * effective level of [parentFolderId] (`ConflictException` otherwise -- a sub-folder cannot be
     * MORE visible than its parent, only equally or less). A [parentFolderId] the caller may not
     * currently read is reported as [NotFoundException], not [ForbiddenException].
     */
    override suspend fun createFolder(
        name: String,
        parentFolderId: String?,
        accessLevel: DocumentAccessLevel,
    ): DocumentFolderDto {
        val current = resolveCurrentMember(call)
        if (current.role !in ESCALATED_ROLES) throw ForbiddenException()
        if (!current.canAccessDocumentAtLevel(accessLevel)) {
            throw ForbiddenException("Not authorized to create a folder at this access level")
        }
        return transaction {
            val parentId = parentFolderId?.let(Uuid::parse)
            if (parentId != null) {
                // Fix round (B2): the parent's ancestor chain is row-locked BEFORE its level is read
                // -- otherwise a concurrent `setFolderAccessLevel` tighten on the parent and this
                // insert can interleave into a permanently too-visible sub-folder (the cascade only
                // runs on a level CHANGE, so nothing repairs it afterwards). See
                // `FolderAccessLevels`' locking contract.
                val snapshot = FolderAccessLevels.lockChainAndSnapshot(parentId)
                if (!snapshot.contains(parentId)) throw NotFoundException("Folder $parentFolderId not found")
                val parentEffective = snapshot.effectiveLevel(parentId)
                if (!current.canAccessDocumentAtLevel(parentEffective)) {
                    throw NotFoundException("Folder $parentFolderId not found")
                }
                if (accessLevel.restrictiveness < parentEffective.restrictiveness) {
                    throw ConflictException("A subfolder cannot be more visible than its parent folder")
                }
            }
            val id = Uuid.random()
            DocumentFolderTable.insert {
                it[DocumentFolderTable.id] = id
                it[DocumentFolderTable.name] = name
                it[DocumentFolderTable.parentFolderId] = parentId
                it[DocumentFolderTable.accessLevel] = accessLevel
            }
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.DOCUMENT_FOLDER,
                entityId = id,
                action = AuditAction.CREATE,
                before = null,
                after =
                    Json.encodeToString(
                        DocumentFolderAccessLevelSnapshot.serializer(),
                        DocumentFolderAccessLevelSnapshot(accessLevel = accessLevel, parentFolderId = parentFolderId),
                    ),
            )
            // Freshly created folder -- documentCount is always 0 by construction, no query needed.
            DocumentFolderTable
                .selectAll()
                .where { DocumentFolderTable.id eq id }
                .single()
                .toDocumentFolderDto(emptyMap())
        }
    }

    /**
     * Welle V1.9.1: a [folderId] the caller may not read (its EFFECTIVE level, not just its own)
     * is reported as [NotFoundException] -- a guessed folder UUID must never leak the
     * `PUBLIC_MEMBERS` documents of a folder the caller cannot otherwise see.
     */
    override suspend fun listDocuments(folderId: String?): List<DocumentDto> {
        val current = resolveCurrentMember(call)
        return transaction {
            val conditions = mutableListOf<Op<Boolean>>(DocumentTable.isDeleted eq false)
            if (folderId != null) {
                val fid = Uuid.parse(folderId)
                if (!current.canAccessDocumentAtLevel(FolderAccessLevels.effectiveLevel(fid))) {
                    throw NotFoundException("Folder $folderId not found")
                }
                conditions += (DocumentTable.folderId eq fid)
            }
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
     *
     * Welle V1.9.1: additionally rejects (`ConflictException`) an [accessLevel] less restrictive
     * than the folder's effective level -- the invariant "a document is never more visible than its
     * folder" must hold from the moment of creation, not only after a later `setFolderAccessLevel`
     * tighten. A [folderId] the caller may not read is reported as [NotFoundException].
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
            val fid = Uuid.parse(folderId)
            // Fix round (B2): row-lock the folder's ancestor chain BEFORE reading its level, so a
            // concurrent `setFolderAccessLevel` tighten cannot commit between the read below and
            // this insert -- that race produced a PUBLIC_MEMBERS document inside an ADMIN_ONLY
            // folder that nothing ever repaired (the cascade only runs on a level CHANGE). See
            // `FolderAccessLevels`' locking contract.
            val snapshot = FolderAccessLevels.lockChainAndSnapshot(fid)
            if (!snapshot.contains(fid)) throw NotFoundException("Folder $folderId not found")
            val folderEffective = snapshot.effectiveLevel(fid)
            if (!current.canAccessDocumentAtLevel(folderEffective)) {
                throw NotFoundException("Folder $folderId not found")
            }
            if (accessLevel.restrictiveness < folderEffective.restrictiveness) {
                throw ConflictException("A document cannot be more visible than the folder it is placed in")
            }
            val id = Uuid.random()
            DocumentTable.insert {
                it[DocumentTable.id] = id
                it[DocumentTable.folderId] = fid
                it[DocumentTable.title] = title
                it[createdBy] = current.memberId
                it[createdAt] = now
                it[DocumentTable.accessLevel] = accessLevel
                it[isDeleted] = false
            }
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.DOCUMENT,
                entityId = id,
                action = AuditAction.CREATE,
                before = null,
                after =
                    Json.encodeToString(
                        DocumentAccessLevelSnapshot.serializer(),
                        DocumentAccessLevelSnapshot(accessLevel = accessLevel, folderId = folderId),
                    ),
            )
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
     *
     * Welle V1.9.1: the soft-delete is recorded as `AuditAction.VOID` under `AuditEntityType
     * .DOCUMENT` -- see [AuditAction] KDoc for why this reuses `VOID` rather than adding a new
     * `DELETE` literal.
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
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.DOCUMENT,
                entityId = docId,
                action = AuditAction.VOID,
                before =
                    Json.encodeToString(
                        DocumentAccessLevelSnapshot.serializer(),
                        DocumentAccessLevelSnapshot(
                            accessLevel = documentRow[DocumentTable.accessLevel],
                            folderId = documentRow[DocumentTable.folderId].toString(),
                        ),
                    ),
                after = null,
            )
        }
        // V1.6.1: a soft-deleted document leaves the AI knowledge base (release + chunks + state).
        // Housekeeping only -- the retriever already filters is_deleted live -- so it never fails the delete.
        runCatching { KnowledgeReleaseStore.revoke(docId) }
    }

    /**
     * Welle V1.9.1. Symmetric checks to [deleteDocument]: the caller must be able to read both the
     * CURRENT and the NEW level, and [ESCALATED_ROLES] gates the write. Rejects
     * (`ConflictException`) an [accessLevel] less restrictive than the document's folder's
     * effective level -- unlike [setFolderAccessLevel]'s cascade, the document side never stutzt
     * itself automatically, a human decides.
     *
     * A no-op (`accessLevel` unchanged) writes NO audit entry -- a chain of non-changes would
     * dilute the log's value as a record of actual decisions.
     */
    override suspend fun setDocumentAccessLevel(
        documentId: String,
        accessLevel: DocumentAccessLevel,
    ): DocumentDto {
        val current = resolveCurrentMember(call)
        if (current.role !in ESCALATED_ROLES) throw ForbiddenException()
        if (!current.canAccessDocumentAtLevel(accessLevel)) {
            throw ForbiddenException("Not authorized to set this access level")
        }
        val docId = Uuid.parse(documentId)
        var tightened = false
        val dto =
            transaction {
                // Fix round (B2): the document's folder chain is row-locked BEFORE the folder's level
                // is read, and the document row is re-read afterwards -- otherwise a concurrent
                // `setFolderAccessLevel` tighten could commit between the folder read and this
                // update, letting a loosening slip past a check that was already stale. Lock order is
                // uniform across every write path: document_folder rows (ascending UUID) -> document
                // rows -> audit chain state, which is what keeps it deadlock-free. `folder_id` is
                // immutable (no move operation exists), so reading it from the unlocked row first is
                // sound.
                val folderId =
                    DocumentTable
                        .select(DocumentTable.folderId)
                        .where { DocumentTable.id eq docId }
                        .singleOrNull()
                        ?.get(DocumentTable.folderId)
                        ?: throw NotFoundException("Document $documentId not found")
                val snapshot = FolderAccessLevels.lockChainAndSnapshot(folderId)
                val documentRow =
                    DocumentTable
                        .selectAll()
                        .where { DocumentTable.id eq docId }
                        .singleOrNull()
                        ?: throw NotFoundException("Document $documentId not found")
                if (documentRow[DocumentTable.isDeleted]) {
                    throw NotFoundException("Document $documentId not found")
                }
                val before = documentRow[DocumentTable.accessLevel]
                if (!current.canAccessDocumentAtLevel(before)) {
                    throw ForbiddenException("Not authorized to change the access level of this document")
                }
                if (before != accessLevel) {
                    val folderEffective = snapshot.effectiveLevel(folderId)
                    if (accessLevel.restrictiveness < folderEffective.restrictiveness) {
                        throw ConflictException("A document cannot be more visible than the folder it is placed in")
                    }
                    DocumentTable.update({ DocumentTable.id eq docId }) {
                        it[DocumentTable.accessLevel] = accessLevel
                    }
                    AuditLogRecorder.record(
                        actorMemberId = current.memberId,
                        actorRole = current.role,
                        entityType = AuditEntityType.DOCUMENT,
                        entityId = docId,
                        action = AuditAction.UPDATE,
                        before =
                            Json.encodeToString(
                                DocumentAccessLevelSnapshot.serializer(),
                                DocumentAccessLevelSnapshot(accessLevel = before, folderId = folderId.toString()),
                            ),
                        after =
                            Json.encodeToString(
                                DocumentAccessLevelSnapshot.serializer(),
                                DocumentAccessLevelSnapshot(accessLevel = accessLevel, folderId = folderId.toString()),
                            ),
                    )
                    tightened = accessLevel.restrictiveness > before.restrictiveness
                }
                (DocumentTable innerJoin MemberTable)
                    .selectAll()
                    .where { DocumentTable.id eq docId }
                    .single()
                    .toDocumentDto()
            }
        // Welle V1.9.1 (K4): a tightening (never a loosening -- that would be a silent, unreviewed
        // re-exposure to the AI) revokes any existing knowledge-base release. Housekeeping only, same
        // "never fail the write itself" posture as `deleteDocument` above.
        if (tightened) runCatching { KnowledgeReleaseStore.revoke(docId) }
        return dto
    }

    /**
     * Welle V1.9.1. A **tightening** (more restrictive than the folder's own CURRENT level)
     * MATERIALIZES itself into the whole subtree, in the same write transaction:
     *
     * 1. every DESCENDANT FOLDER whose own level is less restrictive than its new effective level is
     *    clamped up to that effective level (fix round, B5), and
     * 2. every DOCUMENT in the subtree that is less restrictive than its own folder's new effective
     *    level is clamped up to it.
     *
     * A **loosening** changes only this folder's own level and never cascades -- the asymmetry is
     * deliberate: tightening is a safety decision that must reach everything it covers, loosening is
     * a re-exposure decision that a human must make per object. See
     * `docs/architecture/document-access.adoc` for the full "stutzen statt ablehnen" argument (four
     * of this codebase's five content gates -- the AI retriever, the MCP statute tool, the document
     * download route, the conference-recording media route -- read ONLY a document's/recording's own
     * `access_level`, none of them know about folders; materializing a folder tighten into every
     * affected row is what actually closes the leak, a mere rejection would not).
     *
     * **Why descendant FOLDERS are materialized too (fix round, B5)**: before this, a folder's own
     * level could stay LOOSER than the level its ancestors enforce, permanently (tighten the parent,
     * the child keeps `PUBLIC_MEMBERS`), and the client's "Sichtbarkeit" badge -- which necessarily
     * renders the own level, the only one on the wire -- then stated the opposite of the truth in the
     * very feature whose purpose is making the level visible. After the cascade every descendant's own
     * level is at least as restrictive as what its chain enforces; it may be STRICTER (a child
     * tightened on its own is never clamped back down), so the guarantee is "never looser", not "the
     * same level as the parent". `FolderAccessLevels.effectiveLevel` stays as the safety net for a row
     * that is not (yet) materialized; it is no longer the only thing standing between a misleading
     * badge and a leak.
     *
     * **Ordering (fix round, B3)**: every `UPDATE` of the cascade runs BEFORE the first
     * [AuditLogRecorder.record] call, and all audit entries are then appended in one closed block at
     * the end. [AuditLogRecorder.record] takes the application's ONE global `audit_log_chain_state`
     * row lock and documents that it must be the LAST lock-taking operation of its transaction; the
     * previous interleaving (`record` inside the per-document loop, more row locks after it) violated
     * that contract outright and could deadlock against a concurrent `setDocumentAccessLevel`
     * holding the document row and waiting for the chain lock. As a side effect the global audit lock
     * is now held for the audit-append block only, not for the whole cascade.
     *
     * **No size cap, deliberately**: a tightening is a security measure and must never be refused
     * because a folder happens to contain many documents. Splitting the audit appends into separate
     * transactions to shorten the global lock was considered and rejected -- it would break
     * `AuditLogRecorder`'s central guarantee that an audit row commits atomically with the mutation
     * it describes. See the CHANGELOG entry for the full trade-off. The one place a size limit is
     * unavoidable is the bind-parameter count of the cascade's own document query, which is why that
     * query is chunked rather than left to grow (polish round, P3 -- see [FOLDER_ID_CHUNK_SIZE]).
     *
     * **Transaction retry (polish round, P1)**: `lockSubtreeAndSnapshot` raises
     * [FolderAccessLevels.SubtreeGrewUnderLockException] when a concurrent `createFolder` slipped a
     * descendant in between its pre-lock read and the lock acquisition. That descendant is outside the
     * ordered lock set, so neither cascading into it (a lock acquired out of ascending UUID order --
     * a real deadlock window) nor skipping it (a document the racing writer inserted under the old,
     * looser level stays too visible) is acceptable. The whole transaction is therefore rolled back
     * and retried with the enlarged lock set, at most [MAX_TIGHTEN_ATTEMPTS] times; only an
     * implausible run of consecutive losses surfaces as a [ConflictException], which is the honest
     * answer ("try again") rather than the 500 a real deadlock used to produce. Holding this folder's
     * own row blocks every insert below it -- every path locks its target's whole ancestor chain --
     * so one agreeing re-read freezes the subtree for the rest of the transaction.
     *
     * **Operator note, a one-way street by design (polish round, P9)**: tightening a folder to
     * `ADMIN_ONLY` is not reversible by the BOARD/TREASURER who did it. The very next call fails at the
     * "caller can read this folder's effective level" check below with [NotFoundException] -- an
     * `ADMIN_ONLY` folder is invisible to anyone who is not ADMIN, and `listFolders` stops showing it as
     * well. Follows directly from "existence must not leak" and is not a bug, but it does surprise: only
     * an ADMIN can loosen such a folder again. Also in `docs/architecture/document-access.adoc`.
     *
     * **Known limitation, not addressed here**: no `lock_timeout`/`statement_timeout` is set anywhere
     * in this codebase (`DatabaseConfig` says so itself), so a transaction waiting on any of these
     * `FOR UPDATE` acquisitions occupies one of the ten pool connections for as long as the holder
     * runs. Pre-existing, widened by this wave's five new lock sites, scoped to its own future wave.
     */
    override suspend fun setFolderAccessLevel(
        folderId: String,
        accessLevel: DocumentAccessLevel,
    ): FolderAccessLevelChangeDto {
        val current = resolveCurrentMember(call)
        if (current.role !in ESCALATED_ROLES) throw ForbiddenException()
        if (!current.canAccessDocumentAtLevel(accessLevel)) {
            throw ForbiddenException("Not authorized to set this access level")
        }
        val fid = Uuid.parse(folderId)
        repeat(MAX_TIGHTEN_ATTEMPTS) { attempt ->
            var tightenedDocumentIds: List<Uuid> = emptyList()
            val result =
                try {
                    transaction {
                        // Fix round (B2): lock this folder's ancestor chain AND its whole subtree, ascending
                        // UUID order, BEFORE anything is read or decided -- this method both reads ancestors
                        // and writes descendants, and every other write path takes the same ascending-UUID
                        // discipline, which is what makes the scheme deadlock-free. See `FolderAccessLevels`'
                        // locking contract.
                        val snapshot = FolderAccessLevels.lockSubtreeAndSnapshot(fid)
                        val before =
                            snapshot.ownLevel(fid)
                                ?: throw NotFoundException("Folder $folderId not found")
                        if (!current.canAccessDocumentAtLevel(snapshot.effectiveLevel(fid))) {
                            throw NotFoundException("Folder $folderId not found")
                        }
                        val parentId = snapshot.parentFolderId(fid)
                        if (parentId != null) {
                            val parentEffective = snapshot.effectiveLevel(parentId)
                            if (accessLevel.restrictiveness < parentEffective.restrictiveness) {
                                throw ConflictException("A subfolder cannot be more visible than its parent folder")
                            }
                        }

                        var folderChanges: List<AccessLevelChange> = emptyList()
                        var documentChanges: List<AccessLevelChange> = emptyList()
                        if (before != accessLevel) {
                            DocumentFolderTable.update({ DocumentFolderTable.id eq fid }) {
                                it[DocumentFolderTable.accessLevel] = accessLevel
                            }

                            if (accessLevel.restrictiveness > before.restrictiveness) {
                                // The subtree as it will be AFTER this folder's own update -- computed from the
                                // in-memory snapshot rather than re-reading the table (fix round, W5: the
                                // public entry points used to re-read every `document_folder` row per question,
                                // three times per call here).
                                val subtreeEffective =
                                    snapshot
                                        .withOwnLevel(folderId = fid, level = accessLevel)
                                        .subtreeEffectiveLevels(fid)

                                folderChanges =
                                    subtreeEffective
                                        .filter { (descendantId, _) -> descendantId != fid }
                                        .mapNotNull { (descendantId, effective) ->
                                            val own = snapshot.ownLevel(descendantId) ?: return@mapNotNull null
                                            if (own.restrictiveness < effective.restrictiveness) {
                                                AccessLevelChange(id = descendantId, before = own, after = effective)
                                            } else {
                                                null
                                            }
                                        }.sortedBy { it.id.toString() } // stable order for a deterministic hash chain
                                for (change in folderChanges) {
                                    DocumentFolderTable.update({ DocumentFolderTable.id eq change.id }) {
                                        it[DocumentFolderTable.accessLevel] = change.after
                                    }
                                }

                                // Fix round (W3): soft-deleted documents are clamped too. They used to be
                                // filtered out, while both the adoc and the CHANGELOG claimed EVERY document
                                // was guaranteed at least as restrictive as its folder -- harmless only for as
                                // long as nothing ever undeletes a row, and there is no reason to leave a
                                // deleted row unclamped in the first place.
                                // Polish round (P3): chunked, because "no size cap" is a promise about
                                // the FEATURE, not a licence to build an unbounded statement --
                                // PostgreSQL refuses more than 65535 bind parameters, so a wide enough
                                // subtree would have failed the whole tighten, the exact outcome the
                                // deliberate absence of a cap exists to prevent. See FOLDER_ID_CHUNK_SIZE.
                                val affectedFolderIds = subtreeEffective.keys.toList()
                                documentChanges =
                                    affectedFolderIds
                                        .chunked(FOLDER_ID_CHUNK_SIZE)
                                        .flatMap { folderIdChunk ->
                                            DocumentTable
                                                .select(DocumentTable.id, DocumentTable.folderId, DocumentTable.accessLevel)
                                                .where { DocumentTable.folderId inList folderIdChunk }
                                                .mapNotNull { row ->
                                                    val ownFolderEffective = subtreeEffective.getValue(row[DocumentTable.folderId])
                                                    val docLevel = row[DocumentTable.accessLevel]
                                                    if (docLevel.restrictiveness < ownFolderEffective.restrictiveness) {
                                                        AccessLevelChange(
                                                            id = row[DocumentTable.id],
                                                            before = docLevel,
                                                            after = ownFolderEffective,
                                                            folderId = row[DocumentTable.folderId],
                                                        )
                                                    } else {
                                                        null
                                                    }
                                                }
                                        }.sortedBy { it.id.toString() } // stable order for a deterministic hash chain
                                for (change in documentChanges) {
                                    DocumentTable.update({ DocumentTable.id eq change.id }) {
                                        it[DocumentTable.accessLevel] = change.after
                                    }
                                }
                            }

                            // ── Every UPDATE above is done. Only now the audit block (fix round, B3). ──
                            for (change in folderChanges) {
                                AuditLogRecorder.record(
                                    actorMemberId = current.memberId,
                                    actorRole = current.role,
                                    entityType = AuditEntityType.DOCUMENT_FOLDER,
                                    entityId = change.id,
                                    action = AuditAction.UPDATE,
                                    before =
                                        Json.encodeToString(
                                            DocumentFolderAccessLevelSnapshot.serializer(),
                                            DocumentFolderAccessLevelSnapshot(
                                                accessLevel = change.before,
                                                parentFolderId = snapshot.parentFolderId(change.id)?.toString(),
                                            ),
                                        ),
                                    after =
                                        Json.encodeToString(
                                            DocumentFolderAccessLevelSnapshot.serializer(),
                                            DocumentFolderAccessLevelSnapshot(
                                                accessLevel = change.after,
                                                parentFolderId = snapshot.parentFolderId(change.id)?.toString(),
                                                cascadedFromFolderId = folderId,
                                            ),
                                        ),
                                )
                            }
                            for (change in documentChanges) {
                                AuditLogRecorder.record(
                                    actorMemberId = current.memberId,
                                    actorRole = current.role,
                                    entityType = AuditEntityType.DOCUMENT,
                                    entityId = change.id,
                                    action = AuditAction.UPDATE,
                                    before =
                                        Json.encodeToString(
                                            DocumentAccessLevelSnapshot.serializer(),
                                            DocumentAccessLevelSnapshot(
                                                accessLevel = change.before,
                                                folderId = change.folderId.toString(),
                                            ),
                                        ),
                                    after =
                                        Json.encodeToString(
                                            DocumentAccessLevelSnapshot.serializer(),
                                            DocumentAccessLevelSnapshot(
                                                accessLevel = change.after,
                                                folderId = change.folderId.toString(),
                                                cascadedFromFolderId = folderId,
                                            ),
                                        ),
                                )
                            }
                            AuditLogRecorder.record(
                                actorMemberId = current.memberId,
                                actorRole = current.role,
                                entityType = AuditEntityType.DOCUMENT_FOLDER,
                                entityId = fid,
                                action = AuditAction.UPDATE,
                                before =
                                    Json.encodeToString(
                                        DocumentFolderAccessLevelSnapshot.serializer(),
                                        DocumentFolderAccessLevelSnapshot(
                                            accessLevel = before,
                                            parentFolderId = parentId?.toString(),
                                        ),
                                    ),
                                after =
                                    Json.encodeToString(
                                        DocumentFolderAccessLevelSnapshot.serializer(),
                                        DocumentFolderAccessLevelSnapshot(
                                            accessLevel = accessLevel,
                                            parentFolderId = parentId?.toString(),
                                            tightenedDocuments = documentChanges.size,
                                            tightenedFolders = folderChanges.size,
                                        ),
                                    ),
                            )
                            tightenedDocumentIds = documentChanges.map { it.id }
                        }

                        val updatedFolder =
                            DocumentFolderTable
                                .selectAll()
                                .where { DocumentFolderTable.id eq fid }
                                .single()
                                .toDocumentFolderDto(emptyMap())
                        FolderAccessLevelChangeDto(
                            folder = updatedFolder,
                            tightenedDocuments = documentChanges.size,
                            tightenedFolders = folderChanges.size,
                        )
                    }
                } catch (growth: FolderAccessLevels.SubtreeGrewUnderLockException) {
                    // Polish round (P1): a concurrent createFolder committed a descendant outside our
                    // ordered lock set. Roll back, retry with the enlarged set -- see this method's KDoc.
                    logger.info {
                        "setFolderAccessLevel($folderId): attempt ${attempt + 1} of " +
                            "$MAX_TIGHTEN_ATTEMPTS rolled back -- the subtree gained " +
                            "${growth.unlockedDescendantIds.size} folder(s) under lock acquisition"
                    }
                    null
                }
            if (result != null) {
                // Welle V1.9.1 (K4): every cascaded document whose NEW level is no longer PUBLIC_MEMBERS
                // loses any existing knowledge-base release. Housekeeping only, outside the transaction,
                // never fails the write -- same posture as setDocumentAccessLevel/deleteDocument above.
                tightenedDocumentIds.forEach { docId ->
                    runCatching { KnowledgeReleaseStore.revoke(docId) }
                }
                return result
            }
        }
        // Every attempt lost the same race -- implausible in practice, and an honest "retry" beats
        // both a silent partial cascade and the 500 an actual deadlock used to produce.
        throw ConflictException(
            "Folder $folderId was modified concurrently -- the access level was not changed, please retry",
        )
    }
}

/**
 * One row's access-level change inside [DocumentService.setFolderAccessLevel]'s cascade -- collected
 * first, applied as `UPDATE`s, and only then written to the audit log (see that method's KDoc, fix
 * round B3). [folderId] is carried along for the `document` variant so the audit payload needs no
 * second per-row `SELECT`; it is unused for the `document_folder` variant.
 */
private data class AccessLevelChange(
    val id: Uuid,
    val before: DocumentAccessLevel,
    val after: DocumentAccessLevel,
    val folderId: Uuid = id,
)

private fun ResultRow.toDocumentFolderDto(countByFolderId: Map<Uuid, Long>): DocumentFolderDto =
    DocumentFolderDto(
        id = this[DocumentFolderTable.id].toString(),
        name = this[DocumentFolderTable.name],
        parentFolderId = this[DocumentFolderTable.parentFolderId]?.toString(),
        documentCount = (countByFolderId[this[DocumentFolderTable.id]] ?: 0L).toInt(),
        accessLevel = this[DocumentFolderTable.accessLevel],
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
