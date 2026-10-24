package network.lapis.cloud.shared.domain

import kotlinx.serialization.Serializable

/**
 * V1.9.206 "Finance setup for laypeople". The six steps of the guided setup that sits in the
 * "Einrichtung" tab of the ledger screen. Literals are append-only.
 *
 * The pure functions in this file are the ONLY place where "which accounts may a step use" and
 * "when is the setup complete" are decided. Client and server both call them, so the screen can
 * never offer an account the server would refuse, and the server's activation gate for online
 * payments can never disagree with what the screen shows.
 */
@Serializable
enum class FinanceSetupStep { CHART, BANK_ACCOUNT, CONTRIBUTION_INCOME, DONATION_INCOME, EVENT_INCOME, FEE_ACCOUNT }

@Serializable
enum class FinanceSetupStepState { DONE, OPEN, OPTIONAL, ERROR }

/** Deep-link value (`?step=`). Never translated; append-only like the enum. */
val FinanceSetupStep.slug: String
    get() =
        when (this) {
            FinanceSetupStep.CHART -> "chart"
            FinanceSetupStep.BANK_ACCOUNT -> "bank"
            FinanceSetupStep.CONTRIBUTION_INCOME -> "contribution"
            FinanceSetupStep.DONATION_INCOME -> "donation"
            FinanceSetupStep.EVENT_INCOME -> "event"
            FinanceSetupStep.FEE_ACCOUNT -> "fee"
        }

/** Inverse of [slug]; `null` for an unknown value. */
fun financeSetupStepFromSlug(slug: String?): FinanceSetupStep? = FinanceSetupStep.entries.firstOrNull { it.slug == slug }

/** Only the fee account is optional: no payment provider reports fees today. */
val FinanceSetupStep.required: Boolean
    get() = this != FinanceSetupStep.FEE_ACCOUNT

/** The ledger-account type a mapping step accepts; `null` for [FinanceSetupStep.CHART]. */
val FinanceSetupStep.requiredType: LedgerAccountType?
    get() =
        when (this) {
            FinanceSetupStep.CHART -> null
            FinanceSetupStep.BANK_ACCOUNT -> LedgerAccountType.ASSET
            FinanceSetupStep.CONTRIBUTION_INCOME,
            FinanceSetupStep.DONATION_INCOME,
            FinanceSetupStep.EVENT_INCOME,
            -> LedgerAccountType.INCOME
            FinanceSetupStep.FEE_ACCOUNT -> LedgerAccountType.EXPENSE
        }

/** Number of required steps ([FinanceSetupStep.CHART] plus the four required mappings). */
const val FINANCE_SETUP_REQUIRED_STEP_COUNT: Int = 5

@Serializable
data class FinanceSetupStepStatus(
    val step: FinanceSetupStep,
    val state: FinanceSetupStepState,
    /** The stored mapping (may point at an inactive account); `null` for CHART and unset mappings. */
    val mappedAccountId: String? = null,
    /** ERROR reason: the mapped account exists but is inactive. */
    val mappedAccountInactive: Boolean = false,
)

@Serializable
data class FinanceSetupStatusDto(
    /** Always six entries, in [FinanceSetupStep] enum order. */
    val steps: List<FinanceSetupStepStatus>,
    val activeAccountCount: Int,
    /** BANK, CONTRIBUTION, DONATION and EVENT are all [FinanceSetupStepState.DONE]. FEE is irrelevant. */
    val mappingComplete: Boolean,
) {
    private val unfinishedRequired: List<FinanceSetupStepStatus>
        get() = steps.filter { it.step.required && it.state != FinanceSetupStepState.DONE }

    val firstOpenRequired: FinanceSetupStep?
        get() = unfinishedRequired.firstOrNull()?.step
    val openRequiredCount: Int
        get() = unfinishedRequired.size
    val doneRequiredCount: Int
        get() = FINANCE_SETUP_REQUIRED_STEP_COUNT - openRequiredCount

    fun stateOf(step: FinanceSetupStep): FinanceSetupStepState = steps.first { it.step == step }.state
}

/** Minimal projection so client ([LedgerAccountDto]) and server (table rows) can both feed the rules. */
data class SetupAccountView(
    val id: String,
    val number: String,
    val type: LedgerAccountType,
    val active: Boolean,
    val isCashRegister: Boolean,
)

fun LedgerAccountDto.toSetupView(): SetupAccountView =
    SetupAccountView(id = id, number = accountNumber, type = type, active = active, isCashRegister = isCashRegister)

/**
 * The select filter: active, of the step's type, and never a cash-register account (the server's
 * `requireValidPaymentAccountMapping` refuses exactly these). Always `false` for CHART.
 */
fun FinanceSetupStep.accepts(a: SetupAccountView): Boolean {
    val expected = requiredType ?: return false
    return a.active && a.type == expected && !a.isCashRegister
}

fun financeSetupStatusOf(
    accounts: List<SetupAccountView>,
    bankId: String?,
    contributionId: String?,
    donationId: String?,
    eventId: String?,
    feeId: String?,
): FinanceSetupStatusDto {
    val activeCount = accounts.count { it.active }
    val byId = accounts.associateBy { it.id }
    val mapped =
        mapOf(
            FinanceSetupStep.BANK_ACCOUNT to bankId,
            FinanceSetupStep.CONTRIBUTION_INCOME to contributionId,
            FinanceSetupStep.DONATION_INCOME to donationId,
            FinanceSetupStep.EVENT_INCOME to eventId,
            FinanceSetupStep.FEE_ACCOUNT to feeId,
        )
    val steps =
        FinanceSetupStep.entries.map { step ->
            if (step == FinanceSetupStep.CHART) {
                FinanceSetupStepStatus(
                    step = step,
                    state = if (activeCount > 0) FinanceSetupStepState.DONE else FinanceSetupStepState.OPEN,
                )
            } else {
                val id = mapped.getValue(step)
                val account = id?.let { byId[it] }
                when {
                    id == null ->
                        FinanceSetupStepStatus(
                            step = step,
                            state = if (step.required) FinanceSetupStepState.OPEN else FinanceSetupStepState.OPTIONAL,
                        )
                    account == null || !step.accepts(account) ->
                        FinanceSetupStepStatus(
                            step = step,
                            state = FinanceSetupStepState.ERROR,
                            mappedAccountId = id,
                            mappedAccountInactive = account != null && !account.active,
                        )
                    else -> FinanceSetupStepStatus(step = step, state = FinanceSetupStepState.DONE, mappedAccountId = id)
                }
            }
        }
    val complete =
        listOf(
            FinanceSetupStep.BANK_ACCOUNT,
            FinanceSetupStep.CONTRIBUTION_INCOME,
            FinanceSetupStep.DONATION_INCOME,
            FinanceSetupStep.EVENT_INCOME,
        ).all { required -> steps.first { it.step == required }.state == FinanceSetupStepState.DONE }
    return FinanceSetupStatusDto(steps = steps, activeAccountCount = activeCount, mappingComplete = complete)
}

/** Result of `IAccountingService.createStandardChart`: the concrete account numbers. */
@Serializable
data class StandardChartResultDto(
    val created: List<String>,
    val skipped: List<String>,
)
