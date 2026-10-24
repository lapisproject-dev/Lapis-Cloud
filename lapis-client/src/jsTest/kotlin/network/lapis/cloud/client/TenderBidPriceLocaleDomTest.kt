package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** V1.9.202 -- the price of a bid is read the way the language of the interface writes numbers; the wire value always has a point. */
class TenderBidPriceLocaleDomTest {
    private fun valid(
        raw: String,
        language: String,
    ): Double {
        val parsed = parseAmountInput(raw, language = language, maxFractionDigits = 2)
        assertTrue(parsed is AmountInput.Valid, "$raw ($language) -> $parsed")
        return parsed.value.toString().toDouble()
    }

    private fun invalid(
        raw: String,
        language: String,
    ) = assertTrue(
        parseAmountInput(raw, language = language, maxFractionDigits = 2) is AmountInput.Invalid,
        "$raw ($language) must be refused",
    )

    @Test
    fun german_dotIsTheThousandsSeparator_commaTheDecimal() {
        assertEquals(1250.5, valid("1.250,50", "de"))
        assertEquals(20.0, valid("20", "de"))
        assertEquals(0.5, valid("0,50", "de"))
    }

    @Test
    fun english_commaIsTheThousandsSeparator_dotTheDecimal() {
        assertEquals(1250.5, valid("1,250.50", "en"))
        assertEquals(1250.5, valid("1250.5", "en"))
    }

    @Test
    fun threeDecimals_zeroAndNonsense_areRefused_inEveryLanguage() {
        invalid("1.234", "en") // three decimals in English
        invalid("1,234", "de") // three decimals in German
        invalid("0", "de")
        invalid("abc", "en")
        invalid("-5", "de")
    }
}
