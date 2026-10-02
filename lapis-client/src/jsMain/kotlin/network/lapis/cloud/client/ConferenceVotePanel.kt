package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.Widget
import io.kvision.core.onEvent
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.icon
import io.kvision.html.link
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.ConferenceStreamPauseReason
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
import network.lapis.cloud.shared.domain.ElectionDto
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.LtrLedgerBalanceDto
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.RoomBallotDto
import network.lapis.cloud.shared.domain.RoomBallotKind
import network.lapis.cloud.shared.domain.RoomBallotStatus
import network.lapis.cloud.shared.domain.RoomVotingStateDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.domain.SystemicConsensusDto
import network.lapis.cloud.shared.domain.VoteBallotDto
import network.lapis.cloud.shared.domain.VoteBallotInput
import network.lapis.cloud.shared.rpc.IConferenceService
import network.lapis.cloud.shared.rpc.IElectionService
import network.lapis.cloud.shared.rpc.IGovernanceService
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import kotlin.js.Date

/*
 * V1.9.25 "Abstimmen im Konferenzraum", Welle 2 -- the panel shell. Client only: the server state and the nudge channel are V1.9.24.
 *
 * Everything that touches a ballot lives in THIS file, so one tripwire (`ElectionSecrecyTripwireTest`) can watch it: the receipt code
 * of a secret ballot exists only in the two DOM nodes of the embedded booth (`ElectionBooth.kt`); this file never sees it. The only
 * thing that crosses over is one Boolean ("a receipt is on screen"), through `electionReceiptVisibilityHook`.
 *
 * Layout of the file: the pure room state (`voteRoomReduce`, no DOM, tested without a browser), the polling controller (the timer
 * and the in-flight rule, behind a scheduler seam), the unload guard and the hook scope, then the rendering.
 */

// ── the pure room state ──────────────────────────────────────────────────────────────────────────────

/** What the client knows about the ballots of the room. [bound] is `null` until the first answer; [consecutiveFailures] counts failed polls in a row. */
internal data class ConferenceVoteRoomState(
    val bound: Boolean? = null,
    val ballots: List<RoomBallotDto> = emptyList(),
    val truncated: Boolean = false,
    val seenOpenBallotIds: Set<String> = emptySet(),
    val consecutiveFailures: Int = 0,
    /** V1.9.32 -- consensus id -> how often it has entered RATING so far (a re-rating after a result is a new generation). */
    val consensusGenerations: Map<String, Int> = emptyMap(),
)

internal data class ConferenceVoteRoomUpdate(
    val state: ConferenceVoteRoomState,
    /** Elections, meritocratic votes and consensuses in RATING that are open now and were not before. Never filled by the FIRST answer: a member who joins a running vote sees the badge, no pop-up. */
    val newlyOpenedBallots: List<RoomBallotDto>,
    val listChanged: Boolean,
)

internal fun voteRoomReduce(
    prev: ConferenceVoteRoomState,
    dto: RoomVotingStateDto,
): ConferenceVoteRoomUpdate {
    val openBallots = dto.ballots.filter { it.isOpenForMembers() }
    val firstAnswer = prev.bound == null
    val enteredRating = openBallots.filter { it.kind == RoomBallotKind.CONSENSUS && it.openSeenKey() !in prev.seenOpenBallotIds }
    val generations = prev.consensusGenerations + enteredRating.associate { it.id to (prev.consensusGenerations[it.id] ?: 0) + 1 }
    // V1.9.32: a consensus is announced only to a member who can act on it, and only in RATING (a COLLECTION is open for the room, nothing to
    // rate yet). The id handed on carries the rating generation after the first one, so a re-rating opens the panel again although the
    // screen remembers the first id for good.
    val newlyOpened =
        if (firstAnswer) {
            emptyList()
        } else {
            openBallots
                .filter { it.openSeenKey() !in prev.seenOpenBallotIds && (it.kind != RoomBallotKind.CONSENSUS || it.memberActionable()) }
                .map { ballot ->
                    val generation = generations[ballot.id] ?: 1
                    if (ballot.kind == RoomBallotKind.CONSENSUS && generation > 1) ballot.copy(id = "${ballot.id}@$generation") else ballot
                }
        }
    // a consensus that left RATING is forgotten, so a re-rating is announced again
    val leftRating =
        dto.ballots
            .filter { it.kind == RoomBallotKind.CONSENSUS && !it.isConsensusRating() }
            .map { it.openSeenKey() }
            .toSet()
    val next =
        ConferenceVoteRoomState(
            bound = dto.bound,
            ballots = dto.ballots,
            truncated = dto.truncated,
            seenOpenBallotIds = (prev.seenOpenBallotIds - leftRating) + openBallots.map { it.openSeenKey() },
            consecutiveFailures = 0,
            consensusGenerations = generations,
        )
    val listChanged = prev.bound != next.bound || prev.ballots != next.ballots || prev.truncated != next.truncated
    return ConferenceVoteRoomUpdate(state = next, newlyOpenedBallots = newlyOpened, listChanged = listChanged)
}

/** A failed poll changes nothing but the failure counter: the list the member sees stays as it was. */
internal fun voteRoomFailure(prev: ConferenceVoteRoomState): ConferenceVoteRoomState =
    prev.copy(
        consecutiveFailures =
            prev.consecutiveFailures + 1,
    )

/** The number on the toggle button: open elections, votes and consensuses (in RATING) the member may vote in and has not voted in yet. */
internal fun ConferenceVoteRoomState.badgeCount(): Int = ballots.count { it.memberActionable() }

internal fun ConferenceVoteRoomState.anyActive(): Boolean =
    ballots.any { it.status == RoomBallotStatus.OPEN || it.status == RoomBallotStatus.CLOSED_AWAITING_TALLY }

/** Three failed polls in a row: a calm "status is being refreshed" line, never an error text. */
internal fun ConferenceVoteRoomState.showQuietRefreshHint(): Boolean = consecutiveFailures >= QUIET_HINT_AFTER_FAILURES

private const val QUIET_HINT_AFTER_FAILURES = 3

private const val POLL_ACTIVE_MS = 5_000
private const val POLL_IDLE_MS = 15_000
private const val POLL_HIDDEN_MS = 30_000

internal fun conferenceVotePollDelayMs(
    state: ConferenceVoteRoomState,
    hidden: Boolean,
): Int =
    when {
        hidden -> POLL_HIDDEN_MS
        state.anyActive() -> POLL_ACTIVE_MS
        else -> POLL_IDLE_MS
    }

/** Two nudges closer together than this are one request. The server allows 90 requests a minute; this client stays far below. */
internal const val CONFERENCE_VOTE_NUDGE_MIN_GAP_MS = 1_000

/** Only a member of the organization may vote (the server decides again; this only keeps the panel from being built for nobody). */
internal fun conferenceIsVotingMember(session: SessionInfoDto?): Boolean =
    session != null && !session.isGuest && session.status in MemberStatusSets.ORGANIZATION_MEMBER

internal val ROOM_ID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/** The in-app address of an election; `null` unless [id] has the shape of an id this server mints. Never a foreign URL. */
internal fun conferenceElectionDetailHref(id: String): String? = if (ROOM_ID_PATTERN.matches(id)) "#/elections/$id" else null

// ── the polling controller ───────────────────────────────────────────────────────────────────────────

/** The timer, behind a seam so a test runs on a fake clock. */
internal interface ConferenceVoteScheduler {
    fun now(): Double

    fun schedule(
        delayMs: Int,
        block: () -> Unit,
    ): Int

    fun cancel(handle: Int)
}

internal object BrowserVoteScheduler : ConferenceVoteScheduler {
    override fun now(): Double = Date.now()

    override fun schedule(
        delayMs: Int,
        block: () -> Unit,
    ): Int = window.setTimeout({ block() }, delayMs)

    override fun cancel(handle: Int) = window.clearTimeout(handle)
}

/** The one place the room's ballots are read: the poll controller and the fresh check before a bid view opens (V1.9.27) share it. */
internal suspend fun fetchRoomVotingState(roomId: String): RoomVotingStateDto = rpcService<IConferenceService>().getRoomVotingState(roomId)

/**
 * Asks the server for the ballots of the room: every 5 s while something is open, 15 s otherwise, 30 s in a hidden tab, at once when
 * the tab becomes visible and when the data channel nudges. At most ONE request is in flight; whatever arrives meanwhile becomes
 * exactly one follow-up. A failed poll is counted and never shown as an error (no toast, no message); [stop] ends everything and a
 * result that arrives afterwards is ignored.
 */
internal class ConferenceVotePollController(
    private val roomId: String,
    private val fetch: suspend (String) -> RoomVotingStateDto = ::fetchRoomVotingState,
    private val isHidden: () -> Boolean = { document.asDynamic().visibilityState == "hidden" },
    private val scheduler: ConferenceVoteScheduler = BrowserVoteScheduler,
    private val scope: CoroutineScope = AppScope,
    private val onUpdate: (ConferenceVoteRoomUpdate) -> Unit,
    private val onFailure: (ConferenceVoteRoomState) -> Unit,
) {
    var state: ConferenceVoteRoomState = ConferenceVoteRoomState()
        private set

    private var started = false
    private var stopped = false
    private var inFlight = false
    private var followUp = false
    private var generation = 0
    private var timer: Int? = null
    private var nudgeTimer: Int? = null
    private var lastRequestAt = Double.NEGATIVE_INFINITY
    private val visibilityListener: (Event) -> Unit = { onVisibilityChanged() }

    fun start() {
        if (started || stopped) return
        started = true
        document.addEventListener("visibilitychange", visibilityListener)
        requestNow()
    }

    /** A nudge from the data channel: ask now, but never more often than [CONFERENCE_VOTE_NUDGE_MIN_GAP_MS]; the rest waits for one later request. */
    fun nudge() {
        if (!started || stopped) return
        val gap = scheduler.now() - lastRequestAt
        if (gap >= CONFERENCE_VOTE_NUDGE_MIN_GAP_MS) {
            requestNow()
        } else if (nudgeTimer == null) {
            nudgeTimer =
                scheduler.schedule((CONFERENCE_VOTE_NUDGE_MIN_GAP_MS - gap).toInt().coerceAtLeast(0)) {
                    nudgeTimer = null
                    requestNow()
                }
        }
    }

    /** After a state change the member caused (Sitzung assigned, booth left, panel opened). */
    fun refreshNow() {
        if (!started || stopped) return
        requestNow()
    }

    fun stop() {
        if (stopped) return
        stopped = true
        generation++
        cancelTimers()
        if (started) document.removeEventListener("visibilitychange", visibilityListener)
    }

    internal fun onVisibilityChanged() {
        if (!started || stopped) return
        if (isHidden()) {
            if (!inFlight) scheduleNext()
        } else {
            requestNow()
        }
    }

    private fun cancelTimers() {
        timer?.let { scheduler.cancel(it) }
        timer = null
        nudgeTimer?.let { scheduler.cancel(it) }
        nudgeTimer = null
    }

    private fun scheduleNext() {
        timer?.let { scheduler.cancel(it) }
        timer =
            scheduler.schedule(conferenceVotePollDelayMs(state, isHidden())) {
                timer = null
                requestNow()
            }
    }

    private fun requestNow() {
        if (stopped) return
        if (inFlight) {
            followUp = true
            return
        }
        timer?.let { scheduler.cancel(it) }
        timer = null
        inFlight = true
        lastRequestAt = scheduler.now()
        val mine = generation
        scope.launch {
            var answer: RoomVotingStateDto? = null
            var failed = false
            try {
                answer = fetch(roomId)
            } catch (e: CancellationException) {
                inFlight = false
                throw e
            } catch (ignored: Throwable) {
                // Never shown: the quiet hint after three misses is the whole reaction, and `message` is never read.
                failed = true
            }
            inFlight = false
            if (stopped || mine != generation) return@launch
            if (failed || answer == null) {
                state = voteRoomFailure(state)
                onFailure(state)
            } else {
                val update = voteRoomReduce(state, answer)
                state = update.state
                onUpdate(update)
            }
            if (stopped) return@launch
            if (followUp) {
                followUp = false
                requestNow()
            } else {
                scheduleNext()
            }
        }
    }
}

// ── leaving the page ─────────────────────────────────────────────────────────────────────────────────

/**
 * True while a secret-ballot receipt is on screen. A module singleton on purpose: there is exactly one conference screen, and the
 * unload guard, which lives one level above the call, must read it without a parameter chain through every re-entry path.
 */
internal object ConferenceReceiptGate {
    var visible: Boolean = false

    /** V1.9.26 -- true while the ballot request is in flight: the receipt of that very request has no place to appear once the page is gone. */
    var casting: Boolean = false

    /** Leaving the page (or the room) would lose a receipt that is on screen or about to arrive. */
    val blocksUnload: Boolean get() = visible || casting
}

/**
 * V1.9.26 -- the browser's own "leave this page?" question while a ballot request is in flight. Registered only for that time (the panel
 * installs it when the request starts and removes it when it ends), so there is no standing listener and nothing to leak.
 */
internal class ConferenceCastingUnloadPrompt(
    private val isCasting: () -> Boolean,
) {
    private val listener: (Event) -> Unit = { event ->
        if (isCasting()) {
            event.preventDefault()
            event.asDynamic().returnValue = ""
        }
    }
    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        window.addEventListener("beforeunload", listener)
    }

    fun uninstall() {
        if (!installed) return
        installed = false
        window.removeEventListener("beforeunload", listener)
    }
}

/**
 * Leaving the page during a call. The old listener disconnected on `beforeunload` -- which fires BEFORE the browser asks "leave this
 * page?". With a receipt on screen the booth asks (its own `beforeunload` guard), and answering "stay" left the member in the room
 * page but out of the room. Now `beforeunload` disconnects only when no receipt is on screen, and `pagehide` -- which fires only
 * when the page really goes -- always does. Nothing is awaited: the disconnect is fire-and-forget, as before.
 */
internal class ConferenceUnloadGuard(
    private val receiptVisible: () -> Boolean,
    private val disconnect: () -> Unit,
) {
    val beforeUnload: (Event) -> Unit = { if (!receiptVisible()) disconnect() }
    val pageHide: (Event) -> Unit = { disconnect() }

    fun install() {
        window.addEventListener("beforeunload", beforeUnload)
        window.addEventListener("pagehide", pageHide)
    }

    fun uninstall() {
        window.removeEventListener("beforeunload", beforeUnload)
        window.removeEventListener("pagehide", pageHide)
    }
}

/**
 * Takes `electionReceiptVisibilityHook` for the life of the panel and gives it back. The hook is one global variable that the
 * elections screen also sets and never clears; a panel that simply overwrote it would leave its own closure behind. The identity
 * check keeps a hook somebody else set in the meantime.
 */
internal class ConferenceReceiptHookScope(
    private val onChange: (Boolean) -> Unit,
) {
    private var previous: ((Boolean) -> Unit)? = null
    private var installed = false

    fun install() {
        if (installed) return
        previous = electionReceiptVisibilityHook
        electionReceiptVisibilityHook = onChange
        installed = true
    }

    fun restore() {
        if (installed && electionReceiptVisibilityHook === onChange) electionReceiptVisibilityHook = previous
        installed = false
        previous = null
    }
}

/**
 * Whatever has to be torn down when the call ends -- the controller, the hook, a listener -- registers here. One call, several exits
 * (leaving, "end for all", a kick, a breakout hand-over, the screen being closed): each of them calls [disposeActive].
 */
internal object ConferenceVoteRuntime {
    private var active: Registration? = null

    class Registration internal constructor(
        private val action: () -> Unit,
    ) {
        private var done = false

        fun dispose() {
            if (done) return
            done = true
            action()
            if (active === this) active = null
        }
    }

    fun register(action: () -> Unit): Registration {
        active?.dispose()
        return Registration(action).also { active = it }
    }

    fun disposeActive() {
        active?.dispose()
    }
}

// ── rendering ────────────────────────────────────────────────────────────────────────────────────────

private const val LOCK_REASON_ID = "lapis-vote-lock-reason"

/** Same as the private helper of `ConferenceScreen.kt`: `Widget.setAttribute` re-renders on every call, so only a real change is written. */
internal fun Widget.setAttr(
    name: String,
    value: String,
) {
    if (getAttribute(name) != value) setAttribute(name, value)
}

/** The toggle button of the control bar, with the badge for the number of open ballots. */
internal class ConferenceVotingToggle(
    val button: Button,
) {
    private var badge: HTMLElement? = null
    private var count = 0

    fun attachBadge(host: HTMLElement?) {
        val element = host ?: return
        val created = document.createElement("span") as HTMLElement
        created.className = "lapis-conference-control-badge"
        element.appendChild(created)
        badge = created
        paintBadge()
    }

    private fun paintBadge() {
        val el = badge ?: return
        if (count > 0) {
            el.textContent = count.toString()
            el.style.display = "flex"
        } else {
            el.style.display = "none"
        }
    }

    fun update(
        openCount: Int,
        pressed: Boolean,
        visible: Boolean,
    ) {
        count = openCount
        val label = if (openCount > 0) gettext("Abstimmen, offen: %1", openCount) else gettext("Abstimmen")
        if (button.title != label) button.title = label
        button.setAttr("aria-label", label)
        button.setAttr("aria-pressed", pressed.toString())
        if (pressed) button.addCssClass("active") else button.removeCssClass("active")
        if (visible) button.show() else button.hide()
        paintBadge()
    }
}

/**
 * The toggle button, placed by the caller in the control bar. Built by constructor and added through [addWithLifecycle] so the badge
 * hook exists before the first render (no late hook). Its accessible name is set inside the call; [ConferenceVotingToggle.update] keeps
 * it current.
 */
internal fun Container.conferenceVotingToggle(): ConferenceVotingToggle {
    val button =
        Button(text = "", icon = "fas fa-check-to-slot", style = ButtonStyle.OUTLINESECONDARY) {
            title = gettext("Abstimmen")
            setAttribute("aria-label", gettext("Abstimmen"))
        }
    val toggle = ConferenceVotingToggle(button)
    addWithLifecycle(button, onInsert = { vnode -> toggle.attachBadge(vnode.elm as? HTMLElement) })
    button.hide()
    return toggle
}

internal fun Container.votingBadge(
    text: String,
    color: String,
    faIcon: String,
) {
    span {
        addCssClasses("badge rounded-pill text-bg-$color")
        icon(faIcon) { addCssClass("me-1") }
        span(text)
    }
}

internal fun roomBallotStatusLabel(status: RoomBallotStatus): String =
    when (status) {
        RoomBallotStatus.OPEN -> tr("offen")
        RoomBallotStatus.CLOSED_AWAITING_TALLY -> tr("geschlossen, wartet auf Auszählung")
        RoomBallotStatus.DECIDED -> tr("ausgezählt")
    }

private fun roomBallotStatusColor(status: RoomBallotStatus): String =
    when (status) {
        RoomBallotStatus.OPEN -> "success"
        RoomBallotStatus.CLOSED_AWAITING_TALLY -> "warning"
        RoomBallotStatus.DECIDED -> "secondary"
    }

private fun roomBallotStatusIcon(status: RoomBallotStatus): String =
    when (status) {
        RoomBallotStatus.OPEN -> "fas fa-circle"
        RoomBallotStatus.CLOSED_AWAITING_TALLY -> "fas fa-hourglass-half"
        RoomBallotStatus.DECIDED -> "fas fa-check"
    }

private fun Container.ballotBadges(ballot: RoomBallotDto) {
    val row = hPanel(spacing = 6) { addCssClasses("flex-wrap align-items-center") }
    row.votingBadge(roomBallotStatusLabel(ballot.status), roomBallotStatusColor(ballot.status), roomBallotStatusIcon(ballot.status))
    if (ballot.secret) {
        row.votingBadge(tr("geheim"), "dark", "fas fa-lock")
    } else {
        row.votingBadge(tr("offen – namentlich"), "info", "fas fa-user-pen")
    }
}

/** Your own standing in one ballot. No other member appears anywhere in the panel. */
private fun ownParticipationLabel(ballot: RoomBallotDto): String =
    when {
        ballot.ownHasVoted -> tr("Sie haben abgestimmt")
        ballot.ownEligible -> tr("Sie sind stimmberechtigt")
        else -> tr("Nicht stimmberechtigt")
    }

/**
 * One election as a card of three lines: title, state, your standing. Title and motion title are member-written free text, so both go
 * through the untrusted helpers. The single primary button exists only when the member can vote right now.
 */
internal fun renderElectionLiveCard(
    parent: Container,
    ballot: RoomBallotDto,
    onEnterBooth: (Button) -> Unit,
): SimplePanel {
    val card = parent.vPanel(spacing = 4) { addCssClasses("lapis-vote-card border rounded p-2") }
    card.untrustedP(ballot.title, className = "fw-bold mb-0")
    if (ballot.motionTitle.isNotBlank() && ballot.motionTitle != ballot.title) {
        card.untrustedSpan(ballot.motionTitle, className = "text-muted small")
    }
    card.ballotBadges(ballot)
    card.div(ownParticipationLabel(ballot)) { addCssClasses("small") }
    if (ballot.status == RoomBallotStatus.OPEN && ballot.ownEligible && !ballot.ownHasVoted) {
        val enter = card.button(tr("Zur Wahlkabine"), style = ButtonStyle.PRIMARY)
        enter.onClick { onEnterBooth(enter) }
    } else if (ballot.status != RoomBallotStatus.OPEN) {
        val href = conferenceElectionDetailHref(ballot.id)
        if (href != null) {
            card.link(tr("Details in neuem Tab"), url = href, target = "_blank") {
                setAttribute("rel", "noopener noreferrer")
            }
        }
    }
    return card
}

/** A ballot of a kind without a card of its own: only its state. Votes have `ConferenceMeritVoteCard.kt`, consensuses `ConferenceConsensusCard.kt`. */
internal fun renderRoomBallotReadOnlyRow(
    parent: Container,
    ballot: RoomBallotDto,
) {
    val card = parent.vPanel(spacing = 4) { addCssClasses("lapis-vote-card border rounded p-2") }
    card.untrustedP(ballot.title, className = "fw-bold mb-0")
    card.ballotBadges(ballot)
    if (ballot.status == RoomBallotStatus.OPEN) {
        card.div(tr("Abstimmung läuft – Stimmabgabe im Raum folgt")) { addCssClasses("text-muted small") }
    }
}

/** The single line a guest or friend sees while ballots are running: they may be in the room, they may not vote. Hidden until [show]n. */
internal fun renderGuestVotingStatusLine(parent: Container): Div {
    val line =
        parent.div(tr("Abstimmungen laufen – nur Mitglieder können abstimmen")) {
            addCssClasses("text-muted small")
            setAttribute("role", "status")
        }
    line.hide()
    return line
}

internal class ConferenceVotePanelHandle(
    val panel: SimplePanel,
    val apply: (ConferenceVoteRoomUpdate) -> Unit,
    val applyFailure: (ConferenceVoteRoomState) -> Unit,
    val announce: (String) -> Unit,
    val isBoothOpen: () -> Boolean,
    val dispose: () -> Unit,
    /** V1.9.26 -- the stream status of the room, from the screen's one choke point; re-renders only when a lock level changes, never the booth. */
    val onStreamState: (ConferenceStreamStatus?, ConferenceStreamPauseReason?) -> Unit = { _, _ -> },
    /** V1.9.26 -- show or hide the panel; opening it is what lets the operator side read anything. */
    val setOpen: (Boolean) -> Unit = {},
)

/**
 * The panel itself: header with the (lockable) close button, the overview of the room's ballots and, in place of the overview, the
 * embedded voting booth.
 *
 * The quiet rule: [ConferenceVotePanelHandle.apply] rebuilds only the overview. While the booth is open the overview is merely
 * hidden and the booth's container is never touched -- a poll answer must not move anything under a member's hand.
 * [ConferenceVotePanelHandle.announce] speaks through a live region OUTSIDE the panel, so a new ballot is announced even while the
 * panel is closed (and, on a narrow screen, never opened for the member).
 */
internal fun renderConferenceVotePanel(
    parent: Container,
    loadElection: suspend (String) -> ElectionDto = { id -> rpcService<IElectionService>().getElection(id) },
    onLockChanged: (ConferenceVotingLock) -> Unit,
    onCloseRequested: () -> Unit,
    onBoothExited: () -> Unit,
    operatorContext: OperatorContext? = null,
    operatorRpc: OperatorRpc = OperatorRpc(),
    sendNudge: suspend () -> Unit = {},
    onStopStreamRequested: (() -> Unit)? = null,
    onRefreshRoom: () -> Unit = {},
    scheduler: ConferenceVoteScheduler = BrowserVoteScheduler,
    onStreamMirrorChanged: (StreamMirrorState) -> Unit = {},
    meritRpc: MeritOperatorRpc = MeritOperatorRpc(),
    loadBalance: suspend () -> LtrLedgerBalanceDto? = { defaultLoadBalance() },
    castVote: suspend (VoteBallotInput) -> VoteBallotDto = { rpcService<IGovernanceService>().castVoteBallot(it) },
    loadRoomState: suspend () -> RoomVotingStateDto? = { null },
    consensusRpc: ConferenceConsensusRpc = ConferenceConsensusRpc(),
    isVotingMember: Boolean = true,
    measureWidth: (() -> Int)? = null,
): ConferenceVotePanelHandle {
    val liveRegion =
        parent.div("") {
            addCssClass("visually-hidden")
            setAttribute("role", "status")
            setAttribute("aria-live", "polite")
        }
    val panel = parent.vPanel(spacing = 6) { addCssClasses("border rounded p-2 lapis-conference-voting") }
    panel.hide()

    val header = panel.hPanel(spacing = 6) { addCssClasses("align-items-center justify-content-between") }
    header.h2(tr("Abstimmen")) { addCssClasses("h6 mb-0") }
    val closeButton = header.button(tr("Abstimmen schließen"), style = ButtonStyle.OUTLINESECONDARY) { addCssClass("btn-sm") }
    val lockReason = panel.div("") { addCssClasses("text-muted small") }
    lockReason.id = LOCK_REASON_ID
    lockReason.hide()
    val inlineNote = panel.div("") { addCssClasses("text-muted small") }
    inlineNote.hide()
    val overview = panel.vPanel(spacing = 6) { addCssClass("lapis-vote-overview") }
    val refreshHint = panel.div(tr("Status wird aktualisiert …")) { addCssClasses("text-muted small") }
    refreshHint.hide()
    val boothHost = panel.vPanel(spacing = 6) { addCssClass("lapis-vote-booth-host") }
    boothHost.hide()
    val backRow = boothHost.hPanel(spacing = 6)
    val backButton = backRow.button(tr("Zurück zur Übersicht"), style = ButtonStyle.OUTLINESECONDARY) { addCssClass("btn-sm") }
    val boothNote = boothHost.div("") { addCssClasses("text-muted small") }
    boothNote.hide()
    val boothArea = SimplePanel()
    boothHost.add(boothArea)

    var roomState = ConferenceVoteRoomState()
    var boothOpen = false
    var receiptShown = false
    var boothBusy = false
    var disposed = false
    // V1.9.32 -- consensus ids whose booth does not fit the panel: their card offers the booth in a new tab (link) instead of the button
    val narrowConsensusTabs = mutableMapOf<String, String>()
    val castPrompt = ConferenceCastingUnloadPrompt { boothBusy }
    val lockClock = BallotLockClock(scheduler)
    var lockedHung = false

    fun currentLock(): ConferenceVotingLock =
        when {
            receiptShown -> ConferenceVotingLock.RECEIPT
            boothBusy -> ConferenceVotingLock.CASTING
            boothOpen -> ConferenceVotingLock.BOOTH
            else -> ConferenceVotingLock.NONE
        }

    fun paintLock(lock: ConferenceVotingLock) {
        val locked = lock != ConferenceVotingLock.NONE
        closeButton.disabled = locked
        closeButton.setAttr("aria-disabled", locked.toString())
        if (locked) closeButton.setAttr("aria-describedby", LOCK_REASON_ID) else closeButton.removeAttribute("aria-describedby")
        val backLocked = lock == ConferenceVotingLock.RECEIPT || boothBusy
        backButton.disabled = backLocked
        backButton.setAttr("aria-disabled", backLocked.toString())
        when (lock) {
            ConferenceVotingLock.RECEIPT -> lockReason.content = tr("Bitte notieren Sie zuerst Ihre Quittung.")
            ConferenceVotingLock.CASTING -> lockReason.content = tr("Ihre Stimme wird gerade übermittelt …")
            ConferenceVotingLock.BOOTH -> lockReason.content = tr("Bitte beenden Sie zuerst die Stimmabgabe.")
            ConferenceVotingLock.NONE -> Unit
        }
        if (locked) lockReason.show() else lockReason.hide()
    }

    fun publishLock() {
        val lock = currentLock()
        ConferenceReceiptGate.visible = receiptShown
        ConferenceReceiptGate.casting = boothBusy
        paintLock(lock)
        onLockChanged(lock)
    }

    val hookScope =
        ConferenceReceiptHookScope { visible ->
            if (!disposed) {
                receiptShown = visible
                publishLock()
            }
        }
    hookScope.install()
    // V1.9.32 -- the consensus booth reports its receipt through its OWN hook (`consensusReceiptVisibilityHook`)
    val consensusHookScope =
        ConferenceConsensusReceiptHookScope { visible ->
            if (!disposed) {
                receiptShown = visible
                publishLock()
            }
        }
    consensusHookScope.install()

    fun showNote(text: String?) {
        if (text == null) {
            inlineNote.hide()
        } else {
            inlineNote.content = text
            inlineNote.show()
        }
    }

    // The overview, the booth and the card buttons call each other; a local function cannot be referenced before it is declared.
    var enterBoothAction: (Button, RoomBallotDto) -> Unit = { _, _ -> }
    var enterMeritAction: (Button, RoomBallotDto) -> Unit = { _, _ -> }
    var enterConsensusAction: (Button, RoomBallotDto) -> Unit = { _, _ -> }
    var overviewRenderer: () -> Unit = {}

    // V1.9.26 -- the operator side (open, close, count, emergency card). Only for a member of the organization; everything it needs from the
    // screen comes through the arguments above. `panel.visible` is the "panel is open" test: a hidden panel reads nothing.
    val operator =
        operatorContext?.let { context ->
            ConferenceVoteOperatorController(
                ctx = context,
                rpc = operatorRpc,
                scheduler = scheduler,
                lockClock = lockClock,
                sendNudge = sendNudge,
                refreshRoom = onRefreshRoom,
                onStopStreamRequested = onStopStreamRequested,
                showNote = { text -> showNote(text) },
                isOpen = { panel.visible },
                requestOverviewRender = { overviewRenderer() },
                meritRpc = meritRpc,
                consensusRpc = consensusRpc,
            )
        }

    fun renderOverview() {
        overview.removeAll()
        operator?.beginRender()
        when {
            roomState.bound == null -> overview.div(tr("Wird geladen …")) { addCssClasses("text-muted small") }
            roomState.ballots.isEmpty() -> overview.div(tr("Zurzeit keine Abstimmungen.")) { addCssClasses("text-muted small") }
            else -> {
                var meritNoticeShown = false
                roomState.ballots.forEach { ballot ->
                    when {
                        ballot.kind == RoomBallotKind.VOTE -> {
                            if (ballot.status == RoomBallotStatus.OPEN && !meritNoticeShown) {
                                meritNoticeShown = true
                                renderMeritOpenNotice(overview)
                            }
                            val card = renderMeritVoteLiveCard(overview, ballot) { bid -> enterMeritAction(bid, ballot) }
                            operator?.merit?.renderForBallot(card, ballot)
                        }
                        ballot.kind == RoomBallotKind.CONSENSUS && !isVotingMember -> renderConsensusGuestRow(overview, ballot)
                        ballot.kind == RoomBallotKind.CONSENSUS ->
                            if (operator != null && operator.consensus.showsHungCard(ballot)) {
                                operator.consensus.renderHungCard(overview, ballot)
                            } else {
                                val card =
                                    renderConsensusLiveCard(
                                        parent = overview,
                                        ballot = ballot,
                                        canAct = true,
                                        onRate = { rate -> enterConsensusAction(rate, ballot) },
                                        tabHref = narrowConsensusTabs[ballot.id],
                                    )
                                operator?.consensus?.renderForBallot(card, ballot)
                            }
                        ballot.kind != RoomBallotKind.ELECTION -> renderRoomBallotReadOnlyRow(overview, ballot)
                        operator != null && operator.showsHungCard(ballot) -> operator.renderHungCard(overview, ballot)
                        else -> {
                            val card = renderElectionLiveCard(overview, ballot) { enter -> enterBoothAction(enter, ballot) }
                            operator?.renderForBallot(card, ballot)
                        }
                    }
                }
            }
        }
        if (roomState.truncated) {
            overview.div(tr("Weitere Abstimmungen – alle Details in der Sitzung")) { addCssClasses("text-muted small") }
        }
        if (roomState.bound == true) operator?.renderPreparedSection(overview)
    }
    overviewRenderer = { renderOverview() }

    // Every change of the lock -- a new stream status, or the 20 s / 60 s of PAUSING running out -- lands here. Only a change of the emergency
    // level rebuilds the overview (never the booth); every other change repaints the slots and the booth listeners hear it from the clock.
    lockClock.subscribe {
        if (!disposed) {
            val hungNow = lockClock.lock(true) == BallotStreamLock.HUNG
            if (hungNow != lockedHung) {
                lockedHung = hungNow
                renderOverview()
            } else {
                operator?.repaintAll()
            }
        }
    }

    fun focusFirstHeading() {
        window.setTimeout({
            val heading = boothHost.getElement()?.querySelector("h2") as? HTMLElement
            if (heading != null) {
                heading.tabIndex = -1
                heading.focus()
            }
        }, 0)
    }

    fun exitBooth() {
        boothArea.removeAll()
        boothHost.hide()
        boothOpen = false
        receiptShown = false
        boothBusy = false
        castPrompt.uninstall()
        boothNote.hide()
        overview.show()
        renderOverview()
        publishLock()
        onBoothExited()
        window.setTimeout({
            val target = overview.getElement()?.querySelector("button") as? HTMLElement
            (target ?: closeButton.getElement())?.focus()
        }, 0)
    }

    fun openBooth(election: ElectionDto) {
        boothOpen = true
        receiptShown = false
        boothBusy = false
        showNote(null)
        if (operator?.isOperatorFor(election.id) == true) {
            boothNote.content = tr("Sie bedienen diese Wahl. Die Steuerung erscheint nach Ihrer Stimmabgabe wieder.")
            boothNote.show()
        } else {
            boothNote.hide()
        }
        overview.hide()
        boothHost.show()
        publishLock()
        renderElectionBooth(
            boothArea,
            election,
            openBallotBanner = true,
            onBusyChanged = { busy ->
                boothBusy = busy
                if (busy) castPrompt.install() else castPrompt.uninstall()
                publishLock()
            },
            ballotLock = if (election.secret) memberBallotLock(lockClock, onRefreshRoom, scheduler) else null,
        ) { exitBooth() }
        focusFirstHeading()
    }

    enterBoothAction = { enter, ballot ->
        runGuardedAction(enter) {
            val election =
                try {
                    loadElection(ballot.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (ignored: Throwable) {
                    null
                }
            when {
                election == null -> showNote(tr("Die Wahlkabine konnte nicht geladen werden. Bitte erneut versuchen."))
                election.status != ElectionStatus.OPEN -> showNote(tr("Diese Wahl ist nicht mehr offen."))
                else -> openBooth(election)
            }
        }
    }

    // V1.9.27 -- the bid view of a meritocratic vote lives in the SAME booth host: that is what gives it the close lock, the "no auto-open
    // while it is open" rule and the unload prompt for free. It never takes the stream lock (a meritocratic vote is never secret) and it
    // never pauses anything.
    fun openMeritBid(ballot: RoomBallotDto) {
        boothOpen = true
        receiptShown = false
        boothBusy = false
        showNote(null)
        boothNote.hide()
        overview.hide()
        boothHost.show()
        publishLock()
        renderMeritBidView(
            host = boothArea,
            ballot = ballot,
            loadBalance = loadBalance,
            cast = castVote,
            onDone = {
                // One empty nudge, like every other write of the room: a lost one only costs the others their next poll.
                AppScope.launch {
                    try {
                        sendNudge()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (ignored: Throwable) {
                        // the data channel may be gone
                    }
                }
                onRefreshRoom()
                exitBooth()
            },
            onConflict = {
                showNote(gettext("Der Stand hat sich geändert und wurde neu geladen."))
                onRefreshRoom()
                exitBooth()
            },
            onBusyChanged = { busy ->
                boothBusy = busy
                if (busy) castPrompt.install() else castPrompt.uninstall()
                publishLock()
            },
        )
        focusFirstHeading()
    }

    enterMeritAction = { bid, ballot ->
        runGuardedAction(bid) {
            // A stale card must never open a bid on something that has changed: ask the server again first.
            val fresh =
                try {
                    loadRoomState()
                } catch (e: CancellationException) {
                    throw e
                } catch (ignored: Throwable) {
                    null
                }
            val current = fresh?.ballots?.firstOrNull { it.id == ballot.id && it.kind == RoomBallotKind.VOTE }
            when {
                fresh == null -> showNote(tr("Die Wahlkabine konnte nicht geladen werden. Bitte erneut versuchen."))
                current == null || current.status != RoomBallotStatus.OPEN -> {
                    showNote(tr("Diese Abstimmung ist nicht mehr offen."))
                    onRefreshRoom()
                }
                current.ownHasVoted -> {
                    showNote(tr("Sie haben bereits geboten."))
                    onRefreshRoom()
                }
                else -> openMeritBid(current)
            }
        }
    }

    // V1.9.32 -- the resistance booth of a systemic consensus lives in the SAME booth host (close lock, "no auto-open while it is open", unload
    // prompt for free). An anonymous consensus takes the stream lock of a secret ballot, an open one never does. The width is measured once, at the click.
    fun openConsensusBooth(consensus: SystemicConsensusDto) {
        boothOpen = true
        receiptShown = false
        boothBusy = false
        showNote(null)
        boothNote.hide()
        overview.hide()
        boothHost.show()
        publishLock()
        renderConsensusBoothInRoom(
            boothArea = boothArea,
            consensus = consensus,
            exitLabel = tr("Zurück zur Übersicht"),
            ballotLock = consensusMemberBallotLock(lockClock, onRefreshRoom, scheduler),
            onBusyChanged = { busy ->
                boothBusy = busy
                if (busy) castPrompt.install() else castPrompt.uninstall()
                publishLock()
            },
            onExit = { exitBooth() },
        )
        focusFirstHeading()
    }

    enterConsensusAction = { rate, ballot ->
        runGuardedAction(rate) {
            val width = measureWidth ?: { panel.getElement()?.clientWidth?.takeIf { it > 0 } ?: Int.MAX_VALUE }
            when (val entry = decideConsensusEntry(consensusRpc, ballot.id, width)) {
                is ConsensusEntry.Booth -> openConsensusBooth(entry.consensus)
                is ConsensusEntry.NarrowTab -> {
                    narrowConsensusTabs[ballot.id] = entry.href
                    renderOverview()
                }
                ConsensusEntry.NotOpen -> {
                    showNote(tr("Diese Bewertung ist nicht mehr offen."))
                    onRefreshRoom()
                }
                ConsensusEntry.LoadFailed -> showNote(tr("Die Bewertung konnte nicht geladen werden. Bitte erneut versuchen."))
            }
        }
    }

    closeButton.onClick { if (currentLock() == ConferenceVotingLock.NONE) onCloseRequested() }
    backButton.onClick { if (currentLock() != ConferenceVotingLock.RECEIPT && !boothBusy) exitBooth() }
    panel.onEvent {
        keydown = { event ->
            if (event.key == "Escape" && !event.defaultPrevented && currentLock() == ConferenceVotingLock.NONE) onCloseRequested()
        }
    }
    paintLock(ConferenceVotingLock.NONE)
    renderOverview()

    return ConferenceVotePanelHandle(
        panel = panel,
        apply = { update ->
            roomState = update.state
            if (update.listChanged) renderOverview()
            operator?.onRoomUpdate(update.state.ballots, update.state.bound == true)
            if (roomState.showQuietRefreshHint()) refreshHint.show() else refreshHint.hide()
        },
        applyFailure = { failed ->
            roomState = failed
            if (failed.showQuietRefreshHint()) refreshHint.show() else refreshHint.hide()
        },
        announce = { title ->
            liveRegion.content = gettext("Neue Abstimmung geöffnet: %1", sanitizeUntrustedI18nText(title))
        },
        isBoothOpen = { boothOpen },
        dispose = {
            if (!disposed) {
                disposed = true
                hookScope.restore()
                consensusHookScope.restore()
                ConferenceReceiptGate.visible = false
                ConferenceReceiptGate.casting = false
                castPrompt.uninstall()
                lockClock.dispose()
                operator?.dispose()
            }
        },
        onStreamState = { status, reason ->
            if (!disposed) {
                lockClock.update(status, reason)
                onStreamMirrorChanged(lockClock.mirror)
            }
        },
        setOpen = { open ->
            val wasOpen = panel.visible
            if (open) panel.show() else panel.hide()
            if (open && !wasOpen && !disposed) operator?.onOpened()
        },
    )
}

/** The lock of a secret ballot as the booth sees it: members read HUNG as "slow", the stream status comes from the panel's clock. */
private fun memberBallotLock(
    clock: BallotLockClock,
    onRefreshRoom: () -> Unit,
    voteScheduler: ConferenceVoteScheduler,
): BallotLockHook =
    object : BallotLockHook {
        override fun lockedReason(): String? = ballotLockReason(clock.lock(true).forMember())

        override fun subscribe(listener: () -> Unit): () -> Unit = clock.subscribe(listener)

        override fun onConflict() = onRefreshRoom()

        override val scheduler: ConferenceVoteScheduler = voteScheduler
    }

// ── the lock on the leave buttons ────────────────────────────────────────────────────────────────────

/**
 * While a receipt is on screen "Verlassen", "Für alle beenden" and "Zurück zum Hauptraum" are disabled (and say why): each of them
 * disconnects, and the receipt exists nowhere else. [leaving] is the screen's own "a leave is already running" flag -- those buttons
 * are also disabled by their own click handlers, and unlocking must not undo that.
 */
internal class ConferenceReceiptLockApplier {
    private var lastLocked = false

    fun apply(
        locked: Boolean,
        leaving: Boolean,
        buttons: List<Button?>,
    ) {
        if (locked == lastLocked) return
        lastLocked = locked
        buttons.filterNotNull().forEach { button ->
            if (locked) {
                button.disabled = true
                button.setAttr("aria-disabled", "true")
                button.setAttr("aria-describedby", LOCK_REASON_ID)
            } else {
                if (!leaving) button.disabled = false
                button.removeAttribute("aria-disabled")
                button.removeAttribute("aria-describedby")
            }
        }
    }
}
