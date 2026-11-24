package network.lapis.cloud.shared.domain

import kotlinx.serialization.descriptors.elementNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** V1.9.322 -- the wire type of an auction hint carries exactly ten fields and nothing about seller, bids, offers or payment. */
class AuctionTeaserTripwireTest {
    private val id = "a".repeat(64)
    private val now = Instant.parse("2026-12-01T10:00:00Z")

    @Test
    fun theWireTypeHasExactlyTenFields() {
        assertEquals(
            setOf(
                "teaserId",
                "revision",
                "title",
                "currency",
                "priceKind",
                "priceMinor",
                "buyNowMinor",
                "offersPossible",
                "endsAt",
                "priceAsOf",
            ),
            AuctionTeaser
                .serializer()
                .descriptor.elementNames
                .toSet(),
        )
    }

    @Test
    fun theMemberDtoHasExactlyTheShownFields() {
        assertEquals(
            setOf(
                "originHost",
                "teaserId",
                "title",
                "currency",
                "priceKind",
                "priceMinor",
                "priceAsOf",
                "buyNowMinor",
                "offersPossible",
                "endsAt",
            ),
            PartnerAuctionHintDto
                .serializer()
                .descriptor.elementNames
                .toSet(),
        )
    }

    @Test
    fun noFieldNameCanLeakPeopleOrPayment() {
        val forbidden = listOf("seller", "bid", "buyer", "offer", "iban", "payment", "description", "image", "url", "member", "winner")
        val names =
            AuctionTeaser
                .serializer()
                .descriptor.elementNames
                .toList() +
                PartnerAuctionHintDto
                    .serializer()
                    .descriptor.elementNames
                    .toList()
        names.forEach { n ->
            forbidden.forEach { f ->
                // "offersPossible" is the one allowed carrier of the word "offer": a boolean badge, never an amount or a person.
                if (n != "offersPossible") assertFalse(n.lowercase().contains(f), "$n contains $f")
            }
        }
    }

    private fun make(
        teaserId: String = id,
        revision: Long = 1,
        price: Long = 1000,
        buyNow: Long? = null,
    ) = AuctionTeaser(
        teaserId = teaserId,
        revision = revision,
        title = "A bike",
        currency = "EUR",
        priceKind = AuctionTeaserPriceKind.START,
        priceMinor = price,
        buyNowMinor = buyNow,
        offersPossible = false,
        endsAt = now + 5.hours,
        priceAsOf = now,
    )

    @Test
    fun initChecksTheSyntaxOnly() {
        assertNotNull(make())
        assertFailsWith<IllegalArgumentException> { make(teaserId = "A".repeat(64)) }
        assertFailsWith<IllegalArgumentException> { make(teaserId = "a".repeat(63)) }
        assertFailsWith<IllegalArgumentException> { make(revision = 0) }
        assertFailsWith<IllegalArgumentException> { make(price = 0) }
        assertFailsWith<IllegalArgumentException> { make(buyNow = 0) }
    }

    @Test
    fun titleRules() {
        assertEquals(AuctionTitleProblem.EMPTY, AuctionTeaserRules.titleProblem("  "))
        assertEquals(AuctionTitleProblem.TOO_LONG, AuctionTeaserRules.titleProblem("x".repeat(81)))
        assertEquals(AuctionTitleProblem.CONTAINS_URL, AuctionTeaserRules.titleProblem("see HTTP://evil.example"))
        assertEquals(AuctionTitleProblem.CONTAINS_URL, AuctionTeaserRules.titleProblem("WWW.evil.example"))
        assertEquals(AuctionTitleProblem.CONTROL_OR_BIDI_OR_ZERO_WIDTH, AuctionTeaserRules.titleProblem("a‮b"))
        assertEquals(AuctionTitleProblem.CONTROL_OR_BIDI_OR_ZERO_WIDTH, AuctionTeaserRules.titleProblem("a​b"))
        assertEquals(AuctionTitleProblem.CONTROL_OR_BIDI_OR_ZERO_WIDTH, AuctionTeaserRules.titleProblem("a\u0007b"))
        assertEquals(AuctionTitleProblem.CONTROL_OR_BIDI_OR_ZERO_WIDTH, AuctionTeaserRules.titleProblem("a\u061Cb"))
        for (cp in 0x2060..0x2064) {
            assertEquals(AuctionTitleProblem.CONTROL_OR_BIDI_OR_ZERO_WIDTH, AuctionTeaserRules.titleProblem("a${cp.toChar()}b"))
        }
        assertNull(AuctionTeaserRules.titleProblem("Trekking bike, 28 inch"))
    }

    @Test
    fun projectionCollapsesCutsAndRefuses() {
        assertEquals("a b", AuctionTeaserRules.projectTitle("  a \n  b "))
        assertNull(AuctionTeaserRules.projectTitle("buy at www.x.de"))
        assertNull(AuctionTeaserRules.projectTitle("a‮b"))
        val cut = AuctionTeaserRules.projectTitle("y".repeat(200))!!
        assertEquals(80, cut.length)
        assertTrue(cut.endsWith("…"))
        // A space right before the cut leaves no gap in front of the ellipsis.
        val spaced = AuctionTeaserRules.projectTitle("y".repeat(78) + " " + "z".repeat(50))!!
        assertEquals("y".repeat(78) + "…", spaced)
        // A surrogate pair at the cut position is never split.
        val emoji = "😀"
        val projected = AuctionTeaserRules.projectTitle(emoji.repeat(100))!!
        assertNull(AuctionTeaserRules.titleProblem(projected))
        assertFalse(projected.dropLast(1).last().isHighSurrogate())
    }

    @Test
    fun hostAndLinkBuilding() {
        assertEquals(
            "https://cloud.example.org/app#/auction/hint/$id",
            buildAuctionHintLink(originHost = "cloud.example.org", teaserId = id),
        )
        assertEquals(
            "https://xn--bcher-kva.example/app#/auction/hint/$id",
            buildAuctionHintLink(originHost = "xn--bcher-kva.example", teaserId = id),
        )
        listOf(
            "1.2.3.4",
            "localhost",
            "Cloud.Example.org",
            "a_b.example",
            "a.example:8443",
            "u@a.example",
            "a.example/x",
            "-a.example",
            "",
        ).forEach {
            assertNull(buildAuctionHintLink(originHost = it, teaserId = id), it)
        }
        assertNull(buildAuctionHintLink(originHost = "cloud.example.org", teaserId = "A".repeat(64)))
        assertNull(buildAuctionHintLink(originHost = "cloud.example.org", teaserId = "a".repeat(63)))
    }

    private fun eligibility(
        currency: String = "EUR",
        status: AuctionStatus = AuctionStatus.OPEN,
        endsAt: Instant = now + 5.hours,
        optIn: Boolean = true,
        instance: Boolean = true,
        auction: Boolean = true,
        fiat: Boolean = true,
        sharing: Boolean = true,
        active: Boolean = true,
        blocked: Boolean = false,
        pending: Boolean = false,
        sellerActive: Boolean = true,
        title: String = "A bike",
    ) = AuctionTeaserEligibility(
        currency = currency,
        status = status,
        endsAt = endsAt,
        optIn = optIn,
        instanceEnabled = instance,
        auctionEnabled = auction,
        fiatEnabled = fiat,
        partnerSharing = sharing,
        partnerActive = active,
        partnerBlocked = blocked,
        partnerKeyPending = pending,
        sellerActive = sellerActive,
        title = title,
    )

    @Test
    fun eligibilityMatrix() {
        assertTrue(isAuctionTeaserEligible(e = eligibility(), now = now))
        assertFalse(isAuctionTeaserEligible(e = eligibility(currency = "LTR"), now = now))
        assertFalse(isAuctionTeaserEligible(e = eligibility(status = AuctionStatus.AWAITING_PAYMENT), now = now))
        // Lazy settlement: an ended auction still says OPEN in the database.
        assertFalse(isAuctionTeaserEligible(e = eligibility(endsAt = now), now = now))
        assertFalse(isAuctionTeaserEligible(e = eligibility(endsAt = now - 1.minutes), now = now))
        assertFalse(isAuctionTeaserEligible(e = eligibility(optIn = false), now = now))
        assertFalse(isAuctionTeaserEligible(e = eligibility(instance = false), now = now))
        assertFalse(isAuctionTeaserEligible(e = eligibility(auction = false), now = now))
        assertFalse(isAuctionTeaserEligible(e = eligibility(fiat = false), now = now))
        assertFalse(isAuctionTeaserEligible(e = eligibility(sharing = false), now = now))
        assertFalse(isAuctionTeaserEligible(e = eligibility(active = false), now = now))
        assertFalse(isAuctionTeaserEligible(e = eligibility(blocked = true), now = now))
        assertFalse(isAuctionTeaserEligible(e = eligibility(pending = true), now = now))
        assertFalse(isAuctionTeaserEligible(e = eligibility(sellerActive = false), now = now))
        assertFalse(isAuctionTeaserEligible(e = eligibility(title = "see www.x.de"), now = now))
    }

    @Test
    fun consentRules() {
        fun r(
            currency: String = "EUR",
            status: AuctionStatus = AuctionStatus.OPEN,
            ends: Instant = now + 5.hours,
            instance: Boolean = true,
            title: String = "A bike",
        ) = mayConsentToAuctionFederation(
            currency = currency,
            status = status,
            endsAt = ends,
            instanceEnabled = instance,
            title = title,
            now = now,
        )
        assertNull(r())
        assertEquals(AuctionConsentRefusal.INSTANCE_OFF, r(instance = false))
        assertEquals(AuctionConsentRefusal.NOT_FIAT, r(currency = "LTR"))
        assertEquals(AuctionConsentRefusal.NOT_OPEN, r(status = AuctionStatus.PAID))
        assertEquals(AuctionConsentRefusal.NOT_OPEN, r(ends = now))
        assertEquals(AuctionConsentRefusal.ENDS_TOO_SOON, r(ends = now + 119.minutes))
        assertNull(r(ends = now + 120.minutes))
        assertEquals(AuctionConsentRefusal.TITLE, r(title = "go to www.x.de"))
    }

    @Test
    fun hopLimitsAreOneToTwo() {
        assertEquals(1, AuctionFederationLimits.MIN_HOPS)
        assertEquals(1, AuctionFederationLimits.DEFAULT_HOPS)
        assertEquals(2, AuctionFederationLimits.MAX_HOPS)
    }
}
