// Hand-maintained, NOT codegen-generated -- see 39-events.kuml.kts file header "Welle V1.4.37"
// addendum. `splitFromSeriesId` is self-referential (UmlToErmTransformer skips self-referential
// UML associations, same treatment as DocumentFolderTable.parentFolderId), so this file cannot be
// regenerated from the merged ERM model the way most *Table.kt files in this package are --
// mirrors DocumentFolderTable.kt's own header note for the same reason.

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

public object EventSeriesTable : Table("event_series") {
    public val id: Column<Uuid> = uuid("id")
    public val rrule: Column<String> = varchar("rrule", 255)
    public val dtstart: Column<LocalDateTime> = datetime("dtstart")
    public val timezone: Column<String> = varchar("timezone", 64)
    public val durationMinutes: Column<Int> = integer("duration_minutes")

    // Self-referential -- see file header. No `.references()`: a typed self-FK in the Exposed DSL
    // would be circular. The real FK exists only in SQL (V56__event_series.sql).
    public val splitFromSeriesId: Column<Uuid?> = uuid("split_from_series_id").nullable()

    public val createdBy: Column<Uuid> = reference("created_by", MemberTable.id)
    public val createdAt: Column<LocalDateTime> = datetime("created_at")

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
