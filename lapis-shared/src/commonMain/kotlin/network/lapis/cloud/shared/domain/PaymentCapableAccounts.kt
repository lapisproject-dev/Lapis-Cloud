package network.lapis.cloud.shared.domain

/**
 * Welle V1.4.22 "Zahlungskonto im Offene-Posten-Pfad" -- the ONE place this codebase answers "may
 * money be booked against this [LedgerAccountDto] as the paying/receiving side of a payment?".
 *
 * ## Why this exists at all
 *
 * Found live on staging (V1.4.21): the settlement dialog of the open-items screen offered EVERY
 * `ASSET` account, i.e. also `06500 Betriebs- und Geschäftsausstattung` (fixed assets) and
 * `12000 Forderungen aus Lieferungen und Leistungen` -- the receivables collective account ITSELF.
 * Picking the latter made `OpenItemPostingBridge.postSettlement` book Soll receivables / Haben
 * receivables: a balanced, posted, completely meaningless journal entry that silently closed the
 * open item without any money having moved.
 *
 * ## Where the rule comes from (nothing here is newly invented)
 *
 * Every criterion below is an existing rule of this repo, collected into one function instead of a
 * second, drifting copy:
 *
 * - **active** -- `OrganizationSettingsService.requireValidPaymentAccountMapping` rejects an
 *   inactive mapping target; `OpenItemPostingBridge` degrades any posting that references an
 *   inactive/missing account (`ledger_account_inactive`).
 * - **`ASSET`** -- the same `requireValidPaymentAccountMapping` demands `expectedType = ASSET` for
 *   `organization_settings.payment_bank_account_id`, and the settlement Buchungssatz
 *   (`OpenItemPostingBridge` KDoc: "Kreditor bezahlt Soll payables / Haben bank", "Debitor bezahlt
 *   Soll bank / Haben receivables") only makes sense with an asset account on the money side.
 * - **not the receivables/payables collective account** -- `OpenItemPostingBridge.postSettlement`
 *   puts that very account on the OTHER side of the entry; the same account on both sides is the
 *   staging finding above. (`requireValidPaymentAccountMapping` cannot catch this: the receivables
 *   account is itself an active, non-cash-register `ASSET` account, so it passes every check that
 *   guards the bank mapping.)
 * - **SKR42 Kontenklasse 1 ("liquide Mittel")** -- the only signal this chart of accounts has for
 *   "Bank/Kasse rather than any other asset": see `10-accounting.kuml.kts`'s file header and
 *   `DevSeedData.demoLedgerAccounts` (`16000 Kasse`, `18000 Bank (Girokonto)` -- class 1;
 *   `06500 Betriebs- und Geschäftsausstattung` -- class 0). It is deliberately the ONLY
 *   [PaymentAccountRejection] outside [PaymentCapableAccounts.SERVER_ENFORCED], see below.
 *
 * **A cash register (`isCashRegister`) IS payment-capable here** -- deliberately UNLIKE
 * `requireValidPaymentAccountMapping`, which rejects one as the organization-wide DEFAULT mapping
 * (a Kassenbuch till is not a bank account). Paying a Kreditor in cash is an everyday act, and
 * `OpenItemPostingBridge.postEntry` already runs the full `CashRegisterGuard` GoBD checks
 * (`cash_voucher_required`, `cash_register_balance_insufficient`) on such a posting. That is
 * why `isCashRegister` is not even a field of [PaymentAccountCandidate]: it changes nothing here.
 */
enum class PaymentAccountRejection {
    /** Deactivated account -- `OpenItemPostingBridge` would degrade to `ledger_account_inactive`. */
    INACTIVE,

    /** Not an `ASSET` account, so it cannot be the money side of a payment Buchungssatz. */
    NOT_ASSET,

    /**
     * The configured `organization_settings.receivables_account_id`/`payables_account_id` -- the
     * sub-ledger collective account that already sits on the OTHER side of the settlement entry.
     */
    SUBLEDGER_COLLECTIVE_ACCOUNT,

    /**
     * An `ASSET` account outside SKR42 Kontenklasse 1 ("liquide Mittel"), e.g. fixed assets
     * (`06500`). **The only rejection the server does NOT enforce** -- see
     * [PaymentCapableAccounts.SERVER_ENFORCED].
     */
    NOT_LIQUID_ASSET_CLASS,
}

/**
 * The organization-wide payment-account mapping a payment-capability question is answered against
 * (`organization_settings`). All three are ids as strings, never [kotlin.uuid.Uuid] -- this type is
 * `commonMain` and the client only ever sees the string form ([OrganizationSettingsDto]).
 *
 * `null` for the whole mapping means "not known (yet)" at the call site -- see
 * `network.lapis.cloud.client.settlementBankChoice`, which must not claim "no default bank account
 * configured" while the settings fetch is still in flight or has failed.
 */
data class PaymentAccountMapping(
    val defaultBankAccountId: String? = null,
    val receivablesAccountId: String? = null,
    val payablesAccountId: String? = null,
)

/**
 * The minimal projection of a [LedgerAccountDto] the rule needs -- so the server can answer the
 * same question straight from an Exposed `ResultRow` without building a full DTO, and so that
 * adding a field to [LedgerAccountDto] can never change this rule by accident.
 */
data class PaymentAccountCandidate(
    val id: String,
    val type: LedgerAccountType,
    val accountClass: Int,
    val active: Boolean,
)

fun LedgerAccountDto.toPaymentAccountCandidate(): PaymentAccountCandidate =
    PaymentAccountCandidate(id = id, type = type, accountClass = accountClass, active = active)

/** See [PaymentAccountRejection] KDoc for the whole rationale of this object. */
object PaymentCapableAccounts {
    /** SKR42 Kontenklasse of the liquid means (Kasse, Bank) -- see [PaymentAccountRejection] KDoc. */
    const val LIQUID_ASSET_ACCOUNT_CLASS = 1

    /**
     * The rejections the SERVER turns into a hard `BadRequestException`
     * (`OpenItemService.requirePaymentCapableAccount`). [PaymentAccountRejection.NOT_LIQUID_ASSET_CLASS]
     * is deliberately NOT among them, and that asymmetry is the whole point:
     *
     * - The account CLASS is a chart-of-accounts convention, not an invariant this codebase owns.
     *   An organization whose bank account carries a different Kontenklasse (or whose accounts were
     *   imported with `accountClass = 0` throughout -- exactly what `OpenItemServiceTest`'s own
     *   fixtures do) would suddenly be unable to settle anything at all. Rejecting on it would be a
     *   breaking change to a path that has worked since V1.4.15.
     * - The other three are real booking defects: an inactive account cannot be posted to at all, a
     *   non-`ASSET` account inverts the Buchungssatz, and the collective account books against
     *   itself.
     *
     * So the class criterion shapes what the UI OFFERS (a treasurer is never handed a fixed-asset
     * account again), while the server only refuses what is certainly wrong. An explicitly chosen
     * non-class-1 asset account is logged (WARN) and accepted.
     */
    val SERVER_ENFORCED: Set<PaymentAccountRejection> =
        setOf(
            PaymentAccountRejection.INACTIVE,
            PaymentAccountRejection.NOT_ASSET,
            PaymentAccountRejection.SUBLEDGER_COLLECTIVE_ACCOUNT,
        )

    /**
     * `null` = [account] may be used as the money side of a payment. The order of the checks is
     * load-bearing: [PaymentAccountRejection.SUBLEDGER_COLLECTIVE_ACCOUNT] is decided BEFORE the
     * [PaymentAccountMapping.defaultBankAccountId] exemption below, so an organization that
     * (mis)configured its receivables account as the default bank account is still rejected rather
     * than exempted.
     *
     * **The configured default bank account is exempt from the class check** -- and only from that
     * one. It is the account the organization itself designated as its payment account (ADMIN
     * choice, already validated by `requireValidPaymentAccountMapping`); hiding it from the very
     * dialog whose pre-selection uses it would be absurd, and would have silently changed behaviour
     * for every organization whose bank account is not class 1.
     */
    fun rejectionOf(
        account: PaymentAccountCandidate,
        mapping: PaymentAccountMapping,
    ): PaymentAccountRejection? =
        when {
            !account.active -> PaymentAccountRejection.INACTIVE
            account.type != LedgerAccountType.ASSET -> PaymentAccountRejection.NOT_ASSET
            account.id == mapping.receivablesAccountId || account.id == mapping.payablesAccountId ->
                PaymentAccountRejection.SUBLEDGER_COLLECTIVE_ACCOUNT
            account.id == mapping.defaultBankAccountId -> null
            account.accountClass != LIQUID_ASSET_ACCOUNT_CLASS -> PaymentAccountRejection.NOT_LIQUID_ASSET_CLASS
            else -> null
        }

    fun isPaymentCapable(
        account: PaymentAccountCandidate,
        mapping: PaymentAccountMapping,
    ): Boolean = rejectionOf(account = account, mapping = mapping) == null

    /** `true` for exactly the rejections the server refuses outright -- see [SERVER_ENFORCED]. */
    fun isServerEnforced(rejection: PaymentAccountRejection?): Boolean = rejection != null && rejection in SERVER_ENFORCED

    /** [accounts] filtered to those a payment may be booked against, order preserved. */
    fun paymentCapableAccounts(
        accounts: List<LedgerAccountDto>,
        mapping: PaymentAccountMapping,
    ): List<LedgerAccountDto> = accounts.filter { isPaymentCapable(account = it.toPaymentAccountCandidate(), mapping = mapping) }
}

/**
 * Welle V1.4.22, Audit-Nachtrag (MAJOR-3): a mapping can be internally contradictory even though each
 * single field passes `OrganizationSettingsService.requireValidPaymentAccountMapping` (which validates
 * every field in isolation -- existence, `active`, expected [LedgerAccountType], non-cash-register).
 * Pointing `payment_bank_account_id` at the receivables or payables collective account is always
 * wrong, and it was configurable: the receivables account IS an active, non-cash-register `ASSET`
 * account, so the per-field check has nothing to object to. Every settlement booked against it would
 * then debit and credit the same account.
 */
enum class PaymentAccountMappingConflict {
    /** `payment_bank_account_id` == `receivables_account_id`. */
    BANK_IS_RECEIVABLES,

    /** `payment_bank_account_id` == `payables_account_id`. */
    BANK_IS_PAYABLES,

    /**
     * `receivables_account_id` == `payables_account_id`. Structurally impossible once both type
     * checks hold (`ASSET` vs `LIABILITY`) -- kept as an explicit literal because a netting posts
     * exactly those two accounts against each other, so if it ever DID happen it would produce a
     * debit-equals-credit entry (see `OpenItemPostingBridge.postNetting`).
     */
    RECEIVABLES_IS_PAYABLES,
}

/**
 * `null` = [mapping] is internally consistent. Checked in the order the damage would occur; the first
 * conflict found is reported. Blank ids are treated as "unset" so an empty form field cannot make a
 * mapping look self-referential. Shared so `OrganizationSettingsService.updateOrganizationSettings`
 * (hard rejection) and the chart-of-accounts screen (a readable message before the round trip) decide
 * by the SAME rule -- see [PaymentAccountMappingConflict].
 */
fun paymentAccountMappingConflictOf(mapping: PaymentAccountMapping): PaymentAccountMappingConflict? {
    val bank = mapping.defaultBankAccountId?.takeIf { it.isNotBlank() }
    val receivables = mapping.receivablesAccountId?.takeIf { it.isNotBlank() }
    val payables = mapping.payablesAccountId?.takeIf { it.isNotBlank() }
    return when {
        bank != null && bank == receivables -> PaymentAccountMappingConflict.BANK_IS_RECEIVABLES
        bank != null && bank == payables -> PaymentAccountMappingConflict.BANK_IS_PAYABLES
        receivables != null && receivables == payables -> PaymentAccountMappingConflict.RECEIVABLES_IS_PAYABLES
        else -> null
    }
}
