package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.form.check.CheckBox
import io.kvision.form.select.Select
import io.kvision.html.ButtonStyle
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h4
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import network.lapis.cloud.shared.domain.AttendanceStatus
import network.lapis.cloud.shared.domain.ConferenceMeetingStateDto
import network.lapis.cloud.shared.domain.MeetingSessionPhase
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.ResolutionStatus
import network.lapis.cloud.shared.domain.SessionMotionDto
import network.lapis.cloud.shared.domain.SessionMotionVoteDto
import network.lapis.cloud.shared.domain.SessionMotionVoteStatus
import network.lapis.cloud.shared.domain.SessionResolutionDto
import network.lapis.cloud.shared.domain.SessionRosterEntryDto

/**
 * V1.9.238 -- the motion part of the Sitzung panel: the banner of a running vote, the motions of the current agenda item (attach, open a
 * vote, show the resolution) and the two dialogs. Everything the server did not send is not drawn: a PARTICIPANT's DOM has no button, and a
 * secret vote has no counter anywhere in here.
 *
 * Every action goes through [MeetingMotionActions.act] (one at a time; the answer is the fresh state). A refused change or a stale agenda
 * item ends in a fresh read, never in a guess.
 */
internal class MeetingMotionActions(
    val roomId: String,
    val rpc: ConferenceMeetingRpc,
    val act: (suspend () -> ConferenceMeetingStateDto) -> Unit,
    /** The newest state the panel holds: the dialogs read roster and live quorum from here, not from the (quiet, rarely redrawn) agenda slice. */
    val latest: () -> ConferenceMeetingStateDto?,
    val onOpenVotingPanel: () -> Unit,
)

/** Fewest people of the election board (the server demands the same). */
internal const val MEETING_MOTION_MIN_BOARD = 3

/** Running vote at the top of the panel; the container is a polite live region, so a vote that opens is announced. */
internal fun renderRunningVoteBanner(
    container: Container,
    state: ConferenceMeetingStateDto,
    actions: MeetingMotionActions,
) {
    container.removeAll()
    val vote = state.runningMotionVote ?: return
    val title = sanitizeUntrustedI18nText(vote.motionTitle)
    val awaiting = vote.status == SessionMotionVoteStatus.CLOSED_AWAITING_TALLY
    val box = container.div(className = "conference-meeting-banner")
    box.div(if (awaiting) gettext("Abstimmung geschlossen: %1", title) else gettext("Abstimmung läuft: %1", title)) {
        addCssClass("fw-semibold")
    }
    // the counters exist for LEAD/MINUTES and never for a secret vote -- nothing is drawn that the answer does not hold
    val ballots = vote.ballotCount
    val eligible = vote.eligibleCount
    if (ballots != null && eligible != null) box.div(gettext("%1 von %2 abgegeben", ballots, eligible)) { addCssClasses("small") }
    val approvals = vote.tallyApprovalCount
    val threshold = vote.tallyThreshold
    if (approvals != null &&
        threshold != null
    ) {
        box.div(gettext("Auszählung: %1 von %2 Freigaben", approvals, threshold)) { addCssClasses("small") }
    }
    box.actionGroup {
        if (awaiting) {
            actionButton(ActionIcon.OPEN_MOTION_VOTE, tr("Im Panel „Abstimmen“ auszählen"), style = ButtonStyle.PRIMARY) {
                onClick { actions.onOpenVotingPanel() }
            }
        } else {
            actionButton(ActionIcon.OPEN_MOTION_VOTE, tr("Jetzt abstimmen"), style = ButtonStyle.PRIMARY) {
                onClick { actions.onOpenVotingPanel() }
            }
            if (state.canManageMotions && vote.canClose && state.phase == MeetingSessionPhase.OPEN) {
                actionButton(ActionIcon.CLOSE, tr("Abstimmung schließen"), style = ButtonStyle.OUTLINESECONDARY) {
                    onClick { confirmCloseVote(vote, actions) }
                }
            }
        }
    }
}

private fun confirmCloseVote(
    vote: SessionMotionVoteDto,
    actions: MeetingMotionActions,
) {
    confirmDialog(
        title = tr("Abstimmung schließen"),
        message = tr("Nach dem Schließen sind keine weiteren Stimmen möglich."),
        confirmLabel = tr("Abstimmung schließen"),
        confirmStyle = ButtonStyle.PRIMARY,
        focusCancel = true,
    ) {
        actions.act {
            actions.rpc.closeVoting(vote.electionId)
            actions.rpc.getState(actions.roomId) ?: throw IllegalStateException("no state")
        }
    }
}

/** How many motions and resolutions an agenda item holds (the line under items that are not the current one). */
internal fun motionCountsText(
    state: ConferenceMeetingStateDto,
    agendaItemId: String,
): String? {
    val motions = state.agendaMotions.filter { it.agendaItemId == agendaItemId }
    if (motions.isEmpty()) return null
    return gettext("Anträge: %1 · Beschlüsse: %2", motions.size, motions.count { it.resolution != null })
}

internal fun renderAgendaMotions(
    container: Container,
    state: ConferenceMeetingStateDto,
    actions: MeetingMotionActions,
) {
    val currentItem = state.currentAgendaItemId ?: return
    container.h4(tr("Anträge zu diesem Punkt")) { addCssClasses("h6 mb-0 mt-2") }
    val motions = state.agendaMotions.filter { it.agendaItemId == currentItem }
    if (motions.isEmpty()) {
        container.div(tr("Zu diesem Punkt liegen keine Anträge vor.")) { addCssClasses("text-muted small") }
    }
    val manage = state.canManageMotions && state.phase == MeetingSessionPhase.OPEN
    val running = state.runningMotionVote != null
    motions.forEach { renderMotionCard(container, state, it, manage = manage, running = running, actions = actions) }
    if (manage && state.attachableMotions.isNotEmpty()) {
        container.actionGroup {
            actionButton(ActionIcon.ATTACH_MOTION, tr("Antrag zuordnen"), style = ButtonStyle.OUTLINESECONDARY) {
                onClick { attachMotionDialog(state, currentItem, actions) }
            }
        }
    }
}

private fun motionStatusText(motion: SessionMotionDto): String =
    when {
        motion.vote?.status == SessionMotionVoteStatus.OPEN -> gettext("Abstimmung läuft")
        motion.vote?.status == SessionMotionVoteStatus.CLOSED_AWAITING_TALLY -> gettext("Abstimmung geschlossen, Auszählung steht aus")
        motion.status == MotionStatus.RESOLVED -> gettext("Beschlossen")
        motion.status == MotionStatus.REJECTED -> gettext("Abgelehnt")
        motion.status == MotionStatus.POSTPONED -> gettext("Vertagt")
        else -> gettext("Auf der Tagesordnung")
    }

private fun renderMotionCard(
    container: Container,
    state: ConferenceMeetingStateDto,
    motion: SessionMotionDto,
    manage: Boolean,
    running: Boolean,
    actions: MeetingMotionActions,
) {
    val card = Tag(TAG.ARTICLE, className = "conference-meeting-motion")
    container.add(card)
    card.untrustedDiv(motion.title, className = "fw-semibold")
    card.div(motionStatusText(motion)) { addCssClasses("small text-muted conference-meeting-motion-status") }
    val text = card.untrustedDiv(motion.effectiveText, className = "conference-meeting-motion-text")
    if (motion.effectiveText.length > LONG_MOTION_TEXT) {
        var expanded = false
        val toggle = card.button(tr("Mehr anzeigen"), style = ButtonStyle.LINK)
        toggle.addCssClasses("p-0 conference-meeting-motion-toggle")
        toggle.setAttribute("aria-expanded", "false")
        toggle.onClick {
            expanded = !expanded
            if (expanded) text.addCssClass("expanded") else text.removeCssClass("expanded")
            toggle.text = if (expanded) gettext("Weniger anzeigen") else gettext("Mehr anzeigen")
            toggle.setAttribute("aria-expanded", expanded.toString())
        }
    }
    motion.resolution?.let { renderResolutionCard(card, it) }
    val canOpen =
        manage && motion.status == MotionStatus.SCHEDULED && (motion.vote == null || motion.vote?.status == SessionMotionVoteStatus.ABORTED)
    if (canOpen) {
        card.actionGroup {
            actionButton(ActionIcon.OPEN_MOTION_VOTE, tr("Abstimmung eröffnen"), style = ButtonStyle.PRIMARY) {
                addCssClass("w-100")
                disabled = running
                onClick { openMotionVoteDialog(state, motion, actions) }
            }
        }
        if (running) card.div(tr("Es läuft bereits eine Abstimmung.")) { addCssClasses("small text-muted") }
    }
}

private const val LONG_MOTION_TEXT = 160

/** The resolution: number, the word of the result (a tie is "Stimmengleichheit"), the figures (or the note that they are withheld), the quorum word. */
internal fun renderResolutionCard(
    container: Container,
    resolution: SessionResolutionDto,
) {
    val box = container.div(className = "conference-meeting-resolution")
    box.div(gettext("Beschluss %1", sanitizeUntrustedI18nText(resolution.number))) { addCssClasses("small text-muted") }
    box.div(resolutionWord(resolution.status)) { addCssClasses("fs-5 fw-semibold") }
    if (resolution.figuresWithheld) {
        box.div(tr("Zahlen nicht angezeigt (zu wenige Stimmen)")) { addCssClasses("small") }
    } else {
        box.div(gettext("Ja %1 · Nein %2 · Enthaltung %3", resolution.votesYes, resolution.votesNo, resolution.votesAbstain)) {
            addCssClasses("small")
        }
    }
    val quorum = if (resolution.quorumMet) gettext("beschlussfähig") else gettext("nicht beschlussfähig")
    box.div("$quorum · ${formatSystemTime(resolution.decidedAt)}") { addCssClasses("small text-muted") }
}

internal fun resolutionWord(status: ResolutionStatus): String =
    when (status) {
        ResolutionStatus.ADOPTED -> gettext("Angenommen")
        ResolutionStatus.REJECTED -> gettext("Abgelehnt")
        ResolutionStatus.POSTPONED -> gettext("Stimmengleichheit")
    }

/** Pure: who is ticked when the dialog opens -- the caller first (the lead must be able to close), then the present people, at most three. */
internal fun defaultBoardSelection(
    roster: List<SessionRosterEntryDto>,
    callerMemberId: String?,
): Set<String> {
    val picked = LinkedHashSet<String>()
    roster.firstOrNull { it.memberId == callerMemberId }?.let { picked.add(it.memberId) }
    roster
        .filter { it.status == AttendanceStatus.PRESENT }
        .forEach { if (picked.size < MEETING_MOTION_MIN_BOARD) picked.add(it.memberId) }
    return picked
}

private fun openMotionVoteDialog(
    drawn: ConferenceMeetingStateDto,
    motion: SessionMotionDto,
    actions: MeetingMotionActions,
) {
    val state = actions.latest() ?: drawn
    val currentItem = state.currentAgendaItemId ?: return
    val modal = Modal(caption = tr("Abstimmung eröffnen"))
    modal.untrustedDiv(motion.title, className = "fw-semibold")
    modal.untrustedDiv(motion.effectiveText, className = "conference-meeting-motion-text-full")
    modal.div(tr("Der Beschluss übernimmt diesen Wortlaut unverändert.")) { addCssClasses("text-muted small mb-2") }
    val secret = CheckBox(value = false, label = tr("Geheime Abstimmung"))
    modal.add(secret)
    modal.div(tr("Bei weniger als 5 Stimmen werden die Zahlen nicht angezeigt.")) { addCssClasses("text-muted small mb-2") }
    val live = state.liveQuorum
    if (live != null && !live.met) {
        modal.div(
            gettext(
                "Das Gremium ist derzeit nicht beschlussfähig (%1 von %2 anwesend, nötig %3). Der Beschluss wird so vermerkt.",
                live.presentCount,
                live.eligibleMemberCount,
                live.requiredCount,
            ),
        ) { addCssClasses("text-danger small mb-2") }
    } else if (state.establishedQuorum == null) {
        modal.div(tr("Die Beschlussfähigkeit wurde noch nicht festgestellt.")) { addCssClasses("text-muted small mb-2") }
    }
    val fieldset = Tag(TAG.FIELDSET, className = "border-0 p-0 m-0")
    fieldset.add(Tag(TAG.LEGEND, tr("Auszählung durch (mindestens 3)"), className = "h6 mb-1"))
    modal.add(fieldset)
    val preselected = defaultBoardSelection(state.roster, AppState.session?.memberId)
    val picked = LinkedHashSet(preselected)
    val confirm = newActionButton(ActionIcon.OPEN_MOTION_VOTE, tr("Abstimmung eröffnen"), ButtonStyle.PRIMARY)
    val hint = modal.div(tr("Bitte wählen Sie mindestens 3 Personen.")) { addCssClasses("small text-muted") }

    fun refresh() {
        val enough = picked.size >= MEETING_MOTION_MIN_BOARD
        confirm.disabled = !enough
        if (enough) hint.hide() else hint.show()
    }
    state.roster.forEach { entry ->
        val box = CheckBox(value = entry.memberId in preselected, label = sanitizeUntrustedI18nText(entry.displayName))
        box.subscribe { checked ->
            if (checked) picked.add(entry.memberId) else picked.remove(entry.memberId)
            refresh()
        }
        fieldset.add(box)
    }
    refresh()
    val cancel = newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY).apply { onClick { modal.hide() } }
    val once = ConfirmOnce()
    confirm.onClick {
        if (picked.size < MEETING_MOTION_MIN_BOARD) return@onClick
        val board = picked.toList()
        val secretNow = secret.value
        once.run(confirm) {
            modal.hide()
            actions.act { actions.rpc.openVote(actions.roomId, state.meetingId, motion.id, currentItem, secretNow, board) }
        }
    }
    modal.addButton(cancel)
    modal.addButton(confirm)
    modal.show()
}

private fun attachMotionDialog(
    state: ConferenceMeetingStateDto,
    currentItem: String,
    actions: MeetingMotionActions,
) {
    val candidates = state.attachableMotions
    if (candidates.isEmpty()) return
    val modal = Modal(caption = tr("Antrag zuordnen"))
    val select = Select(options = untrustedOptions(candidates.map { it.id to it.title }), value = candidates.first().id)
    select.input.setAttribute("aria-label", gettext("Antrag zuordnen"))
    modal.add(select)
    val question = modal.span("")

    fun chosen() = candidates.firstOrNull { it.id == select.value } ?: candidates.first()

    fun paint() {
        question.content = gettext("Antrag „%1“ diesem Punkt zuordnen?", sanitizeUntrustedI18nText(chosen().title))
    }
    paint()
    select.subscribe { paint() }
    val cancel = newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY).apply { onClick { modal.hide() } }
    val once = ConfirmOnce()
    val confirm = newActionButton(ActionIcon.ATTACH_MOTION, tr("Zuordnen"), ButtonStyle.PRIMARY)
    confirm.onClick {
        val motionId = chosen().id
        once.run(confirm) {
            modal.hide()
            actions.act { actions.rpc.attach(actions.roomId, state.meetingId, motionId, currentItem) }
        }
    }
    modal.addButton(cancel)
    modal.addButton(confirm)
    modal.show()
}
