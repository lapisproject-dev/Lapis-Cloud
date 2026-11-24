package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import dev.kilua.rpc.types.Decimal
import network.lapis.cloud.shared.domain.AuctionBidDto
import network.lapis.cloud.shared.domain.AuctionBidResultDto
import network.lapis.cloud.shared.domain.AuctionComplianceAcknowledgmentInput
import network.lapis.cloud.shared.domain.AuctionComplianceDisclaimerDto
import network.lapis.cloud.shared.domain.AuctionDto
import network.lapis.cloud.shared.domain.AuctionFederationAvailabilityDto
import network.lapis.cloud.shared.domain.AuctionFederationReachDto
import network.lapis.cloud.shared.domain.AuctionFiatAvailabilityDto
import network.lapis.cloud.shared.domain.AuctionHintResolutionDto
import network.lapis.cloud.shared.domain.AuctionOfferDto
import network.lapis.cloud.shared.domain.AuctionPaymentDetailsDto
import network.lapis.cloud.shared.domain.AuctionPaymentDetailsInput
import network.lapis.cloud.shared.domain.AuctionSettingsDto
import network.lapis.cloud.shared.domain.AuctionStatus
import network.lapis.cloud.shared.domain.CreateAuctionListingInput
import network.lapis.cloud.shared.domain.CreateFiatAuctionListingInput
import network.lapis.cloud.shared.domain.PartnerAuctionHintPageDto
import network.lapis.cloud.shared.domain.UpdateAuctionTermsInput

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
 * - **EUR means direct payment, never a conversion (V1.9.320).** An auction may be listed in EUR (opt-in, OFF by default, see
 *   [setAuctionFiatEnabled]); Lapis then only brokers the contract -- seller and winner settle the money directly between themselves, outside the
 *   platform, and no LTR ledger row is ever written for it. The "No automatic EUR/Fiat conversion" cut above still holds.
 */
@RpcService
interface IAuctionService {
    /**
     * Role: MEMBER+, caller must be [network.lapis.cloud.shared.domain.MemberStatus.ACTIVE].
     * Debits the caller a flat 0.01 LTR listing fee (pure spam guard, same disclaimer class/
     * magnitude as [IPeerTransferService.transferLtr]'s `MIN_TRANSFER_LTR`) -- NO platform
     * provision is ever taken on the eventual sale price.
     */
    suspend fun createListing(input: CreateAuctionListingInput): AuctionDto

    /**
     * Role: MEMBER+, caller must be ACTIVE, and may not be the auction's own seller. [maxBidLtr] is
     * the caller's PROXY maximum -- the server only ever charges up to the second-highest bidder's
     * maximum plus the minimum increment (see class KDoc "Reservation model"). A bidder may only
     * ever raise their own standing bid, never lower it.
     */
    suspend fun placeBid(
        auctionId: String,
        maxBidLtr: Decimal,
    ): AuctionBidResultDto

    /**
     * Role: MEMBER+, caller must be ACTIVE, may not be the seller. Immediately ends the auction at
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
     * Role: MEMBER+, caller must be ACTIVE. Explicitly forces the lazy-close evaluation for one
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
    // ── V1.9.320 Auktionen in Euro mit Direktzahlung (Fiat W1) -- methods are APPENDED (the Kilua method order is load-bearing). ──
    // A Fiat auction never touches the LTR ledger; seller and winner settle the money directly. See `21-auction.kuml.kts` file header "Fiat path".

    /** Role: MEMBER+ (`auctionEnabled` gate). Whether "Euro" may be offered, the CURRENT seller disclaimer and whether the caller acknowledged it. */
    suspend fun getAuctionFiatAvailability(): AuctionFiatAvailabilityDto

    /** Role: MEMBER+ (ACTIVE). Records that the caller acknowledged the CURRENT seller disclaimer (version and hash must match). Idempotent per version. */
    suspend fun acknowledgeFiatSellerDisclaimer(input: AuctionComplianceAcknowledgmentInput): AuctionFiatAvailabilityDto

    /** Role: MEMBER+ (ACTIVE). Needs the instance switch, the seller acknowledgment and valid payment details. Writes NO ledger row. */
    suspend fun createFiatListing(input: CreateFiatAuctionListingInput): AuctionDto

    /** Role: MEMBER+ (ACTIVE). Bid on a Fiat auction (minor units); no coverage check, no reservation. */
    suspend fun placeFiatBid(
        auctionId: String,
        maxBidMinor: Long,
    ): AuctionBidResultDto

    /** Role: the SELLER of an OPEN Fiat auction. Replaces the sealed payment details; frozen from the award on. */
    suspend fun updateAuctionPaymentDetails(
        auctionId: String,
        input: AuctionPaymentDetailsInput,
    ): AuctionDto

    /** Role: the SELLER of an OPEN auction WITHOUT any bid. Changes currency and prices; once a bid exists the currency is locked. */
    suspend fun updateAuctionTerms(
        auctionId: String,
        input: UpdateAuctionTermsInput,
    ): AuctionDto

    /**
     * Role: the WINNER or the SELLER of an awarded Fiat auction (AWAITING_PAYMENT, PAID, COMPLETED). Everybody else, an unawarded or an LTR auction and
     * a missing row all answer the SAME [NotFoundException] (no existence leak).
     */
    suspend fun getAuctionPaymentDetails(auctionId: String): AuctionPaymentDetailsDto

    /** Role: the SELLER. The money arrived. AWAITING_PAYMENT -> PAID (or COMPLETED when the winner already confirmed the goods). One-shot. */
    suspend fun confirmPaymentReceived(auctionId: String): AuctionDto

    /** Role: the WINNER. The goods arrived. One-shot; COMPLETED once both confirmations exist. */
    suspend fun confirmDeliveryReceived(auctionId: String): AuctionDto

    /** Role: ADMIN. The OPERATOR disclaimer to acknowledge before switching Fiat auctions on. */
    suspend fun getAuctionFiatOperatorDisclaimer(): AuctionComplianceDisclaimerDto

    /** Role: ADMIN. Switching ON needs [input] (version and hash of the CURRENT operator disclaimer); switching OFF needs none. Running Fiat auctions stay settleable. */
    suspend fun setAuctionFiatEnabled(
        enabled: Boolean,
        input: AuctionComplianceAcknowledgmentInput?,
    ): AuctionSettingsDto

    // ── V1.9.321 (Fiat wave b): price offers. Every method needs an ACTIVE member. A stranger, a missing row and a malformed id all get the SAME
    // NotFoundException (no existence leak). Not legally reviewed: an offer binds the buyer only once the seller accepts. ──

    /** Role: any active member except the seller. Proposes [amountMinor] on a Fiat auction with price offers (below the Sofortkauf price). */
    suspend fun makeOffer(
        auctionId: String,
        amountMinor: Long,
    ): AuctionOfferDto

    /** Role: the SELLER. Answers an open proposal with ONE counter-offer (higher than the proposal, below the Sofortkauf price). */
    suspend fun counterOffer(
        offerId: String,
        amountMinor: Long,
    ): AuctionOfferDto

    /** Role: the seller (for a proposal) or the buyer (for a counter-offer). Awards the auction at the offered price: AWAITING_PAYMENT. */
    suspend fun acceptOffer(offerId: String): AuctionDto

    /** Role: the seller (for a proposal) or the buyer (for a counter-offer). */
    suspend fun declineOffer(offerId: String): AuctionOfferDto

    /** Role: the author of the open offer. */
    suspend fun withdrawOffer(offerId: String): AuctionOfferDto

    /** Role: the SELLER of the auction. All proposals and counter-offers, newest first (at most [network.lapis.cloud.shared.domain.AuctionOfferRules.MAX_OFFERS_PER_AUCTION]). */
    suspend fun listOffersForAuction(auctionId: String): List<AuctionOfferDto>

    /** Role: any active member. The caller's own proposals and the counter-offers addressed to him, newest first, capped. */
    suspend fun listMyOffers(): List<AuctionOfferDto>

    // ── V1.9.322 (Fiat wave c, step c2): visibility to partner servers. APPENDED at the end. Not legally reviewed, off by default. ──

    /** Role: any active member. Whether sharing auctions with partner servers is possible right now (instance on, Euro auctions on, key present). */
    suspend fun getAuctionFederationAvailability(): AuctionFederationAvailabilityDto

    /**
     * Role: the SELLER (an active member who confirmed the Fiat notice). Gives or takes back the consent to show a Euro auction to partner servers.
     * Lock order: auction row, then member row, then snapshot/tombstone, audit last. A stranger's and an unknown auction get the SAME NotFoundException.
     * Taking the consent back is always allowed (also while the exchange is off) and withdraws the hint.
     */
    suspend fun setAuctionFederationOptIn(
        auctionId: String,
        optIn: Boolean,
    ): AuctionDto

    /**
     * Role: any active member. Resolves the id of a hint link to one of OUR auctions. Everything that is not a plainly open, shared Euro auction
     * (unknown, ended, withdrawn, switched off, malformed, rate limited) is ONE answer: [AuctionHintResolutionDto.Unavailable]. Writes nothing.
     */
    suspend fun resolveAuctionHint(teaserId: String): AuctionHintResolutionDto

    /** Role: any active member. To which partner servers the hints of this server travel (host names only, capped). */
    suspend fun getAuctionFederationReach(): AuctionFederationReachDto

    /**
     * V1.9.322 c3. Role: an ACTIVE member (guest, friend and donor are refused). One page (50) of the auctions that PARTNER servers show to us, ordered by
     * end, then id, then origin host. An empty page while the exchange switch or the auction module is off. The membership gate runs BEFORE the page
     * check. Writes nothing, bids nothing: the member opens the origin server by link.
     */
    suspend fun listPartnerAuctionHints(page: Int): PartnerAuctionHintPageDto

    /**
     * V1.9.322 c3. Role: ADMIN (with an active membership). Hides ONE received hint: it goes and cannot come back, not even from its origin (kind HIDDEN
     * until the end plus two days). Unknown and malformed ids are the SAME `NotFoundException`. Audited without title and amount. Nothing is reported to
     * the origin.
     */
    suspend fun hidePartnerAuctionHint(
        originHost: String,
        teaserId: String,
    )
}
