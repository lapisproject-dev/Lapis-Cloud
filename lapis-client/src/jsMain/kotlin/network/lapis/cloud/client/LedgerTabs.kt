package network.lapis.cloud.client

import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.FinanceSetupStatusDto
import network.lapis.cloud.shared.domain.FinanceSetupStep
import network.lapis.cloud.shared.domain.financeSetupStepFromSlug
import network.lapis.cloud.shared.domain.slug

/**
 * V1.9.206. The three tabs of "Kontenplan & Journal". [slug] is the deep-link value of `?tab=` and is never translated.
 */
internal enum class LedgerTab(
    val slug: String,
) {
    SETUP("einrichtung"),
    ACCOUNTS("konten"),
    JOURNAL("journal"),
}

/** `?tab=` of a location hash; `null` when absent or unknown. */
internal fun parseLedgerTab(hash: String): LedgerTab? =
    parseHashQueryParam(hash, "tab")?.let { slug ->
        LedgerTab.entries.firstOrNull {
            it.slug ==
                slug
        }
    }

/** `?step=` of a location hash; `null` when absent or unknown. */
internal fun parseSetupStepAnchor(hash: String): FinanceSetupStep? = financeSetupStepFromSlug(parseHashQueryParam(hash, "step"))

/**
 * The tab to show first: an explicit `?tab=` wins; otherwise "Einrichtung" for a person who may set things up (TREASURER/ADMIN) while a
 * required step is open; otherwise "Konten". A BOARD member has no setup rights and so never lands on a tab full of things they cannot do.
 */
internal fun defaultLedgerTab(
    explicit: LedgerTab?,
    status: FinanceSetupStatusDto?,
    hasFinanceRights: Boolean,
): LedgerTab = explicit ?: if (hasFinanceRights && status != null && status.openRequiredCount > 0) LedgerTab.SETUP else LedgerTab.ACCOUNTS

/** A hash that opens [tab] (and scrolls to [step] on the setup tab). One `#` only -- the hash router cannot carry a second one. */
internal fun ledgerHash(
    tab: LedgerTab,
    step: FinanceSetupStep? = null,
): String =
    buildString {
        append("#").append(Routes.LEDGER).append("?tab=").append(tab.slug)
        if (step != null) append("&step=").append(step.slug)
    }

/** The hash a `/ledger` route match stands for: its own query string (the browser URL is not updated yet while Navigo runs the handler). */
internal fun ledgerHashOfMatch(queryString: String?): String =
    if (queryString.isNullOrEmpty()) "#" + Routes.LEDGER else "#" + Routes.LEDGER + "?" + queryString

/** Hint above the account tabs; the sentence about unbooked contributions only for the steps that really block ContributionPostingBridge. */
internal fun setupHintText(missing: FinanceSetupStep): String {
    val affectsContributions =
        missing == FinanceSetupStep.BANK_ACCOUNT ||
            missing == FinanceSetupStep.CONTRIBUTION_INCOME ||
            missing == FinanceSetupStep.FEE_ACCOUNT
    return if (affectsContributions) {
        gettext("Es fehlt noch: %1. Solange das fehlt, werden bezahlte Beiträge nicht gebucht.", setupStepTitle(missing))
    } else {
        gettext("Es fehlt noch: %1.", setupStepTitle(missing))
    }
}

/** The step to hand to the FIRST render of the setup tab; an already rendered tab is scrolled to directly instead. */
internal fun pendingSetupStep(
    setupTabRendered: Boolean,
    step: FinanceSetupStep?,
): FinanceSetupStep? = if (setupTabRendered) null else step
