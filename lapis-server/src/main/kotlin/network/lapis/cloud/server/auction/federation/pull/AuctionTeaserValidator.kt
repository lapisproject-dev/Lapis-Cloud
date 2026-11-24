package network.lapis.cloud.server.auction.federation.pull

import network.lapis.cloud.server.partner.ForeignText
import network.lapis.cloud.shared.domain.AuctionCurrencies
import network.lapis.cloud.shared.domain.AuctionDurationRules
import network.lapis.cloud.shared.domain.AuctionFederationLimits
import network.lapis.cloud.shared.domain.AuctionReceivedRejectReason
import network.lapis.cloud.shared.domain.AuctionTeaser
import network.lapis.cloud.shared.domain.AuctionTeaserProjection
import network.lapis.cloud.shared.domain.AuctionTeaserRules
import java.text.Normalizer
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * V1.9.322 (step c3) -- the CONTENT rules of a hint, in ONE place and in a FIXED order (pinned by a test, because the order decides which cause is
 * counted when a hint has several faults). Pure: no database, no network. The receiver (reader) and the origin (feed builder, before it signs) both
 * call it, so what we would reject we never publish ourselves.
 *
 * Order: TITLE, CURRENCY, AMOUNT, EXPIRED, END, PRICE_AS_OF. The signature and the key are NOT checked here (the store does that, against the pin).
 */
internal object AuctionTeaserValidator {
    /** The first violated rule, or `null` when the hint is acceptable at [now]. */
    @Suppress("ReturnCount")
    fun check(
        teaser: AuctionTeaser,
        now: Instant,
    ): AuctionReceivedRejectReason? {
        if (AuctionTeaserRules.titleProblem(teaser.title) != null ||
            Normalizer.normalize(teaser.title, Normalizer.Form.NFC) != teaser.title ||
            ForeignText.clean(raw = teaser.title, maxChars = AuctionFederationLimits.TITLE_MAX) != teaser.title
        ) {
            return AuctionReceivedRejectReason.TITLE
        }
        // LTR is refused by name as a SECOND layer: whatever SUPPORTED may contain one day, an LTR hint is never shown or stored.
        if (teaser.currency == AuctionCurrencies.LTR || teaser.currency !in AuctionCurrencies.SUPPORTED) {
            return AuctionReceivedRejectReason.CURRENCY
        }
        val buyNow = teaser.buyNowMinor
        if (teaser.priceMinor !in 1..AuctionCurrencies.MAX_MINOR ||
            (buyNow != null && (buyNow <= teaser.priceMinor || buyNow > AuctionCurrencies.MAX_MINOR))
        ) {
            return AuctionReceivedRejectReason.AMOUNT
        }
        if (teaser.endsAt <= now) return AuctionReceivedRejectReason.EXPIRED
        if (teaser.endsAt > now + AuctionDurationRules.MAX_HOURS.hours || !isMinute(teaser.endsAt)) return AuctionReceivedRejectReason.END
        if (teaser.priceAsOf > now + AuctionFederationLimits.PRICE_AS_OF_FUTURE_SKEW_MINUTES.minutes ||
            teaser.priceAsOf > teaser.endsAt ||
            !isMinute(teaser.priceAsOf)
        ) {
            return AuctionReceivedRejectReason.PRICE_AS_OF
        }
        return null
    }

    private fun isMinute(i: Instant): Boolean = AuctionTeaserProjection.floorToMinute(i) == i
}
