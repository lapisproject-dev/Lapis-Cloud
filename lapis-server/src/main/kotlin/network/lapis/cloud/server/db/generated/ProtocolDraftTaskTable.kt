// Welle V1.9.239 "Protokollentwurf in der Sitzung" -- hand-edited, see 05-governance.kuml.kts and V129__meeting_protocol_draft.sql.

package network.lapis.cloud.server.db.generated

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.date
import org.jetbrains.exposed.v1.datetime.datetime
import kotlin.uuid.Uuid

public object ProtocolDraftTaskTable : Table("protocol_draft_task") {
    public val id: Column<Uuid> = uuid("id")
    public val draftId: Column<Uuid> = reference("draft_id", ProtocolDraftTable.id)
    public val agendaItemId: Column<Uuid> = reference("agenda_item_id", AgendaItemTable.id)
    public val assigneeMemberId: Column<Uuid> = reference("assignee_member_id", MemberTable.id)
    public val description: Column<String> = varchar("description", 500)
    public val dueDate: Column<LocalDate?> = date("due_date").nullable()
    public val createdAt: Column<LocalDateTime> = datetime("created_at")
    // V1.9.240 (V130): the key of the task that stays the same across versions (NULL until the task is first finalised).
    public val taskKey: Column<Uuid?> = uuid("task_key").nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
