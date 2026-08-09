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
}
