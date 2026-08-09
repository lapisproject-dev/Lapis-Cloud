package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import network.lapis.cloud.shared.domain.AnchorAsset
import network.lapis.cloud.shared.domain.PriceStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * LTR-Wirtschaft UI wave -- covers the pure, DOM-independent surface of `PriceOracleScreen.kt`:
 * [formatDonationAmount] (the currency-parameterized sibling of `Money.kt`'s [formatMoney]) plus
 * the label/color tables ([anchorAssetLabel], [priceStatusLabel]/[priceStatusColor]). No rendering
 * harness exists in this module (see [GovernanceAuthzUiTest] KDoc), so the DOM-building
 * `renderPriceOracleScreen` etc. are out of scope here, same as every other screen's
 * `*ScreenTest.kt`.
 */
class PriceOracleScreenTest {
    private val semanticColors = setOf("primary", "secondary", "success", "danger", "warning", "info", "dark")

    @Test
    fun formatDonationAmount_appendsTheCurrencyCodeVerbatim() {
        val amount = 42.5.toDecimal()
        assertEquals("$amount USD", formatDonationAmount(amount, "USD"))
    }

    @Test
    fun formatDonationAmount_blankCurrencyRendersTheBareNumberOnly() {
        val amount = 42.5.toDecimal()
        assertEquals("$amount", formatDonationAmount(amount, ""))
    }

    @Test
    fun formatDonationAmount_preservesANegativeSignVerbatim() {
        val amount = (-3.0).toDecimal()
        val formatted = formatDonationAmount(amount, "EUR")
        assertTrue(formatted.startsWith("-"), "expected the server-controlled leading '-' preserved verbatim, got \"$formatted\"")
        assertTrue(formatted.endsWith(" EUR"), "expected the ' EUR' suffix, got \"$formatted\"")
    }

    @Test
    fun anchorAssetLabel_isNonBlankForEveryValue() {
        AnchorAsset.entries.forEach { asset ->
            assertTrue(anchorAssetLabel(asset).isNotBlank(), "expected a non-blank label for $asset")
        }
    }

    @Test
    fun priceStatusLabel_isNonBlankForEveryValue() {
        PriceStatus.entries.forEach { status ->
            assertTrue(priceStatusLabel(status).isNotBlank(), "expected a non-blank label for $status")
        }
    }

    @Test
    fun priceStatusColor_isARealBootstrapHueForEveryValue() {
        PriceStatus.entries.forEach { status ->
            val color = priceStatusColor(status)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $status, got \"$color\"")
        }
    }
}
