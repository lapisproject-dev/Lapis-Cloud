package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.auction.federation.AuctionWithdrawals
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AuctionBidTable
import network.lapis.cloud.server.db.generated.AuctionComplianceAcknowledgmentTable
import network.lapis.cloud.server.db.generated.AuctionFiatAcknowledgmentTable
import network.lapis.cloud.server.db.generated.AuctionOfferTable
import network.lapis.cloud.server.db.generated.AuctionPaymentDetailsTable
import network.lapis.cloud.server.db.generated.AuctionTable
import network.lapis.cloud.shared.domain.AuctionOfferStatus
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
 *
 * **V1.9.321 price offers** (`auction_offer`): the export lists the member's own offers (as buyer) and the offers on the auctions he sold, with
 * amounts and status. On erasure the negotiation chains of the member as BUYER that did NOT lead to an award (no ACCEPTED row in the chain) are
 * deleted -- they are only useful while the auction runs. A chain with an ACCEPTED offer is the proof of the purchase price of a shared
 * transaction record and is retained like the auction itself; offers on auctions the member only SOLD belong to the buyers' records and stay.
 *
 * **V1.9.322 federation (c4).** The seller's consent to share the hint with partner servers (`federation_opt_in`/`_at`) is part of the export
 * (`auctionsSold` only, never the winner's `auctionsWon`). On erasure [AuctionWithdrawals.onSellerErased] runs FIRST (before any row of the member
 * changes): the hints are withdrawn for everybody (global tombstones) and the consent ends, because the auction row itself is retained. The
 * federation tables (`auction_teaser_*`) carry no member FK and are therefore not listed in [coveredTables].
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
            AuctionOfferTable,
        )

    override fun exportMember(memberId: Uuid) =
        buildJsonObject {
            putJsonArray("auctionsSold") {
                AuctionTable
                    .selectAll()
                    .where { AuctionTable.sellerMemberId eq memberId }
                    .forEach { row -> add(soldAuctionJson(row)) }
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
            putJsonArray("priceOffers") {
                AuctionOfferTable
                    .selectAll()
                    .where {
                        (AuctionOfferTable.buyerMemberId eq memberId) or
                            (
                                AuctionOfferTable.auctionId inList
                                    AuctionTable.selectAll().where { AuctionTable.sellerMemberId eq memberId }.map { it[AuctionTable.id] }
                            )
                    }.forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[AuctionOfferTable.id].toString())
                                put("auctionId", row[AuctionOfferTable.auctionId].toString())
                                // Art. 15(4): never the internal ID of another member -- only whether the row is the requester's own.
                                put("ownOffer", row[AuctionOfferTable.buyerMemberId] == memberId)
                                put("fromSeller", row[AuctionOfferTable.fromSeller])
                                put("amountMinor", row[AuctionOfferTable.amountMinor])
                                put("status", row[AuctionOfferTable.status].name)
                                put("createdAt", row[AuctionOfferTable.createdAt].toString())
                                put("expiresAt", row[AuctionOfferTable.expiresAt].toString())
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
        // V1.9.322 c4: FIRST withdraw every hint this member shared with partner servers and end the consent (the auction row is retained, the
        // consent must not outlive the erasure). Before any row of the member is changed; the member row lock is held by the caller.
        AuctionWithdrawals.onSellerErased(memberId = memberId, now = DbClock.nowLocalDateTime())
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

        // V1.9.321: the buyer's negotiation chains without an award are deleted (counter-offers first: they reference their parent).
        val ownOffers = AuctionOfferTable.selectAll().where { AuctionOfferTable.buyerMemberId eq memberId }.toList()
        val acceptedIds = ownOffers.filter { it[AuctionOfferTable.status] == AuctionOfferStatus.ACCEPTED }.map { it[AuctionOfferTable.id] }
        val keepIds =
            acceptedIds.toSet() +
                ownOffers.filter { it[AuctionOfferTable.id] in acceptedIds }.mapNotNull { it[AuctionOfferTable.parentOfferId] }
        val deletable = ownOffers.filter { it[AuctionOfferTable.id] !in keepIds }
        val deletableChildren = deletable.filter { it[AuctionOfferTable.parentOfferId] != null }.map { it[AuctionOfferTable.id] }
        val deletableParents = deletable.filter { it[AuctionOfferTable.parentOfferId] == null }.map { it[AuctionOfferTable.id] }
        var offerRowsDeleted = 0
        if (deletableChildren.isNotEmpty()) {
            offerRowsDeleted +=
                AuctionOfferTable.deleteWhere { AuctionOfferTable.id inList deletableChildren }
        }
        if (deletableParents.isNotEmpty()) {
            offerRowsDeleted +=
                AuctionOfferTable.deleteWhere { AuctionOfferTable.id inList deletableParents }
        }
        val offerRowsRetained =
            AuctionOfferTable
                .selectAll()
                .where { AuctionOfferTable.buyerMemberId eq memberId }
                .count()
                .toInt()

        return listOf(
            TableErasureOutcome(
                table = "auction_offer",
                rowsDeleted = offerRowsDeleted,
                rowsRetained = offerRowsRetained,
                retentionReason =
                    "Offers that led to an award are the proof of the agreed price of a shared transaction record; offers on auctions " +
                        "the member only sold belong to the buyers' records.",
            ),
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

/** The seller's own view: the summary plus whether (and since when) the hint is shared with partner servers. Never shown to the winner. */
private fun soldAuctionJson(row: ResultRow) =
    buildJsonObject {
        auctionSummaryFields(row)
        put("sharedWithPartnerServers", row[AuctionTable.federationOptIn])
        put("sharedWithPartnerServersSince", row[AuctionTable.federationOptInAt]?.toString())
    }

private fun auctionSummaryJson(row: ResultRow) = buildJsonObject { auctionSummaryFields(row) }

private fun kotlinx.serialization.json.JsonObjectBuilder.auctionSummaryFields(row: ResultRow) {
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
