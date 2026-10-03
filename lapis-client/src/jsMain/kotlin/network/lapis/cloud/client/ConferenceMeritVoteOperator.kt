package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.icon
import io.kvision.html.link
import io.kvision.html.span
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.CommitteeMembershipDto
import network.lapis.cloud.shared.domain.ElectionDto
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.MotionDto
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.RoomBallotDto
import network.lapis.cloud.shared.domain.RoomBallotKind
import network.lapis.cloud.shared.domain.RoomBallotStatus
import network.lapis.cloud.shared.domain.SystemicConsensusDto
import network.lapis.cloud.shared.domain.SystemicConsensusStatus
import network.lapis.cloud.shared.domain.VoteDto
import network.lapis.cloud.shared.domain.VoteOpenInput
import network.lapis.cloud.shared.domain.VoteStatus
import network.lapis.cloud.shared.rpc.IElectionService
import network.lapis.cloud.shared.rpc.IGovernanceService
import network.lapis.cloud.shared.rpc.ISystemicConsensusService

/*
 * V1.9.27 "Abstimmen im Konferenzraum", Welle 4 -- the operator side of a meritocratic vote: open a plain Yes/No vote on a prepared
 * motion and close a running one, without leaving the call. Everything else (custom options, aborting, changing a bid) stays on the
 * motion page, one click away in a new tab.
 *
 * Nothing here is authority: the gate is `GovernanceAuthzUi.canRecordForMeeting` (the mirror of the server's `canRecordForMeeting`, which
 * is NOT the room moderation), the server decides again, and every write goes through the controller's ONE guarded function
 * (`runOperatorAction`, handed in as a function reference). No bid or amount passes through this file.
 */

/** At most this many scheduled motions are looked at (each costs four reads). */
private const val MERIT_CANDIDATE_CAP = 5

/** Candidates and the committee rosters are re-read at most this often by the poll rhythm; own actions and opening the panel force it. */
private const val MERIT_REFRESH_MS = 30_000

/**
 * The option labels of the room's Yes/No vote. The server decides the outcome by the label text (`closeVote`: the label "NO", case-insensitive,
 * means REJECTED, any other winning label means ADOPTED), so these are the fixed literals and are NEVER translated.
 */
internal val MERIT_YES_NO_LABELS: List<String> = listOf("YES", "NO")

/** Every RPC of the merit operator behind one seam: the DOM tests run against the fetch stub, a unit test can pass fakes. */
internal class MeritOperatorRpc(
    /** SCHEDULED motions of the room's Sitzung, capped. */
    val listScheduledMotions: suspend (meetingId: String) -> List<MotionDto> = { meetingId ->
        rpcService<IGovernanceService>()
            .listMotions(status = MotionStatus.SCHEDULED)
            .filter { it.meetingId == meetingId }
            .take(MERIT_CANDIDATE_CAP + 1)
    },
    val getMotion: suspend (motionId: String) -> MotionDto = { rpcService<IGovernanceService>().getMotion(it) },
    val listCommitteeMembers: suspend (committeeId: String) -> List<CommitteeMembershipDto> = {
        rpcService<IGovernanceService>().listCommitteeMembers(it, activeOnly = true)
    },
    val listVotesFor: suspend (motionId: String) -> List<VoteDto> = { rpcService<IGovernanceService>().listVotes(motionId = it) },
    val listElectionsFor: suspend (motionId: String) -> List<ElectionDto> = { rpcService<IElectionService>().listElections(motionId = it) },
    val listConsensusesFor: suspend (motionId: String) -> List<SystemicConsensusDto> = {
        rpcService<ISystemicConsensusService>().listSystemicConsensuses(motionId = it)
    },
    val listAmendments: suspend (motionId: String) -> List<MotionDto> = {
        rpcService<IGovernanceService>().listMotions(amendsMotionId = it)
    },
    val openVote: suspend (motionId: String) -> Unit = {
        rpcService<IGovernanceService>().openVote(VoteOpenInput(motionId = it, optionLabels = MERIT_YES_NO_LABELS))
    },
    val closeVote: suspend (voteId: String) -> Unit = { rpcService<IGovernanceService>().closeVote(it) },
)

/** The election states in which an election owns the decision of its motion (the server's `RUNNING_ELECTION_STATUSES`, mirrored). */
private val RUNNING_ELECTION_UI_STATUSES =
    setOf(
        ElectionStatus.PREPARATION,
        ElectionStatus.CANDIDATE_LIST_RELEASED,
        ElectionStatus.OPEN,
        ElectionStatus.CLOSED,
    )

internal fun isRunningElectionStatus(status: ElectionStatus): Boolean = status in RUNNING_ELECTION_UI_STATUSES

/**
 * May the room offer "Ja/Nein-Abstimmung eröffnen" for [motion]? Mirrors `openVote` and `closeVote` together: SCHEDULED in THIS room's
 * Sitzung, the caller may record, no open or finished vote, no running election, no consensus unless aborted -- and no pending amendment,
 * because `closeVote` would refuse later and leave a vote that cannot be closed (the motion page behaves the same, design decision D2).
 */
internal fun meritCanOpen(
    motion: MotionDto,
    roomMeetingId: String?,
    canRecord: Boolean,
    votes: List<VoteDto>,
    elections: List<ElectionDto>,
    consensuses: List<SystemicConsensusDto>,
    amendments: List<MotionDto>,
): Boolean =
    canRecord &&
        roomMeetingId != null &&
        motion.status == MotionStatus.SCHEDULED &&
        motion.meetingId == roomMeetingId &&
        votes.none { it.status == VoteStatus.OPEN || it.status == VoteStatus.CLOSED } &&
        elections.none { isRunningElectionStatus(it.status) } &&
        consensuses.none { it.status != SystemicConsensusStatus.ABORTED } &&
        amendments.none { it.status in NON_TERMINAL_MOTION_STATUSES }

private class MeritSlot(
    val container: SimplePanel,
    val paint: (SimplePanel) -> Unit,
)

internal class ConferenceMeritVoteOperator(
    private val ctx: OperatorContext,
    private val rpc: MeritOperatorRpc,
    private val scheduler: ConferenceVoteScheduler,
    private val runOperatorAction: (Button, Boolean, suspend () -> Unit) -> Unit,
    private val isOpen: () -> Boolean,
    private val scope: CoroutineScope = AppScope,
) {
    private var candidates: List<MotionDto> = emptyList()
    private var readFailed = false
    private var moreCandidates = false
    private var readInFlight = false
    private var readAgain = false
    private var lastReadAt = Double.NEGATIVE_INFINITY
    private var bound = false
    private var disposed = false
    private var preparedSlot: MeritSlot? = null
    private val closeSlots = mutableMapOf<String, MeritSlot>()
    private val rosters = mutableMapOf<String, Pair<Double, List<CommitteeMembershipDto>>>()
    private val closeGates = mutableMapOf<String, Pair<Double, Boolean>>()
    private val closeGateLoading = mutableSetOf<String>()

    // ── the roster: the one input of the gate ───────────────────────────────────────────────────────

    private suspend fun canRecord(committeeId: String): Boolean {
        if (ctx.isBoardOrAdmin) return true
        val now = scheduler.now()
        val cached = rosters[committeeId]
        val roster =
            if (cached != null && now - cached.first < MERIT_REFRESH_MS) {
                cached.second
            } else {
                rpc.listCommitteeMembers(committeeId).also { rosters[committeeId] = now to it }
            }
        return GovernanceAuthzUi.canRecordForMeeting(ctx.isBoardOrAdmin, ctx.currentMemberId, committeeId, roster)
    }

    // ── closing: a card section on a running vote ───────────────────────────────────────────────────

    /** The operator section inside the live card of one meritocratic vote: "Abstimmung schließen" for somebody who may record. */
    fun renderForBallot(
        card: Container,
        ballot: RoomBallotDto,
    ) {
        if (ballot.kind != RoomBallotKind.VOTE || ballot.status != RoomBallotStatus.OPEN) return
        val slot = card.vPanel(spacing = 4)
        val entry = MeritSlot(slot) { content -> paintClose(content, ballot) }
        closeSlots[ballot.id] = entry
        entry.paint(slot)
    }

    private fun paintClose(
        content: SimplePanel,
        ballot: RoomBallotDto,
    ) {
        val gate = closeGates[ballot.id]
        val stale = gate == null || scheduler.now() - gate.first >= MERIT_REFRESH_MS
        if (stale && isOpen() && !disposed && closeGateLoading.add(ballot.id)) loadCloseGate(ballot)
        if (gate?.second != true) return
        val close = content.actionButton(ActionIcon.CLOSE, tr("Abstimmung schließen"), style = ButtonStyle.PRIMARY)
        close.onClick {
            confirmDialog(
                title = tr("Abstimmung schließen"),
                message = tr("Abstimmung schließen? Danach sind keine Gebote mehr möglich, das Ergebnis wird sofort berechnet."),
                confirmLabel = tr("Abstimmung schließen"),
                confirmStyle = ButtonStyle.PRIMARY,
                focusCancel = true,
            ) { runOperatorAction(close, true) { rpc.closeVote(ballot.id) } }
        }
    }

    private fun loadCloseGate(ballot: RoomBallotDto) {
        scope.launch {
            val allowed =
                try {
                    canRecord(rpc.getMotion(ballot.motionId).targetCommitteeId)
                } catch (e: CancellationException) {
                    throw e
                } catch (ignored: Throwable) {
                    false
                }
            closeGateLoading.remove(ballot.id)
            closeGates[ballot.id] = scheduler.now() to allowed
            if (!disposed) closeSlots[ballot.id]?.let { slot -> repaint(slot) }
        }
    }

    // ── opening: the prepared block ─────────────────────────────────────────────────────────────────

    /** The "prepared motions" block, directly under the prepared elections. Empty (and invisible) unless the member may open at least one. */
    fun renderPrepared(parent: Container) {
        val slot = parent.vPanel(spacing = 4)
        val entry = MeritSlot(slot) { content -> paintPrepared(content) }
        preparedSlot = entry
        entry.paint(slot)
        maybeRead(force = false)
    }

    private fun paintPrepared(content: SimplePanel) {
        // Only BOARD/ADMIN (who are told how to prepare a motion) see a failed read; for everybody else it is simply "nothing to open".
        if (readFailed && ctx.isBoardOrAdmin) content.dataErrorState { maybeRead(force = true) }
        if (candidates.isEmpty()) return
        val heading = content.hPanel(spacing = 4) { addCssClasses("align-items-center fw-bold small") }
        heading.icon("fas fa-scale-balanced") { addCssClass("text-primary") }
        heading.span(tr("Vorbereitete Anträge"))
        candidates.take(MERIT_CANDIDATE_CAP).forEach { motion ->
            val card = content.vPanel(spacing = 4) { addCssClasses("lapis-vote-card border rounded p-2") }
            card.untrustedSpan(motion.title, className = "fw-bold")
            val open = card.button(tr("Ja/Nein-Abstimmung eröffnen"), style = ButtonStyle.PRIMARY)
            open.onClick { runOperatorAction(open, true) { rpc.openVote(motion.id) } }
            conferenceMotionDetailHref(motion.id)?.let { href ->
                card.link(tr("Andere Optionen: auf der Antragsseite"), url = href, target = "_blank") {
                    addCssClass("small")
                    setAttribute("rel", "noopener noreferrer")
                }
            }
        }
        if (moreCandidates) content.div(tr("Weitere Anträge auf der Antragsseite")) { addCssClasses("small text-muted") }
    }

    // ── reading ─────────────────────────────────────────────────────────────────────────────────────

    /** The panel hands over each answer of the room poll; reads are throttled to [MERIT_REFRESH_MS]. */
    fun onRoomUpdate(boundNow: Boolean) {
        bound = boundNow
        if (!isOpen() || disposed) return
        if (boundNow) maybeRead(force = false)
    }

    /** The panel became visible: nothing was read while it was hidden. */
    fun onOpened() {
        if (disposed) return
        closeGates.clear()
        rosters.clear()
        closeSlots.values.toList().forEach { repaint(it) }
        maybeRead(force = true)
    }

    /** After an own write the cached verdicts are stale (the vote just opened or closed). */
    fun invalidate() {
        closeGates.clear()
        maybeRead(force = true)
    }

    private fun maybeRead(force: Boolean) {
        if (disposed || !isOpen() || !bound) return
        val meetingId = ctx.roomMeetingId() ?: return
        val now = scheduler.now()
        if (!force && now - lastReadAt < MERIT_REFRESH_MS) return
        if (readInFlight) {
            if (force) readAgain = true
            return
        }
        lastReadAt = now
        readInFlight = true
        scope.launch {
            try {
                val scheduled = rpc.listScheduledMotions(meetingId)
                val open = mutableListOf<MotionDto>()
                for (motion in scheduled.take(MERIT_CANDIDATE_CAP + 1)) {
                    if (open.size >= MERIT_CANDIDATE_CAP) break
                    if (!canRecord(motion.targetCommitteeId)) continue
                    val ok =
                        meritCanOpen(
                            motion = motion,
                            roomMeetingId = meetingId,
                            canRecord = true,
                            votes = rpc.listVotesFor(motion.id),
                            elections = rpc.listElectionsFor(motion.id),
                            consensuses = rpc.listConsensusesFor(motion.id),
                            amendments = rpc.listAmendments(motion.id),
                        )
                    if (ok) open += motion
                }
                moreCandidates = scheduled.size > MERIT_CANDIDATE_CAP
                candidates = open
                readFailed = false
            } catch (ex: CancellationException) {
                throw ex
            } catch (ignored: Throwable) {
                // never shown with its message: without the read there is simply no "open" offer; the next poll tries again
                readFailed = true
            }
            readInFlight = false
            if (disposed) return@launch
            preparedSlot?.let { repaint(it) }
            if (readAgain) {
                readAgain = false
                maybeRead(force = true)
            }
        }
    }

    private fun repaint(slot: MeritSlot) {
        slot.container.removeAll()
        slot.paint(slot.container)
    }

    /** The panel rebuilds its overview: the slots of the old cards are gone with them. */
    fun beginRender() {
        closeSlots.clear()
        preparedSlot = null
    }

    fun dispose() {
        disposed = true
        closeSlots.clear()
        preparedSlot = null
        candidates = emptyList()
    }
}
