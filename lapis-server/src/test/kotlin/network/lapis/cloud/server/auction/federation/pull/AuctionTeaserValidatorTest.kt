package network.lapis.cloud.server.auction.federation.pull

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.AuctionDurationRules
import network.lapis.cloud.shared.domain.AuctionReceivedRejectReason
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** V1.9.322 c3 -- the content rules of a received hint, each on its own and in the pinned order. */
class AuctionTeaserValidatorTest :
    FunSpec({
        val o = AuctionOriginServer(host = "a.beispiel.de")
        val now = AUCTION_PULL_NOW_INSTANT

        fun check(t: network.lapis.cloud.shared.domain.AuctionTeaser) = AuctionTeaserValidator.check(teaser = t, now = now)

        test("a plain euro hint is fine") {
            check(o.teaser(seed = "1")) shouldBe null
            check(o.teaser(seed = "1", buyNowMinor = 5000, offersPossible = true)) shouldBe null
        }

        test("TITLE: bidi, zero width, a link, a title that is not NFC, and a title of 81 code points") {
            listOf(
                "ab‮cd",
                "ab​cd",
                "see http://x.de",
                "www.example.de",
                "é", // not NFC
                "x".repeat(81),
                "  leading blanks",
            ).forEach { check(o.teaser(seed = "1", title = it)) shouldBe AuctionReceivedRejectReason.TITLE }
            check(o.teaser(seed = "1", title = "x".repeat(80))) shouldBe null
        }

        test("CURRENCY: LTR is refused by name, an unsupported code too") {
            check(o.teaser(seed = "1", currency = "LTR")) shouldBe AuctionReceivedRejectReason.CURRENCY
            check(o.teaser(seed = "1", currency = "USD")) shouldBe AuctionReceivedRejectReason.CURRENCY
        }

        test("AMOUNT: price 0 and above the maximum, a Sofortkauf price not above the price") {
            check(o.teaser(seed = "1", priceMinor = 10_000_001)) shouldBe AuctionReceivedRejectReason.AMOUNT
            check(o.teaser(seed = "1", priceMinor = 100, buyNowMinor = 100)) shouldBe AuctionReceivedRejectReason.AMOUNT
            check(o.teaser(seed = "1", priceMinor = 100, buyNowMinor = 10_000_001)) shouldBe AuctionReceivedRejectReason.AMOUNT
        }

        test("EXPIRED and END") {
            check(o.teaser(seed = "1").copy(endsAt = now)) shouldBe AuctionReceivedRejectReason.EXPIRED
            check(o.teaser(seed = "1").copy(endsAt = now - 1.hours)) shouldBe AuctionReceivedRejectReason.EXPIRED
            check(o.teaser(seed = "1").copy(endsAt = now + AuctionDurationRules.MAX_HOURS.hours + 1.minutes)) shouldBe
                AuctionReceivedRejectReason.END
            check(o.teaser(seed = "1").copy(endsAt = now + AuctionDurationRules.MAX_HOURS.hours)) shouldBe null
            check(o.teaser(seed = "1").copy(endsAt = now + 5.hours + 30.seconds)) shouldBe AuctionReceivedRejectReason.END
        }

        test("PRICE_AS_OF: in the future, after the end, not on the minute") {
            check(o.teaser(seed = "1").copy(priceAsOf = now + 6.minutes)) shouldBe AuctionReceivedRejectReason.PRICE_AS_OF
            check(o.teaser(seed = "1").copy(priceAsOf = now + 5.minutes)) shouldBe null
            check(o.teaser(seed = "1", endsIn = 1).copy(priceAsOf = now + 2.hours)) shouldBe AuctionReceivedRejectReason.PRICE_AS_OF
            check(o.teaser(seed = "1").copy(priceAsOf = now - 30.seconds)) shouldBe AuctionReceivedRejectReason.PRICE_AS_OF
        }

        test("the order is pinned: a hint with several faults is counted by the FIRST rule") {
            val all = o.teaser(seed = "1", title = "ab​cd", currency = "LTR").copy(endsAt = now)
            check(all) shouldBe AuctionReceivedRejectReason.TITLE
            check(all.copy(title = "ok")) shouldBe AuctionReceivedRejectReason.CURRENCY
            check(all.copy(title = "ok", currency = "EUR", priceMinor = 10_000_001)) shouldBe AuctionReceivedRejectReason.AMOUNT
            check(all.copy(title = "ok", currency = "EUR")) shouldBe AuctionReceivedRejectReason.EXPIRED
        }
    })
