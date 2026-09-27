package network.lapis.cloud.server.routes

import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.DocumentFolderTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.DocumentVersionTable
import network.lapis.cloud.server.documents.FolderAccessLevels
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.moreRestrictive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
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
 * the copy, so the DB insert afterward is a single short transaction. On a copy failure the
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
                        it[DocumentFolderTable.accessLevel] = DocumentAccessLevel.PUBLIC_MEMBERS
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

        documentId
    }
}
