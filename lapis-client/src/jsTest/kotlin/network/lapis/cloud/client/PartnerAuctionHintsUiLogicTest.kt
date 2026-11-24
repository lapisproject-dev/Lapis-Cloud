package network.lapis.cloud.client

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.shared.domain.AuctionTeaserPriceKind
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PartnerAuctionHintDto
import network.lapis.cloud.shared.domain.PartnerAuctionHintRules
import network.lapis.cloud.shared.domain.mayShowPartnerAuctionHints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** V1.9.322 c3 -- the DOM-free decisions of the partner auction cards. German source texts (no catalog is loaded in this test). */
class PartnerAuctionHintsUiLogicTest {
    private val logic = PartnerAuctionHintsUiLogic
    private val now = Instant.parse("2026-11-24T10:00:00Z")
    private val id = "a".repeat(64)

    private fun hint(
        host: String = "o.beispiel.de",
        kind: AuctionTeaserPriceKind = AuctionTeaserPriceKind.CURRENT,
        buyNow: Long? = null,
        offers: Boolean = false,
        endsAt: Instant = now + 5.hours,
    ) = PartnerAuctionHintDto(
        originHost = host,
        teaserId = id,
        title = "Lastenrad",
        currency = "EUR",
        priceKind = kind,
        priceMinor = 2500,
        priceAsOf = now,
        buyNowMinor = buyNow,
        offersPossible = offers,
        endsAt = endsAt,
    )

    @Test
    fun theLabelFollowsTheKindOfThePrice() {
        assertEquals("Startpreis", logic.priceLabel(AuctionTeaserPriceKind.START))
        assertEquals("Aktueller Preis", logic.priceLabel(AuctionTeaserPriceKind.CURRENT))
    }

    @Test
    fun aPriceIsShownExactToTheCent() {
        assertTrue(logic.formatPrice(1, "EUR").contains("0,01"), logic.formatPrice(1, "EUR"))
        assertTrue(logic.formatPrice(10_000_000, "EUR").contains("100.000,00"), logic.formatPrice(10_000_000, "EUR"))
        assertTrue(logic.formatPrice(2500, "EUR").contains("25,00"))
        assertEquals("LTR von o.beispiel.de", logic.ltrFallback("o.beispiel.de"))
    }

    @Test
    fun theStandTextOnTheBoundaries() {
        val current = AuctionTeaserPriceKind.CURRENT
        assertNull(logic.asOfText(AuctionTeaserPriceKind.START, now, now + 3.hours))
        assertEquals("· Stand: gerade eben", logic.asOfText(current, now, now))
        assertEquals("· Stand: gerade eben", logic.asOfText(current, now, now + 59.seconds))
        assertEquals("· Stand: vor 1 Min.", logic.asOfText(current, now, now + 60.seconds))
        assertEquals("· Stand: vor 59 Min.", logic.asOfText(current, now, now + 59.minutes + 59.seconds))
        assertEquals("· Stand: vor 1 Std.", logic.asOfText(current, now, now + 60.minutes))
        assertEquals("· Stand: vor 1 Std.", logic.asOfText(current, now, now + 119.minutes))
        assertEquals("· Stand: vor 2 Std.", logic.asOfText(current, now, now + 120.minutes))
    }

    @Test
    fun theEndTextOnTheBoundaries() {
        val absolute = "endet am " + formatSystemDateTime((now + 61.minutes).toLocalDateTime(TimeZone.UTC))
        assertEquals(absolute, logic.endText(now + 61.minutes, now))
        assertEquals(
            "endet am " + formatSystemDateTime((now + 60.minutes).toLocalDateTime(TimeZone.UTC)),
            logic.endText(now + 60.minutes, now),
        )
        assertEquals("endet in 59 Min.", logic.endText(now + 59.minutes, now))
        assertEquals("endet in 59 Min.", logic.endText(now + 59.minutes + 59.seconds, now))
        assertEquals("endet in 1 Min.", logic.endText(now + 1.minutes, now))
        assertEquals("endet in weniger als 1 Min.", logic.endText(now + 59.seconds, now))
        assertEquals("endet in weniger als 1 Min.", logic.endText(now, now))
    }

    @Test
    fun aHintThatEndedIsFilteredOutAtTheMomentOfItsEnd() {
        val ending = hint(host = "a.beispiel.de", endsAt = now + 1.minutes)
        val later = hint(host = "b.beispiel.de", endsAt = now + 2.hours)
        assertEquals(2, logic.visible(listOf(ending, later), now).size)
        assertEquals(listOf("b.beispiel.de"), logic.visible(listOf(ending, later), now + 1.minutes).map { it.originHost })
        assertTrue(PartnerAuctionHintRules.isShowable(now + 1.seconds, now))
        assertFalse(PartnerAuctionHintRules.isShowable(now, now))
    }

    @Test
    fun theClientNeverAsksForAPageTheServerWouldRefuse() {
        assertTrue(logic.canLoadMore(hasMore = true, page = 18))
        assertFalse(logic.canLoadMore(hasMore = true, page = 19))
        assertFalse(logic.canLoadMore(hasMore = false, page = 0))
    }

    @Test
    fun theBadgeAndTheBuyNowLineAppearOnlyWhenTheHintCarriesThem() {
        assertFalse(logic.showBadge(hint()))
        assertTrue(logic.showBadge(hint(offers = true)))
        assertNull(logic.buyNowLine(hint()))
        assertTrue(assertNotNull(logic.buyNowLine(hint(buyNow = 9900))).startsWith("Sofortkauf "))
    }

    @Test
    fun theLinkIsBuiltOnlyForACanonicalHost() {
        assertEquals("https://o.beispiel.de/app#/auction/hint/$id", logic.link(hint()))
        assertNull(logic.link(hint(host = "o.beispiel.de:8443")))
        assertNull(logic.link(hint(host = "127.0.0.1")))
        assertNull(logic.link(hint(host = "O.Beispiel.de")))
        assertEquals("Auf o.beispiel.de ansehen", logic.viewLabel("o.beispiel.de"))
        assertEquals("Auf o.beispiel.de ansehen (öffnet neues Fenster)", logic.viewAria("o.beispiel.de"))
    }

    @Test
    fun onlyAnActiveMemberWithTheSwitchSeesTheSection() {
        assertTrue(mayShowPartnerAuctionHints(MemberStatus.ACTIVE, true))
        assertFalse(mayShowPartnerAuctionHints(MemberStatus.ACTIVE, false))
        listOf(MemberStatus.GUEST, MemberStatus.FRIEND, MemberStatus.DONOR, MemberStatus.APPLICATION, null).forEach {
            assertFalse(mayShowPartnerAuctionHints(it, true), "$it")
        }
    }
}
