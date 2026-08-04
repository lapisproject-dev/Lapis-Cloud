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
}
