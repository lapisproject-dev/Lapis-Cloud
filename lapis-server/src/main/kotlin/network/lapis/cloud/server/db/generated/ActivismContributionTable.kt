// Welle V1.9.246 "Aktionen A1" -- hand-edited, see 80-activism.kuml.kts file header.

package network.lapis.cloud.server.db.generated

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.ActivismContributionStatus
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime
import kotlin.uuid.Uuid

public object ActivismContributionTable : Table("activism_contribution") {
    public val id: Column<Uuid> = uuid("id")
    public val actionId: Column<Uuid> = uuid("action_id")
    public val memberId: Column<Uuid?> = uuid("member_id").nullable()
    public val quantity: Column<Int> = integer("quantity")
    public val area: Column<String?> = varchar("area", 120).nullable()
    public val note: Column<String?> = varchar("note", 500).nullable()
    public val status: Column<ActivismContributionStatus> = enumerationByName<ActivismContributionStatus>("status", 10).default(ActivismContributionStatus.REPORTED)
    public val rejectReason: Column<String?> = varchar("reject_reason", 200).nullable()
    public val decidedBy: Column<Uuid?> = uuid("decided_by").nullable()
    public val decidedAt: Column<LocalDateTime?> = datetime("decided_at").nullable()
    public val reportedAt: Column<LocalDateTime> = datetime("reported_at")
    public val updatedAt: Column<LocalDateTime> = datetime("updated_at")

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
