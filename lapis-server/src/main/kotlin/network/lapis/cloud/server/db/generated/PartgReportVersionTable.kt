// Hand-written, mirroring the generated-file convention every other db/generated table in this package establishes -- kept in sync with
// 76-partg-report.kuml.kts by PartgReportSchemaDriftTest. V1.9.291 (R6).

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

public object PartgReportVersionTable : Table("partg_report_version") {
    public val id: Column<Uuid> = uuid("id")
    public val counterId: Column<Uuid> = reference("counter_id", PartgReportVersionCounterTable.id)
    public val fiscalYear: Column<Int> = integer("fiscal_year")
    public val scopeKey: Column<String> = varchar("scope_key", 48)
    public val versionNumber: Column<Int> = integer("version_number")
    public val formatVersion: Column<Int> = integer("format_version")
    public val contentJson: Column<String> = text("content_json")
    public val contentHash: Column<String> = varchar("content_hash", 64)
    public val byteSize: Column<Int> = integer("byte_size")
    public val hasThirdPartyData: Column<Boolean> = bool("has_third_party_data")
    public val comment: Column<String?> = varchar("comment", 500).nullable()
    public val frozenAt: Column<LocalDateTime> = datetime("frozen_at")
    public val frozenBy: Column<Uuid> = reference("frozen_by", MemberTable.id)
    public val finalizedAt: Column<LocalDateTime?> = datetime("finalized_at").nullable()
    public val finalizedBy: Column<Uuid?> = optReference("finalized_by", MemberTable.id)

    override val primaryKey: PrimaryKey = PrimaryKey(id)

    // Note: the CHECKs and the unique index (fiscal_year, scope_key, version_number) exist in SQL only.
}
