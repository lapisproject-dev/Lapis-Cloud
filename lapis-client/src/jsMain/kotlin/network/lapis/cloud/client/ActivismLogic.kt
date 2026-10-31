package network.lapis.cloud.client

import io.kvision.i18n.I18n
import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.ActivismActionDetailDto
import network.lapis.cloud.shared.domain.ActivismActionSummaryDto
import network.lapis.cloud.shared.domain.ActivismLimits
import network.lapis.cloud.shared.domain.ActivismSignalStateDto
import network.lapis.cloud.shared.domain.ActivismStatus
import network.lapis.cloud.shared.domain.ActivismTransitions
import network.lapis.cloud.shared.domain.ActivismViewerRole

/** V1.9.246 -- pure client logic of the action pages (no DOM, no RPC): what to show, which buttons are offered. The server decides every time. */
internal object ActivismLogic {
    /** The list shows "Geplant" and "Läuft" until the person picks other statuses. */
    val DEFAULT_STATUSES: List<ActivismStatus> = listOf(ActivismStatus.PLANNED, ActivismStatus.RUNNING)

    /** The status choices of the list (the hidden ones are only useful for responsible persons and managers). */
    fun filterStatuses(isManagerOrResponsibleView: Boolean): List<ActivismStatus> =
        if (isManagerOrResponsibleView) {
            ActivismStatus.entries
        } else {
            listOf(
                ActivismStatus.PLANNED,
                ActivismStatus.RUNNING,
                ActivismStatus.COMPLETED,
            )
        }

    fun count(value: Int): String = formatCountDigits(value.toString(), moneyLocale(I18n.language))

    /** "1.240 von 5.000 Plakaten" (with a goal) or "1.240 Plakate" (without), plus ", davon 800 bestätigt" when contributions are confirmed. */
    fun progressText(
        reported: Int,
        confirmed: Int?,
        goal: Int?,
        unit: String?,
    ): String {
        val unitText = unit?.takeIf { it.isNotBlank() }
        val base =
            when {
                goal != null && unitText != null -> gettext("%1 von %2 %3", count(reported), count(goal), unitText)
                goal != null -> gettext("%1 von %2", count(reported), count(goal))
                unitText != null -> gettext("%1 %2", count(reported), unitText)
                else -> count(reported)
            }
        return if (confirmed == null) base else gettext("%1 gemeldet, davon %2 bestätigt", base, count(confirmed))
    }

    /** Share for the native `<progress>` element, 0..100; no goal = no bar. */
    fun progressPercent(
        reported: Int,
        goal: Int?,
    ): Int? = goal?.takeIf { it > 0 }?.let { (reported.toLong() * 100 / it).coerceIn(0, 100).toInt() }

    fun summaryProgress(summary: ActivismActionSummaryDto): String? =
        if (!summary.formProgress) {
            null
        } else {
            progressText(summary.progressReported, summary.progressConfirmed, summary.goalQuantity, summary.unit)
        }

    /** "weniger als 5" below the display threshold, else the number. */
    fun signalText(state: ActivismSignalStateDto): String =
        state.countAtLeast5?.let { gettext("%1 Personen kommen", count(it)) }
            ?: gettext("Weniger als %1 Personen haben zugesagt", ActivismLimits.SIGNAL_MIN_DISPLAY.toString())

    fun canEdit(role: ActivismViewerRole): Boolean = role != ActivismViewerRole.MEMBER

    fun isManager(role: ActivismViewerRole): Boolean = role == ActivismViewerRole.MANAGER

    /** The statuses the viewer may move this action to with one click (the review switch decides the way out of IDEA). */
    fun statusTargets(
        detail: ActivismActionDetailDto,
        reviewRequired: Boolean,
    ): List<ActivismStatus> =
        listOf(ActivismStatus.PLANNED, ActivismStatus.RUNNING, ActivismStatus.COMPLETED).filter {
            detail.status != ActivismStatus.AWAITING_REVIEW &&
                detail.status != ActivismStatus.WITHDRAWN &&
                ActivismTransitions.allowed(detail.status, it, detail.viewerRole, reviewRequired)
        }

    fun canSubmitForReview(
        detail: ActivismActionDetailDto,
        reviewRequired: Boolean,
    ): Boolean = ActivismTransitions.allowed(detail.status, ActivismStatus.AWAITING_REVIEW, detail.viewerRole, reviewRequired)

    fun canParticipate(detail: ActivismActionDetailDto): Boolean = ActivismTransitions.acceptsParticipation(detail.status)

    fun canReportProgress(detail: ActivismActionDetailDto): Boolean = detail.formProgress && canParticipate(detail)

    fun canSignal(detail: ActivismActionDetailDto): Boolean = detail.formSignal && canParticipate(detail)

    /** A notice text of the operator, or the translated standard text of the kind. */
    fun noticeFor(detail: ActivismActionDetailDto): String = detail.noticeText ?: ActivismTexts.defaultNotice(detail.kind)

    /** A number typed into a field; null for anything that is not a whole number in range. */
    fun parseWhole(
        raw: String,
        min: Int,
        max: Int,
    ): Int? = raw.trim().toIntOrNull()?.takeIf { it in min..max }
}
