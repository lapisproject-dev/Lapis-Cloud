package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AuctionDto
import network.lapis.cloud.shared.domain.AuctionStatus
import network.lapis.cloud.shared.rpc.AuctionFederationEndsTooSoonException
import network.lapis.cloud.shared.rpc.AuctionFederationNotAllowedException
import network.lapis.cloud.shared.rpc.AuctionFederationShareLimitException
import network.lapis.cloud.shared.rpc.AuctionFederationTitleException
import network.lapis.cloud.shared.rpc.AuctionFederationUnavailableException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** V1.9.322 (step c2) -- the DOM-free decisions of the consent box and of the landing route. */
class AuctionFederationUiLogicTest {
    private val now = Instant.parse("2026-12-01T10:00:00Z")
    private val end = now + 24.hours

    private fun show(
        currency: String = "EUR",
        status: AuctionStatus = AuctionStatus.OPEN,
        endsAt: Instant = end,
        instance: Boolean = true,
        shared: Boolean = false,
    ) = AuctionFederationUiLogic.showOptIn(
        instanceEnabled = instance,
        currency = currency,
        status = status,
        endsAt = endsAt,
        now = now,
        alreadyOptedIn = shared,
    )

    @Test
    fun theBoxIsOfferedOnlyForAnOpenEuroAuctionWhileTheExchangeIsOn() {
        assertTrue(show())
        assertFalse(show(currency = "LTR"))
        assertFalse(show(instance = false))
        assertFalse(show(status = AuctionStatus.CLOSED_NO_SALE))
        assertFalse(show(status = AuctionStatus.AWAITING_PAYMENT))
        assertFalse(show(endsAt = now - 1.minutes))
        assertFalse(show(endsAt = now))
    }

    @Test
    fun anAlreadySharedAuctionKeepsTheBoxSoTheConsentCanBeTakenBack() {
        assertTrue(show(instance = false, shared = true))
        assertFalse(show(instance = false, shared = true, currency = "LTR"))
    }

    private fun preview(
        title: String = "Lastenrad",
        price: Long = 4200,
        bids: Boolean = false,
        buyNow: Long? = null,
        offers: Boolean = false,
        currency: String = "EUR",
    ) = AuctionFederationUiLogic.previewTeaser(
        title = title,
        currency = currency,
        priceMinor = price,
        hasBids = bids,
        buyNowMinor = buyNow,
        offersEnabled = offers,
        endsAt = end,
        now = now,
    )

    @Test
    fun thePreviewIsTheSharedProjectionAndNeverLtr() {
        assertNull(preview(currency = "LTR"))
        assertNull(preview(title = "Siehe http://spam.example"))
        val t = assertNotNull(preview(buyNow = 9000, offers = true))
        assertEquals("Lastenrad", t.title)
        assertEquals(9000L, t.buyNowMinor)
        assertTrue(t.offersPossible)
        assertEquals("0".repeat(64), t.teaserId)
    }

    @Test
    fun thePreviewTextHasNoPlaceholderLeftAndNamesTheOptionalParts() {
        val plain = AuctionFederationUiLogic.previewText(preview())
        assertFalse(plain.contains("%1") || plain.contains("%2") || plain.contains("%3") || plain.contains("%4"), plain)
        assertTrue(plain.contains("Lastenrad"))
        assertFalse(plain.contains("Sofortkauf"))
        assertFalse(plain.contains("Preisvorschlag"))
        val rich = AuctionFederationUiLogic.previewText(preview(buyNow = 9000, offers = true))
        assertTrue(rich.contains("Sofortkauf"), rich)
        assertTrue(rich.contains("Preisvorschlag möglich"), rich)
        assertEquals(AuctionFederationUiLogic.titleRefusalText(), AuctionFederationUiLogic.previewText(null))
        // with bids the price carries the current-price label
        assertTrue(AuctionFederationUiLogic.previewText(preview(bids = true)).contains("Aktueller Preis"))
    }

    @Test
    fun theExplanationNamesTheRefreshIntervalAndThatAJumpShowsBids() {
        val text = AuctionFederationUiLogic.explanationText()
        assertTrue(text.contains("30 Minuten"), text)
        assertTrue(text.contains("lässt erkennen, ob geboten wurde"), text)
        assertFalse(text.contains("%1"))
    }

    @Test
    fun everyTypedRefusalHasItsOwnFixedText() {
        val texts =
            listOf(
                AuctionFederationEndsTooSoonException(),
                AuctionFederationShareLimitException(),
                AuctionFederationUnavailableException(),
                AuctionFederationTitleException(),
                AuctionFederationNotAllowedException(),
            ).map { assertNotNull(AuctionFederationUiLogic.refusalText(it)) }
        assertEquals(5, texts.toSet().size, "each refusal says something different")
        assertTrue(texts[1].contains("20"), "the limit comes from the shared constant")
        assertTrue(texts[0].contains("2"), "the minimum comes from the shared constant")
        assertNull(AuctionFederationUiLogic.refusalText(IllegalStateException("x")))
        texts.forEach { assertFalse(it.contains("%1"), it) }
    }

    @Test
    fun theConsentCallIsMadeOnlyWhenTheChoiceChangedAndTheHintShowsOnlyWhenTakingBack() {
        assertTrue(AuctionFederationUiLogic.mustCallSetOptIn(initial = false, wanted = true))
        assertTrue(AuctionFederationUiLogic.mustCallSetOptIn(initial = true, wanted = false))
        assertFalse(AuctionFederationUiLogic.mustCallSetOptIn(initial = true, wanted = true))
        assertFalse(AuctionFederationUiLogic.mustCallSetOptIn(initial = false, wanted = false))
        assertTrue(AuctionFederationUiLogic.withdrawalHintVisible(initial = true, checked = false))
        assertFalse(AuctionFederationUiLogic.withdrawalHintVisible(initial = false, checked = false))
        assertFalse(AuctionFederationUiLogic.withdrawalHintVisible(initial = true, checked = true))
        assertFalse(AuctionFederationUiLogic.initialOptIn(null))
    }

    private fun dto(
        optIn: Boolean,
        status: AuctionStatus = AuctionStatus.OPEN,
        ends: LocalDateTime = LocalDateTime(2026, 12, 2, 10, 0),
    ) = AuctionDto(
        id = "a",
        title = "T",
        description = "d",
        sellerMemberId = "s",
        sellerDisplayName = "S",
        startingBidLtr = null,
        buyNowPriceLtr = null,
        status = status,
        effectiveStatus = status,
        currentPriceLtr = null,
        currentLeaderDisplayName = null,
        leaderIsMe = false,
        bidCount = 0,
        winnerMemberId = null,
        winnerDisplayName = null,
        finalPriceLtr = null,
        listingFeeLtr = 0.0.toDecimal(),
        createdAt = LocalDateTime(2026, 12, 1, 0, 0),
        endsAt = ends,
        settledAt = null,
        currency = "EUR",
        federationOptIn = optIn,
    )

    @Test
    fun theBadgeShowsOnlyWhileTheAuctionReallyIsShared() {
        assertTrue(AuctionFederationUiLogic.badgeVisible(dto(optIn = true), now))
        assertFalse(AuctionFederationUiLogic.badgeVisible(dto(optIn = false), now))
        assertFalse(AuctionFederationUiLogic.badgeVisible(dto(optIn = true, status = AuctionStatus.CLOSED_NO_SALE), now))
        assertFalse(AuctionFederationUiLogic.badgeVisible(dto(optIn = true, ends = LocalDateTime(2026, 12, 1, 9, 0)), now))
        assertEquals(carpoolFederationBadgeText(), auctionFederationBadgeText())
        assertTrue(AuctionFederationUiLogic.initialOptIn(dto(optIn = true)))
    }

    @Test
    fun theHintReturnRemembersOnlyAValidIdAndOnlyOnce() {
        val valid = "a".repeat(64)
        consumeAuctionHintReturn()
        rememberAuctionHintReturn("not-an-id")
        assertNull(consumeAuctionHintReturn())
        rememberAuctionHintReturn("A".repeat(64))
        assertNull(consumeAuctionHintReturn())
        rememberAuctionHintReturn(valid)
        assertEquals(valid, consumeAuctionHintReturn())
        assertNull(consumeAuctionHintReturn(), "used once")
        rememberAuctionHintReturn(valid, nowMs = 1000.0)
        assertNull(consumeAuctionHintReturn(nowMs = 1000.0 + AUCTION_HINT_RETURN_TTL_MS + 1), "expired")
        assertEquals("/auction/hint/$valid", auctionHintRoute(valid))
    }

    @Test
    fun theSavedButNotSharedSentenceKeepsTheReason() {
        val text = AuctionFederationUiLogic.savedButNotSharedText("Grund")
        assertTrue(text.contains("Grund"))
        assertTrue(text.contains("gespeichert"))
    }
}
