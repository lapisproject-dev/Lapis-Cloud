// Hand-written, mirroring the generated-file convention every other db/generated table in this package establishes -- kept in sync with
// 76-partg-report.kuml.kts by PartgReportSchemaDriftTest. V1.9.290 (R5).

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.PartgTransferKind
import network.lapis.cloud.shared.domain.PartgUnitLevel
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

public object PartgIntraTransferTable : Table("partg_intra_transfer") {
    public val id: Column<Uuid> = uuid("id")
    public val consolidationId: Column<Uuid> = reference("consolidation_id", PartgConsolidationSheetTable.id)
    public val transferKind: Column<PartgTransferKind> = enumerationByName<PartgTransferKind>("transfer_kind", 7)
    public val payerLevel: Column<PartgUnitLevel> = enumerationByName<PartgUnitLevel>("payer_level", 11)
    public val payerChapterId: Column<Uuid?> = optReference("payer_chapter_id", RegionalChapterTable.id)
    public val payerUnitKey: Column<String> = varchar("payer_unit_key", 48)
    public val payeeLevel: Column<PartgUnitLevel> = enumerationByName<PartgUnitLevel>("payee_level", 11)
    public val payeeChapterId: Column<Uuid?> = optReference("payee_chapter_id", RegionalChapterTable.id)
    public val payeeUnitKey: Column<String> = varchar("payee_unit_key", 48)
    public val payerAmountCents: Column<Long?> = long("payer_amount_cents").nullable()
    public val payeeAmountCents: Column<Long?> = long("payee_amount_cents").nullable()
    public val note: Column<String?> = varchar("note", 200).nullable()
    public val createdAt: Column<LocalDateTime> = datetime("created_at")
    public val createdBy: Column<Uuid> = reference("created_by", MemberTable.id)
    public val updatedAt: Column<LocalDateTime> = datetime("updated_at")
    public val updatedBy: Column<Uuid> = reference("updated_by", MemberTable.id)

    override val primaryKey: PrimaryKey = PrimaryKey(id)

    // Note: the CHECKs and the unique index (consolidation_id, transfer_kind, payer_unit_key, payee_unit_key) exist in SQL only.
}
