package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.tag
import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.ElectionDto
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.ElectionType

/** The visible stations of an election's life, left to right. */
enum class ElectionPhaseStep { PREPARATION, CANDIDATES_RELEASED, VOTING_OPEN, VOTING_CLOSED, TALLIED }

/** The stations of [type]: a yes/no election has no candidate list, so it skips [ElectionPhaseStep.CANDIDATES_RELEASED]. */
fun phaseSteps(type: ElectionType): List<ElectionPhaseStep> =
    if (type == ElectionType.YES_NO) {
        listOf(ElectionPhaseStep.PREPARATION, ElectionPhaseStep.VOTING_OPEN, ElectionPhaseStep.VOTING_CLOSED, ElectionPhaseStep.TALLIED)
    } else {
        ElectionPhaseStep.entries.toList()
    }

/**
 * The last station [e] has reached. An aborted election keeps the station it was aborted at, read from its timestamps (the server
 * stores no "aborted at" and the status itself carries no position).
 */
fun reachedStep(e: ElectionDto): ElectionPhaseStep =
    when (e.status) {
        ElectionStatus.PREPARATION -> ElectionPhaseStep.PREPARATION
        ElectionStatus.CANDIDATE_LIST_RELEASED -> ElectionPhaseStep.CANDIDATES_RELEASED
        ElectionStatus.OPEN -> ElectionPhaseStep.VOTING_OPEN
        ElectionStatus.CLOSED -> ElectionPhaseStep.VOTING_CLOSED
        ElectionStatus.TALLIED -> ElectionPhaseStep.TALLIED
        ElectionStatus.ABORTED ->
            when {
                e.votingClosedAt != null -> ElectionPhaseStep.VOTING_CLOSED
                e.votingOpenedAt != null -> ElectionPhaseStep.VOTING_OPEN
                e.candidateListApprovedAt != null && e.electionType != ElectionType.YES_NO -> ElectionPhaseStep.CANDIDATES_RELEASED
                else -> ElectionPhaseStep.PREPARATION
            }
    }

fun electionPhaseStepLabel(step: ElectionPhaseStep): String =
    when (step) {
        ElectionPhaseStep.PREPARATION -> gettext("Vorbereitung")
        ElectionPhaseStep.CANDIDATES_RELEASED -> gettext("Kandidatenliste")
        ElectionPhaseStep.VOTING_OPEN -> gettext("Abstimmung")
        ElectionPhaseStep.VOTING_CLOSED -> gettext("Freigabe")
        ElectionPhaseStep.TALLIED -> gettext("Ergebnis")
    }

/**
 * The phase bar: an ordered list, the current station carries `aria-current="step"`, stations already passed are marked done.
 * Laid out horizontally; below 576 px the CSS stacks it vertically (`theme.css`, `.lapis-election-phase`).
 */
fun Container.electionPhaseBar(e: ElectionDto): Tag {
    val steps = phaseSteps(e.electionType)
    val reached = steps.indexOf(reachedStep(e)).coerceAtLeast(0)
    val list = tag(TAG.OL, className = "lapis-election-phase")
    list.setAttribute("aria-label", gettext("Ablauf der Wahl"))
    steps.forEachIndexed { index, step ->
        val item =
            list.tag(TAG.LI, content = electionPhaseStepLabel(step), className = "lapis-election-phase__item") {
                if (index < reached) addCssClass("lapis-election-phase__item--done")
                if (index == reached) {
                    addCssClass("lapis-election-phase__item--current")
                    setAttribute("aria-current", "step")
                }
            }
        item.setAttribute("data-step", step.name)
    }
    return list
}
