// Hand-written, mirroring the generated-file convention every other db/generated table in this package establishes -- kept in sync with
// 76-partg-report.kuml.kts by PartgReportSchemaDriftTest. V1.9.291 (R6).

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

public object PartgReportVersionCounterTable : Table("partg_report_version_counter") {
    public val id: Column<Uuid> = uuid("id")
    public val fiscalYear: Column<Int> = integer("fiscal_year")
    public val scopeKey: Column<String> = varchar("scope_key", 48)
    public val lastNumber: Column<Int> = integer("last_number")
    public val updatedAt: Column<LocalDateTime> = datetime("updated_at")
    public val updatedBy: Column<Uuid> = reference("updated_by", MemberTable.id)

    override val primaryKey: PrimaryKey = PrimaryKey(id)

    // Note: the CHECKs and the unique index (fiscal_year, scope_key) exist in SQL only.
}
