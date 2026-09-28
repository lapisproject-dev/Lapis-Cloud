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

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
