package network.lapis.cloud.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Welle V1.4.22: pins [PaymentCapableAccounts] -- the rule both the settlement dialog's offer list
 * and `OpenItemService.settleOpenItem`'s server-side check use. The two staging findings this wave
 * fixes are the first two tests.
 */
class PaymentCapableAccountsTest {
    private fun account(
        id: String,
        type: LedgerAccountType = LedgerAccountType.ASSET,
        accountClass: Int = PaymentCapableAccounts.LIQUID_ASSET_ACCOUNT_CLASS,
        active: Boolean = true,
    ) = PaymentAccountCandidate(id = id, type = type, accountClass = accountClass, active = active)

    private val mapping =
        PaymentAccountMapping(
            defaultBankAccountId = "bank-18000",
            receivablesAccountId = "receivables-12000",
            payablesAccountId = "payables-34000",
        )

    @Test
    fun receivablesCollectiveAccount_isRejected_althoughItIsAnActiveAssetAccount() {
        // The staging finding: "12000 Forderungen aus Lieferungen und Leistungen" was offered, and
        // choosing it booked receivables against receivables.
        assertEquals(
            PaymentAccountRejection.SUBLEDGER_COLLECTIVE_ACCOUNT,
            PaymentCapableAccounts.rejectionOf(account = account(id = "receivables-12000"), mapping = mapping),
        )
        assertEquals(
            PaymentAccountRejection.SUBLEDGER_COLLECTIVE_ACCOUNT,
            PaymentCapableAccounts.rejectionOf(account = account(id = "payables-34000", type = LedgerAccountType.ASSET), mapping = mapping),
        )
    }

    @Test
    fun fixedAssetAccountOutsideClassOne_isRejected_butNotByTheServer() {
        // "06500 Betriebs- und Geschäftsausstattung", SKR42 Kontenklasse 0.
        val rejection = PaymentCapableAccounts.rejectionOf(account = account(id = "fixed-06500", accountClass = 0), mapping = mapping)
        assertEquals(PaymentAccountRejection.NOT_LIQUID_ASSET_CLASS, rejection)
        assertFalse(PaymentCapableAccounts.isServerEnforced(rejection))
    }

    @Test
    fun bankAndCashAccountsOfClassOne_arePaymentCapable() {
        assertNull(PaymentCapableAccounts.rejectionOf(account = account(id = "bank-18000"), mapping = mapping))
        // A cash register is an ordinary class-1 ASSET account here -- deliberately allowed.
        assertTrue(PaymentCapableAccounts.isPaymentCapable(account = account(id = "kasse-16000"), mapping = mapping))
    }

    @Test
    fun inactiveAndNonAssetAccounts_areRejectedAndServerEnforced() {
        val inactive = PaymentCapableAccounts.rejectionOf(account = account(id = "bank-18000", active = false), mapping = mapping)
        assertEquals(PaymentAccountRejection.INACTIVE, inactive)
        assertTrue(PaymentCapableAccounts.isServerEnforced(inactive))

        val income =
            PaymentCapableAccounts.rejectionOf(
                account = account(id = "income-40000", type = LedgerAccountType.INCOME),
                mapping = mapping,
            )
        assertEquals(PaymentAccountRejection.NOT_ASSET, income)
        assertTrue(PaymentCapableAccounts.isServerEnforced(income))
    }

    /**
     * The exemption exists so an organization whose designated bank account is not class 1 keeps
     * working exactly as before -- but it must never rescue an account that is wrong for a harder
     * reason (see [PaymentCapableAccounts.rejectionOf] KDoc on check order).
     */
    @Test
    fun configuredDefaultBankAccount_isExemptFromTheClassCheckOnly() {
        val classZeroDefault =
            PaymentAccountMapping(defaultBankAccountId = "bank-old", receivablesAccountId = "receivables-12000")
        assertNull(
            PaymentCapableAccounts.rejectionOf(account = account(id = "bank-old", accountClass = 0), mapping = classZeroDefault),
        )
        assertEquals(
            PaymentAccountRejection.INACTIVE,
            PaymentCapableAccounts.rejectionOf(
                account = account(id = "bank-old", accountClass = 0, active = false),
                mapping = classZeroDefault,
            ),
        )
        // Receivables account ALSO configured as the default bank account -- still rejected.
        val misconfigured = PaymentAccountMapping(defaultBankAccountId = "receivables-12000", receivablesAccountId = "receivables-12000")
        assertEquals(
            PaymentAccountRejection.SUBLEDGER_COLLECTIVE_ACCOUNT,
            PaymentCapableAccounts.rejectionOf(account = account(id = "receivables-12000"), mapping = misconfigured),
        )
    }

    @Test
    fun paymentCapableAccounts_keepsOrderAndDropsEveryRejectedAccount() {
        fun dto(
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

        val accounts =
            listOf(
                dto(id = "fixed-06500", number = "06500", accountClass = 0),
                dto(id = "kasse-16000", number = "16000"),
                dto(id = "bank-18000", number = "18000"),
                dto(id = "receivables-12000", number = "12000"),
                dto(id = "inactive", number = "18001", active = false),
                dto(id = "income-40000", number = "40000", type = LedgerAccountType.INCOME, accountClass = 4),
            )
        assertEquals(
            listOf("kasse-16000", "bank-18000"),
            PaymentCapableAccounts.paymentCapableAccounts(accounts = accounts, mapping = mapping).map { it.id },
        )
    }

    /** An empty mapping (nothing configured at all) must not make every asset account a collective account. */
    @Test
    fun emptyMapping_stillAcceptsAClassOneAssetAccount() {
        assertNull(PaymentCapableAccounts.rejectionOf(account = account(id = "bank-18000"), mapping = PaymentAccountMapping()))
    }

    // ── paymentAccountMappingConflictOf (Audit-Nachtrag MAJOR-3) ─────────────────────────────────

    @Test
    fun aConsistentMapping_hasNoConflict() {
        assertNull(paymentAccountMappingConflictOf(mapping))
        assertNull(paymentAccountMappingConflictOf(PaymentAccountMapping()))
        assertNull(paymentAccountMappingConflictOf(PaymentAccountMapping(defaultBankAccountId = "bank-18000")))
    }

    @Test
    fun theBankAccountMustDifferFromBothCollectiveAccounts() {
        assertEquals(
            PaymentAccountMappingConflict.BANK_IS_RECEIVABLES,
            paymentAccountMappingConflictOf(PaymentAccountMapping(defaultBankAccountId = "x", receivablesAccountId = "x")),
        )
        assertEquals(
            PaymentAccountMappingConflict.BANK_IS_PAYABLES,
            paymentAccountMappingConflictOf(PaymentAccountMapping(defaultBankAccountId = "x", payablesAccountId = "x")),
        )
        assertEquals(
            PaymentAccountMappingConflict.RECEIVABLES_IS_PAYABLES,
            paymentAccountMappingConflictOf(PaymentAccountMapping(receivablesAccountId = "y", payablesAccountId = "y")),
        )
    }

    /** Blank is "unset", not a value -- otherwise two empty form fields would look self-referential. */
    @Test
    fun blankIdsCountAsUnset() {
        assertNull(paymentAccountMappingConflictOf(PaymentAccountMapping(defaultBankAccountId = "", receivablesAccountId = "")))
        assertNull(paymentAccountMappingConflictOf(PaymentAccountMapping(receivablesAccountId = "  ", payablesAccountId = "  ")))
    }
}
