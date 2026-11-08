// Hand-written, mirroring the generated-file convention every other db/generated table in this package establishes -- kept in sync with
// 76-partg-report.kuml.kts by PartgReportSchemaDriftTest. V1.9.290 (R5).

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table

public object PartgDonorCombinationRowTable : Table("partg_donor_combination_row") {
    public val id: Column<Uuid> = uuid("id")
    public val combinationId: Column<Uuid> = reference("combination_id", PartgDonorCombinationTable.id)
    public val largeDonorId: Column<Uuid> = reference("large_donor_id", PartgReportLargeDonorTable.id)

    override val primaryKey: PrimaryKey = PrimaryKey(id)

    // Note: the unique constraint on large_donor_id (one row belongs to at most one group) exists in SQL only.
}
