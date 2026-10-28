// Welle V1.9.240 "Protokoll fertigstellen" -- hand-edited, see 05-governance.kuml.kts and V130__meeting_protocol_versions.sql.

package network.lapis.cloud.server.db.generated

import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.date
import kotlin.uuid.Uuid

public object ProtocolVersionTaskTable : Table("protocol_version_task") {
    public val id: Column<Uuid> = uuid("id")
    public val versionId: Column<Uuid> = reference("version_id", ProtocolVersionTable.id)
    public val taskKey: Column<Uuid> = uuid("task_key")
    public val assigneeMemberId: Column<Uuid> = reference("assignee_member_id", MemberTable.id)
    public val dueDate: Column<LocalDate?> = date("due_date").nullable()
    public val description: Column<String> = varchar("description", 500)

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
