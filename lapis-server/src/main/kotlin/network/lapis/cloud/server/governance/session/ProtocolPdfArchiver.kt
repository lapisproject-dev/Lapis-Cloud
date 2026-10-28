package network.lapis.cloud.server.governance.session

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.TimeZone
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.ProtocolVersionConfirmationTable
import network.lapis.cloud.server.db.generated.ProtocolVersionTable
import network.lapis.cloud.server.pdf.MeetingProtocolPdfGenerator
import network.lapis.cloud.server.pdf.PdfConfirmation
import network.lapis.cloud.server.pdf.PdfLimitExceeded
import network.lapis.cloud.server.pdf.addressLines
import network.lapis.cloud.server.routes.archiveGeneratedFile
import network.lapis.cloud.server.routes.archiveNewVersionOfFile
import network.lapis.cloud.server.rpc.toOrganizationSettingsDto
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.MeetingSessionAuditKind
import network.lapis.cloud.shared.domain.MeetingSessionAuditSnapshot
import network.lapis.cloud.shared.domain.ProtocolVersionStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import java.nio.file.Files
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** How an archiving attempt ended. */
internal enum class PdfArchiveResult { DONE, NOTHING_TO_DO, FAILED, LIMIT }

/** Everything the PDF needs, read in one short transaction. */
private class ArchiveInput(
    val snapshot: ProtocolSnapshotV1,
    val sha: String,
    val versionNo: Int,
    val meetingId: Uuid,
    val meetingDate: kotlinx.datetime.LocalDate,
    val committeeName: String,
    val finalisedBy: Uuid,
    val existingDocumentId: Uuid?,
    val confirmations: List<PdfConfirmation>,
    val organizationName: String,
    val organizationAddress: List<String>,
)

/**
 * V1.9.240 -- renders and archives the PDF of one CONFIRMED version. The caller runs this OUTSIDE any transaction. The Protokoll is ONE document per
 * Sitzung (`meeting.protocol_document_id`, folder "Protokolle", ADMIN_ONLY); every confirmed version is one further `document_version`, so the
 * document list shows the newest and the history shows all. The version row is linked to its PDF by a CONDITIONAL update (only a CONFIRMED version
 * without a PDF), inside the same transaction that creates the document version -- if that update changes nothing (a race), everything rolls back and
 * the written file is removed. A PDF that already exists is NEVER rebuilt.
 */
internal class ProtocolPdfArchiver(
    storageRoot: File?,
) {
    private val root: File = storageRoot ?: File(System.getenv("LAPIS_DOCUMENT_STORAGE_ROOT") ?: "build/document-storage")

    fun archive(
        versionId: Uuid,
        actor: CurrentMember,
    ): Boolean =
        archiveDetailed(versionId = versionId, actor = actor).let {
            it == PdfArchiveResult.DONE ||
                it == PdfArchiveResult.NOTHING_TO_DO
        }

    fun archiveDetailed(
        versionId: Uuid,
        actor: CurrentMember,
    ): PdfArchiveResult {
        // One archiving per version at a time inside this process (the conditional update still guards against a second process).
        return LOCKS[(versionId.hashCode() and Int.MAX_VALUE) % LOCKS.size].withLock {
            try {
                doArchive(versionId = versionId, actor = actor)
            } catch (_: PdfLimitExceeded) {
                logger.warn { "protocol pdf limit exceeded version=$versionId" }
                PdfArchiveResult.LIMIT
            } catch (e: Exception) {
                // Ids only: never a text, a name or a hash in a log line.
                logger.error(e) { "protocol pdf archiving failed version=$versionId" }
                PdfArchiveResult.FAILED
            }
        }
    }

    private fun doArchive(
        versionId: Uuid,
        actor: CurrentMember,
    ): PdfArchiveResult {
        val input = transaction { loadInput(versionId) } ?: return PdfArchiveResult.NOTHING_TO_DO
        val bytes =
            MeetingProtocolPdfGenerator.generate(
                snapshot = input.snapshot,
                contentSha256 = input.sha,
                confirmations = input.confirmations,
                organizationName = input.organizationName,
                organizationAddressLines = input.organizationAddress,
                zone = zone(),
                includeConfirmationPage = true,
            )
        val file = Files.createTempFile("lapis-protocol-", ".pdf").toFile()
        try {
            file.writeBytes(bytes)
            val fileName = "Protokoll_${input.meetingDate}_${slug(input.committeeName)}_F${input.versionNo}.pdf"
            val title =
                "Protokoll ${input.committeeName} ${network.lapis.cloud.server.pdf.formatGermanDate(
                    input.meetingDate,
                )}".take(TITLE_MAX)
            val audit =
                MeetingSessionAuditSnapshot(
                    kind = MeetingSessionAuditKind.PROTOCOL_PDF_ARCHIVED,
                    meetingId = input.meetingId.toString(),
                    protocolVersionId = versionId.toString(),
                    protocolVersionNo = input.versionNo,
                )
            val existing = input.existingDocumentId
            val done =
                if (existing == null || !transaction { documentUsable(existing) }) {
                    createDocument(
                        input = input,
                        versionId = versionId,
                        actor = actor,
                        file = file,
                        fileName = fileName,
                        title = title,
                        audit = audit,
                    )
                    true
                } else {
                    val newVersion =
                        archiveNewVersionOfFile(
                            storageRoot = root,
                            documentId = existing,
                            sourceFile = file,
                            fileName = fileName,
                            mimeType = "application/pdf",
                            uploadedBy = input.finalisedBy,
                            changeNote = "Protokoll Fassung ${input.versionNo}",
                            fence = { _, _ -> true },
                            // The link needs the document_version row (foreign key): it runs after the insert, in the same transaction.
                            link = { documentVersionId ->
                                ProtocolVersionStore.attachPdf(versionId = versionId, documentVersionId = documentVersionId)
                            },
                            finalize = { MeetingSessionAudit.record(actor = actor, snapshot = audit) },
                        )
                    newVersion != null
                }
            return if (done) PdfArchiveResult.DONE else PdfArchiveResult.FAILED
        } finally {
            file.delete()
        }
    }

    private fun createDocument(
        input: ArchiveInput,
        versionId: Uuid,
        actor: CurrentMember,
        file: File,
        fileName: String,
        title: String,
        audit: MeetingSessionAuditSnapshot,
    ) {
        archiveGeneratedFile(
            storageRoot = root,
            folderName = FOLDER_NAME,
            fileName = fileName,
            title = title,
            sourceFile = file,
            mimeType = "application/pdf",
            uploadedBy = input.finalisedBy,
            accessLevel = DocumentAccessLevel.ADMIN_ONLY,
            folderAccessLevel = DocumentAccessLevel.ADMIN_ONLY,
            onDocumentCreated = { documentId ->
                val documentVersionId =
                    DocumentTable.selectAll().where { DocumentTable.id eq documentId }.single()[DocumentTable.currentVersionId]!!
                check(
                    ProtocolVersionStore.attachPdf(versionId = versionId, documentVersionId = documentVersionId),
                ) { "version already has a pdf" }
                val old = input.existingDocumentId
                val linked =
                    MeetingTable.update({
                        (MeetingTable.id eq input.meetingId) and
                            (if (old == null) MeetingTable.protocolDocumentId.isNull() else MeetingTable.protocolDocumentId eq old)
                    }) { it[protocolDocumentId] = documentId }
                check(linked > 0) { "protocol document already set" }
                MeetingSessionAudit.record(actor = actor, snapshot = audit.copy(documentId = documentId.toString()))
            },
        )
    }

    private fun documentUsable(documentId: Uuid): Boolean =
        DocumentTable
            .selectAll()
            .where { DocumentTable.id eq documentId }
            .singleOrNull()
            ?.let { !it[DocumentTable.isDeleted] } ?: false

    private fun loadInput(versionId: Uuid): ArchiveInput? {
        val version = ProtocolVersionStore.versionRow(versionId = versionId) ?: return null
        if (version[ProtocolVersionTable.status] != ProtocolVersionStatus.CONFIRMED) return null
        if (version[ProtocolVersionTable.pdfDocumentVersionId] != null) return null
        val meeting = MeetingTable.selectAll().where { MeetingTable.id eq version[ProtocolVersionTable.meetingId] }.single()
        val committee = CommitteeTable.selectAll().where { CommitteeTable.id eq meeting[MeetingTable.committeeId] }.single()
        val confirmationRows = ProtocolVersionStore.confirmations(versionId)
        val names =
            MemberTable
                .selectAll()
                .where { MemberTable.id inList confirmationRows.map { it[ProtocolVersionConfirmationTable.memberId] } }
                .associate { it[MemberTable.id] to it[MemberTable.displayName] }
        val org =
            OrganizationSettingsTable
                .selectAll()
                .limit(1)
                .singleOrNull()
                ?.toOrganizationSettingsDto()
        return ArchiveInput(
            snapshot = ProtocolSnapshots.parse(version[ProtocolVersionTable.contentJson]),
            sha = version[ProtocolVersionTable.contentSha256],
            versionNo = version[ProtocolVersionTable.versionNo],
            meetingId = meeting[MeetingTable.id],
            meetingDate = meeting[MeetingTable.scheduledAt].date,
            committeeName = committee[CommitteeTable.name],
            finalisedBy = version[ProtocolVersionTable.finalisedBy],
            existingDocumentId = meeting[MeetingTable.protocolDocumentId],
            confirmations =
                confirmationRows.map {
                    PdfConfirmation(
                        role = it[ProtocolVersionConfirmationTable.role],
                        name = names[it[ProtocolVersionConfirmationTable.memberId]].orEmpty(),
                        confirmedAt = it[ProtocolVersionConfirmationTable.confirmedAt],
                    )
                },
            organizationName = org?.name.orEmpty(),
            organizationAddress = org?.addressLines().orEmpty(),
        )
    }

    private fun zone(): TimeZone = OrganizationTimeZone.current()

    private companion object {
        const val FOLDER_NAME = "Protokolle"
        const val TITLE_MAX = 200
        const val SLUG_MAX = 40
        val LOCKS = Array(STRIPES) { ReentrantLock() }
        const val STRIPES = 64

        /** `[a-z0-9-]` only, at most [SLUG_MAX] characters: the committee name never reaches a file name raw. */
        fun slug(name: String): String {
            val ascii =
                java.text.Normalizer
                    .normalize(name.lowercase(), java.text.Normalizer.Form.NFD)
                    .replace(Regex("\\p{M}+"), "")
            return ascii
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .take(SLUG_MAX)
                .trim('-')
                .ifEmpty { "sitzung" }
        }
    }
}
