// Hand-written, mirroring the generated-file convention every other db/generated table in this
// package establishes (see CarpoolPostingTable.kt) -- kept in sync with
// 58-regional-chapter.kuml.kts by SchemaDriftTest. Welle V1.9.13 "Gliederungsverwaltung
// (Landesverbände)".

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

public object RegionalChapterOfficerTable : Table("regional_chapter_officer") {
    public val id: Column<Uuid> = uuid("id")
    public val memberId: Column<Uuid> = reference("member_id", MemberTable.id)
    public val regionalChapterId: Column<Uuid> = reference("regional_chapter_id", RegionalChapterTable.id)
    public val grantedAt: Column<LocalDateTime> = datetime("granted_at")
    public val grantedByMemberId: Column<Uuid?> = optReference("granted_by_member_id", MemberTable.id)
    public val revokedAt: Column<LocalDateTime?> = datetime("revoked_at").nullable()

    // See 58-regional-chapter.kuml.kts file header -- NOT a foreign key, a self-consistency
    // uniqueness guard value (equals memberId while active, NULL once revoked). Enforced against
    // drift by chk_regional_chapter_officer_active_consistency (V59__regional_chapters.sql).
    public val activeForMemberId: Column<Uuid?> = uuid("active_for_member_id").nullable().uniqueIndex()

    override val primaryKey: PrimaryKey = PrimaryKey(id)

    // Note: 1 check constraint declared on this entity is not emitted -- Exposed's check {} DSL
    // needs a typed Op<Boolean>, not a raw SQL string (same convention MemberTable.kt documents).
}
