package network.lapis.cloud.server.routes

import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.DocumentFolderTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.DocumentVersionTable
import network.lapis.cloud.server.documents.FolderAccessLevels
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.moreRestrictive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.uuid.Uuid

/**
 * Archives generated [bytes] into the existing Document/DocumentVersion store, mirroring
 * [registerDocumentRoutes]'s own insert pattern (`{documentId}/{versionId}.bin` storage key,
 * SHA-256 checksum, `currentVersionId` update). Generalized (V1.0 Wave 7 "Whiteboard") from what
 * used to be [archiveGeneratedPdf]'s own hardcoded body -- [mimeType]/[changeNote] are now caller-
 * supplied instead of hardcoded to `"application/pdf"`, since a whiteboard PNG (Wave 7,
 * `network.lapis.cloud.server.rpc.ConferenceWhiteboardService.saveAsDocument`) needs the identical
 * in-memory-`ByteArray` shape but a different MIME type. [archiveGeneratedPdf] below is now a thin,
 * behavior-preserving wrapper around this function.
 *
 * Finds-or-creates a top-level [DocumentFolderTable] row named [folderName] (e.g.
 * "Beitragsrechnungen"/"Spendenbescheinigungen"/"Whiteboards") so repeated archiving calls land in
 * the same folder instead of creating a fresh one every time.
 *
 * Returns the new [DocumentTable] row's id.
 */
fun archiveGeneratedBytes(
    storageRoot: File,
    folderName: String,
    fileName: String,
    title: String,
    bytes: ByteArray,
    mimeType: String,
    uploadedBy: Uuid,
    accessLevel: DocumentAccessLevel,
    changeNote: String,
): Uuid {
    val now = DbClock.nowLocalDateTime()
    val checksum = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    return transaction {
        val folderId =
            DocumentFolderTable
                .selectAll()
                .where { DocumentFolderTable.name eq folderName }
                .singleOrNull()
                ?.get(DocumentFolderTable.id)
                ?: Uuid.random().also { newId ->
                    // Welle V1.9.1: archive folders are containers -- confidentiality lives at the
                    // document, not the folder (pre-existing behaviour, unchanged). Set explicitly
                    // because `DocumentFolderTable.accessLevel` deliberately carries NO Exposed-side
                    // default any more (fix round, W2) -- see that column's own KDoc.
                    DocumentFolderTable.insert {
                        it[id] = newId
                        it[name] = folderName
                        it[parentFolderId] = null
                        it[DocumentFolderTable.accessLevel] = DocumentAccessLevel.PUBLIC_MEMBERS
                    }
                }

        val documentId = Uuid.random()
        val versionId = Uuid.random()
        val storageKey = "$documentId/$versionId.bin"

        // Welle V1.9.1 (K3): this path inserts directly into DocumentTable, bypassing
        // DocumentService.createDocument's own folder-effective-level check entirely. Without this
        // clamp, a caller-supplied [accessLevel] (e.g. RecordingPoller's `row.accessLevel`, which
        // can be `PUBLIC_MEMBERS`) could land in a folder an admin has since tightened to
        // `ADMIN_ONLY` -- the resulting document would still be `PUBLIC_MEMBERS`, reachable by
        // direct download URL and by the AI knowledge base, defeating the very tighten it landed
        // in. `moreRestrictive` only ever tightens, never loosens -- an already-stricter caller-
        // supplied level is left untouched.
        //
        // Fix round (B2): the folder's ancestor chain is row-locked BEFORE its level is read.
        // This function runs from a BACKGROUND poller (RecordingPoller), so the collision with an
        // admin tightening the archive folder at the same moment is not user-driven and cannot be
        // dismissed as unlikely; without the lock, the clamp could read the old, looser level and
        // insert a PUBLIC_MEMBERS document into an ADMIN_ONLY folder that nothing ever repairs (the
        // cascade only runs on a level CHANGE). See `FolderAccessLevels`' locking contract.
        val effectiveAccessLevel =
            moreRestrictive(a = accessLevel, b = FolderAccessLevels.lockChainAndSnapshot(folderId).effectiveLevel(folderId))
        DocumentTable.insert {
            it[id] = documentId
            it[DocumentTable.folderId] = folderId
            it[DocumentTable.title] = title
            it[currentVersionId] = null
            it[createdBy] = uploadedBy
            it[createdAt] = now
            it[DocumentTable.accessLevel] = effectiveAccessLevel
            it[isDeleted] = false
        }

        val targetFile = storageRoot.resolve(storageKey)
        targetFile.parentFile.mkdirs()
        targetFile.writeBytes(bytes)

        DocumentVersionTable.insert {
            it[id] = versionId
            it[DocumentVersionTable.documentId] = documentId
            it[versionNumber] = 1
            it[DocumentVersionTable.fileName] = fileName
            it[DocumentVersionTable.mimeType] = mimeType
            it[fileSizeBytes] = bytes.size.toLong()
            it[DocumentVersionTable.storageKey] = storageKey
            it[checksumSha256] = checksum
            it[DocumentVersionTable.uploadedBy] = uploadedBy
            it[uploadedAt] = now
            it[DocumentVersionTable.changeNote] = changeNote
        }
        DocumentTable.update({ DocumentTable.id eq documentId }) {
            it[currentVersionId] = versionId
        }

        documentId
    }
}

/**
 * PDF-specific convenience wrapper around [archiveGeneratedBytes], preserved for
 * [registerMailmergeRoutes]'s Beitragsrechnung/Spendenbescheinigung call sites (retention/audit
 * argument: an issued financial/tax document must remain reproducible byte-for-byte even if the
 * underlying Contribution/Member/JournalEntry rows change later) -- NOT for Einladung (ephemeral
 * governance correspondence, no retention argument, avoids inflating the Document store with
 * routine invitations). Pure delegation, zero behavior change from before the Wave 7 generalization.
 */
fun archiveGeneratedPdf(
    storageRoot: File,
    folderName: String,
    fileName: String,
    title: String,
    bytes: ByteArray,
    uploadedBy: Uuid,
    accessLevel: DocumentAccessLevel,
): Uuid =
    archiveGeneratedBytes(
        storageRoot = storageRoot,
        folderName = folderName,
        fileName = fileName,
        title = title,
        bytes = bytes,
        mimeType = "application/pdf",
        uploadedBy = uploadedBy,
        accessLevel = accessLevel,
        changeNote = "Automatisch generiert (V0.4.1 Serienbrief/PDF-Engine)",
    )

/**
 * Streaming sibling of [archiveGeneratedPdf] -- identical Document/DocumentVersion insert pattern,
 * except the bytes are copied from [sourceFile] via a buffered stream-to-stream copy, NEVER read
 * into a `ByteArray` first. Added for V1.0 Videokonferenzen (Kleinsitzung), Wave 2 "Aufzeichnung"
 * (`network.lapis.cloud.server.conference.RecordingPoller`'s successful-composition path) -- a
 * composed recording can run to hundreds of megabytes or more, and must never be fully materialized
 * in memory just to archive it. [mimeType]/[accessLevel] are caller-supplied (not hardcoded like
 * [archiveGeneratedPdf]'s own `"application/pdf"`) since this sibling is not PDF-specific.
 *
 * The file COPY itself runs OUTSIDE any Exposed `transaction {}` -- real, potentially slow disk I/O
 * must never run while holding a DB connection/transaction open, same discipline every outbound
 * network call in this codebase already follows (see `ConferenceService`'s own "Transaction
 * boundaries" KDoc). `documentId`/`versionId`/`storageKey` are therefore generated up front, before
 * the copy, so the DB insert afterward is a single short transaction. [folderAccessLevel] (V1.9.143) is the level a NEWLY created folder gets
 * (default `PUBLIC_MEMBERS`, as before; an existing folder is never changed) and [onDocumentCreated] runs inside that same transaction right after
 * the document row exists (the pulpit recording marker). On a copy failure the
 * partially-written [File.resolve] target under [storageRoot] is deleted before the exception
 * propagates -- no dangling half-written blob left behind, and (because nothing was inserted) no
 * dangling DB reference to it either.
 */
fun archiveGeneratedFile(
    storageRoot: File,
    folderName: String,
    fileName: String,
    title: String,
    sourceFile: File,
    mimeType: String,
    uploadedBy: Uuid,
    accessLevel: DocumentAccessLevel,
    folderAccessLevel: DocumentAccessLevel = DocumentAccessLevel.PUBLIC_MEMBERS,
    onDocumentCreated: (documentId: Uuid) -> Unit = {},
): Uuid {
    val now = DbClock.nowLocalDateTime()
    val documentId = Uuid.random()
    val versionId = Uuid.random()
    val storageKey = "$documentId/$versionId.bin"
    val targetFile = storageRoot.resolve(storageKey)
    targetFile.parentFile.mkdirs()

    val digest = MessageDigest.getInstance("SHA-256")
    var fileSizeBytes = 0L
    try {
        sourceFile.inputStream().use { input ->
            targetFile.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                    fileSizeBytes += read
                }
            }
        }
    } catch (e: IOException) {
        targetFile.delete()
        throw e
    }
    val checksum = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    return transaction {
        val folderId =
            DocumentFolderTable
                .selectAll()
                .where { DocumentFolderTable.name eq folderName }
                .singleOrNull()
                ?.get(DocumentFolderTable.id)
                ?: Uuid.random().also { newId ->
                    // Welle V1.9.1: see archiveGeneratedBytes' identical comment above.
                    DocumentFolderTable.insert {
                        it[id] = newId
                        it[name] = folderName
                        it[parentFolderId] = null
                        it[DocumentFolderTable.accessLevel] = folderAccessLevel
                    }
                }

        // Welle V1.9.1 (K3) + fix round (B2): see archiveGeneratedBytes' identical comment above --
        // this insert bypasses DocumentService.createDocument's folder-effective-level check just the
        // same, and takes the same folder-chain row lock before reading the level it clamps against.
        val effectiveAccessLevel =
            moreRestrictive(a = accessLevel, b = FolderAccessLevels.lockChainAndSnapshot(folderId).effectiveLevel(folderId))
        DocumentTable.insert {
            it[id] = documentId
            it[DocumentTable.folderId] = folderId
            it[DocumentTable.title] = title
            it[currentVersionId] = null
            it[createdBy] = uploadedBy
            it[createdAt] = now
            it[DocumentTable.accessLevel] = effectiveAccessLevel
            it[isDeleted] = false
        }
        DocumentVersionTable.insert {
            it[id] = versionId
            it[DocumentVersionTable.documentId] = documentId
            it[versionNumber] = 1
            it[DocumentVersionTable.fileName] = fileName
            it[DocumentVersionTable.mimeType] = mimeType
            it[DocumentVersionTable.fileSizeBytes] = fileSizeBytes
            it[DocumentVersionTable.storageKey] = storageKey
            it[checksumSha256] = checksum
            it[DocumentVersionTable.uploadedBy] = uploadedBy
            it[uploadedAt] = now
            it[changeNote] = "Automatisch generiert (V1.0 Wave 2 Aufzeichnung)"
        }
        DocumentTable.update({ DocumentTable.id eq documentId }) {
            it[currentVersionId] = versionId
        }
        // V1.9.143: lets the caller write a marker row in the SAME transaction as the document (the pulpit recording marker).
        onDocumentCreated(documentId)

        documentId
    }
}

/** Thrown inside [archiveNewVersionOfFile]'s transaction to roll it back when the caller's fence refuses; never leaves that function. */
private class VersionCommitRefused : RuntimeException("version commit refused", null, false, false)

/**
 * Adds ONE NEW VERSION to the existing document [documentId] from [sourceFile] (V1.9.109, the recompute of a READY recording): the
 * streaming copy of [archiveGeneratedFile] (outside any transaction, SHA-256 computed on the way, the partial blob removed on a
 * failure), then ONE short transaction that
 *
 * 1. runs [fence] FIRST -- the caller's conditional update of its own row (for a recording: `WHERE id = ? AND status = READY AND
 *    recompute_started_at = <fence value>`), which also takes that row's lock BEFORE the document is touched (the order every other
 *    path uses: recording, then document); a `false` answer means the situation changed meanwhile (recording deleted, a newer recompute
 *    started) and everything is rolled back, the blob removed, `null` returned;
 * 2. locks the document row, refuses a missing or soft-deleted document (same rollback), inserts the version as `max(versionNumber) + 1`
 *    and switches `currentVersionId` to it.
 *
 * That last switch is the atomic replacement: a reader sees the old version until the commit and the new one after it, never a mix.
 * The previous version row and blob stay (the document history). [fence] receives the new version's id and its size in bytes; [finalize]
 * runs last inside the same transaction (the audit entry, see `AuditLogRecorder`'s deadlock-avoidance contract).
 *
 * Returns the new version's id, or `null` when the fence or the document refused (nothing changed, no blob left behind).
 */
fun archiveNewVersionOfFile(
    storageRoot: File,
    documentId: Uuid,
    sourceFile: File,
    fileName: String,
    mimeType: String,
    uploadedBy: Uuid,
    changeNote: String,
    fence: (versionId: Uuid, sizeBytes: Long) -> Boolean,
    finalize: () -> Unit = {},
): Uuid? {
    val now = DbClock.nowLocalDateTime()
    val versionId = Uuid.random()
    val storageKey = "$documentId/$versionId.bin"
    val targetFile = storageRoot.resolve(storageKey)
    targetFile.parentFile.mkdirs()

    val digest = MessageDigest.getInstance("SHA-256")
    var sizeBytes = 0L
    try {
        sourceFile.inputStream().use { input ->
            targetFile.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                    sizeBytes += read
                }
            }
        }
    } catch (e: IOException) {
        targetFile.delete()
        throw e
    }
    val checksum = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    try {
        transaction {
            if (!fence(versionId, sizeBytes)) throw VersionCommitRefused()
            val document =
                DocumentTable
                    .selectAll()
                    .where { DocumentTable.id eq documentId }
                    .forUpdate()
                    .singleOrNull()
            if (document == null || document[DocumentTable.isDeleted]) throw VersionCommitRefused()
            val maxColumn = DocumentVersionTable.versionNumber.max()
            val maxNumber =
                DocumentVersionTable
                    .select(maxColumn)
                    .where { DocumentVersionTable.documentId eq documentId }
                    .singleOrNull()
                    ?.get(maxColumn) ?: 0
            DocumentVersionTable.insert {
                it[id] = versionId
                it[DocumentVersionTable.documentId] = documentId
                it[versionNumber] = maxNumber + 1
                it[DocumentVersionTable.fileName] = fileName
                it[DocumentVersionTable.mimeType] = mimeType
                it[fileSizeBytes] = sizeBytes
                it[DocumentVersionTable.storageKey] = storageKey
                it[checksumSha256] = checksum
                it[DocumentVersionTable.uploadedBy] = uploadedBy
                it[uploadedAt] = now
                it[DocumentVersionTable.changeNote] = changeNote
            }
            DocumentTable.update({ DocumentTable.id eq documentId }) {
                it[currentVersionId] = versionId
            }
            // The caller's last step (the audit entry): it takes the audit chain's lock and must therefore be the LAST lock-taking operation.
            finalize()
        }
    } catch (_: VersionCommitRefused) {
        targetFile.delete()
        return null
    } catch (e: Throwable) {
        targetFile.delete()
        throw e
    }
    return versionId
}
