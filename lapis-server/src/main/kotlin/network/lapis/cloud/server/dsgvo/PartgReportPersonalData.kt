package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.db.generated.PartgReportAnnexTable
import network.lapis.cloud.server.db.generated.PartgReportHoldingTable
import network.lapis.cloud.server.db.generated.PartgReportLargeDonorTable
import network.lapis.cloud.server.db.generated.PartgReportNoteTable
import network.lapis.cloud.server.db.generated.PartgReportNotesSheetTable
import network.lapis.cloud.server.db.generated.PartgReportPositionTable
import network.lapis.cloud.server.db.generated.PartgReportSheetTable
import network.lapis.cloud.server.db.generated.PartgReportSponsorTable
import network.lapis.cloud.server.db.generated.PartgReportSponsoringEntryTable
import network.lapis.cloud.server.db.generated.PartgReportValueTable
import network.lapis.cloud.server.db.generated.PartgReportYouthOrgTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
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
 *
 * **V1.9.269 (R3)** adds `partg_report_annex`, `partg_report_large_donor`, `partg_report_notes_sheet`, `partg_report_note` and
 * `partg_report_holding`. Their member foreign keys (`updated_by`, `created_by`) are exported as "changed by you" with year, unit and kind of
 * change only -- NEVER a name, an address, an amount or a note text -- and retained on erasure for the same reason. The large-donor rows
 * themselves are personal data of THIRD PARTIES without a member key; that gap is listed in `PersonalDataRegistry.knownUncoveredSubjectRoots`.
 *
 * **V1.9.283 (R3b)** adds `partg_report_sponsor`, `partg_report_sponsoring_entry` and `partg_report_youth_org`: same treatment (member foreign keys
 * exported as "changed by you" with year, unit and time only -- no name, no amount, no purpose; retained on erasure). Sponsor and sponsoring entry
 * are personal data of THIRD PARTIES without a member key (listed in `knownUncoveredSubjectRoots`); the youth organisation holds organisation data.
 */
object PartgReportPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "partyReport"
    override val displayName = "Rechenschaftsbericht (PartG)"
    override val coveredTables =
        setOf(
            PartgReportPositionTable,
            PartgReportSheetTable,
            PartgReportValueTable,
            PartgReportAnnexTable,
            PartgReportLargeDonorTable,
            PartgReportNotesSheetTable,
            PartgReportNoteTable,
            PartgReportHoldingTable,
            PartgReportSponsorTable,
            PartgReportSponsoringEntryTable,
            PartgReportYouthOrgTable,
        )

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
                "annexChangedByYou",
                buildJsonArray {
                    PartgReportAnnexTable
                        .selectAll()
                        .where { PartgReportAnnexTable.updatedBy eq memberId }
                        .toList()
                        .sortedWith(compareBy({ it[PartgReportAnnexTable.fiscalYear] }, { it[PartgReportAnnexTable.unitKey] }))
                        .forEach { row ->
                            add(
                                buildJsonObject {
                                    put("year", row[PartgReportAnnexTable.fiscalYear])
                                    put("unit", row[PartgReportAnnexTable.unitKey])
                                    put("changedAt", row[PartgReportAnnexTable.updatedAt].toString())
                                },
                            )
                        }
                },
            )
            put(
                "largeDonorRowsChangedByYou",
                buildJsonArray {
                    val annexes = PartgReportAnnexTable.selectAll().toList().associateBy { it[PartgReportAnnexTable.id] }
                    PartgReportLargeDonorTable
                        .selectAll()
                        .where { (PartgReportLargeDonorTable.updatedBy eq memberId) or (PartgReportLargeDonorTable.createdBy eq memberId) }
                        .toList()
                        .sortedWith(
                            compareBy({ it[PartgReportLargeDonorTable.updatedAt] }, { it[PartgReportLargeDonorTable.id].toString() }),
                        ).forEach { row ->
                            val annex = annexes[row[PartgReportLargeDonorTable.annexId]]
                            add(
                                buildJsonObject {
                                    put("year", annex?.get(PartgReportAnnexTable.fiscalYear))
                                    put("unit", annex?.get(PartgReportAnnexTable.unitKey))
                                    put("changedAt", row[PartgReportLargeDonorTable.updatedAt].toString())
                                },
                            )
                        }
                },
            )
            val annexById = PartgReportAnnexTable.selectAll().toList().associateBy { it[PartgReportAnnexTable.id] }
            put(
                "sponsorsChangedByYou",
                buildJsonArray {
                    PartgReportSponsorTable
                        .selectAll()
                        .where { (PartgReportSponsorTable.updatedBy eq memberId) or (PartgReportSponsorTable.createdBy eq memberId) }
                        .toList()
                        .sortedWith(compareBy({ it[PartgReportSponsorTable.updatedAt] }, { it[PartgReportSponsorTable.id].toString() }))
                        .forEach { row ->
                            val annex = annexById[row[PartgReportSponsorTable.annexId]]
                            add(
                                buildJsonObject {
                                    put("year", annex?.get(PartgReportAnnexTable.fiscalYear))
                                    put("unit", annex?.get(PartgReportAnnexTable.unitKey))
                                    put("changedAt", row[PartgReportSponsorTable.updatedAt].toString())
                                },
                            )
                        }
                },
            )
            put(
                "sponsoringEntriesChangedByYou",
                buildJsonArray {
                    val sponsorAnnex =
                        PartgReportSponsorTable.selectAll().toList().associate {
                            it[PartgReportSponsorTable.id] to annexById[it[PartgReportSponsorTable.annexId]]
                        }
                    PartgReportSponsoringEntryTable
                        .selectAll()
                        .where {
                            (PartgReportSponsoringEntryTable.updatedBy eq memberId) or
                                (PartgReportSponsoringEntryTable.createdBy eq memberId)
                        }.toList()
                        .sortedWith(
                            compareBy(
                                { it[PartgReportSponsoringEntryTable.updatedAt] },
                                { it[PartgReportSponsoringEntryTable.id].toString() },
                            ),
                        ).forEach { row ->
                            val annex = sponsorAnnex[row[PartgReportSponsoringEntryTable.sponsorId]]
                            add(
                                buildJsonObject {
                                    put("year", annex?.get(PartgReportAnnexTable.fiscalYear))
                                    put("unit", annex?.get(PartgReportAnnexTable.unitKey))
                                    put("changedAt", row[PartgReportSponsoringEntryTable.updatedAt].toString())
                                },
                            )
                        }
                },
            )
            put(
                "youthOrgsChangedByYou",
                buildJsonArray {
                    PartgReportYouthOrgTable
                        .selectAll()
                        .where { (PartgReportYouthOrgTable.updatedBy eq memberId) or (PartgReportYouthOrgTable.createdBy eq memberId) }
                        .toList()
                        .sortedWith(compareBy({ it[PartgReportYouthOrgTable.updatedAt] }, { it[PartgReportYouthOrgTable.id].toString() }))
                        .forEach { row ->
                            val annex = annexById[row[PartgReportYouthOrgTable.annexId]]
                            add(
                                buildJsonObject {
                                    put("year", annex?.get(PartgReportAnnexTable.fiscalYear))
                                    put("unit", annex?.get(PartgReportAnnexTable.unitKey))
                                    put("changedAt", row[PartgReportYouthOrgTable.updatedAt].toString())
                                },
                            )
                        }
                },
            )
            put(
                "notesChangedByYou",
                buildJsonArray {
                    PartgReportNotesSheetTable
                        .selectAll()
                        .where { PartgReportNotesSheetTable.updatedBy eq memberId }
                        .toList()
                        .sortedBy { it[PartgReportNotesSheetTable.fiscalYear] }
                        .forEach { row ->
                            add(
                                buildJsonObject {
                                    put("year", row[PartgReportNotesSheetTable.fiscalYear])
                                    put("changedAt", row[PartgReportNotesSheetTable.updatedAt].toString())
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
                table = "partg_report_annex",
                rowsRetained =
                    PartgReportAnnexTable
                        .selectAll()
                        .where { PartgReportAnnexTable.updatedBy eq memberId }
                        .count()
                        .toInt(),
                retentionReason = reason,
            ),
            TableErasureOutcome(
                table = "partg_report_large_donor",
                rowsRetained =
                    PartgReportLargeDonorTable
                        .selectAll()
                        .where { (PartgReportLargeDonorTable.updatedBy eq memberId) or (PartgReportLargeDonorTable.createdBy eq memberId) }
                        .count()
                        .toInt(),
                retentionReason = reason,
            ),
            TableErasureOutcome(
                table = "partg_report_sponsor",
                rowsRetained =
                    PartgReportSponsorTable
                        .selectAll()
                        .where { (PartgReportSponsorTable.updatedBy eq memberId) or (PartgReportSponsorTable.createdBy eq memberId) }
                        .count()
                        .toInt(),
                retentionReason = reason,
            ),
            TableErasureOutcome(
                table = "partg_report_sponsoring_entry",
                rowsRetained =
                    PartgReportSponsoringEntryTable
                        .selectAll()
                        .where {
                            (PartgReportSponsoringEntryTable.updatedBy eq memberId) or
                                (PartgReportSponsoringEntryTable.createdBy eq memberId)
                        }.count()
                        .toInt(),
                retentionReason = reason,
            ),
            TableErasureOutcome(
                table = "partg_report_youth_org",
                rowsRetained =
                    PartgReportYouthOrgTable
                        .selectAll()
                        .where { (PartgReportYouthOrgTable.updatedBy eq memberId) or (PartgReportYouthOrgTable.createdBy eq memberId) }
                        .count()
                        .toInt(),
                retentionReason = reason,
            ),
            TableErasureOutcome(
                table = "partg_report_notes_sheet",
                rowsRetained =
                    PartgReportNotesSheetTable
                        .selectAll()
                        .where { PartgReportNotesSheetTable.updatedBy eq memberId }
                        .count()
                        .toInt(),
                retentionReason = reason,
            ),
            TableErasureOutcome(
                table = "partg_report_note",
                rowsRetained =
                    PartgReportNoteTable
                        .selectAll()
                        .where { PartgReportNoteTable.updatedBy eq memberId }
                        .count()
                        .toInt(),
                retentionReason = reason,
            ),
            TableErasureOutcome(
                table = "partg_report_holding",
                rowsRetained =
                    PartgReportHoldingTable
                        .selectAll()
                        .where { PartgReportHoldingTable.updatedBy eq memberId }
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
