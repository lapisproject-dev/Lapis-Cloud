// Hand-written, mirroring the generated-file convention every other db/generated table in this package establishes -- kept in sync with
// 76-partg-report.kuml.kts by PartgReportSchemaDriftTest. V1.9.290 (R5).

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

public object PartgDonorCombinationTable : Table("partg_donor_combination") {
    public val id: Column<Uuid> = uuid("id")
    public val consolidationId: Column<Uuid> = reference("consolidation_id", PartgConsolidationSheetTable.id)
    public val createdAt: Column<LocalDateTime> = datetime("created_at")
    public val createdBy: Column<Uuid> = reference("created_by", MemberTable.id)

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
