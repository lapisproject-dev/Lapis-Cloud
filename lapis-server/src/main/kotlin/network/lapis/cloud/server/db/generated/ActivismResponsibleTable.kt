// Welle V1.9.246 "Aktionen A1" -- hand-edited, see 80-activism.kuml.kts file header.

package network.lapis.cloud.server.db.generated

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import kotlin.uuid.Uuid

public object ActivismResponsibleTable : Table("activism_responsible") {
    public val id: Column<Uuid> = uuid("id")
    public val actionId: Column<Uuid> = uuid("action_id")
    public val memberId: Column<Uuid> = uuid("member_id")

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
