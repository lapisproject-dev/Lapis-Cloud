package network.lapis.cloud.shared.domain

import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * V1.9.322 (Fiat wave (c)): "federation visibility" for Euro auctions. A seller may show his auction to PARTNER servers as a PREVIEW HINT (title,
 * price, end, a pseudonymous id). Nothing else leaves the origin: no seller, no bidder, no offer, no payment data. Bidding, buying and paying
 * happen on the ORIGIN server only. Architecture: `docs/architecture/auction-federation.adoc`.
 *
 * The wire type [AuctionTeaser] is pinned to exactly ten fields by `AuctionTeaserTripwireTest`; adding a field is a deliberate, reviewed act.
 */
@Serializable
enum class AuctionTeaserPriceKind { START, CURRENT }

/**
 * One hint on the wire. [teaserId] is a 64-character lower-case hex HMAC of the auction id (never the id itself). [revision] rises strictly per
 * teaser. [endsAt] and [priceAsOf] are floored to the minute. The `init` block checks the SYNTAX only; the content rules live in
 * [AuctionTeaserRules] and the receiver's validator, so a receiver can count a rejection per reason.
 */
@Serializable
data class AuctionTeaser(
    val teaserId: String,
    val revision: Long,
    val title: String,
    val currency: String,
    val priceKind: AuctionTeaserPriceKind,
    val priceMinor: Long,
    val buyNowMinor: Long? = null,
    val offersPossible: Boolean,
    val endsAt: Instant,
    val priceAsOf: Instant,
) {
    init {
        require(TEASER_ID_REGEX.matches(teaserId)) { "teaserId must be 64 lower-case hex characters" }
        require(revision >= 1) { "revision must be >= 1" }
        require(priceMinor > 0) { "priceMinor must be > 0" }
        require(buyNowMinor == null || buyNowMinor > 0) { "buyNowMinor must be > 0" }
    }

    companion object {
        val TEASER_ID_REGEX: Regex = Regex("^[0-9a-f]{64}$")
    }
}

object AuctionFederationLimits {
    const val MIN_HOPS: Int = 1
    const val DEFAULT_HOPS: Int = 1
    const val MAX_HOPS: Int = 2
    const val TITLE_MAX: Int = 80

    /** A consent needs at least this much running time left (a hint that would be withdrawn almost at once is noise). */
    const val MIN_REMAINING_FOR_CONSENT_HOURS: Int = 2

    /** The shown price is refreshed at most this often (a feed must not reveal the bid history). */
    const val PRICE_REFRESH_MINUTES: Int = 30

    /** At most this many auctions of one seller are shared at once (same number as [AuctionCurrencies.MAX_OPEN_PER_SELLER], separate on purpose). */
    const val MAX_SHARED_PER_SELLER: Int = 20
    const val PRICE_AS_OF_FUTURE_SKEW_MINUTES: Int = 5
}

enum class AuctionTitleProblem { EMPTY, TOO_LONG, CONTROL_OR_BIDI_OR_ZERO_WIDTH, CONTAINS_URL }

object AuctionTeaserRules {
    private fun isForbiddenChar(ch: Char): Boolean {
        val c = ch.code
        return c < 0x20 ||
            c in 0x7F..0x9F ||
            // control characters (Cc)
            c in 0x202A..0x202E ||
            c in 0x2066..0x2069 ||
            c == 0x200E ||
            c == 0x200F ||
            c == 0x061C ||
            // bidi controls (incl. ARABIC LETTER MARK)
            c in 0x200B..0x200D ||
            c in 0x2060..0x2064 ||
            c == 0xFEFF ||
            c == 0x2028 ||
            c == 0x2029 // zero width, line separators
    }

    private fun codePointCount(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            i += if (s[i].isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) 2 else 1
            n++
        }
        return n
    }

    /** The first problem of [title] as the receiver sees it, or `null` when it is acceptable. */
    fun titleProblem(title: String): AuctionTitleProblem? {
        if (title.isBlank()) return AuctionTitleProblem.EMPTY
        if (codePointCount(title) > AuctionFederationLimits.TITLE_MAX) return AuctionTitleProblem.TOO_LONG
        if (title.any(::isForbiddenChar)) return AuctionTitleProblem.CONTROL_OR_BIDI_OR_ZERO_WIDTH
        val lower = title.lowercase()
        if ("://" in lower || "www." in lower) return AuctionTitleProblem.CONTAINS_URL
        return null
    }

    /**
     * The title as it is shown to partners: trimmed, whitespace collapsed, cut to [AuctionFederationLimits.TITLE_MAX] code points (79 + an ellipsis).
     * `null` when a problem remains (control/bidi/zero-width characters, a URL, empty): such an auction is NOT shared, we never "repair" it.
     */
    fun projectTitle(localTitle: String): String? {
        val collapsed = localTitle.trim().replace(Regex("\\s+"), " ")
        val cut =
            if (codePointCount(collapsed) > AuctionFederationLimits.TITLE_MAX) {
                val sb = StringBuilder()
                var n = 0
                var i = 0
                while (i < collapsed.length && n < AuctionFederationLimits.TITLE_MAX - 1) {
                    val step = if (collapsed[i].isHighSurrogate() && i + 1 < collapsed.length && collapsed[i + 1].isLowSurrogate()) 2 else 1
                    sb.append(collapsed, i, i + step)
                    i += step
                    n++
                }
                sb.toString().trimEnd() + '…'
            } else {
                collapsed
            }
        return if (titleProblem(cut) == null) cut else null
    }
}

/** Why a seller may not (yet) share an auction. */
enum class AuctionConsentRefusal { NOT_FIAT, NOT_OPEN, ENDS_TOO_SOON, INSTANCE_OFF, TITLE }

/** The reason the consent is refused, or `null` when the seller may share. Used by the client preview AND the server, so both agree. */
fun mayConsentToAuctionFederation(
    currency: String,
    status: AuctionStatus,
    endsAt: Instant,
    instanceEnabled: Boolean,
    title: String,
    now: Instant,
): AuctionConsentRefusal? =
    when {
        !instanceEnabled -> AuctionConsentRefusal.INSTANCE_OFF
        currency !in AuctionCurrencies.SUPPORTED -> AuctionConsentRefusal.NOT_FIAT
        status != AuctionStatus.OPEN || endsAt <= now -> AuctionConsentRefusal.NOT_OPEN
        endsAt - now < AuctionFederationLimits.MIN_REMAINING_FOR_CONSENT_HOURS.hours -> AuctionConsentRefusal.ENDS_TOO_SOON
        AuctionTeaserRules.projectTitle(title) == null -> AuctionConsentRefusal.TITLE
        else -> null
    }

data class AuctionTeaserEligibility(
    val currency: String,
    val status: AuctionStatus,
    val endsAt: Instant,
    val optIn: Boolean,
    val instanceEnabled: Boolean,
    val auctionEnabled: Boolean,
    val fiatEnabled: Boolean,
    val partnerSharing: Boolean,
    val partnerActive: Boolean,
    val partnerBlocked: Boolean,
    val partnerKeyPending: Boolean,
    val sellerActive: Boolean,
    val title: String,
)

/**
 * THE eligibility rule for a hint to a partner: true only if EVERY condition holds. Status `OPEN` is never enough on its own: settlement is lazy,
 * so an ended auction can still say `OPEN` in the database -- [endsAt] must lie in the future.
 */
fun isAuctionTeaserEligible(
    e: AuctionTeaserEligibility,
    now: Instant,
): Boolean {
    if (!e.optIn || !e.instanceEnabled || !e.auctionEnabled || !e.fiatEnabled) return false
    if (e.currency !in AuctionCurrencies.SUPPORTED) return false
    if (e.status != AuctionStatus.OPEN || e.endsAt <= now) return false
    if (!e.partnerSharing || !e.partnerActive || e.partnerBlocked || e.partnerKeyPending) return false
    if (!e.sellerActive) return false
    return AuctionTeaserRules.projectTitle(e.title) != null
}

/** Canonical lower-case DNS host: labels `[a-z0-9-]` (1..63), at least one dot, no IP literal, no port, no userinfo. `xn--` labels are fine. */
fun isCanonicalFederationHost(host: String): Boolean {
    if (host.isEmpty() || host.length > 253 || '.' !in host) return false
    val labels = host.split('.')
    if (labels.any { it.isEmpty() || it.length > 63 || it.startsWith('-') || it.endsWith('-') }) return false
    if (!labels.all { l -> l.all { it in 'a'..'z' || it in '0'..'9' || it == '-' } }) return false
    // An all-numeric last label is an IPv4 literal, never a registrable name.
    return !labels.last().all { it in '0'..'9' }
}

/**
 * The ONLY builder of the link to the origin. The feed carries NO URL; the receiver builds it from the (signed) origin host and the teaser id. The
 * id sits in the hash fragment, so it reaches neither server logs nor the Referer. `null` if the host is not canonical or the id is not 64 hex.
 */
fun buildAuctionHintLink(
    originHost: String,
    teaserId: String,
): String? =
    if (isCanonicalFederationHost(originHost) && AuctionTeaser.TEASER_ID_REGEX.matches(teaserId)) {
        "https://$originHost/app#/auction/hint/$teaserId"
    } else {
        null
    }

@Serializable
data class AuctionFederationAvailabilityDto(
    val enabled: Boolean,
)

/** Admin view of the exchange: the switch, the hop limit and operational counters (numbers only, never a title or price). */
@Serializable
data class AuctionFederationSettingsDto(
    val enabled: Boolean,
    val hopLimit: Int,
    /** The key that derives the pseudonymous teaser ids is configured; without it the feed refuses every request. */
    val feedKeyAvailable: Boolean = false,
    val feedServedSinceStart: Long = 0,
    val feedNotModifiedSinceStart: Long = 0,
    val feedRefusedSinceStart: Long = 0,
    val feedRateLimitedSinceStart: Long = 0,
    val withdrawalsActive: Int = 0,
    val receivedHintsTotal: Int = 0,
    val receivedStoredRows: Int = 0,
    /** Rejected received hints by cause; the keys are [AuctionReceivedRejectReason] names, nothing else. */
    val receivedRejectedByReason: Map<String, Long> = emptyMap(),
    val receivedAcceptedSinceStart: Long = 0,
    val hiddenActive: Int = 0,
    val serverStartedAt: kotlinx.datetime.LocalDateTime? = null,
    /** V1.9.322 c2: how many auctions are shared with partners right now ("Derzeit geteilt: N"). */
    val sharedActive: Int = 0,
)

@Serializable
enum class AuctionReceivedRejectReason {
    SIGNATURE,
    UNKNOWN_ORIGIN_KEY,
    FORMAT,
    UNKNOWN_FIELD,
    TITLE,
    CURRENCY,
    AMOUNT,
    END,
    PRICE_AS_OF,
    HOP_LIMIT,
    REPLAY,
    TAMPER,
    EXPIRED,
    BLOCKED,
    CAPACITY,
}

/** A partner's auction as a member sees it. Pinned by a tripwire: exactly these fields, nothing about seller, bids or payment. */
@Serializable
data class PartnerAuctionHintDto(
    val originHost: String,
    val teaserId: String,
    val title: String,
    val currency: String,
    val priceKind: AuctionTeaserPriceKind,
    val priceMinor: Long,
    val priceAsOf: Instant,
    val buyNowMinor: Long?,
    val offersPossible: Boolean,
    val endsAt: Instant,
)

@Serializable
data class PartnerAuctionHintPageDto(
    val items: List<PartnerAuctionHintDto>,
    val hasMore: Boolean,
)

object PartnerAuctionHintLimits {
    const val PAGE_SIZE: Int = 50
    const val MAX_PAGE: Int = 19
    const val MAX_REACH_PARTNERS: Int = 50
}

/** The answer of the landing route: a hint link either opens one of OUR auctions or says, uniformly, that it is not available. */
@Serializable
sealed interface AuctionHintResolutionDto {
    @Serializable
    data class Available(
        val auctionId: String,
    ) : AuctionHintResolutionDto

    @Serializable
    data object Unavailable : AuctionHintResolutionDto
}

@Serializable
data class AuctionFederationReachDto(
    val enabled: Boolean,
    val partners: List<String>,
    val maxHops: Int,
    val partnersTruncated: Boolean,
)

/**
 * V1.9.322 (step c2): THE one projection of local auction values onto the wire. The server feed AND the client preview call it (like
 * `projectCarpoolTeaser`), so the owner sees exactly what a partner sees. Pinned by `AuctionFederationTripwireTest`.
 */
object AuctionTeaserProjection {
    /** The ONE switch of the badge "Preisvorschlag möglich" (decision 2026-11-24). `false` = never sent, never previewed. */
    const val OFFERS_BADGE_SHARED: Boolean = true

    /** Preview id for the client (64 zeros). Never sent anywhere. */
    const val PREVIEW_ID: String = "0000000000000000000000000000000000000000000000000000000000000000"

    /** The ONLY producer of the wire value `offersPossible`. */
    fun offersPossible(
        offersEnabled: Boolean,
        buyNowMinor: Long?,
        fiatEnabled: Boolean,
    ): Boolean = OFFERS_BADGE_SHARED && offersEnabled && buyNowMinor != null && fiatEnabled

    /** Floors an instant to the full minute (a feed must not reveal the second of a bid). */
    fun floorToMinute(i: Instant): Instant = Instant.fromEpochSeconds(i.epochSeconds.floorDiv(60L) * 60L)

    /**
     * `null` when the auction cannot be projected: LTR (never shared), a title that is not presentable (the caller normalises to NFC BEFORE the call),
     * or a price outside the accepted range. A Sofortkauf price is only sent when it lies above the transferred price.
     */
    fun project(
        teaserId: String,
        revision: Long,
        localTitle: String,
        currency: String,
        priceKind: AuctionTeaserPriceKind,
        priceMinor: Long,
        buyNowMinor: Long?,
        offersEnabled: Boolean,
        fiatEnabled: Boolean,
        endsAt: Instant,
        priceAsOf: Instant,
    ): AuctionTeaser? {
        if (currency !in AuctionCurrencies.SUPPORTED) return null
        val title = AuctionTeaserRules.projectTitle(localTitle) ?: return null
        if (priceMinor !in 1..AuctionCurrencies.MAX_MINOR) return null
        if (!AuctionTeaser.TEASER_ID_REGEX.matches(teaserId) || revision < 1) return null
        val buyNow = buyNowMinor?.takeIf { it > priceMinor && it <= AuctionCurrencies.MAX_MINOR }
        return AuctionTeaser(
            teaserId = teaserId,
            revision = revision,
            title = title,
            currency = currency,
            priceKind = priceKind,
            priceMinor = priceMinor,
            buyNowMinor = buyNow,
            offersPossible = offersPossible(offersEnabled = offersEnabled, buyNowMinor = buyNow, fiatEnabled = fiatEnabled),
            endsAt = floorToMinute(endsAt),
            priceAsOf = floorToMinute(priceAsOf),
        )
    }
}
