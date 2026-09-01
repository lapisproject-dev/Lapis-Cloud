package network.lapis.cloud.client

import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.DunningNoticeStatus

/**
 * Client-UI wave for GitHub Issue #5 -- German label/badge-color tables for [DunningNoticeStatus]
 * and [ContributionStatus], same `SepaLabels.kt`/`AccountingLabels.kt` grammar: `when` over
 * `entries`, exhaustive, `gettext(...)`.
 *
 * [ContributionStatus] is the FIRST use of that enum in this client (previously server/shared
 * only) -- all eight literals are labelled here, not just the three [network.lapis.cloud.shared.domain.ContributionStatusSets.DUNNABLE]
 * ones, since a case's history can show a contribution that has since left the dunnable set
 * (PAID/WAIVED after being dunned, or a debit run started underneath it).
 *
 * `postalDeliveryStatusLabel`/`postalDeliveryStatusColor` are deliberately NOT redeclared here --
 * they already exist as public top-level functions in `PostalMailScreen.kt`, same package
 * (`network.lapis.cloud.client`), and are reused as-is by [renderDunningCasesScreen]'s notice-
 * history table. Redeclaring them here would be an immediate redeclaration compile error.
 */
fun dunningNoticeStatusLabel(status: DunningNoticeStatus): String =
    when (status) {
        DunningNoticeStatus.ISSUED -> gettext("Ausgestellt")
        DunningNoticeStatus.SKIPPED -> gettext("Übersprungen")
        DunningNoticeStatus.CANCELLED -> gettext("Storniert")
    }

fun dunningNoticeStatusColor(status: DunningNoticeStatus): String =
    when (status) {
        DunningNoticeStatus.ISSUED -> "success"
        DunningNoticeStatus.SKIPPED -> "secondary"
        DunningNoticeStatus.CANCELLED -> "danger"
    }

fun contributionStatusLabel(status: ContributionStatus): String =
    when (status) {
        ContributionStatus.OPEN -> gettext("Offen")
        ContributionStatus.PAID -> gettext("Bezahlt")
        ContributionStatus.WAIVED -> gettext("Erlassen")
        ContributionStatus.OVERDUE -> gettext("Überfällig")
        ContributionStatus.DEBIT_SCHEDULED -> gettext("Lastschrift geplant")
        ContributionStatus.DEBIT_SUBMITTED -> gettext("Lastschrift eingereicht")
        ContributionStatus.RETURNED -> gettext("Rücklastschrift")
        ContributionStatus.IN_DUNNING -> gettext("Im Mahnverfahren")
    }

fun contributionStatusColor(status: ContributionStatus): String =
    when (status) {
        ContributionStatus.OPEN -> "secondary"
        ContributionStatus.PAID -> "success"
        ContributionStatus.WAIVED -> "info"
        ContributionStatus.OVERDUE -> "warning"
        ContributionStatus.DEBIT_SCHEDULED -> "info"
        ContributionStatus.DEBIT_SUBMITTED -> "primary"
        ContributionStatus.RETURNED -> "danger"
        ContributionStatus.IN_DUNNING -> "danger"
    }
