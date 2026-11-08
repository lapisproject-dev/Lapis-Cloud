// Hand-written, mirroring the generated-file convention every other db/generated table in this package establishes -- kept in sync with
// 76-partg-report.kuml.kts by PartgReportSchemaDriftTest. V1.9.289 (R4a).

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.PartgBookkeepingMode
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

public object PartgReportUnitModeTable : Table("partg_report_unit_mode") {
    public val id: Column<Uuid> = uuid("id")
    public val fiscalYear: Column<Int> = integer("fiscal_year")
    public val unitKey: Column<String> = varchar("unit_key", 48)
    public val regionalChapterId: Column<Uuid?> = optReference("regional_chapter_id", RegionalChapterTable.id)
    public val bookkeepingMode: Column<PartgBookkeepingMode> = enumerationByName<PartgBookkeepingMode>("bookkeeping_mode", 1)
    public val updatedAt: Column<LocalDateTime> = datetime("updated_at")
    public val updatedBy: Column<Uuid> = reference("updated_by", MemberTable.id)

    override val primaryKey: PrimaryKey = PrimaryKey(id)

    // Note: the CHECKs and the unique index (fiscal_year, unit_key) exist in SQL only.
}
