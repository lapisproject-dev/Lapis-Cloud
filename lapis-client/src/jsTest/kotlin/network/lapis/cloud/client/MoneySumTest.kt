package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import network.lapis.cloud.shared.domain.PostingSide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** W6a S7: sums and cent conversions are string-based and exact -- no `1234.5600000000001` in the posting confirmation. */
class MoneySumTest {
    @Test
    fun sumExact_noDoubleNoise() {
        val sum = sumExact(listOf(0.1.toDecimal(), 0.2.toDecimal()))
        assertEquals("0,30$NBSP€", formatMoneyIn("de", sum))
        assertFalse(formatMoneyIn("de", sum).contains("0000000"))
        assertEquals("1.234,56$NBSP€", formatMoneyIn("de", sumExact(listOf(1000.0.toDecimal(), 234.56.toDecimal()))))
        assertEquals("0,00$NBSP€", formatMoneyIn("de", sumExact(emptyList())))
        assertEquals("0,00$NBSP€", formatMoneyIn("de", sumExact(listOf(0.1.toDecimal(), (-0.1).toDecimal()))))
    }

    @Test
    fun sumExact_mixedScalesAndSubCent() {
        assertEquals("100.005", displayDigits(sumExact(listOf(100.0.toDecimal(), 0.005.toDecimal()))))
        assertEquals("-0.5", displayDigits(sumExact(listOf(0.25.toDecimal(), (-0.75).toDecimal()))))
    }

    @Test
    fun sumExact_scaleAboveMaximumFailsLoud() {
        val error = assertFailsWith<IllegalArgumentException> { sumExact(listOf(0.000012345678901.toDecimal())) }
        assertFalse(error.message.orEmpty().contains("12345"), "the message names scale/count, not the amount")
    }

    @Test
    fun sumExact_overflowFailsLoud_noSilentDoubleFallback() {
        assertFailsWith<IllegalStateException> { sumExact(listOf(9.0e15.toDecimal(), 9.0e15.toDecimal())) }
        assertFailsWith<IllegalStateException> { sumExact(listOf(1e21.toDecimal())) }
    }

    @Test
    fun scaledUnitsOf_isStringBased() {
        assertEquals(12345L, scaledUnitsOf(123.45.toDecimal(), 2))
        assertEquals(-5L, scaledUnitsOf((-0.05).toDecimal(), 2))
        assertEquals(0L, scaledUnitsOf(0.0.toDecimal(), 2))
        assertEquals(30L, scaledUnitsOf((0.1 + 0.2).toDecimal(), 2), "noise is cleaned first")
        assertNull(scaledUnitsOf(12.345.toDecimal(), 2), "a longer fraction is not truncated")
        assertNull(scaledUnitsOf(1e21.toDecimal(), 2))
    }

    @Test
    fun centsRoundTrip_isExact() {
        val table =
            mapOf(
                0L to "0",
                1L to "0.01",
                99L to "0.99",
                100L to "1",
                30L to "0.3",
                -30L to "-0.3",
                123456789012L to "1234567890.12",
                999999999999999L to "9999999999999.99",
                -999999999999999L to "-9999999999999.99",
            )
        table.forEach { (cents, expected) ->
            assertEquals(expected, displayDigits(centsToDecimal(cents)), "cents=$cents")
            assertEquals(cents, scaledUnitsOf(centsToDecimal(cents), 2), "cents=$cents")
        }
    }

    @Test
    fun centsBeyondDoubleResolution_failLoud_notWrong() {
        assertFailsWith<IllegalStateException> { centsToDecimal(9007199254740991L) }
    }

    @Test
    fun sumPostingLines_summingClassicNoiseCasesGivesCleanText() {
        val lines =
            listOf(
                PostingLineDisplay("1000 A", PostingSide.DEBIT, 1000.0.toDecimal(), "S", null),
                PostingLineDisplay("1010 B", PostingSide.DEBIT, 234.56.toDecimal(), "S", null),
                PostingLineDisplay("1020 C", PostingSide.DEBIT, 0.000012345678901.toDecimal(), "S", null),
            )
        // the third summand has scale 10 > MONEY_MAX_SCALE: fail loud rather than a wrong figure
        assertFailsWith<IllegalArgumentException> { sumPostingLines(lines, PostingSide.DEBIT) }
        assertEquals("1.234,56$NBSP€", formatMoneyIn("de", sumPostingLines(lines.take(2), PostingSide.DEBIT)))
        assertEquals("0,00$NBSP€", formatMoneyIn("de", sumPostingLines(lines, PostingSide.CREDIT)))
    }
}
