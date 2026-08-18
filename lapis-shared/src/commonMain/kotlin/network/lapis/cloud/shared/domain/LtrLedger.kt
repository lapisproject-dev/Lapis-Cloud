package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.Decimal
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * V0.6.1 (Internes Crowdfunding) LTR-Ledger entry kinds -- see
 * `08-ltr-balance.kuml.kts` file header for the full rationale (append-only, signed-amount,
 * single-entry ledger, deliberately NOT the double-entry Journal/Posting shape
 * `10-accounting.kuml.kts` uses for EUR-Buchführung). [MINT]/[PROJECT_STAKE]/[VOTE_STAKE] are the
 * three kinds this wave actually writes; [PROJECT_STAKE_RELEASE] is reserved, defined-but-unused
 * (no release path exists yet for a bound stake -- same limitation applies to [VOTE_STAKE], see
 * that literal's own KDoc). Additively extensible -- cheap to extend, expensive to reorder
 * (literal order is pinned by `LtrLedgerSchemaDriftTest`).
 */
@Serializable
enum class LtrLedgerEntryType {
    MINT,
    PROJECT_STAKE,
    PROJECT_STAKE_RELEASE,

    /**
     * [network.lapis.cloud.server.rpc.GovernanceService.castVoteBallot] binding LTR into a
     * Vote's Vickrey-auction stake (security fix, post-V0.6.1: the original wave documented this
     * write in [network.lapis.cloud.server.economy.LtrBalanceProvider]'s KDoc but never actually
     * implemented it, leaving `freeBalance` blind to open vote stakes -- see that KDoc). Bound,
     * not burned, same as [PROJECT_STAKE]; no release path exists yet for a recast-down,
     * `closeVote` settlement below the full stake, or `abortVote` -- reserved for a later wave,
     * analogous to [PROJECT_STAKE_RELEASE].
     */
    VOTE_STAKE,

    /**
     * [network.lapis.cloud.server.rpc.PeerTransferService.transferLtr]/
     * [network.lapis.cloud.server.rpc.PeerTransferService.executeArbitrationTransfer] debiting
     * the sender of a direct member-to-member LTR transfer (V0.6.3). Always paired, same
     * transaction, same `peer_transfer.id` as `referenceId`, with a [PEER_TRANSFER_IN] entry
     * crediting the recipient -- see `18-peer-transfer.kuml.kts` file header for the full
     * fachlich model.
     */
    PEER_TRANSFER_OUT,

    /** Credit half of the [PEER_TRANSFER_OUT] pair -- see that literal's KDoc. */
    PEER_TRANSFER_IN,

    /**
     * [network.lapis.cloud.server.rpc.AuctionService.createListing] debiting the seller's flat
     * 0.01 LTR listing fee (V0.6.2) -- pure spam guard, same disclaimer class as [PEER_TRANSFER_OUT]'s
     * own minimum-transfer constant. Never released/refunded, even for a
     * [network.lapis.cloud.shared.domain.AuctionStatus.CLOSED_NO_SALE] outcome -- see
     * `21-auction.kuml.kts` file header.
     */
    AUCTION_LISTING_FEE,

    /**
     * [network.lapis.cloud.server.rpc.AuctionService.placeBid] reserving the CURRENT leading
     * bidder's `maxBidLtr` out of their free balance (V0.6.2) -- only the current leader ever
     * holds one of these at a time per auction, released ([AUCTION_HOLD_RELEASE]) the instant a
     * higher bid takes the lead. See `21-auction.kuml.kts` file header "Reservation model".
     */
    AUCTION_HOLD,

    /** Credit releasing a previously-held [AUCTION_HOLD] -- either the former leader was outbid, or the auction settled. See that literal's KDoc. */
    AUCTION_HOLD_RELEASE,

    /**
     * [network.lapis.cloud.server.rpc.AuctionService] debiting the WINNING bidder the final
     * (second-price, or fixed Sofortkauf) price at settlement (V0.6.2) -- always paired, same
     * transaction, same `auction.id` as `referenceId`, with an [AUCTION_SALE_IN] entry crediting
     * the seller.
     */
    AUCTION_SALE_OUT,

    /** Credit half of the [AUCTION_SALE_OUT] pair, crediting the seller -- see that literal's KDoc. */
    AUCTION_SALE_IN,

    /**
     * [network.lapis.cloud.server.rpc.SocialNetworkService.createPost] binding a post's
     * author-chosen Sichtbarkeits-Gewicht (V1.1.1 Soziales Netzwerk) -- see
     * `32-social-network.kuml.kts` file header for the full fachlich model. Never released/
     * refunded, same "no release path" posture as [AUCTION_LISTING_FEE] -- `hideOwnPost` does not
     * reverse this debit, see that method's own KDoc.
     */
    SOCIAL_POST_STAKE,
}

/**
 * Polymorphic target-table discriminator for [LtrLedgerEntryDto.referenceId] -- mirrors
 * [AuditEntityType]'s own role for `audit_log_entry.entity_id`. [CROWDFUNDING_PROJECT] is this
 * wave's original literal, [VOTE] the security-fix addition for [LtrLedgerEntryType.VOTE_STAKE],
 * [PEER_TRANSFER] the V0.6.3 addition for [LtrLedgerEntryType.PEER_TRANSFER_OUT]/
 * [LtrLedgerEntryType.PEER_TRANSFER_IN] (see that literal's KDoc), [AUCTION] the V0.6.2 addition
 * for every `AUCTION_*` [LtrLedgerEntryType] literal (`referenceId` always points at the
 * `auction.id`, never at `auction_bid`); additively extended by later waves.
 */
@Serializable
enum class LtrLedgerReferenceType { CROWDFUNDING_PROJECT, VOTE, PEER_TRANSFER, AUCTION, SOCIAL_POST }

/**
 * One row of the append-only LTR ledger. [amountLtr] is signed: positive for a credit (e.g.
 * [LtrLedgerEntryType.MINT]), negative for a debit (e.g. [LtrLedgerEntryType.PROJECT_STAKE]) --
 * see `network.lapis.cloud.server.economy.LedgerBackedLtrBalanceProvider` for the
 * `SUM(amountLtr)` balance derivation this convention supports.
 */
@Serializable
data class LtrLedgerEntryDto(
    val id: String,
    val memberId: String,
    val memberDisplayName: String,
    val entryType: LtrLedgerEntryType,
    val amountLtr: Decimal,
    val referenceType: LtrLedgerReferenceType?,
    val referenceId: String?,
    val note: String?,
    val createdById: String?,
    val createdByDisplayName: String?,
    val createdAt: LocalDateTime,
)

/** A member's current free LTR balance -- always derived (`SUM(amountLtr)`), never a cached/persisted column. */
@Serializable
data class LtrLedgerBalanceDto(
    val memberId: String,
    val freeBalanceLtr: Decimal,
)

/** Role: TREASURER/BOARD/ADMIN. [amountLtr] must be strictly positive -- a MINT is always a credit. */
@Serializable
data class MintLtrInput(
    val memberId: String,
    val amountLtr: Decimal,
    val note: String? = null,
)
