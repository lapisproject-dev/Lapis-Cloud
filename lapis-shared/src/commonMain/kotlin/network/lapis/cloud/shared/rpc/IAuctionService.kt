package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import dev.kilua.rpc.types.Decimal
import network.lapis.cloud.shared.domain.AuctionBidDto
import network.lapis.cloud.shared.domain.AuctionBidResultDto
import network.lapis.cloud.shared.domain.AuctionComplianceAcknowledgmentInput
import network.lapis.cloud.shared.domain.AuctionComplianceDisclaimerDto
import network.lapis.cloud.shared.domain.AuctionDto
import network.lapis.cloud.shared.domain.AuctionSettingsDto
import network.lapis.cloud.shared.domain.AuctionStatus
import network.lapis.cloud.shared.domain.CreateAuctionListingInput

/**
 * V0.6.2 LTR-Auktion -- englische Proxy-Bid-Auktion mit Second-Price-Zuschlag, reines
 * LTR-Buchungssystem, optionalem Sofortkauf, Wertobergrenze, Lazy-Close (kein Scheduler
 * existiert). See `21-auction.kuml.kts` file header for the full fachlich model (concept document:
 * vault "03 Bereiche/Lapis Cloud/Meritokratisches System und Libertaler.md", section "Auktion –
 * Marktplatz fuer LTR-Inhaber").
 *
 * ## The `auctionEnabled` gate
 *
 * Every participant method below (everything except the ADMIN governance methods) calls a
 * `requireAuctionEnabled` guard first -- if `OrganizationSettingsDto.auctionEnabled` is `false`
 * (the default), the call is rejected with a [ConflictException] and has zero side effects. Same
 * `requirePostalMailEnabled`/`requirePoliticianRankingEnabled`-style gate
 * [network.lapis.cloud.server.rpc.PostalMailService]/[network.lapis.cloud.server.rpc.PoliticianService]
 * already establish for their own opt-in flags -- **stronger here**: unlike those two,
 * `auctionEnabled` is deliberately NOT part of `IOrganizationSettingsService.updateOrganizationSettings`'s
 * writable field set at all -- it can only be flipped on via [enableAuction] (which requires the
 * disclaimer acknowledgment below) or off via [disableAuction].
 *
 * ## The disclaimer-acknowledgment mechanism (auditable, not a bare boolean flip)
 *
 * The legal classification of an LTR-for-LTR-or-goods marketplace (Zahlungsdiensteaufsicht
 * ZAG/MiCAR, Gewerbeordnung, Steuerrecht UStG/EStG/GewStG, Verbraucherschutz, PartG bei
 * Parteiinstanzen, GwG) is jurisdiction-/organization-dependent -- this platform performs NO
 * automated legal classification of its own (same "Selbstauskunft, keine automatisierte
 * Rechtsberatung" framing [network.lapis.cloud.server.rpc.PartyDonationComplianceCalculator]/
 * [network.lapis.cloud.server.rpc.DsgvoComplianceService] already establish). [enableAuction]
 * therefore requires the calling ADMIN to first [getAuctionComplianceDisclaimer] (the current,
 * versioned+hashed legal-risk text) and echo BOTH its `version` and `sha256` back unmodified --
 * the server re-verifies the hash (constant-time comparison) before flipping the gate, proving the
 * ADMIN was shown the CURRENT text, not a stale/tampered one. On success the acknowledgment is
 * persisted as its own append-only row (who/when/which version+hash) -- auditable, never a bare
 * boolean flip. [disableAuction] requires no such acknowledgment (turning a risk feature off is
 * always safe) and does not erase the acknowledgment history.
 *
 * ## The `auctionMaxValueLtr` cap (LTR only, never oracle-derived)
 *
 * If an ADMIN has set `OrganizationSettingsDto.auctionMaxValueLtr` (nullable, default `null` = no
 * cap) via [setAuctionMaxValueLtr], [createListing] rejects a `startingBidLtr`/`buyNowPriceLtr`
 * above it. Deliberately NOT derived from an EUR-equivalent via the Price-Oracle -- the ADMIN sets
 * the value directly in LTR, in knowledge of their own organization's anchor-asset binding (see
 * `19-price-oracle.kuml.kts`); no Oracle dependency is introduced into auction core validation.
 * The cap governs what a seller may LIST, not any individual bid.
 *
 * ## Reservation model (LTR is held, not spent, while bidding)
 *
 * A bid's `maxBidLtr` is checked for coverage at bid time, but only the CURRENT leading bid
 * actually reserves (`ltr_ledger_entry` type `AUCTION_HOLD`) LTR out of the bidder's free balance
 * -- a bid that does not (yet) take the lead is checked for coverage but holds nothing net (it
 * still legitimately sets the second price). Being outbid immediately releases the former leader's
 * hold (`AUCTION_HOLD_RELEASE`). See `network.lapis.cloud.server.rpc.AuctionService` KDoc for the
 * full mechanism and the race-condition guarantees this provides across concurrent bids/auctions.
 *
 * ## Lazy-Close (no scheduler)
 *
 * This codebase has no scheduler/cron-job infrastructure anywhere (same absence
 * `network.lapis.cloud.server.rpc.CrowdfundingService`'s "Silence-is-Approval" KDoc documents for
 * its own domain). An auction whose `endsAt` has passed is settled lazily, on the next call that
 * touches it ([getAuction], [placeBid], [buyNow], [settleAuction]) -- never by [listAuctions],
 * which only shows a computed `effectiveStatus` without writing anything (see [AuctionDto] KDoc).
 *
 * ## Scope-cuts (deliberate, not gaps)
 *
 * - **Member-only.** No Gast/guest identity model exists in this codebase yet (same V0.7-dependent
 *   scope-cut [IPeerTransferService]/[IPoliticianService] already document for their own domains)
 *   -- every seller/bidder id is a real, `NOT NULL` member FK.
 * - **No central arbitration / Kaeuferschutz / chargeback.** Unlike V0.6.3's `peer_transfer`
 *   arbitration-correction path, an auction has NO analogous privileged-correction endpoint, and
 *   none is planned -- the concept document deliberately avoids giving this platform a content
 *   arbitration role for auction disputes. Reputation (LTR balance visibility, social ratings) is
 *   the only protection. **This is intentional, not a missing feature.**
 * - **No automatic EUR/Fiat conversion for the value cap** -- see "The `auctionMaxValueLtr` cap"
 *   above.
 * - **No Politiker-Profile/Price-Oracle coupling** of any kind.
 */
@RpcService
interface IAuctionService {
    /**
     * Role: MEMBER+, caller must be [network.lapis.cloud.shared.domain.MemberStatus.AKTIV].
     * Debits the caller a flat 0.01 LTR listing fee (pure spam guard, same disclaimer class/
     * magnitude as [IPeerTransferService.transferLtr]'s `MIN_TRANSFER_LTR`) -- NO platform
     * provision is ever taken on the eventual sale price.
     */
    suspend fun createListing(input: CreateAuctionListingInput): AuctionDto

    /**
     * Role: MEMBER+, caller must be AKTIV, and may not be the auction's own seller. [maxBidLtr] is
     * the caller's PROXY maximum -- the server only ever charges up to the second-highest bidder's
     * maximum plus the minimum increment (see class KDoc "Reservation model"). A bidder may only
     * ever raise their own standing bid, never lower it.
     */
    suspend fun placeBid(
        auctionId: String,
        maxBidLtr: Decimal,
    ): AuctionBidResultDto

    /**
     * Role: MEMBER+, caller must be AKTIV, may not be the seller. Immediately ends the auction at
     * the fixed `buyNowPriceLtr`, only while the live proxy-bid price has not already reached it.
     */
    suspend fun buyNow(auctionId: String): AuctionDto

    /** Any authenticated member. Lazily settles the auction first if `endsAt` has passed -- see class KDoc "Lazy-Close". */
    suspend fun getAuction(id: String): AuctionDto

    /**
     * Any authenticated member. Bounded to the 200 most recently created matches -- DoS guard, same
     * class of cap `network.lapis.cloud.server.rpc.PoliticianService.getTopPoliticians`'s own
     * `limit` parameter enforces. Never triggers a lazy-close write -- see class KDoc "Lazy-Close".
     */
    suspend fun listAuctions(statusFilter: AuctionStatus? = null): List<AuctionDto>

    /** Any authenticated member. Only the caller's own bids, with their own `maxBidLtr` visible -- see [AuctionBidDto] KDoc. */
    suspend fun listMyBids(): List<AuctionBidDto>

    /** Any authenticated member. Only auctions the caller is the SELLER of. */
    suspend fun listMyAuctions(): List<AuctionDto>

    /**
     * Role: MEMBER+, caller must be AKTIV. Explicitly forces the lazy-close evaluation for one
     * auction -- rejected with [ConflictException] if `endsAt` has not passed yet (no early
     * settlement). Idempotent once already SETTLED/CLOSED_NO_SALE.
     */
    suspend fun settleAuction(id: String): AuctionDto

    /** Role: ADMIN. Not gated by `auctionEnabled` (must be readable BEFORE the feature can be switched on). */
    suspend fun getAuctionComplianceDisclaimer(): AuctionComplianceDisclaimerDto

    /** Role: ADMIN. See class KDoc "The disclaimer-acknowledgment mechanism". */
    suspend fun enableAuction(input: AuctionComplianceAcknowledgmentInput): AuctionSettingsDto

    /** Role: ADMIN. See class KDoc "The disclaimer-acknowledgment mechanism". No acknowledgment required to turn the feature off. */
    suspend fun disableAuction(): AuctionSettingsDto

    /** Role: ADMIN. `null` clears the cap (no limit). See class KDoc "The `auctionMaxValueLtr` cap". */
    suspend fun setAuctionMaxValueLtr(maxValueLtr: Decimal?): AuctionSettingsDto

    /** Role: ADMIN. */
    suspend fun getAuctionSettings(): AuctionSettingsDto
}
