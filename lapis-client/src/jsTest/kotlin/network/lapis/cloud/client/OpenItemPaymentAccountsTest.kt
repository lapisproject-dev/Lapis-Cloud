package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.LedgerAccountDto
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.PaymentAccountMapping
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Welle V1.4.22: pins [settlementBankChoice]/[settlementBankAccountProblem] -- the DOM-free half of
 * both staging findings (a settlement dialog offering the receivables account, and a pre-selected
 * "(Standard-Bankkonto der Organisation)" that was not configured at all).
 */
class OpenItemPaymentAccountsTest {
    private fun account(
        id: String,
        number: String,
        type: LedgerAccountType = LedgerAccountType.ASSET,
        accountClass: Int = 1,
        active: Boolean = true,
    ) = LedgerAccountDto(
        id = id,
        accountNumber = number,
        name = "Konto $number",
        accountClass = accountClass,
        type = type,
        active = active,
    )

    private val bank = account(id = "bank", number = "18000")
    private val kasse = account(id = "kasse", number = "16000")
    private val fixedAssets = account(id = "fixed", number = "06500", accountClass = 0)
    private val receivables = account(id = "receivables", number = "12000")
    private val income = account(id = "income", number = "40000", type = LedgerAccountType.INCOME, accountClass = 4)
    private val allAccounts = listOf(fixedAssets, kasse, bank, receivables, income)

    private val configured =
        PaymentAccountMapping(defaultBankAccountId = "bank", receivablesAccountId = "receivables", payablesAccountId = "payables")
    private val withoutDefaultBank = PaymentAccountMapping(receivablesAccountId = "receivables", payablesAccountId = "payables")

    @Test
    fun offerList_containsOnlyBankAndCashAccounts() {
        val choice = settlementBankChoice(accounts = allAccounts, mapping = configured)
        assertEquals(listOf("kasse", "bank"), choice.eligible.map { it.id })
    }

    @Test
    fun configuredDefaultBank_keepsTheDefaultOptionAndMakesSelectionOptional() {
        val choice = settlementBankChoice(accounts = allAccounts, mapping = configured)
        assertTrue(choice.contextKnown)
        assertTrue(choice.defaultBankAccountConfigured)
        assertFalse(choice.selectionRequired)
        assertNull(settlementBankAccountProblem(selected = "", choice = choice))
        assertNull(settlementBankAccountProblem(selected = null, choice = choice))
        assertNull(settlementBankAccountProblem(selected = "bank", choice = choice))
    }

    @Test
    fun withoutDefaultBank_anEmptySelectionIsRejected() {
        val choice = settlementBankChoice(accounts = allAccounts, mapping = withoutDefaultBank)
        assertTrue(choice.selectionRequired)
        assertFalse(choice.hasNoChoiceAtAll)
        assertNotNull(settlementBankAccountProblem(selected = "", choice = choice))
        assertNotNull(settlementBankAccountProblem(selected = null, choice = choice))
        assertNotNull(settlementBankAccountProblem(selected = "   ", choice = choice))
        // An explicitly chosen account is fine even without a default mapping -- that IS the fix.
        assertNull(settlementBankAccountProblem(selected = "bank", choice = choice))
    }

    /**
     * The claim "no default bank account is configured" must never be made from a failed/pending
     * `getOrganizationSettings` call: an unknown context keeps the pre-V1.4.22 default option and
     * never turns the choice into a mandatory field.
     *
     * Audit follow-up (MAJOR-2): it must also NOT offer a half-filtered list. Without the mapping the
     * receivables account is indistinguishable from a bank account (both SKR42 class 1), so the dialog
     * offers nothing at all and says so ([paymentAccountsUnknownHint]) instead of handing the treasurer
     * a list that may contain the one account that must never be picked.
     */
    @Test
    fun unknownMapping_keepsTheDefaultOptionAndOffersNoHalfFilteredList() {
        val choice = settlementBankChoice(accounts = allAccounts, mapping = null)
        assertFalse(choice.contextKnown)
        assertFalse(choice.selectionRequired)
        assertNull(settlementBankAccountProblem(selected = "", choice = choice))
        assertTrue(choice.eligible.isEmpty())
    }

    /** Accounts not loaded yet is the same "context unknown" state -- an active chart always has accounts. */
    @Test
    fun accountsNotLoadedYet_isTreatedAsUnknownContext() {
        val choice = settlementBankChoice(accounts = emptyList(), mapping = configured)
        assertFalse(choice.contextKnown)
        assertFalse(choice.selectionRequired)
        assertTrue(choice.eligible.isEmpty())
        assertNull(settlementBankAccountProblem(selected = "", choice = choice))
    }

    /**
     * Audit follow-up (MAJOR-1): the dead end. The mapping still names an account, but it was
     * deactivated in the meantime (`listLedgerAccounts(activeOnly = true)` no longer returns it), so
     * "(Standard-Bankkonto der Organisation)" would be a pre-selected option the server rejects on
     * every attempt. The choice becomes mandatory instead.
     */
    @Test
    fun deactivatedDefaultBankAccount_makesTheChoiceMandatoryInsteadOfADeadEnd() {
        val choice =
            settlementBankChoice(
                accounts = listOf(kasse, bank, receivables),
                mapping = configured.copy(defaultBankAccountId = "bank-gone"),
            )
        assertTrue(choice.contextKnown)
        assertFalse(choice.defaultBankAccountConfigured)
        assertTrue(choice.selectionRequired)
        assertNull(choice.defaultBankAccountId)
        assertNotNull(settlementBankAccountProblem(selected = "", choice = choice))
        // A real alternative is still offered, so this is a choice, not a wall.
        assertEquals(listOf("kasse", "bank"), choice.eligible.map { it.id })
    }

    /** The same dead end from the other side: the mapping points at the receivables collective account. */
    @Test
    fun defaultBankAccountThatIsTheReceivablesAccount_isNotAValidDefault() {
        val choice = settlementBankChoice(accounts = allAccounts, mapping = configured.copy(defaultBankAccountId = "receivables"))
        assertFalse(choice.defaultBankAccountConfigured)
        assertTrue(choice.selectionRequired)
        assertEquals(listOf("kasse", "bank"), choice.eligible.map { it.id })
    }

    /** MINOR-c: the default account also appears as its own list entry -- it has to be recognizable. */
    @Test
    fun defaultAccountIsMarkedInTheList() {
        val choice = settlementBankChoice(accounts = allAccounts, mapping = configured)
        val bankLabel = settlementBankOptionLabel(account = bank, choice = choice)
        assertTrue(bankLabel.contains("18000"))
        assertTrue(bankLabel != "18000 · Konto 18000")
        assertEquals("16000 · Konto 16000", settlementBankOptionLabel(account = kasse, choice = choice))
    }

    /** MAJOR-3: the client says what is wrong before the round trip, for each conflict literal. */
    @Test
    fun selfReferentialMapping_hasItsOwnMessagePerConflict() {
        assertNull(paymentAccountMappingProblem(configured))
        assertNull(paymentAccountMappingProblem(PaymentAccountMapping()))
        val bankIsReceivables =
            paymentAccountMappingProblem(PaymentAccountMapping(defaultBankAccountId = "a", receivablesAccountId = "a"))
        val bankIsPayables = paymentAccountMappingProblem(PaymentAccountMapping(defaultBankAccountId = "a", payablesAccountId = "a"))
        val bothCollective =
            paymentAccountMappingProblem(PaymentAccountMapping(receivablesAccountId = "b", payablesAccountId = "b"))
        assertNotNull(bankIsReceivables)
        assertNotNull(bankIsPayables)
        assertNotNull(bothCollective)
        assertTrue(bankIsReceivables != bankIsPayables)
        assertTrue(bankIsReceivables != bothCollective)
    }

    @Test
    fun noEligibleAccountAndNoDefault_saysSoInsteadOfAskingForAChoice() {
        val choice = settlementBankChoice(accounts = listOf(fixedAssets, income), mapping = withoutDefaultBank)
        assertTrue(choice.hasNoChoiceAtAll)
        val problem = settlementBankAccountProblem(selected = "", choice = choice)
        assertNotNull(problem)
        assertEquals(problem, missingDefaultBankAccountHint(choice))
    }

    @Test
    fun missingDefaultHint_differsFromTheNoAccountAtAllMessage() {
        val withChoices = settlementBankChoice(accounts = allAccounts, mapping = withoutDefaultBank)
        val withoutChoices = settlementBankChoice(accounts = listOf(income), mapping = withoutDefaultBank)
        assertTrue(missingDefaultBankAccountHint(withChoices) != missingDefaultBankAccountHint(withoutChoices))
    }

    /** An account may only disappear from the list for a real reason -- an inactive one must. */
    @Test
    fun inactiveAccountsAreNeverOffered() {
        val inactiveBank = account(id = "bank-old", number = "18001", active = false)
        val choice = settlementBankChoice(accounts = listOf(inactiveBank, bank), mapping = configured)
        assertEquals(listOf("bank"), choice.eligible.map { it.id })
    }
}
