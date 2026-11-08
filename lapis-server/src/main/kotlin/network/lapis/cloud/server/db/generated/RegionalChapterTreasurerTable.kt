// Hand-written, mirroring the generated-file convention every other db/generated table in this package establishes -- kept in sync with
// 58-regional-chapter.kuml.kts by SchemaDriftTest. V1.9.289 (R4a) "chapter treasurer": a plain member may book in the chapters granted.

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

public object RegionalChapterTreasurerTable : Table("regional_chapter_treasurer") {
    public val id: Column<Uuid> = uuid("id")
    public val memberId: Column<Uuid> = reference("member_id", MemberTable.id)
    public val regionalChapterId: Column<Uuid> = reference("regional_chapter_id", RegionalChapterTable.id)
    public val grantedAt: Column<LocalDateTime> = datetime("granted_at")
    public val grantedByMemberId: Column<Uuid?> = optReference("granted_by_member_id", MemberTable.id)
    public val revokedAt: Column<LocalDateTime?> = datetime("revoked_at").nullable()

    // NOT a foreign key: the uniqueness guard `<member id>:<chapter id>` while the grant is active, NULL once revoked (H2 has no partial
    // index). Kept honest by chk_regional_chapter_treasurer_active (V144__partg_report_r4.sql).
    public val activeKey: Column<String?> = varchar("active_key", 73).nullable().uniqueIndex()

    override val primaryKey: PrimaryKey = PrimaryKey(id)

    // Note: the check constraint declared on this entity is not emitted -- Exposed's check {} DSL needs a typed Op<Boolean>.
}
