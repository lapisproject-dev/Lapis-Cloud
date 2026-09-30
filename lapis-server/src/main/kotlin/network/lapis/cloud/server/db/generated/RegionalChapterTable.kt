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

public object RegionalChapterTable : Table("regional_chapter") {
    public val id: Column<Uuid> = uuid("id")
    public val name: Column<String> = varchar("name", 80)

    // See 58-regional-chapter.kuml.kts file header -- H2 cannot index an expression (lower(name)),
    // so uniqueness is enforced on this separate, service-maintained column instead.
    public val nameKey: Column<String> = varchar("name_key", 80).uniqueIndex()
    public val createdAt: Column<LocalDateTime> = datetime("created_at")

    // Welle V1.9.20 "Öffentliche Seiten" (V62__public_profiles.sql) -- see 58-regional-chapter.kuml.kts.
    // The three crest_* columns are all NULL or all set (chk_regional_chapter_crest_state).
    public val description: Column<String?> = varchar("description", 1200).nullable()
    public val crestImageId: Column<Uuid?> = uuid("crest_image_id").nullable()
    public val crestPublicToken: Column<String?> = varchar("crest_public_token", 64).nullable()
    public val crestContentType: Column<String?> = varchar("crest_content_type", 32).nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
