// Hand-written, mirroring the generated-file convention every other db/generated table in this package establishes -- kept in sync with
// 76-partg-report.kuml.kts by PartgReportSchemaDriftTest. V1.9.310 (R6b).

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

public object PartgReviewerInvitationTable : Table("partg_reviewer_invitation") {
    public val id: Column<Uuid> = uuid("id")
    public val reportYear: Column<Int> = integer("report_year")
    public val inviteeEmail: Column<String?> = varchar("invitee_email", 254).nullable()
    public val linkTokenHash: Column<String> = varchar("link_token_hash", 64)
    public val codeHash: Column<String?> = varchar("code_hash", 64).nullable()
    public val codeExpiresAt: Column<LocalDateTime?> = datetime("code_expires_at").nullable()
    public val codeAttempts: Column<Int> = integer("code_attempts")
    public val codesBurned: Column<Int> = integer("codes_burned")
    public val codesSentTotal: Column<Int> = integer("codes_sent_total")
    public val codesWindowStart: Column<LocalDateTime?> = datetime("codes_window_start").nullable()
    public val codesWindowCount: Column<Int> = integer("codes_window_count")
    public val lockedAt: Column<LocalDateTime?> = datetime("locked_at").nullable()
    public val expiresAt: Column<LocalDateTime> = datetime("expires_at")
    public val revokedAt: Column<LocalDateTime?> = datetime("revoked_at").nullable()
    public val revokedBy: Column<Uuid?> = optReference("revoked_by", MemberTable.id)
    public val endReason: Column<String?> = varchar("end_reason", 14).nullable()
    public val createdBy: Column<Uuid> = reference("created_by", MemberTable.id)
    public val createdAt: Column<LocalDateTime> = datetime("created_at")
    public val lastUsedAt: Column<LocalDateTime?> = datetime("last_used_at").nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)

    // Note: the CHECKs and the unique index on link_token_hash exist in SQL only.
}
