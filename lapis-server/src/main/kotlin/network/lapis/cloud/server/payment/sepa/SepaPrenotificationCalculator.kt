package network.lapis.cloud.server.payment.sepa

import java.math.BigDecimal

/**
 * V1.2.2 "SEPA-Lastschriftmandate", decision point E-7 (confirmed by the user): a SEPA mandate
 * survives a contribution amount change -- it authorizes the DEBIT, not a fixed amount. The amount
 * change is communicated via the pre-notification. On an INCREASE, the FULL notice period applies,
 * even if `organization_settings.sepa_prenotification_days` is configured shorter.
 *
 * **Legal status, researched, explicitly NOT legal advice** (same disclosure discipline as
 * `PartyDonationComplianceCalculator`/`UseOfFundsCalculator`): the pre-notification must reach the
 * debtor at least **14 calendar days** before the due date, unless otherwise agreed. The period is
 * waivable -- many associations shorten it via their bylaws/contribution rules. Whether a specific
 * shortening holds up is a matter for a lawyer to assess against the bylaws, not this code.
 * [FULL_NOTICE_DAYS] is therefore a named constant with a justification KDoc, not a magic literal at
 * a call site.
 */
object SepaPrenotificationCalculator {
    /** Regulatory default when nothing else is validly agreed. */
    const val FULL_NOTICE_DAYS: Int = 14

    /**
     * @param previousAmount the amount most recently collected via THIS mandate
     *   (`sepa_mandate.last_debited_amount`), `null` on the very first collection.
     * @param currentAmount the amount to be collected now.
     * @param configuredDays `organization_settings.sepa_prenotification_days`.
     *
     * Rule:
     *  - [previousAmount] `null` (first collection): [configuredDays]. There is no prior amount to
     *    compare against; the amount was already communicated as part of the mandate context.
     *  - `currentAmount > previousAmount` (increase): `maxOf(configuredDays, FULL_NOTICE_DAYS)`. Not
     *    simply [FULL_NOTICE_DAYS] -- a LONGER configured period stays longer.
     *  - otherwise (equal or lower): [configuredDays].
     *
     * Comparison exclusively via [BigDecimal.compareTo], never `equals`/`==` -- `1.50` and `1.500`
     * compare equal but are `equals`-unequal (the trap `JournalEntryBalance`/`UseOfFundsCalculator`/
     * `PartyDonationComplianceCalculator` already name in their own KDocs).
     */
    fun requiredNoticeDays(
        previousAmount: BigDecimal?,
        currentAmount: BigDecimal,
        configuredDays: Int,
    ): Int {
        if (previousAmount == null) return configuredDays
        return if (currentAmount.compareTo(previousAmount) > 0) {
            maxOf(configuredDays, FULL_NOTICE_DAYS)
        } else {
            configuredDays
        }
    }

    /** The whole batch's required notice period = the maximum over every position. */
    fun requiredNoticeDaysForBatch(
        items: List<Pair<BigDecimal?, BigDecimal>>,
        configuredDays: Int,
    ): Int =
        items.maxOfOrNull { (previous, current) ->
            requiredNoticeDays(previousAmount = previous, currentAmount = current, configuredDays = configuredDays)
        } ?: configuredDays
}
