package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.icon
import io.kvision.html.link
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.ElectionDto
import network.lapis.cloud.shared.domain.ElectionParticipationDto
import network.lapis.cloud.shared.domain.ElectionResultDto
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.RoomBallotDto
import network.lapis.cloud.shared.domain.RoomBallotKind
import network.lapis.cloud.shared.domain.RoomBallotStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IElectionService
import network.lapis.cloud.shared.rpc.IGovernanceService

/*
 * V1.9.26 "Abstimmen im Konferenzraum", Welle 3 -- the operator side of the room's voting panel: open, close, approve and count an election
 * without leaving the call, and the emergency card for a stream that does not stop.
 *
 * Nothing here is authority. Every gate comes from `ElectionAuthzUi` (the mirror of the server's rules), every write goes through ONE
 * guarded function ([ConferenceVoteOperatorController.runOperatorAction]) and the server decides again. A `ConflictException` is never read:
 * what it meant is found out by reloading. No ballot ever passes through this file -- only counters, statuses and the election's own
 * title (untrusted text, shown through the untrusted helpers).
 *
 * The gate roles of the room follow the server: opening, closing and counting need the election board or BOARD/ADMIN, the tally approval
 * only a real election board member. The committee leadership has only the "manage" role (abort, appoint) and gets exactly that, on the
 * emergency card; making it open and close would be a server rule change, not a client one.
 */

/** Who the member is in this room. [roomMeetingId] is read at the moment of use: a moderator can bind or unbind the Sitzung during the call. */
internal data class OperatorContext(
    val currentMemberId: String,
    val isBoardOrAdmin: Boolean,
    val canModerateRoom: Boolean,
    val roomMeetingId: () -> String?,
)

/** At most this many prepared elections are looked at (each costs one participation read). */
private const val PREPARED_CAP = 10

/** The prepared list is re-read at most this often by the poll rhythm; own actions and opening the panel force it. */
private const val PREPARED_REFRESH_MS = 30_000

private const val PREPARED_KEY = "#prepared"

/** The room states whose operator counters ("Stimmen bisher", "Freigaben x von N") are re-read with each poll. */
private val COUNTER_STATUSES = setOf(RoomBallotStatus.OPEN, RoomBallotStatus.CLOSED_AWAITING_TALLY)

/** Every RPC of the operator side behind one seam: the DOM tests run against the fetch stub, a unit test can pass fakes. */
internal class OperatorRpc(
    val getElection: suspend (String) -> ElectionDto = { rpcService<IElectionService>().getElection(it) },
    val getParticipation: suspend (String) -> ElectionParticipationDto = { rpcService<IElectionService>().getElectionParticipation(it) },
    val getResult: suspend (String) -> ElectionResultDto = { rpcService<IElectionService>().getElectionResult(it) },
    val listPrepared: suspend (String) -> List<ElectionDto> = { meetingId -> listPreparedElections(meetingId) },
    /** Lazy: only when the stream is hung (motion and roster are needed for the "manage" role, i.e. aborting). */
    val loadManageRoles: suspend (ElectionDto, ElectionParticipationDto, OperatorContext) -> ElectionRoles = { e, p, ctx ->
        manageRolesOf(e, p, ctx)
    },
    val openVoting: suspend (String) -> ElectionDto = { rpcService<IElectionService>().openVoting(it) },
    val closeVoting: suspend (String) -> ElectionDto = { rpcService<IElectionService>().closeVoting(it) },
    val approveTally: suspend (String) -> ElectionDto = { rpcService<IElectionService>().approveTally(it) },
    val tally: suspend (String) -> ElectionResultDto = { rpcService<IElectionService>().tally(it) },
    val abortElection: suspend (String) -> ElectionDto = { rpcService<IElectionService>().abortElection(it) },
)

/** The elections of the Sitzung that are prepared but not open yet. The list is organisation-wide and ungranular, so it is filtered by status first and by Sitzung here. */
private suspend fun listPreparedElections(meetingId: String): List<ElectionDto> {
    val service = rpcService<IElectionService>()
    val preparing = service.listElections(status = ElectionStatus.PREPARATION)
    val released = service.listElections(status = ElectionStatus.CANDIDATE_LIST_RELEASED)
    return (preparing + released)
        .filter {
            it.meetingId == meetingId &&
                (it.status == ElectionStatus.PREPARATION || it.status == ElectionStatus.CANDIDATE_LIST_RELEASED)
        }.distinctBy { it.id }
        .take(PREPARED_CAP)
}

private suspend fun manageRolesOf(
    e: ElectionDto,
    p: ElectionParticipationDto,
    ctx: OperatorContext,
): ElectionRoles {
    val motion = rpcService<IGovernanceService>().getMotion(e.motionId)
    val roster = rpcService<IGovernanceService>().listCommitteeMembers(motion.targetCommitteeId, activeOnly = true)
    return electionRoles(ctx.isBoardOrAdmin, ctx.currentMemberId, motion, roster, p)
}

/** What the operator is offered for one election: pure, decided from the election's own status and the existing gates, never duplicated. */
internal sealed interface OperatorStep {
    data class Open(
        val gate: Gate,
        val secret: Boolean,
    ) : OperatorStep

    data class Close(
        val ballotCount: Int,
    ) : OperatorStep

    data class Closed(
        val approve: Boolean,
        val tally: Gate,
        val approvals: Int,
        val threshold: Int,
    ) : OperatorStep

    data class Tallied(
        val result: ElectionResultDto?,
    ) : OperatorStep

    data object None : OperatorStep
}

internal fun operatorStep(
    e: ElectionDto,
    p: ElectionParticipationDto,
    roles: ElectionRoles,
): OperatorStep =
    when (e.status) {
        ElectionStatus.PREPARATION, ElectionStatus.CANDIDATE_LIST_RELEASED ->
            canOpenVoting(e, p, roles).let { gate -> if (gate is Gate.Hidden) OperatorStep.None else OperatorStep.Open(gate, e.secret) }
        ElectionStatus.OPEN -> if (canCloseVoting(e, roles)) OperatorStep.Close(p.ballotCount) else OperatorStep.None
        ElectionStatus.CLOSED -> {
            val approve = canApproveTally(e, p, roles)
            val tally = canTally(e, p, roles)
            if (!approve && tally is Gate.Hidden) {
                OperatorStep.None
            } else {
                OperatorStep.Closed(approve, tally, p.tallyApprovalCount, p.tallyThreshold)
            }
        }
        ElectionStatus.TALLIED -> if (roles.canOperate) OperatorStep.Tallied(null) else OperatorStep.None
        ElectionStatus.ABORTED -> OperatorStep.None
    }

/** The roles of the room: "manage" (abort) stays `false` until the lazy roster read says otherwise -- opening and closing do not need it. */
internal fun electionRolesForRoom(
    ctx: OperatorContext,
    p: ElectionParticipationDto,
    canManage: Boolean = false,
): ElectionRoles = ElectionRoles(canManage = canManage, boardOrAdmin = ctx.isBoardOrAdmin, boardStrict = p.isElectionBoardMember)

private class OperatorEntry(
    val ballot: RoomBallotDto,
) {
    val forStatus: RoomBallotStatus get() = ballot.status
    var election: ElectionDto? = null
    var participation: ElectionParticipationDto? = null
    var result: ElectionResultDto? = null
    var canManage = false
    var manageLoaded = false
    var manageLoading = false
    var loading = true
    var failed = false
    var refreshing = false
}

private class OperatorSlot(
    val container: SimplePanel,
    val paint: (SimplePanel) -> Unit,
)

internal class ConferenceVoteOperatorController(
    private val ctx: OperatorContext,
    private val rpc: OperatorRpc,
    private val scheduler: ConferenceVoteScheduler,
    private val lockClock: BallotLockClock,
    private val sendNudge: suspend () -> Unit,
    private val refreshRoom: () -> Unit,
    private val onStopStreamRequested: (() -> Unit)?,
    private val showNote: (String?) -> Unit,
    private val isOpen: () -> Boolean,
    private val requestOverviewRender: () -> Unit,
    meritRpc: MeritOperatorRpc = MeritOperatorRpc(),
    consensusRpc: ConferenceConsensusRpc = ConferenceConsensusRpc(),
    private val scope: CoroutineScope = AppScope,
) {
    /** V1.9.27 -- the meritocratic-vote side (open a Yes/No vote, close a running one). All its logic lives in its own file; this class only delegates. */
    val merit: ConferenceMeritVoteOperator =
        ConferenceMeritVoteOperator(
            ctx = ctx,
            rpc = meritRpc,
            scheduler = scheduler,
            runOperatorAction = { button, nudge, write -> runOperatorAction(button, nudge, write) },
            isOpen = isOpen,
            scope = scope,
        )

    /** V1.9.32 -- the systemic-consensus side (freeze, close, evaluate, rate again). All its logic lives in its own file; this class only delegates. */
    val consensus: ConferenceConsensusOperator =
        ConferenceConsensusOperator(
            rpc = consensusRpc,
            scheduler = scheduler,
            lockClock = lockClock,
            runOperatorAction = { button, nudge, write -> runOperatorAction(button, nudge, write) },
            onStopStreamRequested = onStopStreamRequested,
            canModerateRoom = ctx.canModerateRoom,
            isOpen = isOpen,
            requestOverviewRender = requestOverviewRender,
            scope = scope,
        )

    private val entries = mutableMapOf<String, OperatorEntry>()
    private var slots = mutableMapOf<String, OperatorSlot>()
    private val hungShown = mutableSetOf<String>()
    private var prepared: List<Pair<ElectionDto, ElectionParticipationDto>> = emptyList()
    private var preparedFailed = false
    private var preparedInFlight = false
    private var preparedAgain = false
    private var lastPreparedAt = Double.NEGATIVE_INFINITY
    private var bound = false
    private var disposed = false

    // ── rendering ───────────────────────────────────────────────────────────────────────────────────

    /** Called by the panel before it rebuilds its overview: the slots of the old cards are gone with them. */
    fun beginRender() {
        slots = mutableMapOf()
        hungShown.clear()
        merit.beginRender()
        consensus.beginRender()
    }

    private fun register(
        key: String,
        container: SimplePanel,
        paint: (SimplePanel) -> Unit,
    ) {
        slots[key] = OperatorSlot(container, paint)
        paint(container)
    }

    private fun repaint(key: String) {
        val slot = slots[key] ?: return
        slot.container.removeAll()
        slot.paint(slot.container)
    }

    fun repaintAll() {
        slots.keys.toList().forEach(::repaint)
        consensus.repaintAll()
    }

    /** The operator section inside the live card of one election: the lock line for a member who can still vote, and the controls. */
    fun renderForBallot(
        card: Container,
        ballot: RoomBallotDto,
    ) {
        val slot = card.vPanel(spacing = 4)
        register(ballot.id, slot) { content -> paintCardSection(content, ballot) }
    }

    private fun paintCardSection(
        content: SimplePanel,
        ballot: RoomBallotDto,
    ) {
        if (ballot.status == RoomBallotStatus.OPEN && ballot.secret && ballot.ownEligible && !ballot.ownHasVoted) {
            ballotLockReason(lockClock.lock(true).forMember())?.let { reason -> content.div(reason) { addCssClasses("small text-muted") } }
        }
        val entry = entryFor(ballot) ?: return
        if (entry.failed && mayShowError(entry)) {
            content.dataErrorState { retry(ballot) }
            return
        }
        val e = entry.election
        val p = entry.participation
        // The stream hung after this entry was loaded: the committee leadership needs the election to be able to abort it.
        if (e == null && !entry.loading && !entry.failed && isHungOpen(ballot)) load(ballot, entry)
        if (e == null || p == null) return
        when (val step = operatorStep(e, p, rolesOf(entry))) {
            is OperatorStep.Close -> paintClose(content, e, step)
            is OperatorStep.Closed -> paintClosed(content, e, step)
            is OperatorStep.Tallied -> entry.result?.let { renderElectionResultCompact(content, e, it) }
            is OperatorStep.Open, OperatorStep.None -> Unit
        }
    }

    private fun paintClose(
        content: SimplePanel,
        e: ElectionDto,
        step: OperatorStep.Close,
    ) {
        val counter = content.hPanel(spacing = 4) { addCssClasses("align-items-center small text-muted") }
        counter.icon("fas fa-box-archive") { addCssClass("text-primary") }
        counter.span(gettext("Stimmen bisher: %1", step.ballotCount))
        val close = content.button(tr("Abstimmung schließen"), style = ButtonStyle.PRIMARY)
        close.onClick {
            confirmDialog(
                title = tr("Abstimmung schließen"),
                message = closeDialogMessage(step.ballotCount),
                confirmLabel = tr("Abstimmung schließen"),
                confirmStyle = ButtonStyle.PRIMARY,
                dangerNote = if (step.ballotCount == 0) gettext("Es wurde noch keine Stimme abgegeben.") else null,
                focusCancel = true,
            ) { runOperatorAction(close, nudge = true) { rpc.closeVoting(e.id) } }
        }
    }

    private fun closeDialogMessage(count: Int): String =
        if (count == 1) {
            gettext("Bisher 1 Stimme abgegeben. Nach dem Schließen sind keine weiteren Stimmen möglich.")
        } else {
            gettext("Bisher %1 Stimmen abgegeben. Nach dem Schließen sind keine weiteren Stimmen möglich.", count)
        }

    private fun paintClosed(
        content: SimplePanel,
        e: ElectionDto,
        step: OperatorStep.Closed,
    ) {
        content.div(gettext("Freigaben %1 von %2", step.approvals, step.threshold)) { addCssClasses("small text-muted") }
        val tallyGate = step.tally
        val tallyReady = tallyGate is Gate.Enabled
        if (tallyReady) {
            val count = content.button(tr("Auszählen"), style = ButtonStyle.PRIMARY)
            count.onClick {
                confirmDialog(
                    title = tr("Auszählen"),
                    message = tr("Die Stimmen werden jetzt ausgezählt. Das Ergebnis steht danach fest."),
                    confirmLabel = tr("Auszählen"),
                    confirmStyle = ButtonStyle.PRIMARY,
                    focusCancel = true,
                ) { runOperatorAction(count, nudge = true) { rpc.tally(e.id) } }
            }
        }
        if (step.approve) {
            val approve = content.button(tr("Auszählung freigeben"), style = if (tallyReady) ButtonStyle.LINK else ButtonStyle.PRIMARY)
            approve.onClick { runOperatorAction(approve, nudge = false) { rpc.approveTally(e.id) } }
        }
        if (tallyGate is Gate.Disabled) {
            val why = tallyGate.reason
            content.div(why) { addCssClasses("small text-muted") }
        }
    }

    /** The "prepared elections" block: only for a member who can open at least one of them (or BOARD/ADMIN, who is told how to prepare one). */
    fun renderPreparedSection(parent: Container) {
        val slot = parent.vPanel(spacing = 4)
        register(PREPARED_KEY, slot) { content -> paintPrepared(content) }
        merit.renderPrepared(parent)
        maybeRefreshPrepared(force = false)
    }

    private fun paintPrepared(content: SimplePanel) {
        val rows =
            prepared.mapNotNull { (e, p) ->
                val gate = canOpenVoting(e, p, electionRolesForRoom(ctx, p))
                if (gate is Gate.Hidden) null else Triple(e, p, gate)
            }
        if (!ctx.isBoardOrAdmin && rows.isEmpty()) return
        val heading = content.hPanel(spacing = 4) { addCssClasses("align-items-center fw-bold small") }
        heading.icon("fas fa-box-archive") { addCssClass("text-primary") }
        heading.span(tr("Vorbereitete Wahlen"))
        if (preparedFailed && ctx.isBoardOrAdmin) content.dataErrorState { maybeRefreshPrepared(force = true) }
        rows.forEach { (e, _, gate) -> paintPreparedRow(content, e, gate) }
        content.link(tr("Wahl vorbereiten in neuem Tab"), url = "#/elections", target = "_blank") {
            addCssClass("small")
            setAttribute("rel", "noopener noreferrer")
        }
    }

    private fun paintPreparedRow(
        content: SimplePanel,
        e: ElectionDto,
        gate: Gate,
    ) {
        val card = content.vPanel(spacing = 4) { addCssClasses("lapis-vote-card border rounded p-2") }
        val head = card.hPanel(spacing = 6) { addCssClasses("align-items-center flex-wrap") }
        head.untrustedSpan(e.title, className = "fw-bold")
        head.span(tr("vorbereitet")) { addCssClasses("small text-muted") }
        val open = card.button(tr("Abstimmung öffnen"), style = ButtonStyle.PRIMARY)
        if (gate is Gate.Disabled) {
            open.disabled = true
            val why = gate.reason
            card.div(why) { addCssClasses("small text-muted") }
            return
        }
        open.onClick {
            if (e.secret) {
                val lines = secretOpenPreflightLines(lockClock.mirror.status)
                confirmDialog(
                    title = tr("Geheime Wahl öffnen"),
                    message = lines.first(),
                    confirmLabel = tr("Geheime Wahl öffnen"),
                    confirmStyle = ButtonStyle.PRIMARY,
                    extraLines = lines.drop(1),
                    focusCancel = true,
                ) { runOperatorAction(open, nudge = true) { rpc.openVoting(e.id) } }
            } else {
                runOperatorAction(open, nudge = true) { rpc.openVoting(e.id) }
            }
        }
    }

    // ── the emergency card ──────────────────────────────────────────────────────────────────────────

    /** Operators and the moderation get the emergency card for a secret ballot whose stream does not stop; a plain member only reads the lock text. */
    private fun isHungOpen(ballot: RoomBallotDto): Boolean =
        ballot.status == RoomBallotStatus.OPEN && ballot.secret && lockClock.lock(true) == BallotStreamLock.HUNG

    fun showsHungCard(ballot: RoomBallotDto): Boolean =
        ballot.kind == RoomBallotKind.ELECTION &&
            ballot.status == RoomBallotStatus.OPEN &&
            ballot.secret &&
            lockClock.lock(true) == BallotStreamLock.HUNG &&
            (ctx.canModerateRoom || ctx.isBoardOrAdmin || isOperatorFor(ballot.id) || isManagerFor(ballot.id))

    fun renderHungCard(
        parent: Container,
        ballot: RoomBallotDto,
    ) {
        val card = parent.vPanel(spacing = 6) { addCssClasses("lapis-vote-card border border-danger rounded p-2") }
        hungShown += ballot.id
        register(ballot.id, card) { content -> paintHung(content, ballot) }
    }

    private fun paintHung(
        content: SimplePanel,
        ballot: RoomBallotDto,
    ) {
        content.untrustedP(ballot.title, className = "fw-bold mb-0")
        val entry = entryFor(ballot)
        val e = entry?.election
        val p = entry?.participation
        if (entry != null && e != null && p != null) loadManageRolesOnce(ballot, entry, e, p)
        val canStop = onStopStreamRequested != null
        val canAbortNow = e != null && entry != null && canAbort(e, rolesOf(entry))
        val settled = entry?.manageLoaded == true || e == null
        if (!canStop && !canAbortNow && settled) {
            content.div(
                gettext(
                    "Der Stream lässt sich nicht anhalten. Die Moderation kann den Stream stoppen, die Wahlleitung kann die Wahl abbrechen.",
                ),
            ) { addCssClasses("small") }
            return
        }
        content.div(gettext("Der Stream lässt sich nicht anhalten. Die Stimmabgabe bleibt gesperrt, bis er angehalten ist.")) {
            addCssClasses("small")
        }
        if (canStop) {
            val stop = content.button(tr("Stream stoppen"), style = ButtonStyle.PRIMARY)
            stop.onClick { onStopStreamRequested?.invoke() }
        }
        if (canAbortNow && e != null) {
            val abort = content.button(tr("Wahl abbrechen"), style = ButtonStyle.LINK) { addCssClass("text-danger") }
            abort.onClick {
                confirmWithTypedConfirmationDialog(
                    title = tr("Wahl abbrechen"),
                    message = tr("Eine laufende Wahl wird endgültig abgebrochen. Alle bereits abgegebenen Stimmen verfallen."),
                    expectedText = e.title,
                    confirmLabel = tr("Wahl abbrechen"),
                ) {
                    runOperatorAction(abort, nudge = true) {
                        rpc.abortElection(e.id)
                        notifyInfo(tr("Wahl abgebrochen."))
                    }
                }
            }
        }
    }

    private fun loadManageRolesOnce(
        ballot: RoomBallotDto,
        entry: OperatorEntry,
        e: ElectionDto,
        p: ElectionParticipationDto,
    ) {
        if (entry.manageLoaded || entry.manageLoading) return
        entry.manageLoading = true
        scope.launch {
            try {
                entry.canManage = rpc.loadManageRoles(e, p, ctx).canManage
            } catch (ex: CancellationException) {
                throw ex
            } catch (ignored: Throwable) {
                // never shown: without the roster the abort simply stays unavailable
            }
            entry.manageLoaded = true
            entry.manageLoading = false
            if (!disposed) repaint(ballot.id)
        }
    }

    // ── data ────────────────────────────────────────────────────────────────────────────────────────

    fun isOperatorFor(electionId: String): Boolean {
        val entry = entries[electionId] ?: return false
        return entry.election != null && rolesOf(entry).canOperate
    }

    /** The committee leadership: no operator, but allowed to abort -- known once the hung-ballot load has read the roster. */
    private fun isManagerFor(electionId: String): Boolean {
        val entry = entries[electionId] ?: return false
        return entry.election != null && entry.manageLoaded && entry.canManage
    }

    private fun rolesOf(entry: OperatorEntry): ElectionRoles {
        val p = entry.participation ?: return ElectionRoles(canManage = false, boardOrAdmin = ctx.isBoardOrAdmin, boardStrict = false)
        return electionRolesForRoom(ctx, p, entry.canManage)
    }

    private fun mayShowError(entry: OperatorEntry): Boolean = ctx.isBoardOrAdmin || entry.participation?.isElectionBoardMember == true

    /** The cached state of one election, or a new one that starts loading; `null` while the panel is closed (nothing is read for a hidden panel). */
    private fun entryFor(ballot: RoomBallotDto): OperatorEntry? {
        val cached = entries[ballot.id]
        if (cached != null && cached.forStatus == ballot.status) return cached
        if (!isOpen() || disposed) return null
        if (ballot.kind != RoomBallotKind.ELECTION) return null
        // The count may have just been run by an election board member: an earlier entry that showed them as operator keeps the result.
        val wasOperator = cached?.let { it.election != null && rolesOf(it).canOperate } == true
        if (ballot.status == RoomBallotStatus.DECIDED && !ctx.isBoardOrAdmin && !wasOperator) return null
        val entry = OperatorEntry(ballot)
        entries[ballot.id] = entry
        load(ballot, entry)
        return entry
    }

    private fun load(
        ballot: RoomBallotDto,
        entry: OperatorEntry,
    ) {
        entry.loading = true
        entry.failed = false
        scope.launch {
            try {
                val p = rpc.getParticipation(ballot.id)
                entry.participation = p
                // The election itself is only read for somebody who can possibly operate: a plain member costs one participation read.
                val hungOpen = isHungOpen(ballot)
                if (ctx.isBoardOrAdmin || p.isElectionBoardMember || hungOpen) {
                    val e = rpc.getElection(ballot.id)
                    entry.election = e
                    if (e.status == ElectionStatus.TALLIED && rolesOf(entry).canOperate) entry.result = rpc.getResult(ballot.id)
                    // A hung stream: the committee leadership (no operator) may still abort, so its roster is read right away.
                    if (hungOpen && !entry.manageLoaded && !rolesOf(entry).canOperate) {
                        entry.canManage = rpc.loadManageRoles(e, p, ctx).canManage
                        entry.manageLoaded = true
                    }
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ignored: Throwable) {
                // Never shown with its message: a retry state appears for somebody who could operate.
                entry.failed = true
            }
            entry.loading = false
            if (disposed) return@launch
            if (slots[ballot.id] != null &&
                showsHungCard(ballot) &&
                ballot.id !in hungShown
            ) {
                requestOverviewRender()
            } else {
                repaint(ballot.id)
            }
        }
    }

    private fun retry(ballot: RoomBallotDto) {
        entries.remove(ballot.id)
        repaint(ballot.id)
    }

    /** Reloads everything the controller knows after a write (the room list and the counters may all have changed). */
    private fun reloadEntries() {
        entries.values.toList().forEach { entry -> load(entry.ballot, entry) }
    }

    /** The panel hands over each answer of the room poll: an OPEN or CLOSED election refreshes its counters ("Stimmen bisher", "Freigaben x von N") for its operators. */
    fun onRoomUpdate(
        ballots: List<RoomBallotDto>,
        boundNow: Boolean,
    ) {
        // the merit side remembers the binding even while the panel is closed (opening it later needs it); it throttles itself
        merit.onRoomUpdate(boundNow)
        consensus.onRoomUpdate(ballots)
        bound = boundNow
        if (!isOpen() || disposed) return
        if (boundNow) maybeRefreshPrepared(force = false)
        ballots
            .filter { it.kind == RoomBallotKind.ELECTION && it.status in COUNTER_STATUSES }
            .forEach { ballot -> refreshParticipation(ballot) }
    }

    private fun refreshParticipation(ballot: RoomBallotDto) {
        val entry = entries[ballot.id] ?: return
        if (entry.election == null || entry.refreshing || !rolesOf(entry).canOperate) return
        entry.refreshing = true
        scope.launch {
            try {
                entry.participation = rpc.getParticipation(ballot.id)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ignored: Throwable) {
                // a missed refresh keeps the last counter
            }
            entry.refreshing = false
            if (!disposed) repaint(ballot.id)
        }
    }

    /** The panel became visible: nothing was read while it was hidden. */
    fun onOpened() {
        if (disposed) return
        repaintAll()
        maybeRefreshPrepared(force = true)
        merit.onOpened()
        consensus.onOpened()
    }

    private fun maybeRefreshPrepared(force: Boolean) {
        if (disposed || !isOpen() || !bound) return
        val meetingId = ctx.roomMeetingId() ?: return
        val now = scheduler.now()
        if (!force && now - lastPreparedAt < PREPARED_REFRESH_MS) return
        if (preparedInFlight) {
            if (force) preparedAgain = true
            return
        }
        lastPreparedAt = now
        preparedInFlight = true
        scope.launch {
            try {
                val rows = mutableListOf<Pair<ElectionDto, ElectionParticipationDto>>()
                rpc.listPrepared(meetingId).take(PREPARED_CAP).forEach { e ->
                    try {
                        rows += e to rpc.getParticipation(e.id)
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ignored: Throwable) {
                        // one unreadable election is left out, not an error for the others
                    }
                }
                prepared = rows
                preparedFailed = false
            } catch (ex: CancellationException) {
                throw ex
            } catch (ignored: Throwable) {
                preparedFailed = true
            }
            preparedInFlight = false
            if (disposed) return@launch
            repaint(PREPARED_KEY)
            if (preparedAgain) {
                preparedAgain = false
                maybeRefreshPrepared(force = true)
            }
        }
    }

    // ── the one place that writes ───────────────────────────────────────────────────────────────────

    /**
     * The ONLY write path of this file. Guarded against a double click ([runGuardedAction]); a [ConflictException] is answered with a
     * neutral "the state changed, reloaded" -- its message is never read; any other failure gets the fixed texts of [electionGuarded].
     * After every write the room, the counters and the prepared list are reloaded; after a SUCCESSFUL one the other members get one nudge
     * (an empty data message -- a lost nudge only costs them the next poll).
     */
    internal fun runOperatorAction(
        button: Button,
        nudge: Boolean,
        write: suspend () -> Unit,
    ) {
        runGuardedAction(button) {
            showNote(null)
            var done = false
            try {
                write()
                done = true
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: ConflictException) {
                showNote(gettext("Der Stand hat sich geändert und wurde neu geladen."))
            } catch (ex: Throwable) {
                electionGuarded<Unit>(conflictMessage = "") { throw ex }
            }
            if (done && nudge) {
                try {
                    sendNudge()
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ignored: Throwable) {
                    // the data channel may be gone: the poll of the others catches up
                }
            }
            if (!disposed) {
                reloadEntries()
                refreshRoom()
                maybeRefreshPrepared(force = true)
                merit.invalidate()
                consensus.invalidate()
            }
        }
    }

    fun dispose() {
        disposed = true
        merit.dispose()
        consensus.dispose()
        slots = mutableMapOf()
        entries.clear()
    }
}
