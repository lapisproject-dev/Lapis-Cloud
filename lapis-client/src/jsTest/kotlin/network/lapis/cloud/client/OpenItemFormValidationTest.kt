package network.lapis.cloud.client

import dev.kilua.rpc.types.toDouble
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.OpenItemDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Welle V1.4.21 -- pins [OpenItemFormValidation.kt] (no DOM). */
class OpenItemFormValidationTest {
    private fun valid(raw: String): Double = (parseAmountInput(raw) as AmountInput.Valid).value.toDouble()

    // ── parseAmountInput ─────────────────────────────────────────────────────────────────────────

    @Test
    fun parseAmountInput_acceptsDecimalCommaAndPoint() {
        assertEquals(12.5, valid("12,50"))
        assertEquals(12.5, valid("12.50"))
        assertEquals(1234.0, valid("1234"))
        assertEquals(0.01, valid("0,01"))
    }

    @Test
    fun parseAmountInput_rejectsThousandsSeparators() {
        assertTrue(parseAmountInput("1.234,56") is AmountInput.Invalid)
        assertTrue(parseAmountInput("1,234.56") is AmountInput.Invalid)
    }

    @Test
    fun parseAmountInput_rejectsZeroAndNegative() {
        assertTrue(parseAmountInput("0") is AmountInput.Invalid)
        assertTrue(parseAmountInput("0,00") is AmountInput.Invalid)
        assertTrue(parseAmountInput("-5") is AmountInput.Invalid)
    }

    @Test
    fun parseAmountInput_rejectsMoreThanTwoFractionDigits() {
        assertTrue(parseAmountInput("12,505") is AmountInput.Invalid)
        assertTrue(parseAmountInput("12.500") is AmountInput.Invalid)
    }

    /**
     * Audit-Fund N1: `AMOUNT_SHAPE` erlaubt beliebig viele Ziffern, also war
     * "99999999999999999999,99" hier gültig. Auf der Leitung wird [dev.kilua.rpc.types.Decimal] zu
     * einem JSON-Double, server-seitig also `BigDecimal("1.0E20")` -- mit **negativer** Skala, die
     * die `scale() > 2`-Prüfung passiert, und dann in einem `numeric(12,2)`-Überlauf (HTTP 500)
     * endete. Client- UND server-seitig gedeckelt; hier die Client-Seite.
     */
    @Test
    fun parseAmountInput_rejectsAmountsAboveOneBillion() {
        assertEquals(1_000_000_000.00, valid("1000000000"))
        assertEquals(1_000_000_000.00, valid("1000000000,00"))
        assertTrue(parseAmountInput("1000000000,01") is AmountInput.Invalid)
        assertTrue(parseAmountInput("99999999999999999999,99") is AmountInput.Invalid)
        assertTrue(parseAmountInput("1".repeat(40)) is AmountInput.Invalid)
    }

    @Test
    fun parseAmountInput_blankIsEmpty_garbageIsInvalid() {
        assertEquals(AmountInput.Empty, parseAmountInput(""))
        assertEquals(AmountInput.Empty, parseAmountInput(null))
        assertEquals(AmountInput.Empty, parseAmountInput("   "))
        assertTrue(parseAmountInput("abc") is AmountInput.Invalid)
        assertTrue(parseAmountInput("Infinity") is AmountInput.Invalid)
        assertTrue(parseAmountInput("12,") is AmountInput.Invalid)
    }

    // ── validateOpenItemForm ─────────────────────────────────────────────────────────────────────

    private fun err(
        direction: OpenItemDirection? = OpenItemDirection.PAYABLE,
        name: String = "Muster GmbH",
        itemDate: LocalDate? = LocalDate(2026, 1, 10),
        dueDate: LocalDate? = LocalDate(2026, 1, 24),
        amount: AmountInput = parseAmountInput("100,00"),
        contra: String? = "acc-1",
        reference: String? = null,
        note: String? = null,
    ) = validateOpenItemForm(direction, name, itemDate, dueDate, amount, contra, reference, note)

    @Test
    fun validateOpenItemForm_happyPathIsNull() {
        assertNull(err())
    }

    @Test
    fun validateOpenItemForm_directionIsAMandatoryChoice() {
        assertNotNull(err(direction = null))
    }

    @Test
    fun validateOpenItemForm_nameRules() {
        assertNotNull(err(name = ""))
        assertNotNull(err(name = "   "))
        assertNull(err(name = "a".repeat(200)))
        assertNotNull(err(name = "a".repeat(201)))
    }

    @Test
    fun validateOpenItemForm_dueDateMayEqualButNotPrecedeItemDate() {
        assertNull(err(itemDate = LocalDate(2026, 1, 10), dueDate = LocalDate(2026, 1, 10)))
        assertNotNull(err(itemDate = LocalDate(2026, 1, 10), dueDate = LocalDate(2026, 1, 9)))
        assertNotNull(err(itemDate = null))
        assertNotNull(err(dueDate = null))
    }

    @Test
    fun validateOpenItemForm_amountAndContraAccountAreRequired() {
        assertNotNull(err(amount = AmountInput.Empty))
        assertNotNull(err(amount = parseAmountInput("abc")))
        assertNotNull(err(contra = null))
        assertNotNull(err(contra = ""))
    }

    @Test
    fun validateOpenItemForm_referenceAndNoteLengthLimits() {
        assertNull(err(reference = "r".repeat(100), note = "n".repeat(1000)))
        assertNotNull(err(reference = "r".repeat(101)))
        assertNotNull(err(note = "n".repeat(1001)))
    }

    // ── expectedContraAccountType ────────────────────────────────────────────────────────────────

    @Test
    fun expectedContraAccountType_payableExpense_receivableIncome_exhaustive() {
        assertEquals(LedgerAccountType.EXPENSE, expectedContraAccountType(OpenItemDirection.PAYABLE))
        assertEquals(LedgerAccountType.INCOME, expectedContraAccountType(OpenItemDirection.RECEIVABLE))
        OpenItemDirection.entries.forEach { assertNotNull(expectedContraAccountType(it)) }
    }

    // ── openItemPostingErrorMessage ──────────────────────────────────────────────────────────────

    @Test
    fun postingErrorMessage_everyKnownBridgeCodeHasAPlainTextExplanation() {
        listOf(
            "payables_account_not_configured",
            "receivables_account_not_configured",
            "payment_bank_account_not_configured",
            "ledger_account_inactive",
            "contra_account_wrong_type",
            "receivables_account_not_asset_type",
            "payables_account_not_liability_type",
            "cash_voucher_required",
            "cash_register_balance_insufficient",
        ).forEach { code ->
            val message = openItemPostingErrorMessage(code)
            assertNotNull(message, code)
            assertTrue(message.isNotBlank(), code)
        }
    }

    @Test
    fun postingErrorMessage_unknownCodeIsNull_callerShowsTheRawCode() {
        assertNull(openItemPostingErrorMessage("voellig_unbekannt"))
    }
}
