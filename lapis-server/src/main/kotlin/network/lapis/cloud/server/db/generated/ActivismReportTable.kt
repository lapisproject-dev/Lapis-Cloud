// Welle V1.9.246 "Aktionen A1" -- hand-edited, see 80-activism.kuml.kts file header.

package network.lapis.cloud.server.db.generated

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.ActivismReportStatus
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime
import kotlin.uuid.Uuid

public object ActivismReportTable : Table("activism_report") {
    public val id: Column<Uuid> = uuid("id")
    public val actionId: Column<Uuid> = uuid("action_id")
    public val reporterMemberId: Column<Uuid?> = uuid("reporter_member_id").nullable()
    public val reason: Column<String> = varchar("reason", 1000)
    public val status: Column<ActivismReportStatus> = enumerationByName<ActivismReportStatus>("status", 8).default(ActivismReportStatus.OPEN)
    public val createdAt: Column<LocalDateTime> = datetime("created_at")
    public val resolvedAt: Column<LocalDateTime?> = datetime("resolved_at").nullable()
    public val resolvedBy: Column<Uuid?> = uuid("resolved_by").nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
