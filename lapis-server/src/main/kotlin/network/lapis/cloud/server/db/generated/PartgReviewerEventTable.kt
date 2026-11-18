// Hand-written, mirroring the generated-file convention every other db/generated table in this package establishes -- kept in sync with
// 76-partg-report.kuml.kts by PartgReportSchemaDriftTest. V1.9.310 (R6b).

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

public object PartgReviewerEventTable : Table("partg_reviewer_event") {
    public val id: Column<Uuid> = uuid("id")
    public val invitationId: Column<Uuid> = reference("invitation_id", PartgReviewerInvitationTable.id)
    public val kind: Column<String> = varchar("kind", 20)
    // no foreign key: the deletion routine of old versions must never be blocked by the usage list
    public val versionId: Column<Uuid?> = uuid("version_id").nullable()
    public val format: Column<String?> = varchar("format", 4).nullable()
    public val occurredAt: Column<LocalDateTime> = datetime("occurred_at")

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
