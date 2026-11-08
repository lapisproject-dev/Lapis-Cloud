// Hand-written, mirroring the generated-file convention every other db/generated table in this package establishes -- kept in sync with
// 76-partg-report.kuml.kts by PartgReportSchemaDriftTest. V1.9.290 (R5).

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

public object PartgConsolidationSheetTable : Table("partg_consolidation_sheet") {
    public val id: Column<Uuid> = uuid("id")
    public val fiscalYear: Column<Int> = integer("fiscal_year")
    public val revision: Column<Long> = long("revision")
    public val updatedAt: Column<LocalDateTime> = datetime("updated_at")
    public val updatedBy: Column<Uuid> = reference("updated_by", MemberTable.id)

    override val primaryKey: PrimaryKey = PrimaryKey(id)

    // Note: the CHECKs and the unique index (fiscal_year) exist in SQL only.
}
