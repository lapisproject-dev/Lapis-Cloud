package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.AuctionStatus
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * LTR-Wirtschaft UI wave -- covers the pure, DOM-independent label/color table in
 * `AuctionScreen.kt` ([auctionStatusLabel]/[auctionStatusColor]), same scope posture as
 * [AccountingLabelsTest]/[MeetingsScreenTest]. No rendering harness exists in this module (see
 * [GovernanceAuthzUiTest] KDoc), so the DOM-building `renderAuctionScreen`/`renderAuctionCard`
 * etc. are out of scope here, same as every other screen's `*ScreenTest.kt`.
 */
class AuctionScreenTest {
    private val semanticColors = setOf("primary", "secondary", "success", "danger", "warning", "info", "dark")

    @Test
    fun auctionStatusLabel_isNonBlankForEveryValue() {
        AuctionStatus.entries.forEach { status ->
            assertTrue(auctionStatusLabel(status).isNotBlank(), "expected a non-blank label for $status")
        }
    }

    @Test
    fun auctionStatusColor_isARealBootstrapHueForEveryValue() {
        AuctionStatus.entries.forEach { status ->
            val color = auctionStatusColor(status)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $status, got \"$color\"")
        }
    }
}
