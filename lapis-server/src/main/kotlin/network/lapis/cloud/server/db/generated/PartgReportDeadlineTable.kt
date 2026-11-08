// Hand-written, mirroring the generated-file convention every other db/generated table in this package establishes -- kept in sync with
// 76-partg-report.kuml.kts by PartgReportSchemaDriftTest. V1.9.291 (R6).

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.date
import org.jetbrains.exposed.v1.datetime.datetime

public object PartgReportDeadlineTable : Table("partg_report_deadline") {
    public val id: Column<Uuid> = uuid("id")
    public val fiscalYear: Column<Int> = integer("fiscal_year")
    public val deadline: Column<LocalDate> = date("deadline")
    public val confirmedAt: Column<LocalDateTime> = datetime("confirmed_at")
    public val confirmedBy: Column<Uuid> = reference("confirmed_by", MemberTable.id)

    override val primaryKey: PrimaryKey = PrimaryKey(id)

    // Note: the CHECKs and the unique index (fiscal_year) exist in SQL only.
}
