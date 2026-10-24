package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.TenderAcceptanceTable
import network.lapis.cloud.server.db.generated.TenderAwardTable
import network.lapis.cloud.server.db.generated.TenderBidTable
import network.lapis.cloud.server.db.generated.TenderTable
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.TenderBidStatus
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Owns [TenderTable]/[TenderBidTable]/[TenderAwardTable]/[TenderAcceptanceTable] (V1.9.201 tenders). The member columns are `tender.created_by` /
 * `published_by` / `cancelled_by`, `tender_bid.bidder_member_id`, `tender_award.awarded_by` and `tender_acceptance.decided_by`; guests are members
 * (status GUEST) and covered by the same member subject.
 *
 * Erasure, per table:
 *  * `tender_bid` of a bid that did NOT win: the free text is replaced by a fixed marker, the price and the status stay (the ranking and the
 *    outcome of the tender must stay explainable); the name leaves with the anonymised member row.
 *  * a winning bid, the award and the acceptances are the evidence of a decision that other people relied on (the board's, the committee's, the
 *    other bidders'): retained, the name leaves with the anonymised member row. Same reasoning as the auction and the resolution book.
 *  * `tender` itself carries no text about the member beyond the created/published/cancelled-by ids: retained.
 * The export lists the member's own bids (title of the tender, price, text, status), the tenders they created/published and the decisions they took.
 */
object TenderPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "tenders"
    override val displayName = "Ausschreibungen"
    override val coveredTables = setOf(TenderTable, TenderBidTable, TenderAwardTable, TenderAcceptanceTable)

    /** Replaces the text of a bid that did not win when its bidder is erased. */
    const val ERASED_BID_TEXT = "[erased]"

    override fun exportMember(memberId: Uuid) =
        buildJsonObject {
            putJsonArray("bids") {
                TenderBidTable
                    .selectAll()
                    .where { TenderBidTable.bidderMemberId eq memberId }
                    .orderBy(TenderBidTable.submittedAt, SortOrder.ASC)
                    .forEach { row ->
                        val tender = TenderTable.selectAll().where { TenderTable.id eq row[TenderBidTable.tenderId] }.singleOrNull()
                        add(
                            buildJsonObject {
                                put("id", row[TenderBidTable.id].toString())
                                put("tenderId", row[TenderBidTable.tenderId].toString())
                                put("tenderTitle", tender?.get(TenderTable.title))
                                put("price", row[TenderBidTable.price].toPlainString())
                                put("currency", row[TenderBidTable.currency].name)
                                put("text", row[TenderBidTable.text])
                                put("status", row[TenderBidTable.status].name)
                                put("submittedAt", row[TenderBidTable.submittedAt].toString())
                            },
                        )
                    }
            }
            putJsonArray("tendersManaged") {
                TenderTable
                    .selectAll()
                    .where {
                        (TenderTable.createdBy eq memberId) or (TenderTable.publishedBy eq memberId) or
                            (TenderTable.cancelledBy eq memberId)
                    }.orderBy(TenderTable.id, SortOrder.ASC)
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[TenderTable.id].toString())
                                put("title", row[TenderTable.title])
                                put("status", row[TenderTable.status].name)
                                put("createdByMe", row[TenderTable.createdBy] == memberId)
                                put("publishedByMe", row[TenderTable.publishedBy] == memberId)
                                put("cancelledByMe", row[TenderTable.cancelledBy] == memberId)
                            },
                        )
                    }
            }
            putJsonArray("awardsDecided") {
                TenderAwardTable
                    .selectAll()
                    .where { TenderAwardTable.awardedBy eq memberId }
                    .orderBy(TenderAwardTable.id, SortOrder.ASC)
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("tenderId", row[TenderAwardTable.tenderId].toString())
                                put("bidId", row[TenderAwardTable.bidId].toString())
                                put("awardedAt", row[TenderAwardTable.awardedAt].toString())
                            },
                        )
                    }
            }
            putJsonArray("acceptancesDecided") {
                TenderAcceptanceTable
                    .selectAll()
                    .where { TenderAcceptanceTable.decidedBy eq memberId }
                    .orderBy(TenderAcceptanceTable.id, SortOrder.ASC)
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("tenderId", row[TenderAcceptanceTable.tenderId].toString())
                                put("decision", row[TenderAcceptanceTable.decision].name)
                                put("decidedAt", row[TenderAcceptanceTable.decidedAt].toString())
                            },
                        )
                    }
            }
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val ownBids = TenderBidTable.selectAll().where { TenderBidTable.bidderMemberId eq memberId }.toList()
        val erased =
            TenderBidTable.update({
                (TenderBidTable.bidderMemberId eq memberId) and (TenderBidTable.status neq TenderBidStatus.AWARDED)
            }) {
                it[text] = ERASED_BID_TEXT
            }
        val tenders =
            TenderTable
                .selectAll()
                .where {
                    (TenderTable.createdBy eq memberId) or (TenderTable.publishedBy eq memberId) or (TenderTable.cancelledBy eq memberId)
                }.count()
        val awards = TenderAwardTable.selectAll().where { TenderAwardTable.awardedBy eq memberId }.count()
        val acceptances = TenderAcceptanceTable.selectAll().where { TenderAcceptanceTable.decidedBy eq memberId }.count()
        return listOf(
            TableErasureOutcome(
                table = "tender_bid",
                rowsAnonymized = erased,
                rowsRetained = ownBids.size,
                retentionReason =
                    "The text of a bid that did not win was replaced; price, status and the row stay so that the ranking and the " +
                        "outcome of the tender remain explainable. A winning bid is the evidence of an award other people relied on " +
                        "and is kept unchanged. The name leaves with the anonymised member row.",
            ),
            TableErasureOutcome(
                table = "tender",
                rowsRetained = tenders.toInt(),
                retentionReason =
                    "A tender is a decision record of the organisation; it names the member only by id (created/published/cancelled by).",
            ),
            TableErasureOutcome(
                table = "tender_award",
                rowsRetained = awards.toInt(),
                retentionReason =
                    "The award is the evidence of a decision other people relied on (the winner, the other bidders, the committee).",
            ),
            TableErasureOutcome(
                table = "tender_acceptance",
                rowsRetained = acceptances.toInt(),
                retentionReason = "The acceptance is the evidence of the board's decision on the delivered work.",
            ),
        )
    }
}
