// Welle V1.9.246 "Aktionen A1" -- hand-edited, see 80-activism.kuml.kts file header.

package network.lapis.cloud.server.db.generated

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import kotlin.uuid.Uuid

public object ActivismTextBlockTable : Table("activism_text_block") {
    public val id: Column<Uuid> = uuid("id")
    public val actionId: Column<Uuid> = uuid("action_id")
    public val position: Column<Int> = integer("position")
    public val title: Column<String> = varchar("title", 120)
    public val body: Column<String> = varchar("body", 4000)

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
