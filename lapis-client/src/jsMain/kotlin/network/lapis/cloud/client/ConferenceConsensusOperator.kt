package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.link
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.RoomBallotDto
import network.lapis.cloud.shared.domain.RoomBallotKind
import network.lapis.cloud.shared.domain.SystemicConsensusStatus

/*
 * V1.9.32 "Konsensieren im Konferenzraum" -- the operator side of a systemic consensus in the room panel: freeze the options, close the rating,
 * evaluate and rate again, without leaving the call. Adding options, opening a consensus and aborting one stay on the consensus page, one click
 * away in a new tab (`ConsensusScreen.kt`); the room never opens a consensus from a prepared motion.
 *
 * Nothing here is authority. The gate is the server's own `SystemicConsensusParticipationDto.canManage` (= `canManageSystemicConsensus`, which
 * is the recording right of the target committee -- NOT the room moderation), combined with the status by the shared `ConsensusAuthzUi`
 * predicates, and the server decides again. Every write goes through the controller's ONE guarded function (`runOperatorAction`, handed in as a
 * function reference). No rating and no receipt passes through this file: the compact result is the aggregate the server computed
 * (mean and winner), without distribution and without named ratings.
 */

/** A consensus snapshot is re-read at most this often by the poll rhythm; a phase change and the panel opening force it. */
private const val CONSENSUS_REFRESH_MS = 30_000

private class ConsensusSnapshot(
    var phase: SystemicConsensusStatus?,
) {
    var detail: ConsensusDetailData? = null
    var loadedAt: Double = Double.NEGATIVE_INFINITY
    var loading = false
}

private class ConsensusSlot(
    val container: SimplePanel,
    val paint: (SimplePanel) -> Unit,
)

internal class ConferenceConsensusOperator(
    private val rpc: ConferenceConsensusRpc,
    private val scheduler: ConferenceVoteScheduler,
    private val lockClock: BallotLockClock,
    private val runOperatorAction: (Button, Boolean, suspend () -> Unit) -> Unit,
    private val onStopStreamRequested: (() -> Unit)?,
    private val canModerateRoom: Boolean,
    private val isOpen: () -> Boolean,
    private val requestOverviewRender: () -> Unit = {},
    private val scope: CoroutineScope = AppScope,
) {
    private val entries = mutableMapOf<String, ConsensusSnapshot>()
    private var slots = mutableMapOf<String, ConsensusSlot>()
    private val hungShown = mutableSetOf<String>()
    private var disposed = false

    // ── rendering ───────────────────────────────────────────────────────────────────────────────────

    /** Called by the panel before it rebuilds its overview: the slots of the old cards are gone with them. */
    fun beginRender() {
        slots = mutableMapOf()
        hungShown.clear()
    }

    private fun repaint(id: String) {
        val slot = slots[id] ?: return
        slot.container.removeAll()
        slot.paint(slot.container)
    }

    fun repaintAll() {
        slots.keys.toList().forEach(::repaint)
    }

    /** The operator section inside the live card of one consensus: the lock line for a member who can still rate, and the steps of the phase. */
    fun renderForBallot(
        card: Container,
        ballot: RoomBallotDto,
    ) {
        if (ballot.kind != RoomBallotKind.CONSENSUS) return
        val container = card.vPanel(spacing = 4)
        slots[ballot.id] = ConsensusSlot(container) { content -> paintSection(content, ballot) }
        paintSection(container, ballot)
    }

    private fun paintSection(
        content: SimplePanel,
        ballot: RoomBallotDto,
    ) {
        if (ballot.secret && ballot.memberActionable()) {
            consensusLockReason(lockClock.lock(true).forMember())?.let { reason ->
                content.div(reason) { addCssClasses("small text-muted") }
            }
        }
        val entry = ensureLoaded(ballot) ?: return
        val detail = entry.detail ?: return
        if (!detail.participation.canManage) return
        val consensus = detail.consensus
        when (consensus.status) {
            SystemicConsensusStatus.COLLECTION -> paintCollection(content, detail)
            SystemicConsensusStatus.RATING -> paintRating(content, detail)
            SystemicConsensusStatus.CLOSED -> paintClosed(content, detail)
            SystemicConsensusStatus.EVALUATED -> paintEvaluated(content, detail)
            SystemicConsensusStatus.ABORTED -> Unit
        }
        conferenceConsensusDetailHref(consensus.id)?.let { href ->
            content.link(tr("Auf der Konsensieren-Seite bearbeiten"), url = href, target = "_blank") {
                addCssClass("small")
                setAttribute("rel", "noopener noreferrer")
            }
        }
    }

    private fun paintCollection(
        content: SimplePanel,
        detail: ConsensusDetailData,
    ) {
        val consensus = detail.consensus
        val count = consensus.options.size
        content.div(if (count == 1) gettext("1 Option") else gettext("%1 Optionen", count)) { addCssClasses("small text-muted") }
        val gate = canFreeze(consensus, detail.participation)
        if (gate is Gate.Hidden) return
        val freeze = content.button(tr("Optionen festschreiben"), style = ButtonStyle.PRIMARY)
        if (gate is Gate.Disabled) {
            freeze.disabled = true
            val why = gate.reason
            content.div(why) { addCssClasses("small text-muted") }
            return
        }
        freeze.onClick {
            confirmDialog(
                title = tr("Optionen festschreiben"),
                message = tr("Danach können keine Optionen mehr hinzukommen."),
                confirmLabel = tr("Optionen festschreiben"),
                confirmStyle = ButtonStyle.PRIMARY,
                extraLines = streamLines(consensus.secret),
                focusCancel = true,
            ) { runOperatorAction(freeze, true) { rpc.freeze(consensus.id) } }
        }
    }

    private fun paintRating(
        content: SimplePanel,
        detail: ConsensusDetailData,
    ) {
        val consensus = detail.consensus
        if (!canCloseRating(consensus, detail.participation)) return
        val close = content.button(tr("Bewertung schließen"), style = ButtonStyle.PRIMARY)
        close.onClick {
            confirmDialog(
                title = tr("Bewertung schließen"),
                message = tr("Danach sind keine Bewertungen mehr möglich."),
                confirmLabel = tr("Bewertung schließen"),
                confirmStyle = ButtonStyle.PRIMARY,
                focusCancel = true,
            ) { runOperatorAction(close, true) { rpc.closeRating(consensus.id) } }
        }
    }

    private fun paintClosed(
        content: SimplePanel,
        detail: ConsensusDetailData,
    ) {
        val consensus = detail.consensus
        if (!canEvaluate(consensus, detail.participation)) return
        val evaluate = content.button(tr("Auswerten"), style = ButtonStyle.PRIMARY)
        evaluate.onClick { runOperatorAction(evaluate, true) { rpc.evaluate(consensus.id) } }
    }

    private fun paintEvaluated(
        content: SimplePanel,
        detail: ConsensusDetailData,
    ) {
        val consensus = detail.consensus
        val result = detail.result
        if (result != null) {
            result.optionResults.sortedBy { it.meanResistance }.forEach { optionResult ->
                val option = consensus.options.firstOrNull { it.id == optionResult.optionId } ?: return@forEach
                val row = content.hPanel(spacing = 6) { addCssClasses("flex-wrap align-items-center small") }
                row.div(consensusOptionText(option)) { addCssClasses("fw-bold text-break") }
                row.div(formatResistance(mean = optionResult.meanResistance, scaleMax = consensus.scaleMax))
                if (optionResult.optionId == result.winnerOptionId) row.votingBadge(tr("Geringster Widerstand"), "success", "fas fa-trophy")
            }
        }
        if (canReopen(consensus, detail.participation, result) == ReopenOffer.Hidden) return
        val reopen = content.button(tr("Erneut bewerten"), style = ButtonStyle.OUTLINESECONDARY)
        reopen.onClick {
            confirmDialog(
                title = tr("Erneut bewerten"),
                message = tr("Es beginnt eine neue Bewertungsrunde. Die bisherigen Bewertungen zählen dann nicht mehr."),
                confirmLabel = tr("Erneut bewerten"),
                confirmStyle = ButtonStyle.PRIMARY,
                extraLines = streamLines(consensus.secret),
                focusCancel = true,
            ) { runOperatorAction(reopen, true) { rpc.reopen(consensus.id) } }
        }
    }

    /** The lines of a dialog that starts a rating round: an anonymous one pauses the stream, an open one never does. */
    private fun streamLines(secret: Boolean): List<String> =
        if (secret) {
            secretOpenPreflightLines(
                lockClock.mirror.status,
            )
        } else {
            listOf(gettext("Offen und namentlich: Der Live-Stream läuft weiter."))
        }

    // ── the emergency card ──────────────────────────────────────────────────────────────────────────

    /** Moderation and managers get the emergency card for an anonymous rating whose stream does not stop; a plain member only reads the lock text. */
    fun showsHungCard(ballot: RoomBallotDto): Boolean =
        ballot.kind == RoomBallotKind.CONSENSUS &&
            ballot.isConsensusRating() &&
            ballot.secret &&
            lockClock.lock(true) == BallotStreamLock.HUNG &&
            (canModerateRoom || entries[ballot.id]?.detail?.participation?.canManage == true)

    fun renderHungCard(
        parent: Container,
        ballot: RoomBallotDto,
    ) {
        val card = parent.vPanel(spacing = 6) { addCssClasses("lapis-vote-card border border-danger rounded p-2") }
        hungShown += ballot.id
        card.untrustedP(ballot.title, className = "fw-bold mb-0")
        card.div(gettext("Der Stream lässt sich nicht anhalten. Die Stimmabgabe bleibt gesperrt, bis er angehalten ist.")) {
            addCssClasses("small")
        }
        if (onStopStreamRequested != null) {
            val stop = card.button(tr("Stream stoppen"), style = ButtonStyle.PRIMARY)
            stop.onClick { onStopStreamRequested.invoke() }
        }
        conferenceConsensusDetailHref(ballot.id)?.let { href ->
            card.link(tr("Auf der Konsensieren-Seite bearbeiten"), url = href, target = "_blank") {
                addCssClass("small")
                setAttribute("rel", "noopener noreferrer")
            }
        }
        // the hung state of a consensus can only be settled by somebody who manages it: make sure the snapshot is there for the next repaint
        ensureLoaded(ballot)
    }

    // ── data ────────────────────────────────────────────────────────────────────────────────────────

    /** The cached snapshot of one consensus, re-read when its phase changed or it is older than [CONSENSUS_REFRESH_MS]; `null` while the panel is closed. */
    private fun ensureLoaded(ballot: RoomBallotDto): ConsensusSnapshot? {
        val cached = entries[ballot.id]
        val stale = cached == null || cached.phase != ballot.consensusPhase || scheduler.now() - cached.loadedAt >= CONSENSUS_REFRESH_MS
        if (!stale) return cached
        if (!isOpen() || disposed) return cached
        val entry = cached ?: ConsensusSnapshot(ballot.consensusPhase).also { entries[ballot.id] = it }
        if (entry.loading) return entry
        entry.loading = true
        scope.launch {
            var loaded: ConsensusDetailData? = null
            try {
                loaded = rpc.loadDetail(ballot.id)
            } catch (ex: CancellationException) {
                entry.loading = false
                throw ex
            } catch (ignored: Throwable) {
                // never shown with its message: without the snapshot there is simply no operator section; the next refresh tries again
            }
            entry.loading = false
            entry.phase = ballot.consensusPhase
            entry.loadedAt = scheduler.now()
            // a failed read keeps the last snapshot: a short outage must not take the buttons away
            if (loaded != null) entry.detail = loaded
            if (disposed) return@launch
            if (slots[ballot.id] != null &&
                showsHungCard(ballot) != (ballot.id in hungShown)
            ) {
                requestOverviewRender()
            } else {
                repaint(ballot.id)
            }
        }
        return entry
    }

    /** The panel hands over each answer of the room poll: a consensus that left the room list is forgotten. */
    fun onRoomUpdate(ballots: List<RoomBallotDto>) {
        val present = ballots.filter { it.kind == RoomBallotKind.CONSENSUS }.map { it.id }.toSet()
        entries.keys.retainAll(present)
    }

    /** The panel became visible: nothing was read while it was hidden. */
    fun onOpened() {
        if (disposed) return
        entries.clear()
        repaintAll()
    }

    /** After an own write the cached verdicts are stale (the phase just changed). */
    fun invalidate() {
        if (disposed) return
        entries.clear()
        repaintAll()
    }

    fun dispose() {
        disposed = true
        slots = mutableMapOf()
        entries.clear()
    }
}
