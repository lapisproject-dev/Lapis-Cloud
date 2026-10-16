package network.lapis.cloud.server.encounter

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.generated.AiKnowledgeChunkTable
import network.lapis.cloud.server.db.generated.AiKnowledgeIndexStateTable
import network.lapis.cloud.server.db.generated.AiKnowledgeReleaseTable
import network.lapis.cloud.server.db.generated.DocumentFolderTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.DocumentVersionTable
import network.lapis.cloud.server.db.generated.EncounterSpaceTable
import network.lapis.cloud.server.db.generated.PulpitRecordingDocumentTable
import network.lapis.cloud.server.routes.archiveGeneratedFile
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.PulpitRecordingDocumentDto
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import kotlin.uuid.Uuid

// Privacy rule of this file: the logs name counts and exception classes only -- never a path, a title, an id or a person.
private val logger = KotlinLogging.logger {}

/**
 * V1.9.143 -- storing, listing and HARD-deleting pulpit recordings in the document store (the "Ablage").
 *
 * **Stored `BOARD_ONLY` in a `BOARD_ONLY` folder of their own** ([FOLDER_NAME]); the folder level is set explicitly because
 * `archiveGeneratedFile` would otherwise create a missing folder as `PUBLIC_MEMBERS` (the document would be clamped, the folder name would not).
 * The title carries the room and the moment, never a person's name; the file name carries only a random UUID.
 *
 * **Hard delete.** Everywhere else the Ablage deletes softly (`is_deleted`, bytes and rows stay, also in a backup). For the voice and the face of a
 * person that is not "deletion at any time", so a pulpit recording is removed for real: the blobs of every version, the version rows, the marker row
 * and the document row. Older backups keep a copy until they rotate; the UI says so. `DocumentService.deleteDocument` hands pulpit recordings to
 * [hardDelete] too, so the ordinary document list cannot soft-delete one by accident.
 */
internal object PulpitRecordingDocuments {
    const val FOLDER_NAME: String = "Aufzeichnungen Begegnungsraum"

    /** The root of the document store. Set once by `Application.module`; the fallback is the same default the module uses. */
    @Volatile private var configuredRoot: File? = null

    fun useStorageRoot(root: File) {
        configuredRoot = root
    }

    fun storageRoot(): File = configuredRoot ?: File(System.getenv("LAPIS_DOCUMENT_STORAGE_ROOT") ?: "build/document-storage")

    /** `"dd.MM.yyyy HH:mm"` of [utc] on the organization's wall clock (a fixed German format, like every server-generated title). */
    internal fun wallText(utc: LocalDateTime): String {
        val wall = OrganizationTimeZone.wallNowOf(utc)

        fun two(n: Int) = n.toString().padStart(2, '0')
        return "${two(wall.day)}.${two(wall.month.ordinal + 1)}.${wall.year} ${two(wall.hour)}:${two(wall.minute)}"
    }

    /**
     * Copies [file] into the store as a pulpit recording and writes the marker row in the SAME transaction as the document. Returns the document id.
     * [assembly] only selects the wording of the title ("Podium" instead of "Kanzel").
     */
    fun archive(
        file: File,
        spaceId: Uuid,
        fileId: Uuid,
        recordedAt: LocalDateTime,
        uploadedBy: Uuid,
        assembly: Boolean,
        root: File = storageRoot(),
    ): Uuid {
        val spaceTitle =
            transaction {
                EncounterSpaceTable
                    .selectAll()
                    .where { EncounterSpaceTable.id eq spaceId }
                    .singleOrNull()
                    ?.get(EncounterSpaceTable.title)
            } ?: throw IllegalStateException("space missing")
        val word = if (assembly) "Podium" else "Kanzel"
        return archiveGeneratedFile(
            storageRoot = root,
            folderName = FOLDER_NAME,
            fileName = "${word.lowercase()}-aufzeichnung-$fileId.mp4",
            title = "$word-Aufzeichnung – $spaceTitle – ${wallText(recordedAt)}".take(MAX_TITLE),
            sourceFile = file,
            mimeType = "video/mp4",
            uploadedBy = uploadedBy,
            accessLevel = DocumentAccessLevel.BOARD_ONLY,
            folderAccessLevel = DocumentAccessLevel.BOARD_ONLY,
            onDocumentCreated = { documentId ->
                PulpitRecordingDocumentTable.insert {
                    it[PulpitRecordingDocumentTable.documentId] = documentId
                    it[PulpitRecordingDocumentTable.spaceId] = spaceId
                    it[PulpitRecordingDocumentTable.recordedAt] = recordedAt
                }
            },
        )
    }

    /** Inside a transaction: whether [documentId] is a pulpit recording. */
    fun isPulpitRecording(documentId: Uuid): Boolean =
        PulpitRecordingDocumentTable
            .selectAll()
            .where { PulpitRecordingDocumentTable.documentId eq documentId }
            .limit(1)
            .any()

    /**
     * Inside a transaction: whether [folderId] is the pulpit recordings' folder (by its name) or directly holds a pulpit recording. Such a folder
     * and its recordings are never loosened below `BOARD_ONLY` (Art. 9 data, see `docs/features/pulpit-recording.adoc`).
     */
    fun isPulpitRecordingFolder(folderId: Uuid): Boolean =
        DocumentFolderTable
            .selectAll()
            .where { (DocumentFolderTable.id eq folderId) and (DocumentFolderTable.name eq FOLDER_NAME) }
            .limit(1)
            .any() ||
            (PulpitRecordingDocumentTable innerJoin DocumentTable)
                .selectAll()
                .where { DocumentTable.folderId eq folderId }
                .limit(1)
                .any()

    /** Inside a transaction: the pulpit recordings of [spaceId] (all rooms when `null`), newest first, each with the size of its current version. */
    fun list(spaceId: Uuid?): List<PulpitRecordingDocumentDto> =
        (PulpitRecordingDocumentTable innerJoin EncounterSpaceTable)
            .selectAll()
            .where {
                if (spaceId == null) Op.TRUE else PulpitRecordingDocumentTable.spaceId eq spaceId
            }.orderBy(PulpitRecordingDocumentTable.recordedAt, SortOrder.DESC)
            .limit(MAX_LIST)
            .map { row ->
                val documentId = row[PulpitRecordingDocumentTable.documentId]
                val size =
                    DocumentVersionTable
                        .selectAll()
                        .where { DocumentVersionTable.documentId eq documentId }
                        .sumOf { it[DocumentVersionTable.fileSizeBytes] }
                PulpitRecordingDocumentDto(
                    documentId = documentId.toString(),
                    spaceId = row[PulpitRecordingDocumentTable.spaceId].toString(),
                    spaceTitle = row[EncounterSpaceTable.title],
                    recordedAt = row[PulpitRecordingDocumentTable.recordedAt],
                    fileSizeBytes = size,
                )
            }

    /**
     * Deletes the pulpit recording [documentId] for real: rows first, in ONE transaction (with the audit entry LAST, as everywhere), the blobs after the
     * commit. Returns `false` if [documentId] is no pulpit recording. A blob that cannot be removed is logged (a count, never a path); the rows are
     * gone either way, so the recording is no longer reachable.
     */
    fun hardDelete(
        documentId: Uuid,
        actor: CurrentMember,
        root: File = storageRoot(),
    ): Boolean {
        val keys =
            transaction {
                if (!isPulpitRecording(documentId)) return@transaction null
                val storageKeys =
                    DocumentVersionTable
                        .selectAll()
                        .where { DocumentVersionTable.documentId eq documentId }
                        .map { it[DocumentVersionTable.storageKey] }
                // The documents of the AI knowledge base reference the document and its versions; a video is never indexed, but a stray row would block the delete.
                AiKnowledgeChunkTable.deleteWhere { AiKnowledgeChunkTable.documentId eq documentId }
                AiKnowledgeIndexStateTable.deleteWhere { AiKnowledgeIndexStateTable.documentId eq documentId }
                AiKnowledgeReleaseTable.deleteWhere { AiKnowledgeReleaseTable.documentId eq documentId }
                PulpitRecordingDocumentTable.deleteWhere { PulpitRecordingDocumentTable.documentId eq documentId }
                DocumentVersionTable.deleteWhere { DocumentVersionTable.documentId eq documentId }
                DocumentTable.deleteWhere { DocumentTable.id eq documentId }
                AuditLogRecorder.record(
                    actorMemberId = actor.memberId,
                    actorRole = actor.role,
                    entityType = AuditEntityType.PULPIT_RECORDING,
                    entityId = documentId,
                    action = AuditAction.UPDATE,
                    after = """{"event":"DELETED"}""",
                )
                storageKeys
            } ?: return false
        var failed = 0
        val canonicalRoot = root.canonicalFile
        keys.forEach { key ->
            val target = root.resolve(key).canonicalFile
            // Never leave the store: a key that resolves outside the root (path traversal) is not touched.
            if (!target.path.startsWith(canonicalRoot.path + File.separator) || (target.exists() && !target.delete())) failed++
        }
        if (failed > 0) logger.warn { "pulpit recording: $failed blob(s) could not be removed after the rows were deleted" }
        return true
    }

    private const val MAX_TITLE = 300
    private const val MAX_LIST = 500
}
