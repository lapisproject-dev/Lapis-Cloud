package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Owns [LtrLedgerEntryTable] — the V0.6.1 real LTR ledger (see that table's KDoc; replaces the
 * V0.2.3-era `ltr_balance` snapshot this contributor previously covered). Retain-with-reason,
 * mirroring [ContributionPersonalData]'s precedent: an LTR ledger entry is both the member's own
 * property record and, once bound as a `PROJECT_STAKE`, part of a Crowdfunding project's
 * historical visibility-weight record other members relied on — anonymizing or deleting it on
 * erasure would either destroy a member's earned/spent property or corrupt that shared history.
 * `member_id`/`created_by` stay intact and now resolve to the anonymized
 * [network.lapis.cloud.server.db.generated.MemberTable] row post-erasure, same as every other
 * retain-with-reason contributor.
 */
object LtrPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "ltrLedger"
    override val displayName = "LTR-Konto"
    override val coveredTables = setOf(LtrLedgerEntryTable)

    override fun exportMember(memberId: Uuid) =
        buildJsonObject {
            putJsonArray("ledgerEntries") {
                LtrLedgerEntryTable
                    .selectAll()
                    .where { LtrLedgerEntryTable.memberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[LtrLedgerEntryTable.id].toString())
                                put("entryType", row[LtrLedgerEntryTable.entryType].name)
                                put("amountLtr", row[LtrLedgerEntryTable.amountLtr].toPlainString())
                                put("referenceType", row[LtrLedgerEntryTable.referenceType]?.name)
                                put("referenceId", row[LtrLedgerEntryTable.referenceId]?.toString())
                                put("note", row[LtrLedgerEntryTable.note])
                                put("createdAt", row[LtrLedgerEntryTable.createdAt].toString())
                            },
                        )
                    }
            }
            putJsonArray("ledgerEntriesMinted") {
                LtrLedgerEntryTable
                    .selectAll()
                    .where { LtrLedgerEntryTable.createdBy eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[LtrLedgerEntryTable.id].toString())
                                put("beneficiaryMemberId", row[LtrLedgerEntryTable.memberId].toString())
                                put("amountLtr", row[LtrLedgerEntryTable.amountLtr].toPlainString())
                                put("createdAt", row[LtrLedgerEntryTable.createdAt].toString())
                            },
                        )
                    }
            }
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val total =
            LtrLedgerEntryTable
                .selectAll()
                .where { (LtrLedgerEntryTable.memberId eq memberId) or (LtrLedgerEntryTable.createdBy eq memberId) }
                .count()
        return listOf(
            TableErasureOutcome(
                table = "ltr_ledger_entry",
                rowsRetained = total.toInt(),
                retentionReason =
                    "Der LTR-Kontostand ist ein Eigentumsnachweis des Mitglieds und -- sobald ein " +
                        "Eintrag als PROJECT_STAKE gebunden ist -- Grundlage der Sichtbarkeits-" +
                        "Historie eines Crowdfunding-Projekts, auf die andere Mitglieder sich " +
                        "verlassen. Loeschen wuerde Eigentum vernichten bzw. diese gemeinsame " +
                        "Historie beeintraechtigen.",
            ),
        )
    }
}
