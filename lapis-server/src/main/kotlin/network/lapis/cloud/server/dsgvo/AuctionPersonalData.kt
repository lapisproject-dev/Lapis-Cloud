package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.AuctionBidTable
import network.lapis.cloud.server.db.generated.AuctionComplianceAcknowledgmentTable
import network.lapis.cloud.server.db.generated.AuctionFiatAcknowledgmentTable
import network.lapis.cloud.server.db.generated.AuctionPaymentDetailsTable
import network.lapis.cloud.server.db.generated.AuctionTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Owns [AuctionTable]/[AuctionBidTable]/[AuctionComplianceAcknowledgmentTable] (V0.6.2
 * LTR-Auktion). Same three-table shape [CrowdfundingPersonalData]/[PoliticianPersonalData]
 * already establish for their own domains.
 *
 * [AuctionTable] alone has TWO member FKs (`seller_member_id`, `winner_member_id`) -- both checked
 * in [export]/[erase], same "actor plus subject(s)" shape [PeerTransferPersonalData]/
 * [PoliticianPersonalData] already establish.
 *
 * Retain-with-reason across the board, same precedent as [PeerTransferPersonalData]: an auction
 * listing is a completed (or still-running) marketplace transaction record, simultaneously the
 * seller's AND the winning bidder's own property/booking record -- anonymizing or deleting it on
 * erasure would corrupt that shared, mutually-relied-upon transaction history for whichever party
 * did NOT request erasure. A bid is part of the same shared, auditable second-price computation
 * every OTHER bidder's outcome depends on (same reasoning [PoliticianPersonalData] gives for
 * `politician_reaction`). The acknowledgment row is the ADMIN's own compliance-attestation record
 * (Art. 5(2) DSGVO accountability basis), same class as `processing_agreement.created_by`. The
 * corresponding `ltr_ledger_entry` rows (AUCTION_LISTING_FEE/AUCTION_HOLD/AUCTION_HOLD_RELEASE/
 * AUCTION_SALE_OUT/AUCTION_SALE_IN) are already covered by [LtrPersonalData] and not duplicated
 * here.
 *
 * **V1.9.320 Fiat auctions.** Two more tables: `auction_payment_details` (the seller's SEALED holder/IBAN/link) and `auction_fiat_acknowledgment`
 * (who acknowledged which Fiat disclaimer). The sealed ciphertext is NEVER exported (same rule as every other `*_ciphertext` column of this
 * codebase, see [PaymentsPersonalData]); the export names only that details are stored (and whether an IBAN/link is part of them). On erasure of a
 * SELLER the payment details of all his auctions are deleted at once -- they are only useful while the auction runs, and the retained auction row
 * (shared transaction record) does not need them. The acknowledgments are retained like [AuctionComplianceAcknowledgmentTable]: accountability
 * (Art. 5(2) DSGVO).
 */
object AuctionPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "auction"
    override val displayName = "LTR-Auktion"
    override val coveredTables =
        setOf(
            AuctionTable,
            AuctionBidTable,
            AuctionComplianceAcknowledgmentTable,
            AuctionPaymentDetailsTable,
            AuctionFiatAcknowledgmentTable,
        )

    override fun exportMember(memberId: Uuid) =
        buildJsonObject {
            putJsonArray("auctionsSold") {
                AuctionTable
                    .selectAll()
                    .where { AuctionTable.sellerMemberId eq memberId }
                    .forEach { row -> add(auctionSummaryJson(row)) }
            }
            putJsonArray("auctionsWon") {
                AuctionTable
                    .selectAll()
                    .where { AuctionTable.winnerMemberId eq memberId }
                    .forEach { row -> add(auctionSummaryJson(row)) }
            }
            putJsonArray("bidsPlaced") {
                AuctionBidTable
                    .selectAll()
                    .where { AuctionBidTable.bidderMemberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[AuctionBidTable.id].toString())
                                put("auctionId", row[AuctionBidTable.auctionId].toString())
                                put("maxBidLtr", row[AuctionBidTable.maxBidLtr]?.toPlainString())
                                put("maxBidMinor", row[AuctionBidTable.maxBidMinor])
                                put("createdAt", row[AuctionBidTable.createdAt].toString())
                            },
                        )
                    }
            }
            putJsonArray("paymentDetailsStored") {
                // The sealed ciphertext is never exported -- only the fact that details exist for an own auction, and of which kind.
                (AuctionPaymentDetailsTable innerJoin AuctionTable)
                    .selectAll()
                    .where { AuctionTable.sellerMemberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("auctionId", row[AuctionPaymentDetailsTable.auctionId].toString())
                                put("hasIban", row[AuctionPaymentDetailsTable.hasIban])
                                put("hasPaymentLink", row[AuctionPaymentDetailsTable.hasPaymentLink])
                                put("storedSealed", true)
                            },
                        )
                    }
            }
            putJsonArray("fiatAcknowledgments") {
                AuctionFiatAcknowledgmentTable
                    .selectAll()
                    .where { AuctionFiatAcknowledgmentTable.acknowledgedByMemberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[AuctionFiatAcknowledgmentTable.id].toString())
                                put("kind", row[AuctionFiatAcknowledgmentTable.kind])
                                put("disclaimerVersion", row[AuctionFiatAcknowledgmentTable.disclaimerVersion])
                                put("acknowledgedAt", row[AuctionFiatAcknowledgmentTable.acknowledgedAt].toString())
                            },
                        )
                    }
            }
            putJsonArray("complianceAcknowledgments") {
                AuctionComplianceAcknowledgmentTable
                    .selectAll()
                    .where { AuctionComplianceAcknowledgmentTable.acknowledgedByMemberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[AuctionComplianceAcknowledgmentTable.id].toString())
                                put("disclaimerVersion", row[AuctionComplianceAcknowledgmentTable.disclaimerVersion])
                                put("acknowledgedAt", row[AuctionComplianceAcknowledgmentTable.acknowledgedAt].toString())
                            },
                        )
                    }
            }
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val auctionCondition = (AuctionTable.sellerMemberId eq memberId) or (AuctionTable.winnerMemberId eq memberId)
        val auctionCount = AuctionTable.selectAll().where { auctionCondition }.count()

        val bidCount = AuctionBidTable.selectAll().where { AuctionBidTable.bidderMemberId eq memberId }.count()

        val ackCount =
            AuctionComplianceAcknowledgmentTable
                .selectAll()
                .where { AuctionComplianceAcknowledgmentTable.acknowledgedByMemberId eq memberId }
                .count()

        // V1.9.320: the sealed payment details of every auction this member SOLD are deleted now.
        val ownAuctionIds = AuctionTable.selectAll().where { AuctionTable.sellerMemberId eq memberId }.map { it[AuctionTable.id] }
        val detailRows =
            if (ownAuctionIds.isEmpty()) {
                0
            } else {
                AuctionPaymentDetailsTable.deleteWhere { AuctionPaymentDetailsTable.auctionId inList ownAuctionIds }
            }
        val fiatAckCount =
            AuctionFiatAcknowledgmentTable
                .selectAll()
                .where { AuctionFiatAcknowledgmentTable.acknowledgedByMemberId eq memberId }
                .count()

        return listOf(
            TableErasureOutcome(
                table = "auction_payment_details",
                rowsDeleted = detailRows,
            ),
            TableErasureOutcome(
                table = "auction_fiat_acknowledgment",
                rowsRetained = fiatAckCount.toInt(),
                retentionReason =
                    "Who acknowledged which Fiat-auction notice and when is the accountability record (Art. 5(2) DSGVO) " +
                        "of the operator and of the seller.",
            ),
            TableErasureOutcome(
                table = "auction",
                rowsRetained = auctionCount.toInt(),
                retentionReason =
                    "A listing is a shared marketplace transaction record for both seller and winner -- " +
                        "no field is erased.",
            ),
            TableErasureOutcome(
                table = "auction_bid",
                rowsRetained = bidCount.toInt(),
                retentionReason =
                    "A standing maximum is part of the shared, auditable second-price computation every " +
                        "other bidder's outcome on the same auction depends on.",
            ),
            TableErasureOutcome(
                table = "auction_compliance_acknowledgment",
                rowsRetained = ackCount.toInt(),
                retentionReason =
                    "Who acknowledged which disclaimer version and when is the ADMIN's own " +
                        "compliance-accountability record (Art. 5(2) DSGVO).",
            ),
        )
    }
}

private fun auctionSummaryJson(row: ResultRow) =
    buildJsonObject {
        put("id", row[AuctionTable.id].toString())
        put("title", row[AuctionTable.title])
        put("status", row[AuctionTable.status].name)
        put("sellerMemberId", row[AuctionTable.sellerMemberId].toString())
        put("winnerMemberId", row[AuctionTable.winnerMemberId]?.toString())
        put("finalPriceLtr", row[AuctionTable.finalPriceLtr]?.toPlainString())
        put("currency", row[AuctionTable.currency])
        put("finalPriceMinor", row[AuctionTable.finalPriceMinor])
        put("createdAt", row[AuctionTable.createdAt].toString())
    }
