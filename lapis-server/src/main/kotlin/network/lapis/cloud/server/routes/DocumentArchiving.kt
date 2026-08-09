package network.lapis.cloud.server.routes

import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.DocumentFolderTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.DocumentVersionTable
import network.lapis.cloud.shared.domain.DocumentAccessLevel
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
 * Archives a generated PDF ([bytes]) into the existing Document/DocumentVersion store, mirroring
 * [registerDocumentRoutes]'s own insert pattern (`{documentId}/{versionId}.bin` storage key,
 * SHA-256 checksum, `currentVersionId` update). Used by [registerMailmergeRoutes] for
 * Beitragsrechnung/Spendenbescheinigung (retention/audit argument: an issued financial/tax
 * document must remain reproducible byte-for-byte even if the underlying Contribution/Member/
 * JournalEntry rows change later) -- NOT for Einladung (ephemeral governance correspondence, no
 * retention argument, avoids inflating the Document store with routine invitations).
 *
 * Finds-or-creates a top-level [DocumentFolderTable] row named [folderName] (e.g.
 * "Beitragsrechnungen"/"Spendenbescheinigungen") so repeated archiving calls land in the same
 * folder instead of creating a fresh one every time.
 *
 * Returns the new [DocumentTable] row's id.
 */
fun archiveGeneratedPdf(
    storageRoot: File,
    folderName: String,
    fileName: String,
    title: String,
    bytes: ByteArray,
    uploadedBy: Uuid,
    accessLevel: DocumentAccessLevel,
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
                    DocumentFolderTable.insert {
                        it[id] = newId
                        it[name] = folderName
                        it[parentFolderId] = null
                    }
                }

        val documentId = Uuid.random()
        val versionId = Uuid.random()
        val storageKey = "$documentId/$versionId.bin"

        DocumentTable.insert {
            it[id] = documentId
            it[DocumentTable.folderId] = folderId
            it[DocumentTable.title] = title
            it[currentVersionId] = null
            it[createdBy] = uploadedBy
            it[createdAt] = now
            it[DocumentTable.accessLevel] = accessLevel
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
            it[mimeType] = "application/pdf"
            it[fileSizeBytes] = bytes.size.toLong()
            it[DocumentVersionTable.storageKey] = storageKey
            it[checksumSha256] = checksum
            it[DocumentVersionTable.uploadedBy] = uploadedBy
            it[uploadedAt] = now
            it[changeNote] = "Automatisch generiert (V0.4.1 Serienbrief/PDF-Engine)"
        }
        DocumentTable.update({ DocumentTable.id eq documentId }) {
            it[currentVersionId] = versionId
        }

        documentId
    }
}

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
                    DocumentFolderTable.insert {
                        it[id] = newId
                        it[name] = folderName
                        it[parentFolderId] = null
                    }
                }

        DocumentTable.insert {
            it[id] = documentId
            it[DocumentTable.folderId] = folderId
            it[DocumentTable.title] = title
            it[currentVersionId] = null
            it[createdBy] = uploadedBy
            it[createdAt] = now
            it[DocumentTable.accessLevel] = accessLevel
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
