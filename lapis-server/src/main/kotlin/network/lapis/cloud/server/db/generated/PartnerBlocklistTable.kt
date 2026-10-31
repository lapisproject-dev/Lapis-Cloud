// Welle V1.9.244 "Partnerserver P1" -- hand-edited, see 79-partner-servers.kuml.kts file header.

package network.lapis.cloud.server.db.generated

import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime
import kotlin.uuid.Uuid

public object PartnerBlocklistTable : Table("partner_blocklist") {
    public val id: Column<Uuid> = uuid("id")
    public val pattern: Column<String> = varchar("pattern", 255)
    public val createdAt: Column<LocalDateTime> = datetime("created_at")

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
