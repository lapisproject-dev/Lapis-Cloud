package network.lapis.cloud.server.routes

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
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
import java.security.MessageDigest
import kotlin.time.Clock
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
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
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
