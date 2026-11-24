package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.Decimal
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * V0.6.2 LTR-Auktion (englische Proxy-Bid-Auktion, Second-Price-Zuschlag) -- see
 * `21-auction.kuml.kts` file header for the full fachlich model. Literal order is load-bearing:
 * `AuctionSchemaDriftTest` pins `ErmDataType.Enum.values` against this exact order.
 *
 * [OPEN] is the only status a fresh listing starts in. [SETTLED]/[CLOSED_NO_SALE] are both
 * terminal, reached either by an explicit [network.lapis.cloud.shared.rpc.IAuctionService.buyNow]
 * or by lazy-close (no scheduler exists in this codebase -- see file header "Lazy-Close") once
 * `endsAt` has passed: [SETTLED] if at least one bid existed, [CLOSED_NO_SALE] otherwise (the
 * listing fee is NOT refunded in that case -- pure spam-deterrent cost, see file header).
 *
 * **Fiat auctions (V166, Wave V1.9.320)** never reach [SETTLED]: an award of a non-LTR auction is [AWAITING_PAYMENT] (the winner pays the seller
 * directly, outside Lapis); the seller's "money arrived" confirmation makes it [PAID]; the winner's "goods arrived" confirmation on top makes it
 * [COMPLETED]. Without a bid a Fiat auction is [CLOSED_NO_SALE] like an LTR one. The three new literals were APPENDED -- the order is load-bearing.
 */
@Serializable
enum class AuctionStatus { OPEN, SETTLED, CLOSED_NO_SALE, AWAITING_PAYMENT, PAID, COMPLETED }

/**
 * Currency constants and Fiat limits shared by the create form and the server (same idea as [AuctionDurationRules]): both sides read the SAME
 * values. [LTR] is the default currency of every auction; [SUPPORTED] maps each accepted Fiat code to its number of decimal places (minor units).
 */
object AuctionCurrencies {
    const val LTR: String = "LTR"
    const val EUR: String = "EUR"

    /** Accepted Fiat codes -> decimal places. Only EUR for now; the server whitelist is the real gate. */
    val SUPPORTED: Map<String, Int> = mapOf(EUR to 2)

    fun isFiat(code: String): Boolean = code != LTR

    /** Smallest accepted amount per auction (1.00 EUR) in minor units. */
    const val MIN_MINOR: Long = 100L

    /** Largest accepted amount per auction (100,000.00 EUR) in minor units. */
    const val MAX_MINOR: Long = 10_000_000L

    /** Minimum increment of a bid over the second-highest maximum (1.00 EUR) in minor units. */
    const val MIN_INCREMENT_MINOR: Long = 100L

    /** At most this many Fiat auctions of one seller may be OPEN at the same time (there is no listing fee to deter spam). */
    const val MAX_OPEN_PER_SELLER: Int = 20
}

/**
 * Role: MEMBER+, caller must be [MemberStatus.ACTIVE] and `OrganizationSettings.auctionEnabled`
 * must be `true` (see [network.lapis.cloud.shared.rpc.IAuctionService] KDoc "The auctionEnabled
 * gate"). [buyNowPriceLtr], if set, must be strictly greater than [startingBidLtr]. If an ADMIN
 * has configured `OrganizationSettings.auctionMaxValueLtr`, both [startingBidLtr] and
 * [buyNowPriceLtr] must not exceed it -- see that field's own KDoc. [durationHours] is bounded
 * server-side (spam/DoS guard), not modelled as a client-facing constant on this DTO.
 */
@Serializable
data class CreateAuctionListingInput(
    val title: String,
    val description: String,
    val startingBidLtr: Decimal,
    val buyNowPriceLtr: Decimal? = null,
    val durationHours: Int,
)

/**
 * One auction listing. [status] is the PERSISTED database status; [effectiveStatus] additionally
 * reflects a lazy-close that WOULD happen on the next mutating touch (`endsAt` already passed
 * while [status] is still [AuctionStatus.OPEN]) without actually writing it -- same "computed view
 * over a persisted PENDING state" idiom `CrowdfundingProjectDto.effectiveStatus`/`isAutoApproved`
 * already establish. [currentPriceLtr]/[currentLeaderDisplayName]/[leaderIsMe] reflect the live
 * proxy-bid outcome while [status]/[effectiveStatus] == OPEN, and the frozen, persisted sale
 * figures ([winnerMemberId]/[finalPriceLtr]) once SETTLED. **[AuctionBidDto.maxBidLtr] of any
 * OTHER member is never exposed anywhere, including here** -- proxy-auction integrity, see file
 * header "max bids never public".
 */
@Serializable
data class AuctionDto(
    val id: String,
    val title: String,
    val description: String,
    val sellerMemberId: String,
    val sellerDisplayName: String,
    val startingBidLtr: Decimal?,
    val buyNowPriceLtr: Decimal?,
    val status: AuctionStatus,
    val effectiveStatus: AuctionStatus,
    val currentPriceLtr: Decimal?,
    val currentLeaderDisplayName: String?,
    val leaderIsMe: Boolean,
    val bidCount: Int,
    val winnerMemberId: String?,
    val winnerDisplayName: String?,
    val finalPriceLtr: Decimal?,
    val listingFeeLtr: Decimal,
    val createdAt: LocalDateTime,
    val endsAt: LocalDateTime,
    val settledAt: LocalDateTime?,
    /** V1.9.222: delivery URL of the cover image (gallery position 0), or `null` when the listing has no pictures. */
    val coverImageUrl: String? = null,
    /**
     * V1.9.320 -- "LTR" (default, every earlier auction) or a Fiat code ("EUR"). For a Fiat auction the LTR amount fields above are `null` and the
     * `*Minor` fields below carry the amounts as minor units (cents). The payment details of the seller are NEVER part of this DTO.
     */
    val currency: String = "LTR",
    val startingPriceMinor: Long? = null,
    val buyNowPriceMinor: Long? = null,
    val currentPriceMinor: Long? = null,
    val finalPriceMinor: Long? = null,
    /** Fiat only, and only filled for the seller and the winner of the auction (the payment progress is not public). */
    val paymentConfirmedAt: LocalDateTime? = null,
    val deliveryConfirmedAt: LocalDateTime? = null,
    val callerIsSeller: Boolean = false,
    val callerIsWinner: Boolean = false,
    /** Fiat only, only for the seller and the winner: the seller stored a payment link (the link itself comes from `getAuctionPaymentDetails`). */
    val hasPaymentLink: Boolean = false,
    /** V1.9.321 (wave b): the seller allows price offers (fixed at creation; always `false` on an LTR auction). Public: it is part of the listing. */
    val offersEnabled: Boolean = false,
    /** Only for the SELLER: the number of price offers waiting for his decision. Never filled for anybody else (that would be an oracle). */
    val openOfferCountForSeller: Int? = null,
    /** Only for the caller: he has an open offer of his own, or an open counter-offer addressed to him. */
    val callerHasOpenOffer: Boolean = false,
    /** Only for the caller: he cannot make a new offer any more (3 declines, or the auction reached its offer limit), so the button stays hidden. */
    val callerOfferBlocked: Boolean = false,
    /** V1.9.322 (wave c): the seller shows this auction to partner servers. Only filled for the SELLER (else `false`/`null`). */
    val federationOptIn: Boolean = false,
    val federationOptInAt: LocalDateTime? = null,
)

/**
 * One of the CALLER's own bids -- returned only from
 * [network.lapis.cloud.shared.rpc.IAuctionService.listMyBids], never surfaced for any other
 * member's bids (see [AuctionDto] KDoc "max bids never public").
 */
@Serializable
data class AuctionBidDto(
    val id: String,
    val auctionId: String,
    val auctionTitle: String,
    val maxBidLtr: Decimal?,
    val createdAt: LocalDateTime,
    val isCurrentLeader: Boolean,
    val auctionStatus: AuctionStatus,
    /** V1.9.320 -- Fiat bid: [maxBidLtr] is `null` and [maxBidMinor] carries the maximum in minor units. */
    val maxBidMinor: Long? = null,
    val currency: String = "LTR",
)

/** Immediate result of [network.lapis.cloud.shared.rpc.IAuctionService.placeBid]. */
@Serializable
data class AuctionBidResultDto(
    val auctionId: String,
    val accepted: Boolean,
    val youAreLeader: Boolean,
    val currentPriceLtr: Decimal?,
    val yourMaxBidLtr: Decimal?,
    val endsAt: LocalDateTime,
    /** V1.9.320 -- Fiat bid: the LTR amounts above are `null`, these carry minor units. */
    val currentPriceMinor: Long? = null,
    val yourMaxBidMinor: Long? = null,
    val currency: String = "LTR",
)

/**
 * The current, versioned+hashed legal disclaimer text an ADMIN must echo back (unmodified) to
 * [network.lapis.cloud.shared.rpc.IAuctionService.enableAuction] -- see that method's KDoc for the
 * full acknowledgment mechanism.
 */
@Serializable
data class AuctionComplianceDisclaimerDto(
    val version: String,
    val text: String,
    val sha256: String,
)

/**
 * Proof that the ADMIN calling [network.lapis.cloud.shared.rpc.IAuctionService.enableAuction] was
 * shown the CURRENT disclaimer text -- both fields must match
 * [AuctionComplianceDisclaimerDto.version]/[AuctionComplianceDisclaimerDto.sha256] exactly (server
 * re-verifies both, constant-time hash comparison), or the call is rejected with no side effect.
 */
@Serializable
data class AuctionComplianceAcknowledgmentInput(
    val disclaimerVersion: String,
    val disclaimerSha256: String,
)

/**
 * Role: ADMIN (every field). [lastAcknowledgedByDisplayName]/[lastAcknowledgedAt]/
 * [lastDisclaimerVersion] are all `null` if [auctionEnabled] has never been switched on via
 * [network.lapis.cloud.shared.rpc.IAuctionService.enableAuction] -- the acknowledgment history
 * itself is append-only and outlives a later [network.lapis.cloud.shared.rpc.IAuctionService.disableAuction].
 */
@Serializable
data class AuctionSettingsDto(
    val auctionEnabled: Boolean,
    val auctionMaxValueLtr: Decimal?,
    val lastAcknowledgedByDisplayName: String?,
    val lastAcknowledgedAt: LocalDateTime?,
    val lastDisclaimerVersion: String?,
    /** V1.9.320 -- instance switch for auctions in Euro with direct payment (OFF by default) and the last OPERATOR acknowledgment of it. */
    val auctionFiatEnabled: Boolean = false,
    val fiatLastAcknowledgedByDisplayName: String? = null,
    val fiatLastAcknowledgedAt: LocalDateTime? = null,
    val fiatLastDisclaimerVersion: String? = null,
)

/**
 * V1.9.320 -- the seller's payment details for a Fiat auction, as typed into the create form. At least one of [iban]/[paymentLink] is required;
 * [accountHolder] always. The server validates (IBAN checksum and DE format, https link) and stores everything as ONE sealed blob. **[toString] is
 * redacted** so that a stray log line or a test failure message never prints the values.
 */
@Serializable
data class AuctionPaymentDetailsInput(
    val accountHolder: String,
    val iban: String? = null,
    val paymentLink: String? = null,
) {
    override fun toString(): String = "AuctionPaymentDetailsInput(redacted)"
}

/**
 * V1.9.320 -- Role: MEMBER+ (ACTIVE) and the instance switch `auctionFiatEnabled` must be on. [currency] must be a code of
 * [AuctionCurrencies.SUPPORTED]; the amounts are minor units (cents). [sellerDisclaimerVersion]/[sellerDisclaimerSha256] must echo the CURRENT
 * seller disclaimer, which the member must have acknowledged before ([network.lapis.cloud.shared.rpc.IAuctionService.acknowledgeFiatSellerDisclaimer]).
 */
@Serializable
data class CreateFiatAuctionListingInput(
    val title: String,
    val description: String,
    val currency: String,
    val startingPriceMinor: Long,
    val buyNowPriceMinor: Long? = null,
    val durationHours: Int,
    val paymentDetails: AuctionPaymentDetailsInput,
    val sellerDisclaimerVersion: String,
    val sellerDisclaimerSha256: String,
    /** V1.9.321: allow price offers. Needs [buyNowPriceMinor]; cannot be changed afterwards. */
    val offersEnabled: Boolean = false,
    // The federation consent is NOT part of the creation input: it is a separate step on the existing auction (added with its handling in step c2).
) {
    override fun toString(): String = "CreateFiatAuctionListingInput(redacted)"
}

/**
 * V1.9.320 -- the payment details of ONE Fiat auction as the winner (or the seller) may see them after the award. [ibanFormatted] is the IBAN in
 * groups of four; [reference] is the suggested payment reference ("Lapis-Auktion " + the first 8 characters of the auction id). **[toString] is
 * redacted.**
 */
@Serializable
data class AuctionPaymentDetailsDto(
    val accountHolder: String,
    val ibanFormatted: String?,
    val paymentLink: String?,
    /** Host of [paymentLink] (ASCII/punycode), shown next to the "open link" button so the winner sees where the link leads. */
    val paymentLinkHost: String?,
    val amountMinor: Long,
    val currency: String,
    val reference: String,
) {
    override fun toString(): String = "AuctionPaymentDetailsDto(redacted)"
}

/**
 * V1.9.320 -- what the create form needs to decide whether to offer "Euro": the instance switch, the CURRENT seller disclaimer (version, text,
 * hash) and whether the caller has already acknowledged exactly this version.
 */
@Serializable
data class AuctionFiatAvailabilityDto(
    val fiatEnabled: Boolean,
    val sellerDisclaimerVersion: String,
    val sellerDisclaimerText: String,
    val sellerDisclaimerSha256: String,
    val sellerAcknowledgedCurrentVersion: Boolean,
    val sellerAcknowledgedAt: LocalDateTime?,
)

/**
 * V1.9.320 -- change of the terms (currency and prices) of an own auction BEFORE the first bid. Same shape rules as the create calls: for an LTR
 * target [startingBidLtr]/[buyNowPriceLtr] are used, for a Fiat target [startingPriceMinor]/[buyNowPriceMinor] plus the payment details and the seller
 * disclaimer echo.
 */
@Serializable
data class UpdateAuctionTermsInput(
    val currency: String,
    val startingBidLtr: Decimal? = null,
    val buyNowPriceLtr: Decimal? = null,
    val startingPriceMinor: Long? = null,
    val buyNowPriceMinor: Long? = null,
    val paymentDetails: AuctionPaymentDetailsInput? = null,
    val sellerDisclaimerVersion: String? = null,
    val sellerDisclaimerSha256: String? = null,
) {
    override fun toString(): String = "UpdateAuctionTermsInput(redacted)"
}

/**
 * V1.9.321 (Fiat wave b "Preisvorschlag") -- the state of one price proposal. A counter-offer is a NEW row, so [COUNTERED] only ever marks the
 * buyer's proposal that was answered by one. The order is persisted by name (no ordinal), new literals are appended only.
 */
@Serializable
enum class AuctionOfferStatus { OFFERED, ACCEPTED, DECLINED, COUNTERED, EXPIRED, WITHDRAWN }

/** Limits and defaults of the price offers, shared by the server and the UI texts. */
object AuctionOfferRules {
    /** An offer lives this long, but never longer than the auction itself. */
    val OFFER_DEFAULT_VALIDITY: Duration = 48.hours
    const val OFFER_DEFAULT_VALIDITY_HOURS: Int = 48

    /** One open negotiation per buyer and auction. */
    const val MAX_OPEN_OFFERS_PER_BUYER_PER_AUCTION: Int = 1

    /** All NOT withdrawn offers and counter-offers of one auction (DoS guard). Withdrawn rows do not count, so make+withdraw cannot fill it. */
    const val MAX_OFFERS_PER_AUCTION: Int = 20

    /** All proposals of ONE buyer on one auction, withdrawn ones included (a make+withdraw loop is bounded by this). */
    const val MAX_PROPOSALS_PER_BUYER_PER_AUCTION: Int = 5

    /** After this many declined offers of one buyer on one auction he can make no more. */
    const val MAX_DECLINES_PER_BUYER_PER_AUCTION: Int = 3

    /** Cap of `listMyOffers`. */
    const val MAX_MY_OFFERS: Int = 200
}

/**
 * One price proposal (or counter-offer) as the CALLER may see it. [status] is the EFFECTIVE status: an OFFERED row whose [expiresAt] has passed
 * is shown as EXPIRED even if no write happened yet. [endedByAuction] distinguishes "the auction ended" from "the time ran out".
 * [buyerDisplayName] is filled only for the seller of the auction. The `callerCan*` flags tell the UI which buttons to show; the server checks again.
 */
@Serializable
data class AuctionOfferDto(
    val id: String,
    val auctionId: String,
    val auctionTitle: String,
    val fromSeller: Boolean,
    val parentOfferId: String?,
    val amountMinor: Long,
    val currency: String,
    val status: AuctionOfferStatus,
    val endedByAuction: Boolean,
    val createdAt: LocalDateTime,
    val expiresAt: LocalDateTime,
    val decidedAt: LocalDateTime?,
    val buyerDisplayName: String?,
    /** Only meaningful for the seller: the amount is below the current price of the auction. */
    val belowCurrentPrice: Boolean,
    val callerCanAccept: Boolean,
    val callerCanDecline: Boolean,
    val callerCanCounter: Boolean,
    val callerCanWithdraw: Boolean,
)
