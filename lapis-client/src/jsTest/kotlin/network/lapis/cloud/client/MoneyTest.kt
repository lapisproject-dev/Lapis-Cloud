package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * W6a "Betragsanzeige lokalisiert und exakt" -- covers [formatMoney]/[formatLtr]. The original decision D5 (append only " €", never
 * touch the digits) is revised: grouping, two-place padding, the locale's separators and the display minus are applied by pure
 * STRING arithmetic, so the digits still can never diverge from what the server computed (a genuine sub-cent value is never rounded).
 *
 * Every test goes through the `...In(language, ...)` seam: setting `I18n.language` restarts the root and belongs only in the DOM tests.
 * Every expectation is built from [NBSP]/[MINUS] -- a literal space would look identical in a diff and fail.
 */
class MoneyTest {
    private fun eur(
        language: String,
        value: Double,
    ) = formatMoneyIn(language, value.toDecimal())

    @Test
    fun eightLanguageMatrix() {
        assertEquals("1.234,50$NBSP€", eur("de", 1234.5))
        assertEquals("€1,234.50", eur("en", 1234.5))
        assertEquals("1${NBSP}234,50$NBSP€", eur("fr", 1234.5))
        assertEquals("1.234,50$NBSP€", eur("es", 1234.5))
        assertEquals("1.234,50$NBSP€", eur("it", 1234.5))
        assertEquals("€${NBSP}1.234,50", eur("nl", 1234.5))
        assertEquals("1${NBSP}234,50$NBSP€", eur("pl", 1234.5))
        assertEquals("1${NBSP}234,50$NBSP€", eur("ru", 1234.5))
    }

    @Test
    fun unknownOrRegionalLanguageTags() {
        assertEquals(eur("de", 1234.5), eur("xx", 1234.5))
        assertEquals(eur("de", 1234.5), eur("de-DE", 1234.5))
        assertEquals(eur("en", 1234.5), eur("EN", 1234.5))
        assertEquals(eur("en", 1234.5), eur("en-GB", 1234.5))
    }

    @Test
    fun groupSeparatorIsNoBreakSpaceNotNarrow() {
        val fr = eur("fr", 1234.5)
        assertEquals(0x00A0, fr[1].code)
        assertFalse(fr.contains(' '))
        assertFalse(fr.contains(' '), "no plain space anywhere")
    }

    @Test
    fun negativeUsesTheDisplayMinusBeforeAPrefixCurrency() {
        assertEquals("${MINUS}1.234,50$NBSP€", eur("de", -1234.5))
        assertEquals("$MINUS€1,234.50", eur("en", -1234.5))
        assertEquals(0x2212, MINUS.single().code)
    }

    @Test
    fun zeroIsPaddedAndUnsigned() {
        assertEquals("0,00$NBSP€", eur("de", 0.0))
        assertEquals("0,00$NBSP€", eur("de", -0.0))
        assertEquals("0,00$NBSP€", formatMoneyIn("de", (0.1 + 0.2 - 0.3).toDecimal()))
    }

    @Test
    fun wholeAndShortFractionsArePaddedToTwoPlaces() {
        assertEquals("100,00$NBSP€", eur("de", 100.0))
        assertEquals("0,50$NBSP€", eur("de", 0.5))
        assertEquals("5,00$NBSP€", eur("de", 5.0))
    }

    @Test
    fun genuineSubCentValuesAreNeverRounded() {
        assertEquals("0,005$NBSP€", eur("de", 0.005))
        assertEquals("12,345$NBSP€", eur("de", 12.345))
        assertEquals("1.234.567,891$NBSP€", eur("de", 1234567.891))
        assertEquals("0,00000015$NBSP€", eur("de", 1.5e-7))
        assertEquals("0,000001$NBSP€", eur("de", 1e-6))
    }

    @Test
    fun floatingPointNoiseIsRemoved() {
        assertEquals("1.234,56$NBSP€", eur("de", 1234.5600000000001))
        assertEquals("0,30$NBSP€", eur("de", 0.1 + 0.2))
        assertEquals("${MINUS}42,10$NBSP€", eur("de", -42.10000000000001))
        assertEquals("100,00$NBSP€", eur("de", 99.99999999999999))
        assertEquals("100,00$NBSP€", eur("de", 100.00000000000001))
    }

    @Test
    fun veryLargeValuesKeepEveryDigit() {
        assertEquals("1.000.000.000.000.000.000.000,00$NBSP€", eur("de", 1e21))
        assertEquals("12.345.678.901.234.567.890,12", groupDecimalDigits("12345678901234567890.12", moneyLocale("de")))
        assertEquals("12,345,678,901,234,567,890.12", groupDecimalDigits("12345678901234567890.12", moneyLocale("en")))
        // above the cent-smoothing limit the digits stay as they are
        val huge = 1234567890123456.75
        assertEquals(plainDecimal(huge.toString()), displayDigits(huge.toDecimal()))
    }

    @Test
    fun groupDecimalDigits_failsClosedOnGarbage() {
        assertEquals("abc", groupDecimalDigits("abc", moneyLocale("de")))
        assertEquals("NaN${NBSP}LTR", formatAmountDigits("NaN", "LTR", moneyLocale("de")))
    }

    @Test
    fun ltrIsAlwaysASuffix_inEveryLanguage() {
        assertEquals("1.234,50${NBSP}LTR", formatLtrIn("de", 1234.5.toDecimal()))
        assertEquals("1,234.50${NBSP}LTR", formatLtrIn("en", 1234.5.toDecimal()))
        assertEquals("1${NBSP}234,50${NBSP}LTR", formatLtrIn("fr", 1234.5.toDecimal()))
        assertEquals("1.234,50${NBSP}LTR", formatLtrIn("nl", 1234.5.toDecimal()))
        assertEquals("${MINUS}12,50${NBSP}LTR", formatLtrIn("de", (-12.5).toDecimal()))
    }

    @Test
    fun donationAmountsUseTheSameTransformWithAnyCurrency() {
        assertEquals("1.000,00${NBSP}USD", formatAmountDigits("1000", "USD", moneyLocale("de")))
        assertEquals("1.000,00", formatAmountDigits("1000", "", moneyLocale("de")))
    }

    @Test
    fun formatMoneyTokenResolvesPerLanguageAndFailsClosed() {
        val payload = moneyToken(1234.5.toDecimal()).removePrefix(KV_I18N_MARKER)
        assertEquals("€1,234.50", formatMoneyTokenIn("en", payload))
        assertEquals("1.234,50$NBSP€", formatMoneyTokenIn("de", payload))
        assertEquals("◆ 12,50${NBSP}LTR", formatMoneyTokenIn("de", ltrToken(12.5.toDecimal()).removePrefix(KV_I18N_MARKER)))
        assertEquals("garbage", formatMoneyTokenIn("de", MONEY_SENTINEL + "Xgarbage"))
        assertEquals("", formatMoneyTokenIn("de", MONEY_SENTINEL))
        assertEquals("abc$NBSP€", formatMoneyTokenIn("de", MONEY_SENTINEL + MONEY_KIND_EUR + "abc"))
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

    // ---- class C: machine-readable, unchanged (audit fix M3) -----------------------------------------------------------------

    @Test
    fun displayDigits_staysAsciiDotAndUngrouped() {
        assertEquals("100", displayDigits(99.99999999999999.toDecimal()))
        assertEquals("100", displayDigits(100.00000000000001.toDecimal()))
        assertEquals("-100", displayDigits((-99.99999999999999).toDecimal()))
        assertEquals("0", displayDigits((0.1 + 0.2 - 0.3).toDecimal()))
        assertEquals("1234.5", displayDigits(1234.5.toDecimal()))
        assertEquals("1234567.891", displayDigits(1234567.891.toDecimal()))
        assertEquals("0.00000015", displayDigits(1.5e-7.toDecimal()))
        assertEquals("1000000000000000000000", displayDigits(1e21.toDecimal()))
        val noise = displayDigits(5.551115123125783e-17.toDecimal())
        assertEquals("0", noise)
        assertTrue(!noise.contains('e', ignoreCase = true))
    }

    @Test
    fun exceedsDisplayedAmount_usesTheSameCleanedViewAsTheScreen() {
        val open = 99.99999999999999.toDecimal()
        assertEquals("100,00$NBSP€", formatMoneyIn("de", open))
        assertEquals(100.0, displayAmountValue(open))
        assertTrue(!exceedsDisplayedAmount(entered = 100.0.toDecimal(), limit = open), "entering the shown 100 is allowed")
        assertTrue(exceedsDisplayedAmount(entered = 100.01.toDecimal(), limit = open), "a cent more is not")
        assertTrue(!exceedsDisplayedAmount(entered = 99.99.toDecimal(), limit = open))
    }

    @Test
    fun formatPlainAmount_isUnitLess_localized_andPadded() {
        assertEquals("1.234,50", formatPlainAmountIn("de", 1234.5.toDecimal()))
        assertEquals("3,00", formatPlainAmountIn("de", 3.0.toDecimal()))
        assertEquals("1,234.50", formatPlainAmountIn("en", 1234.5.toDecimal()))
        assertEquals("1${NBSP}234,50", formatPlainAmountIn("fr", 1234.5.toDecimal()))
        assertEquals("${MINUS}12,50", formatPlainAmountIn("de", (-12.5).toDecimal()))
        assertEquals("12,345", formatPlainAmountIn("de", 12.345.toDecimal()), "sub-cent value is never rounded")
        listOf("de", "en", "fr", "pl").forEach { language ->
            val text = formatPlainAmountIn(language, 1234.5.toDecimal())
            assertFalse(text.contains("€") || text.contains("LTR"), "no currency in $text")
            assertFalse(text.endsWith(NBSP) || text.endsWith(" "), "no trailing gap in $text")
        }
    }

    @Test
    fun formatCount_groupsButNeverPads() {
        assertEquals("3", formatCountIn("de", 3.0.toDecimal()))
        assertEquals("0", formatCountIn("de", 0.0.toDecimal()))
        assertEquals("1.234", formatCountIn("de", 1234.0.toDecimal()))
        assertEquals("1,234", formatCountIn("en", 1234.0.toDecimal()))
        assertEquals("1${NBSP}234", formatCountIn("fr", 1234.0.toDecimal()))
        assertEquals("${MINUS}3", formatCountIn("de", (-3.0).toDecimal()))
        assertEquals("2,50", formatCountIn("de", 2.5.toDecimal()), "a fractional value falls back to the padded plain form")
    }

    @Test
    fun plainAndCountTokens_resolveLikeTheirFormatters() {
        val plain = plainAmountToken(1234.5.toDecimal()).removePrefix(KV_I18N_MARKER)
        assertEquals("1,234.50", formatMoneyTokenIn("en", plain))
        val count = countToken(1234.0.toDecimal()).removePrefix(KV_I18N_MARKER)
        assertEquals("1.234", formatMoneyTokenIn("de", count))
    }
}
