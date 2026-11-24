package network.lapis.cloud.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** V1.9.322 (step c2) -- the ONE projection of local values onto the wire. */
class AuctionTeaserProjectionTest {
    private val id = "c".repeat(64)
    private val ends = Instant.parse("2026-12-03T10:15:42Z")
    private val asOf = Instant.parse("2026-12-01T09:30:59Z")

    private fun project(
        title: String = "Lastenrad",
        currency: String = "EUR",
        price: Long = 4200,
        buyNow: Long? = 9000,
        offers: Boolean = true,
        fiat: Boolean = true,
    ) = AuctionTeaserProjection.project(
        teaserId = id,
        revision = 3,
        localTitle = title,
        currency = currency,
        priceKind = AuctionTeaserPriceKind.START,
        priceMinor = price,
        buyNowMinor = buyNow,
        offersEnabled = offers,
        fiatEnabled = fiat,
        endsAt = ends,
        priceAsOf = asOf,
    )

    @Test
    fun ltrIsNeverProjected() {
        assertNull(project(currency = "LTR"))
    }

    @Test
    fun aTitleWithAUrlOrBidiControlIsNotProjected() {
        assertNull(project(title = "Schau http://spam.example"))
        assertNull(project(title = "Rad‮txt"))
        assertNull(project(title = "   "))
    }

    @Test
    fun theSofortkaufPriceIsOnlySentWhenItIsAboveThePrice() {
        assertEquals(9000L, project()!!.buyNowMinor)
        assertNull(project(price = 9000, buyNow = 9000)!!.buyNowMinor)
        assertNull(project(price = 9500, buyNow = 9000)!!.buyNowMinor)
        assertNull(project(buyNow = null)!!.buyNowMinor)
    }

    @Test
    fun timesAreFlooredToTheMinute() {
        val t = project()!!
        assertEquals(Instant.parse("2026-12-03T10:15:00Z"), t.endsAt)
        assertEquals(Instant.parse("2026-12-01T09:30:00Z"), t.priceAsOf)
        assertEquals(Instant.parse("2026-12-03T10:15:00Z"), AuctionTeaserProjection.floorToMinute(ends))
    }

    @Test
    fun theOffersBadgeNeedsOffersAndASofortkaufPriceAndTheEuroModuleAndTheSwitch() {
        assertTrue(AuctionTeaserProjection.OFFERS_BADGE_SHARED)
        assertTrue(project()!!.offersPossible)
        assertFalse(project(offers = false)!!.offersPossible)
        assertFalse(project(buyNow = null)!!.offersPossible)
        assertFalse(project(price = 9500, buyNow = 9000)!!.offersPossible)
        assertFalse(project(fiat = false)!!.offersPossible)
        assertFalse(AuctionTeaserProjection.offersPossible(offersEnabled = true, buyNowMinor = null, fiatEnabled = true))
    }

    @Test
    fun aPriceOutsideTheRangeIsNotProjected() {
        assertNull(project(price = 0))
        assertNull(project(price = AuctionCurrencies.MAX_MINOR + 1))
        assertNotNull(project(price = AuctionCurrencies.MAX_MINOR))
    }

    @Test
    fun thePreviewIdIsSixtyFourZeros() {
        assertTrue(AuctionTeaser.TEASER_ID_REGEX.matches(AuctionTeaserProjection.PREVIEW_ID))
        assertEquals(64, AuctionTeaserProjection.PREVIEW_ID.length)
    }
}
