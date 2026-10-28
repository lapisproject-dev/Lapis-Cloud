// Welle V1.9.240 "Protokoll fertigstellen" -- hand-edited, see 05-governance.kuml.kts and V130__meeting_protocol_versions.sql.

package network.lapis.cloud.server.db.generated

import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime
import kotlin.uuid.Uuid

public object ProtocolTaskStatusTable : Table("protocol_task_status") {
    public val taskKey: Column<Uuid> = uuid("task_key")
    public val meetingId: Column<Uuid> = reference("meeting_id", MeetingTable.id)
    public val assigneeMemberId: Column<Uuid> = reference("assignee_member_id", MemberTable.id)
    public val doneAt: Column<LocalDateTime?> = datetime("done_at").nullable()
    public val updatedAt: Column<LocalDateTime> = datetime("updated_at")

    override val primaryKey: PrimaryKey = PrimaryKey(taskKey)
}
