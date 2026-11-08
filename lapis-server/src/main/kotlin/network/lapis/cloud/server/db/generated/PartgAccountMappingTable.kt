// Hand-written, mirroring the generated-file convention every other db/generated table in this package establishes -- kept in sync with
// 76-partg-report.kuml.kts by PartgReportSchemaDriftTest. V1.9.289 (R4a).

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

public object PartgAccountMappingTable : Table("partg_account_mapping") {
    public val id: Column<Uuid> = uuid("id")
    public val ledgerAccountId: Column<Uuid> = reference("ledger_account_id", LedgerAccountTable.id)

    // Foreign key to partg_report_position.code (natural key, SQL only); NULL iff `excluded`.
    public val positionCode: Column<String?> = varchar("position_code", 16).nullable()
    public val excluded: Column<Boolean> = bool("excluded")
    public val updatedAt: Column<LocalDateTime> = datetime("updated_at")
    public val updatedBy: Column<Uuid> = reference("updated_by", MemberTable.id)

    override val primaryKey: PrimaryKey = PrimaryKey(id)

    // Note: unique constraint uq_partg_account_mapping_account (ledger_account_id) and chk_partg_mapping_target exist in SQL only.
}
