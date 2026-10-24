package network.lapis.cloud.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FinanceSetupTest {
    private fun acc(
        id: String,
        type: LedgerAccountType,
        active: Boolean = true,
        cash: Boolean = false,
    ) = SetupAccountView(id = id, number = id, type = type, active = active, isCashRegister = cash)

    private val bank = acc(id = "b", type = LedgerAccountType.ASSET)
    private val inc1 = acc(id = "i1", type = LedgerAccountType.INCOME)
    private val inc2 = acc(id = "i2", type = LedgerAccountType.INCOME)
    private val inc3 = acc(id = "i3", type = LedgerAccountType.INCOME)
    private val fee = acc(id = "f", type = LedgerAccountType.EXPENSE)

    @Test
    fun emptyChart_everythingOpen_feeOptional() {
        val s =
            financeSetupStatusOf(
                accounts = emptyList(),
                bankId = null,
                contributionId = null,
                donationId = null,
                eventId = null,
                feeId = null,
            )
        assertEquals(6, s.steps.size)
        assertEquals(FinanceSetupStepState.OPEN, s.stateOf(FinanceSetupStep.CHART))
        assertEquals(FinanceSetupStepState.OPEN, s.stateOf(FinanceSetupStep.BANK_ACCOUNT))
        assertEquals(FinanceSetupStepState.OPTIONAL, s.stateOf(FinanceSetupStep.FEE_ACCOUNT))
        assertFalse(s.mappingComplete)
        assertEquals(FinanceSetupStep.CHART, s.firstOpenRequired)
        assertEquals(5, s.openRequiredCount)
        assertEquals(0, s.doneRequiredCount)
    }

    @Test
    fun fullMapping_withoutFee_isComplete() {
        val s =
            financeSetupStatusOf(
                accounts = listOf(bank, inc1, inc2, inc3),
                bankId = "b",
                contributionId = "i1",
                donationId = "i2",
                eventId = "i3",
                feeId = null,
            )
        assertTrue(s.mappingComplete)
        assertNull(s.firstOpenRequired)
        assertEquals(0, s.openRequiredCount)
        assertEquals(5, s.doneRequiredCount)
        assertEquals(FinanceSetupStepState.OPTIONAL, s.stateOf(FinanceSetupStep.FEE_ACCOUNT))
    }

    @Test
    fun inactiveMappedAccount_isError_withInactiveFlag() {
        val s =
            financeSetupStatusOf(
                accounts = listOf(bank, inc1.copy(active = false), inc2, inc3),
                bankId = "b",
                contributionId = "i1",
                donationId = "i2",
                eventId = "i3",
                feeId = null,
            )
        val step = s.steps.first { it.step == FinanceSetupStep.CONTRIBUTION_INCOME }
        assertEquals(FinanceSetupStepState.ERROR, step.state)
        assertTrue(step.mappedAccountInactive)
        assertEquals("i1", step.mappedAccountId)
        assertFalse(s.mappingComplete)
        assertEquals(FinanceSetupStep.CONTRIBUTION_INCOME, s.firstOpenRequired)
    }

    @Test
    fun wrongTypeMappedAccount_isError_notInactive() {
        val s =
            financeSetupStatusOf(
                accounts = listOf(bank, inc1, inc2, inc3),
                bankId = "i1",
                contributionId = "i1",
                donationId = "i2",
                eventId = "i3",
                feeId = null,
            )
        val step = s.steps.first { it.step == FinanceSetupStep.BANK_ACCOUNT }
        assertEquals(FinanceSetupStepState.ERROR, step.state)
        assertFalse(step.mappedAccountInactive)
    }

    @Test
    fun missingMappedAccount_isError() {
        val s =
            financeSetupStatusOf(
                accounts = listOf(bank),
                bankId = "b",
                contributionId = "ghost",
                donationId = null,
                eventId = null,
                feeId = null,
            )
        assertEquals(FinanceSetupStepState.ERROR, s.stateOf(FinanceSetupStep.CONTRIBUTION_INCOME))
    }

    @Test
    fun feeError_doesNotAffectMappingComplete_butFeeStaysError() {
        val s =
            financeSetupStatusOf(
                accounts = listOf(bank, inc1, inc2, inc3, fee.copy(active = false)),
                bankId = "b",
                contributionId = "i1",
                donationId = "i2",
                eventId = "i3",
                feeId = "f",
            )
        assertTrue(s.mappingComplete)
        assertEquals(FinanceSetupStepState.ERROR, s.stateOf(FinanceSetupStep.FEE_ACCOUNT))
    }

    @Test
    fun accepts_followsTypeActiveAndCashRule() {
        assertTrue(FinanceSetupStep.BANK_ACCOUNT.accepts(bank))
        assertFalse(FinanceSetupStep.BANK_ACCOUNT.accepts(bank.copy(isCashRegister = true)))
        assertFalse(FinanceSetupStep.BANK_ACCOUNT.accepts(bank.copy(active = false)))
        assertFalse(FinanceSetupStep.BANK_ACCOUNT.accepts(inc1))
        assertTrue(FinanceSetupStep.EVENT_INCOME.accepts(inc1))
        assertFalse(FinanceSetupStep.EVENT_INCOME.accepts(bank))
        assertTrue(FinanceSetupStep.FEE_ACCOUNT.accepts(fee))
        assertFalse(FinanceSetupStep.FEE_ACCOUNT.accepts(inc1))
        assertFalse(FinanceSetupStep.CHART.accepts(bank))
    }

    @Test
    fun slugs_areUniqueAndRoundTrip() {
        val slugs = FinanceSetupStep.entries.map { it.slug }
        assertEquals(slugs.size, slugs.toSet().size)
        FinanceSetupStep.entries.forEach { assertEquals(it, financeSetupStepFromSlug(it.slug)) }
        assertNull(financeSetupStepFromSlug("nope"))
        assertNull(financeSetupStepFromSlug(null))
    }

    @Test
    fun standardChart_isSortedUniqueAndDefaultsAreAccepted() {
        val numbers = StandardAssociationChart.accounts.map { it.accountNumber }
        assertEquals(numbers.sorted(), numbers)
        assertEquals(numbers.size, numbers.toSet().size)
        assertEquals(20, numbers.size)
        assertTrue(numbers.all { Regex("^[0-9]{1,10}$").matches(it) })
        StandardAssociationChart.defaultFor.forEach { (step, number) ->
            val a = StandardAssociationChart.accounts.single { it.accountNumber == number }
            assertTrue(
                step.accepts(
                    a.let {
                        SetupAccountView(
                            id = it.accountNumber,
                            number = it.accountNumber,
                            type = it.type,
                            active = true,
                            isCashRegister = it.isCashRegister,
                        )
                    },
                ),
                "$step -> $number",
            )
        }
    }
}
