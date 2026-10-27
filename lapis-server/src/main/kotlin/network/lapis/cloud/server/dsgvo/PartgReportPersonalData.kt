package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.db.generated.PartgReportPositionTable
import network.lapis.cloud.server.db.generated.PartgReportSheetTable
import network.lapis.cloud.server.db.generated.PartgReportValueTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * V1.9.225 "Rechenschaftsbericht (PartG), R1" -- owns [PartgReportPositionTable], [PartgReportSheetTable] and [PartgReportValueTable]
 * (see `76-partg-report.kuml.kts` file header "DSGVO"). The only personal data is WHO changed WHAT: `partg_report_sheet.updated_by`,
 * `partg_report_value.updated_by` and `partg_report_position.org_hint_updated_by`.
 *
 * **Export** (Art. 15/20): "changed by you" -- year, reporting unit and position code (and the hint position), NEVER the amounts: the figures
 * belong to the organisation, not to the person.
 *
 * **Erasure: retained in every [ErasureMode].** The report values are the material of the report a party owes under section 24 PartG and must be
 * kept for ten years (section 24 (2) PartG, section 19a PartG); the foreign keys keep pointing to the member row, whose display is anonymised
 * (`member.anonymized_at`) like for every other retain-with-reason contributor. Art. 17 (3) (b) GDPR.
 */
object PartgReportPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "partyReport"
    override val displayName = "Rechenschaftsbericht (PartG)"
    override val coveredTables = setOf(PartgReportPositionTable, PartgReportSheetTable, PartgReportValueTable)

    override fun exportMember(memberId: Uuid) =
        buildJsonObject {
            val sheets =
                PartgReportSheetTable
                    .selectAll()
                    .toList()
                    .associateBy { it[PartgReportSheetTable.id] }
            put(
                "valuesChangedByYou",
                buildJsonArray {
                    PartgReportValueTable
                        .selectAll()
                        .where { PartgReportValueTable.updatedBy eq memberId }
                        .toList()
                        .sortedWith(compareBy({ it[PartgReportValueTable.updatedAt] }, { it[PartgReportValueTable.positionCode] }))
                        .forEach { row ->
                            val sheet = sheets[row[PartgReportValueTable.sheetId]]
                            add(
                                buildJsonObject {
                                    put("year", sheet?.get(PartgReportSheetTable.fiscalYear))
                                    put("unit", sheet?.get(PartgReportSheetTable.unitKey))
                                    put("position", row[PartgReportValueTable.positionCode])
                                    put("changedAt", row[PartgReportValueTable.updatedAt].toString())
                                },
                            )
                        }
                },
            )
            put(
                "sheetsChangedByYou",
                buildJsonArray {
                    sheets.values
                        .filter { it[PartgReportSheetTable.updatedBy] == memberId }
                        .sortedWith(compareBy({ it[PartgReportSheetTable.fiscalYear] }, { it[PartgReportSheetTable.unitKey] }))
                        .forEach { row ->
                            add(
                                buildJsonObject {
                                    put("year", row[PartgReportSheetTable.fiscalYear])
                                    put("unit", row[PartgReportSheetTable.unitKey])
                                    put("changedAt", row[PartgReportSheetTable.updatedAt].toString())
                                },
                            )
                        }
                },
            )
            put(
                "positionHintsChangedByYou",
                buildJsonArray {
                    PartgReportPositionTable
                        .selectAll()
                        .where { PartgReportPositionTable.orgHintUpdatedBy eq memberId }
                        .toList()
                        .sortedBy { it[PartgReportPositionTable.sortOrder] }
                        .forEach { row ->
                            add(
                                buildJsonObject {
                                    put("position", row[PartgReportPositionTable.code])
                                    put("changedAt", row[PartgReportPositionTable.orgHintUpdatedAt]?.toString())
                                },
                            )
                        }
                },
            )
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val reason =
            "§ 24 Abs. 2, § 19a PartG: Rechenschaftsbericht und Unterlagen sind 10 Jahre aufzubewahren -- " +
                "kein Wert wird geloescht, die Anzeige des Bearbeiters wird ueber das anonymisierte Mitglied aufgeloest."
        return listOf(
            TableErasureOutcome(
                table = "partg_report_sheet",
                rowsRetained =
                    PartgReportSheetTable
                        .selectAll()
                        .where { PartgReportSheetTable.updatedBy eq memberId }
                        .count()
                        .toInt(),
                retentionReason = reason,
            ),
            TableErasureOutcome(
                table = "partg_report_value",
                rowsRetained =
                    PartgReportValueTable
                        .selectAll()
                        .where { PartgReportValueTable.updatedBy eq memberId }
                        .count()
                        .toInt(),
                retentionReason = reason,
            ),
            TableErasureOutcome(
                table = "partg_report_position",
                rowsRetained =
                    PartgReportPositionTable
                        .selectAll()
                        .where { PartgReportPositionTable.orgHintUpdatedBy eq memberId }
                        .count()
                        .toInt(),
                retentionReason = reason,
            ),
        )
    }
}
