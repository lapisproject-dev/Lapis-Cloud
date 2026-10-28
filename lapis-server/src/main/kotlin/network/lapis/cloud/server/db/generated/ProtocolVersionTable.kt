// Welle V1.9.240 "Protokoll fertigstellen" -- hand-edited, see 05-governance.kuml.kts and V130__meeting_protocol_versions.sql.

package network.lapis.cloud.server.db.generated

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.ProtocolConfirmationMode
import network.lapis.cloud.shared.domain.ProtocolVersionStatus
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime
import kotlin.uuid.Uuid

public object ProtocolVersionTable : Table("protocol_version") {
    public val id: Column<Uuid> = uuid("id")
    public val meetingId: Column<Uuid> = reference("meeting_id", MeetingTable.id)
    public val versionNo: Column<Int> = integer("version_no")
    public val contentJson: Column<String> = text("content_json")
    public val contentSha256: Column<String> = varchar("content_sha256", 64)
    public val correctionNote: Column<String?> = varchar("correction_note", 4000).nullable()
    public val finalisedBy: Column<Uuid> = reference("finalised_by", MemberTable.id)
    public val finalisedAt: Column<LocalDateTime> = datetime("finalised_at")
    public val confirmationMode: Column<ProtocolConfirmationMode> = enumerationByName<ProtocolConfirmationMode>("confirmation_mode", 16)
    public val minutesRequired: Column<Boolean> = bool("minutes_required")
    public val status: Column<ProtocolVersionStatus> = enumerationByName<ProtocolVersionStatus>("status", 21)
    public val pdfDocumentVersionId: Column<Uuid?> = optReference("pdf_document_version_id", DocumentVersionTable.id)

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
