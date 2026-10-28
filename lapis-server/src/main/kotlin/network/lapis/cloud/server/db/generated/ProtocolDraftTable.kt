// Welle V1.9.239 "Protokollentwurf in der Sitzung" -- hand-edited, see 05-governance.kuml.kts and V129__meeting_protocol_draft.sql.

package network.lapis.cloud.server.db.generated

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.ProtocolDraftPhase
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime
import kotlin.uuid.Uuid

public object ProtocolDraftTable : Table("protocol_draft") {
    public val id: Column<Uuid> = uuid("id")
    public val meetingId: Column<Uuid> = reference("meeting_id", MeetingTable.id)
    public val phase: Column<ProtocolDraftPhase> = enumerationByName<ProtocolDraftPhase>("phase", 6)
    public val createdAt: Column<LocalDateTime> = datetime("created_at")
    public val updatedAt: Column<LocalDateTime> = datetime("updated_at")
    // V1.9.240 (V130): the draft is a correction of that version, with the note that says what is corrected.
    public val correctionOfVersionNo: Column<Int?> = integer("correction_of_version_no").nullable()
    public val correctionNote: Column<String?> = varchar("correction_note", 4000).nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
