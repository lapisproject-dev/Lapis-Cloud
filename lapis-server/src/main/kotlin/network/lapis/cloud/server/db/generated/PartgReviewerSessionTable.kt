// Hand-written, mirroring the generated-file convention every other db/generated table in this package establishes -- kept in sync with
// 76-partg-report.kuml.kts by PartgReportSchemaDriftTest. V1.9.310 (R6b).

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

public object PartgReviewerSessionTable : Table("partg_reviewer_session") {
    public val id: Column<Uuid> = uuid("id")
    public val tokenHash: Column<String> = varchar("token_hash", 64)
    public val invitationId: Column<Uuid> = reference("invitation_id", PartgReviewerInvitationTable.id)
    public val createdAt: Column<LocalDateTime> = datetime("created_at")
    public val lastSeenAt: Column<LocalDateTime> = datetime("last_seen_at")
    public val idleExpiresAt: Column<LocalDateTime> = datetime("idle_expires_at")
    public val absoluteExpiresAt: Column<LocalDateTime> = datetime("absolute_expires_at")

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
