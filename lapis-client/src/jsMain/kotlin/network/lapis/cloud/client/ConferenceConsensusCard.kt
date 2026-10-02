package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.link
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import network.lapis.cloud.shared.domain.RoomBallotDto
import network.lapis.cloud.shared.domain.RoomBallotStatus
import network.lapis.cloud.shared.domain.SystemicConsensusStatus

/*
 * V1.9.32 "Konsensieren im Konferenzraum" -- the live card of a systemic consensus in the room panel.
 *
 * Like every other card of the panel it knows only what the room DTO carries: the title, the phase, whether the consensus is anonymous and
 * the caller's OWN two flags. No rating, no option, no count and no other member ever reaches this file. Rating itself happens in the
 * booth (`ConsensusBooth.kt`), the operator steps are `ConferenceConsensusOperator.kt`.
 */

private fun phaseColor(phase: SystemicConsensusStatus?): String =
    when (phase) {
        SystemicConsensusStatus.RATING -> "success"
        SystemicConsensusStatus.COLLECTION -> "info"
        SystemicConsensusStatus.CLOSED -> "warning"
        else -> "secondary"
    }

private fun phaseIcon(phase: SystemicConsensusStatus?): String =
    when (phase) {
        SystemicConsensusStatus.RATING -> "fas fa-circle"
        SystemicConsensusStatus.COLLECTION -> "fas fa-list"
        SystemicConsensusStatus.CLOSED -> "fas fa-hourglass-half"
        else -> "fas fa-check"
    }

/** Line 2 of the card and of the guest row: the phase and whether the rating is anonymous. */
private fun Container.consensusBadges(ballot: RoomBallotDto) {
    val row = hPanel(spacing = 6) { addCssClasses("flex-wrap align-items-center") }
    val phase = ballot.consensusPhase
    val label = if (phase != null) consensusPhaseLabel(phase) else roomBallotStatusLabel(ballot.status)
    row.votingBadge(label, phaseColor(phase), phaseIcon(phase))
    if (ballot.secret) {
        row.votingBadge(tr("anonym"), "dark", "fas fa-lock")
    } else {
        row.votingBadge(tr("offen – namentlich"), "info", "fas fa-user-pen")
    }
}

/** Your own standing in the RATING phase. Nobody else appears. */
private fun ownRatingLabel(ballot: RoomBallotDto): String =
    when {
        ballot.ownHasVoted -> tr("Sie haben bewertet")
        ballot.ownEligible -> tr("Sie können bewerten")
        else -> tr("Nicht stimmberechtigt")
    }

private fun Container.consensusTabLink(
    label: String,
    href: String,
    asButton: Boolean = false,
) {
    link(label, url = href, target = "_blank") {
        setAttribute("rel", "noopener noreferrer")
        if (asButton) addCssClasses("btn btn-primary w-100") else addCssClass("small")
    }
}

/**
 * One consensus as a card of three lines (title; phase and anonymity; your standing -- the last only while RATING) and, when the member can
 * rate right now, the single primary "Bewerten" button. [tabHref] replaces that button by a link-button into a new tab: the room panel is
 * too narrow for the booth (see `consensusFitsPanel`). A card for somebody who cannot act ([canAct] `false`) shows no button at all.
 */
internal fun renderConsensusLiveCard(
    parent: Container,
    ballot: RoomBallotDto,
    canAct: Boolean,
    onRate: (Button) -> Unit,
    tabHref: String? = null,
): SimplePanel {
    val card = parent.vPanel(spacing = 4) { addCssClasses("lapis-vote-card lapis-consensus-card border rounded p-2") }
    card.untrustedP(ballot.title, className = "fw-bold mb-0")
    card.consensusBadges(ballot)
    if (ballot.isConsensusRating()) card.div(ownRatingLabel(ballot)) { addCssClasses("small") }
    if (canAct && ballot.isConsensusRating() && ballot.ownEligible && !ballot.ownHasVoted) {
        if (tabHref != null) {
            card.consensusTabLink(tr("In neuem Tab bewerten"), tabHref, asButton = true)
        } else {
            val rate = card.button(tr("Bewerten"), style = ButtonStyle.PRIMARY) { addCssClass("w-100") }
            rate.onClick { onRate(rate) }
        }
    }
    if (canAct && ballot.status != RoomBallotStatus.OPEN) {
        conferenceConsensusDetailHref(ballot.id)?.let { card.consensusTabLink(tr("Details in neuem Tab"), it) }
    }
    card.consensusTabLink(tr("Wie funktioniert Konsensieren?"), "#/consensus")
    return card
}

/** A guest, friend or federated guest sees lines 1 and 2 only: no standing, no button, no link into the member area. */
internal fun renderConsensusGuestRow(
    parent: Container,
    ballot: RoomBallotDto,
) {
    val card = parent.vPanel(spacing = 4) { addCssClasses("lapis-vote-card lapis-consensus-card border rounded p-2") }
    card.untrustedP(ballot.title, className = "fw-bold mb-0")
    card.consensusBadges(ballot)
}
