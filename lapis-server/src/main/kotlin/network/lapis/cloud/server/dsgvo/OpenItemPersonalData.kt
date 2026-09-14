package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.db.generated.OpenItemNettingTable
import network.lapis.cloud.server.db.generated.OpenItemSettlementTable
import network.lapis.cloud.server.db.generated.OpenItemTable
import network.lapis.cloud.server.db.generated.ReceivableDunningNoticeTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle V1.4.15 "Kreditoren-/Debitorenbuchhaltung". Owns the actor-role member-FKs on
 * [OpenItemTable] (`created_by_member_id`/`cancelled_by_member_id`), [OpenItemNettingTable]
 * (`created_by_member_id`/`reversed_by_member_id`), [OpenItemSettlementTable] (`created_by_member_id`/
 * `reversed_by_member_id`), and [ReceivableDunningNoticeTable] (`created_by_member_id`, nullable --
 * the poller's own automated issuances leave it `null`).
 *
 * **Retained on erasure regardless of [ErasureMode]**, same accounting-retention override
 * [DunningPersonalData]/[ContributionPersonalData] already apply -- every one of these rows is
 * part of the organization's GoBD/§147 AO Buchungshistorie (a `creation_journal_entry_id`/
 * `journal_entry_id` traces straight to a `journal_entry`, 10 Jahre Aufbewahrungspflicht). Only the
 * free-text `note`/`cancellation_reason`/`reversal_reason` columns (may name a person) are cleared
 * for rows where the erased member is the ACTOR -- same "export/erase symmetry, redact only free
 * text" discipline [DunningPersonalData.cancellationReason] already establishes.
 *
 * **`open_item.counterparty_name` is NOT covered here** -- it is PII of a NON-member counterparty,
 * not of the [Uuid] this contributor is erasing. `open_item` is therefore ALSO listed in
 * [PersonalDataRegistry.nonMemberPiiTables]/[PersonalDataRegistry.knownUncoveredSubjectRoots],
 * literally the same `bank_statement_line` pattern that documentation entry's own reason string
 * describes: a Nicht-Mitglied-PII table with a 10-Jahre GoBD/AO retention that makes deletion moot
 * anyway. Sichtbar gemacht, nicht geschlossen (same posture, not a new decision).
 */
object OpenItemPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "openItems"
    override val displayName = "Offene Posten"
    override val coveredTables = setOf(OpenItemTable, OpenItemNettingTable, OpenItemSettlementTable, ReceivableDunningNoticeTable)

    override fun exportMember(memberId: Uuid) =
        buildJsonObject {
            put(
                "openItems",
                buildJsonArray {
                    OpenItemTable
                        .selectAll()
                        .where { (OpenItemTable.createdByMemberId eq memberId) or (OpenItemTable.cancelledByMemberId eq memberId) }
                        .forEach { row ->
                            add(
                                buildJsonObject {
                                    put("id", row[OpenItemTable.id].toString())
                                    put("direction", row[OpenItemTable.direction].name)
                                    put("status", row[OpenItemTable.status].name)
                                    put("amount", row[OpenItemTable.amount].toPlainString())
                                    put("createdByMemberId", row[OpenItemTable.createdByMemberId].toString())
                                    put("cancelledByMemberId", row[OpenItemTable.cancelledByMemberId]?.toString())
                                },
                            )
                        }
                },
            )
            put(
                "settlements",
                buildJsonArray {
                    OpenItemSettlementTable
                        .selectAll()
                        .where {
                            (OpenItemSettlementTable.createdByMemberId eq memberId) or
                                (OpenItemSettlementTable.reversedByMemberId eq memberId)
                        }.forEach { row ->
                            add(
                                buildJsonObject {
                                    put("id", row[OpenItemSettlementTable.id].toString())
                                    put("kind", row[OpenItemSettlementTable.kind].name)
                                    put("amount", row[OpenItemSettlementTable.amount].toPlainString())
                                    put("createdByMemberId", row[OpenItemSettlementTable.createdByMemberId].toString())
                                    put("reversedByMemberId", row[OpenItemSettlementTable.reversedByMemberId]?.toString())
                                },
                            )
                        }
                },
            )
            put(
                "nettings",
                buildJsonArray {
                    OpenItemNettingTable
                        .selectAll()
                        .where {
                            (OpenItemNettingTable.createdByMemberId eq memberId) or
                                (OpenItemNettingTable.reversedByMemberId eq memberId)
                        }.forEach { row ->
                            add(
                                buildJsonObject {
                                    put("id", row[OpenItemNettingTable.id].toString())
                                    put("amount", row[OpenItemNettingTable.amount].toPlainString())
                                    put("createdByMemberId", row[OpenItemNettingTable.createdByMemberId].toString())
                                    put("reversedByMemberId", row[OpenItemNettingTable.reversedByMemberId]?.toString())
                                },
                            )
                        }
                },
            )
            put(
                "receivableDunningNotices",
                buildJsonArray {
                    ReceivableDunningNoticeTable
                        .selectAll()
                        .where { ReceivableDunningNoticeTable.createdByMemberId eq memberId }
                        .forEach { row ->
                            add(
                                buildJsonObject {
                                    put("id", row[ReceivableDunningNoticeTable.id].toString())
                                    put("levelNumber", row[ReceivableDunningNoticeTable.levelNumber])
                                    put("status", row[ReceivableDunningNoticeTable.status].name)
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
        val openItemIds =
            OpenItemTable
                .selectAll()
                .where { (OpenItemTable.createdByMemberId eq memberId) or (OpenItemTable.cancelledByMemberId eq memberId) }
                .map { it[OpenItemTable.id] }
        if (openItemIds.isNotEmpty()) {
            OpenItemTable.update({ (OpenItemTable.id inList openItemIds) and (OpenItemTable.cancelledByMemberId eq memberId) }) {
                it[cancellationReason] = null
            }
        }

        val settlementIds =
            OpenItemSettlementTable
                .selectAll()
                .where {
                    (OpenItemSettlementTable.createdByMemberId eq memberId) or (OpenItemSettlementTable.reversedByMemberId eq memberId)
                }.map { it[OpenItemSettlementTable.id] }
        if (settlementIds.isNotEmpty()) {
            OpenItemSettlementTable.update({
                (OpenItemSettlementTable.id inList settlementIds) and
                    (OpenItemSettlementTable.reversedByMemberId eq memberId)
            }) {
                it[reversalReason] = null
            }
        }

        val nettingIds =
            OpenItemNettingTable
                .selectAll()
                .where { (OpenItemNettingTable.createdByMemberId eq memberId) or (OpenItemNettingTable.reversedByMemberId eq memberId) }
                .map { it[OpenItemNettingTable.id] }
        if (nettingIds.isNotEmpty()) {
            OpenItemNettingTable.update({
                (OpenItemNettingTable.id inList nettingIds) and
                    (OpenItemNettingTable.reversedByMemberId eq memberId)
            }) {
                it[reversalReason] = null
            }
        }

        val noticeCount =
            ReceivableDunningNoticeTable
                .selectAll()
                .where { ReceivableDunningNoticeTable.createdByMemberId eq memberId }
                .count()

        return listOf(
            TableErasureOutcome(
                table = "open_item",
                rowsRetained = openItemIds.size,
                retentionReason = "Handelsrechtliche Aufbewahrungspflicht (GoBD/HGB/AO, 10 Jahre) -- Teil der Buchungshistorie.",
            ),
            TableErasureOutcome(
                table = "open_item_settlement",
                rowsRetained = settlementIds.size,
                retentionReason = "Handelsrechtliche Aufbewahrungspflicht (GoBD/HGB/AO, 10 Jahre) -- Teil der Buchungshistorie.",
            ),
            TableErasureOutcome(
                table = "open_item_netting",
                rowsRetained = nettingIds.size,
                retentionReason = "Handelsrechtliche Aufbewahrungspflicht (GoBD/HGB/AO, 10 Jahre) -- Teil der Buchungshistorie.",
            ),
            TableErasureOutcome(
                table = "receivable_dunning_notice",
                rowsRetained = noticeCount.toInt(),
                retentionReason = "Handelsrechtliche Aufbewahrungspflicht (GoBD/HGB/AO, 10 Jahre) -- Teil der Buchungshistorie.",
            ),
        )
    }
}
