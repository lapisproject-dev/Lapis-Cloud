// Welle V1.9.246 "Aktionen A1" -- hand-edited, see 80-activism.kuml.kts file header.

package network.lapis.cloud.server.db.generated

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.date
import org.jetbrains.exposed.v1.datetime.datetime
import kotlin.uuid.Uuid

public object ActivismTaskTable : Table("activism_task") {
    public val id: Column<Uuid> = uuid("id")
    public val actionId: Column<Uuid> = uuid("action_id")
    public val position: Column<Int> = integer("position")
    public val text: Column<String> = varchar("text", 200)
    public val assigneeMemberId: Column<Uuid?> = uuid("assignee_member_id").nullable()
    public val dueOn: Column<LocalDate?> = date("due_on").nullable()
    public val done: Column<Boolean> = bool("done").default(false)
    public val doneAt: Column<LocalDateTime?> = datetime("done_at").nullable()
    public val doneBy: Column<Uuid?> = uuid("done_by").nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
