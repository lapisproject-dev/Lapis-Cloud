package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import dev.kilua.rpc.types.toDouble
import network.lapis.cloud.shared.domain.AnchorAsset
import network.lapis.cloud.shared.domain.AnchorPolicy
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

    /**
     * V0.6.6: [AnchorPolicy] lives in `commonMain` so the client form validates against the SAME
     * numbers the server enforces -- this test only sanity-checks that every literal yields
     * plausible values (a floor >= 1, a non-negative refresh interval, and a recommended TTL at
     * least as large as the refresh interval it advises around), not the exact numbers (those are
     * covered server-side by `PriceOracleServiceTest`/`PriceOracleOrchestratorTest`).
     */
    @Test
    fun anchorPolicy_returnsSaneValuesForEveryAnchorAsset() {
        AnchorAsset.entries.forEach { anchor ->
            val floor = AnchorPolicy.quorumFloor(anchor)
            val refresh = AnchorPolicy.refreshIntervalSeconds(anchor)
            val recommendedTtl = AnchorPolicy.recommendedCacheTtlSeconds(anchor)
            assertTrue(floor >= 1, "expected quorumFloor >= 1 for $anchor, got $floor")
            assertTrue(refresh >= 0, "expected refreshIntervalSeconds >= 0 for $anchor, got $refresh")
            assertTrue(
                recommendedTtl >= refresh,
                "expected recommendedCacheTtlSeconds >= refreshIntervalSeconds for $anchor, got $recommendedTtl < $refresh",
            )
        }
    }

    // ── Review Round 1 / MAJOR-2: pre-commit ltrMinted estimate ────────────────────────────────

    /**
     * The Gold-integration server-side fixture (`PriceOracleServiceTest`, "Gold integration: ..."):
     * 100.00 EUR donation, anchorUnitsPerLtr 0.01, anchorPrice 2000.00 -> the server mints exactly
     * 5.00 LTR. [estimateLtrMinted] uses [Double] arithmetic (not exact decimal), so this asserts
     * the estimate is CLOSE to the server's real figure, not byte-for-byte equal -- exactness is
     * deliberately not the point (see [estimateLtrMinted] KDoc).
     */
    @Test
    fun estimateLtrMinted_matchesTheServerFormulaCloselyForTheGoldIntegrationFixture() {
        val donationAmount = 100.0.toDecimal()
        val anchorUnitsPerLtr = 0.01.toDecimal()
        val anchorPrice = 2000.0.toDecimal()
        val estimate = estimateLtrMinted(donationAmount, anchorUnitsPerLtr, anchorPrice)
        assertTrue(estimate != null, "expected a non-null estimate for a plausible peg/price")
        val delta = kotlin.math.abs(estimate!!.toDouble() - 5.0)
        assertTrue(delta < 0.01, "expected an estimate close to 5.00 LTR, got $estimate (delta $delta)")
    }

    /**
     * The Fiat-integration server-side fixture: 100.00 EUR donation, anchorUnitsPerLtr 5,
     * anchorPrice 1 -> the server mints exactly 20.00 LTR.
     */
    @Test
    fun estimateLtrMinted_matchesTheServerFormulaCloselyForTheFiatIntegrationFixture() {
        val donationAmount = 100.0.toDecimal()
        val anchorUnitsPerLtr = 5.0.toDecimal()
        val anchorPrice = 1.0.toDecimal()
        val estimate = estimateLtrMinted(donationAmount, anchorUnitsPerLtr, anchorPrice)
        assertTrue(estimate != null, "expected a non-null estimate for a plausible peg/price")
        val delta = kotlin.math.abs(estimate!!.toDouble() - 20.0)
        assertTrue(delta < 0.01, "expected an estimate close to 20.00 LTR, got $estimate (delta $delta)")
    }

    /** MAJOR-2's own concrete failure mode: a BTC-scale peg (0.000001) left over against a FIAT anchorPrice ~1.0 would mint ~50,000x too much -- [estimateLtrMinted] must surface that same wildly-off number, not silently clamp or hide it (the whole point is operator VISIBILITY, not client-side correction). */
    @Test
    fun estimateLtrMinted_surfacesAnImplausiblyLargeResultRatherThanHidingIt() {
        val donationAmount = 10.0.toDecimal()
        val btcScalePeg = 0.000001.toDecimal()
        val fiatPrice = 1.0.toDecimal()
        val estimate = estimateLtrMinted(donationAmount, btcScalePeg, fiatPrice)
        assertTrue(estimate != null, "expected a non-null estimate even for an implausible peg")
        assertTrue(estimate!!.toDouble() > 1_000_000.0, "expected the wildly-too-large estimate to surface as-is, got $estimate")
    }

    @Test
    fun estimateLtrMinted_returnsNullForANonPositivePegOrPrice() {
        val donationAmount = 10.0.toDecimal()
        assertEquals(null, estimateLtrMinted(donationAmount, 0.0.toDecimal(), 1.0.toDecimal()))
        assertEquals(null, estimateLtrMinted(donationAmount, 1.0.toDecimal(), 0.0.toDecimal()))
        assertEquals(null, estimateLtrMinted(donationAmount, (-1.0).toDecimal(), 1.0.toDecimal()))
    }
}
