package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Accounting UI wave -- covers [formatMoney], the single highest-risk formatting decision of this
 * wave (design decision D5): the transform must be nothing more than appending `" €"` to
 * `Decimal.toString()`'s own digits, with no re-rounding, no thousands separators, no decimal-comma
 * localization. `LedgerScreen.kt` is the first consumer of this shared file, hence this test lives
 * alongside [LedgerScreenTest] rather than a later screen's test file.
 *
 * LTR-Wirtschaft UI wave -- extended to also cover [formatLtr] (D2), the LTR-denominated sibling
 * of [formatMoney] added in this wave. Same "nothing but the suffix" rule, same reasons this test
 * class already exists for [formatMoney]; [ltrSpan] itself is not covered here for the same reason
 * [moneySpan] isn't -- both build a `io.kvision.html.Span` and this module has no DOM test harness
 * (see [GovernanceAuthzUiTest] KDoc for the same posture).
 */
class MoneyTest {
    @Test
    fun formatMoney_appendsOnlyTheEuroSuffixNeverAlteringTheDigits() {
        val amount = 1234.5.toDecimal()
        assertEquals("$amount €", formatMoney(amount))
    }

    @Test
    fun formatMoney_preservesANegativeSignVerbatim() {
        val amount = (-42.0).toDecimal()
        val formatted = formatMoney(amount)
        assertTrue(formatted.startsWith("-"), "expected the server-controlled leading '-' preserved verbatim, got \"$formatted\"")
        assertTrue(formatted.endsWith(" €"), "expected the ' €' suffix, got \"$formatted\"")
    }

    @Test
    fun formatMoney_zeroRendersPlainlyWithNoSign() {
        assertEquals("${0.0.toDecimal()} €", formatMoney(0.0.toDecimal()))
    }

    @Test
    fun formatMoney_removesPureFloatingPointNoise_only() {
        assertEquals("1234.56 €", formatMoney(1234.5600000000001.toDecimal()))
        assertEquals("0.3 €", formatMoney((0.1 + 0.2).toDecimal()))
        assertEquals("-42.1 €", formatMoney((-42.10000000000001).toDecimal()))
    }

    @Test
    fun formatMoney_keepsGenuineSubCentValuesAndExactAmountsUntouched() {
        assertEquals("0.005 €", formatMoney(0.005.toDecimal()))
        assertEquals("12.345 €", formatMoney(12.345.toDecimal()))
        assertEquals("1234.56 €", formatMoney(1234.56.toDecimal()))
        assertEquals("100 €", formatMoney(100.0.toDecimal()))
    }

    @Test
    fun formatLtr_appendsOnlyTheLtrSuffixNeverAlteringTheDigits() {
        val amount = 1234.5.toDecimal()
        assertEquals("$amount LTR", formatLtr(amount))
    }

    @Test
    fun formatLtr_preservesANegativeSignVerbatim() {
        val amount = (-42.0).toDecimal()
        val formatted = formatLtr(amount)
        assertTrue(formatted.startsWith("-"), "expected the server-controlled leading '-' preserved verbatim, got \"$formatted\"")
        assertTrue(formatted.endsWith(" LTR"), "expected the ' LTR' suffix, got \"$formatted\"")
    }

    @Test
    fun formatLtr_zeroRendersPlainlyWithNoSign() {
        assertEquals("${0.0.toDecimal()} LTR", formatLtr(0.0.toDecimal()))
    }

    @Test
    fun displayDigits_snapsNoiseNextToAWholeAmountToThatAmount_withoutATrailingPointZero() {
        assertEquals("100 €", formatMoney(99.99999999999999.toDecimal()))
        assertEquals("100 €", formatMoney(100.00000000000001.toDecimal()))
        assertEquals("-100 €", formatMoney((-99.99999999999999).toDecimal()))
        assertEquals("0 €", formatMoney((0.1 + 0.2 - 0.3).toDecimal()), "5.55e-17 is noise around zero")
    }

    @Test
    fun displayDigits_neverRendersExponentNotation_andAGenuineTinyValueIsNotZeroed() {
        // Audit fix M3: 1.5e-7 is a real (tiny) value, not noise -- it used to become "0.0 €" under an absolute 1e-6 tolerance.
        assertEquals("0.00000015 €", formatMoney(1.5e-7.toDecimal()))
        assertEquals("0.000001 €", formatMoney(1e-6.toDecimal()))
        assertEquals("1234567.891 €", formatMoney(1234567.891.toDecimal()), "a genuine sub-cent value stays at any magnitude")
        assertEquals("1000000000000000000000 €", formatMoney(1e21.toDecimal()))
        val noise = displayDigits(5.551115123125783e-17.toDecimal())
        assertEquals("0", noise)
        assertTrue(!noise.contains('e', ignoreCase = true))
    }

    @Test
    fun plainDecimal_expandsExponentsByStringArithmetic() {
        assertEquals("0.00000015", plainDecimal("1.5e-7"))
        assertEquals("1234.5", plainDecimal("1.2345e3"))
        assertEquals("1000", plainDecimal("1E3"))
        assertEquals("-0.05", plainDecimal("-5e-2"))
        assertEquals("100", plainDecimal("100"))
        assertEquals("12.345", plainDecimal("12.345"))
        assertEquals("0.0000000000000000555", plainDecimal("5.55e-17"))
    }

    @Test
    fun exceedsDisplayedAmount_usesTheSameCleanedViewAsTheScreen() {
        val open = 99.99999999999999.toDecimal()
        assertEquals("100 €", formatMoney(open))
        assertEquals(100.0, displayAmountValue(open))
        assertTrue(!exceedsDisplayedAmount(entered = 100.0.toDecimal(), limit = open), "entering the shown 100 is allowed")
        assertTrue(exceedsDisplayedAmount(entered = 100.01.toDecimal(), limit = open), "a cent more is not")
        assertTrue(!exceedsDisplayedAmount(entered = 99.99.toDecimal(), limit = open))
    }
}
