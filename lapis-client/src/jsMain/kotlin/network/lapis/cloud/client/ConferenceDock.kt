package network.lapis.cloud.client

import io.kvision.i18n.gettext
import io.kvision.panel.VPanel
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import network.lapis.cloud.client.livekit.LiveKitRoomSession
import network.lapis.cloud.shared.domain.SessionInfoDto
import org.w3c.dom.HTMLElement
import kotlin.js.Date

/*
 * V1.9.70 -- the conference dock: a running video conference survives a route change.
 *
 * The call view (video grid, chat, roster, vote panel, receipt) is a KVision subtree that is NOT part of the route outlet: it lives in a
 * permanent dock host next to it and is only shown and hidden. "Docking" means showing the host, "undocking" means hiding it -- nothing is
 * torn down, so no chat line, roster entry or vote receipt is lost, and the background-effect processor is never released while the call
 * runs. What the person sees while the view is hidden is the mini bar ([conferenceDockBar]).
 *
 * This file is the single owner of "is a conference running in this tab": the state machine, the one rejoin guard, the unload guard, the
 * call-presence flag, the audio container and the hard termination (sign-out, account switch, language change, root restart).
 *
 * It contains NO `rpcService` read (ledger of `ClientDataStateTripwireTest` must not grow): everything RPC stays in `ConferenceScreen.kt`.
 */

/** What kind of session the dock carries. Only [CONFERENCE] exists today; [ENCOUNTER] is declared for the follow-up wave. */
internal enum class DockSessionKind { CONFERENCE, ENCOUNTER }

/** Why the dock stopped on its own (the person sees it in the bar / on the card). "Removed" and "ended" are indistinguishable on the client. */
internal enum class DockStopReason { ENDED, DUPLICATE_IDENTITY, REJOIN_EXHAUSTED, CONNECT_FAILED }

/** Why the dock was torn down hard. */
internal enum class DockTerminateReason { LEAVE, LOGOUT, ACCOUNT_SWITCH, UNLOAD, LANGUAGE_CHANGE, ROOT_RESTART }

internal enum class DockTransition { CONNECTING, BREAKOUT_SWITCH }

/** The snapshot the mini bar shows. No personal data, no room title, no room id. */
internal data class DockSnapshot(
    val micOn: Boolean,
    val cameraOn: Boolean,
    val screenSharing: Boolean,
    val recording: Boolean,
    val streaming: Boolean,
    /** The stream is paused (secret ballot or by hand). */
    val streamPaused: Boolean,
    val voteOpen: Boolean,
    val transitioning: DockTransition?,
)

internal sealed class DockState {
    data object Idle : DockState()

    data class Joining(
        val attached: Boolean,
    ) : DockState()

    data class Live(
        val attached: Boolean,
        val snapshot: DockSnapshot,
    ) : DockState()

    data class Resolving(
        val attached: Boolean,
        val snapshot: DockSnapshot,
    ) : DockState()

    data class Stopped(
        val reason: DockStopReason,
        val attached: Boolean,
    ) : DockState()
}

/** Whether the call view is currently shown in the route. [DockState.Idle] counts as "attached" (there is nothing to undock). */
internal val DockState.isAttached: Boolean
    get() =
        when (this) {
            is DockState.Idle -> true
            is DockState.Joining -> attached
            is DockState.Live -> attached
            is DockState.Resolving -> attached
            is DockState.Stopped -> attached
        }

/** The snapshot of a state that has one ([DockState.Live], [DockState.Resolving]). */
internal val DockState.snapshotOrNull: DockSnapshot?
    get() =
        when (this) {
            is DockState.Live -> snapshot
            is DockState.Resolving -> snapshot
            else -> null
        }

internal sealed class DockEvent {
    /** Only effective from [DockState.Idle]. */
    data object JoinRequested : DockEvent()

    data class Connected(
        val snapshot: DockSnapshot,
    ) : DockEvent()

    data class SnapshotChanged(
        val snapshot: DockSnapshot,
    ) : DockEvent()

    data object DisconnectResolving : DockEvent()

    /** A breakout hand-over, a recall or a deliberate resume succeeded: a new `enterCall` runs. */
    data object ReEntered : DockEvent()

    data class Stopped(
        val reason: DockStopReason,
    ) : DockEvent()

    data object ViewAttached : DockEvent()

    data object ViewDetached : DockEvent()

    /** "Schliessen" in the end-state bar. */
    data object Dismissed : DockEvent()

    /** Always leads to [DockState.Idle]. */
    data class Terminated(
        val reason: DockTerminateReason,
    ) : DockEvent()
}

/** Pure, no DOM. Unknown (state, event) pairs return the old state unchanged (same style as [conferenceConnectionReduce]). */
internal fun conferenceDockReduce(
    state: DockState,
    event: DockEvent,
): DockState =
    when (event) {
        is DockEvent.JoinRequested -> if (state is DockState.Idle) DockState.Joining(attached = true) else state
        is DockEvent.Connected ->
            when (state) {
                is DockState.Joining -> DockState.Live(state.attached, event.snapshot)
                is DockState.Live -> state.copy(snapshot = event.snapshot)
                else -> state
            }
        is DockEvent.SnapshotChanged ->
            when (state) {
                is DockState.Live -> state.copy(snapshot = event.snapshot)
                is DockState.Resolving -> state.copy(snapshot = event.snapshot)
                else -> state
            }
        is DockEvent.DisconnectResolving ->
            when (state) {
                is DockState.Live -> DockState.Resolving(state.attached, state.snapshot)
                else -> state
            }
        is DockEvent.ReEntered ->
            when (state) {
                is DockState.Live -> DockState.Joining(state.attached)
                is DockState.Resolving -> DockState.Joining(state.attached)
                is DockState.Stopped -> DockState.Joining(state.attached)
                else -> state
            }
        is DockEvent.Stopped ->
            when (state) {
                is DockState.Joining -> DockState.Stopped(event.reason, state.attached)
                is DockState.Live -> DockState.Stopped(event.reason, state.attached)
                is DockState.Resolving -> DockState.Stopped(event.reason, state.attached)
                is DockState.Stopped -> DockState.Stopped(event.reason, state.attached)
                is DockState.Idle -> state
            }
        is DockEvent.ViewAttached ->
            when (state) {
                is DockState.Joining -> state.copy(attached = true)
                is DockState.Live -> state.copy(attached = true)
                is DockState.Resolving -> state.copy(attached = true)
                is DockState.Stopped -> state.copy(attached = true)
                is DockState.Idle -> state
            }
        is DockEvent.ViewDetached ->
            when (state) {
                is DockState.Joining -> state.copy(attached = false)
                is DockState.Live -> state.copy(attached = false)
                is DockState.Resolving -> state.copy(attached = false)
                is DockState.Stopped -> state.copy(attached = false)
                is DockState.Idle -> state
            }
        is DockEvent.Dismissed -> if (state is DockState.Stopped) DockState.Idle else state
        is DockEvent.Terminated -> DockState.Idle
    }

/** The one bridge from the dock into the running call view. Deliberately narrow. */
internal interface DockableSession {
    val kind: DockSessionKind

    /** The view is shown again: let stalled videos play. */
    fun attach()

    /** The view is hidden: leave browser fullscreen, close the dialogs of the call. */
    fun detach()

    /** Exactly the "Verlassen" flow, including the secret-ballot receipt gate. */
    fun leave()

    /** Hard, without a question: disconnect, release the background effect, clean up. */
    suspend fun terminate(reason: DockTerminateReason)

    fun toggleMic()

    fun toggleCamera()

    fun stopScreenShare()

    /** V1.9.71: the pictures the floating window may lend (default: none, so a fake session in a test needs no change). */
    fun floatMedia(): List<FloatMediaSource> = emptyList()
}

/** What microphone and camera were wanted when the connection ended: an automatic re-entry must not switch on what the person had switched off. */
internal data class ConferenceDeviceIntent(
    val mic: Boolean,
    val camera: Boolean,
)

/** The intent an `enterCall` starts with: a deliberate join switches both on, an automatic re-entry takes over the last wish. */
internal fun conferenceInitialDevices(last: ConferenceDeviceIntent?): ConferenceDeviceIntent =
    last ?: ConferenceDeviceIntent(mic = true, camera = true)

/** Identity key for the account-switch decision: member id, guest flag, home server. Never rendered. */
internal fun dockIdentityKey(s: SessionInfoDto?): String? = s?.let { "${it.memberId}|${it.isGuest}|${it.homeserverUrl.orEmpty()}" }

/**
 * The route screen's handle on "show the lobby again": set by the screen while it is mounted, cleared when that same screen goes (a late
 * destroy hook of an older screen must not clear the port of the newer one).
 */
internal object ConferenceLobbyPort {
    var current: (() -> Unit)? = null
        private set
    private var owner: Any? = null

    fun set(
        token: Any,
        showLobby: () -> Unit,
    ) {
        owner = token
        current = showLobby
    }

    fun clear(token: Any) {
        if (owner !== token) return
        owner = null
        current = null
    }

    internal fun resetForTest() {
        owner = null
        current = null
    }
}

/** Delay before the focus moves into the call view after "Zur Konferenz": long enough for KVision's asynchronous patch. */
private const val FOCUS_AFTER_PATCH_MS = 50

/** Longest wait for the running call's own teardown during [ConferenceDock.terminate]; the last-guard `disconnect` follows either way. */
private const val TERMINATE_TIMEOUT_MS = 5_000L

/** Element id of the permanent audio container (outside the KVision root). */
internal const val CONFERENCE_AUDIO_CONTAINER_ID = "lapis-conference-audio"

/** Bootstrap's `display: none !important`: how the dock host and the mini bar are hidden while staying in the document. */
internal const val DOCK_HIDDEN_CLASS = "d-none"

/** `document.body` class that is set exactly while the mini bar is shown (reserves its space, see theme.css). */
internal const val DOCK_BODY_CLASS = "lapis-has-dock"

internal object ConferenceDock {
    /** The ONLY mutable state field. */
    var state: DockState = DockState.Idle
        private set

    private val observers = mutableListOf<(DockState) -> Unit>()

    /** The LiveKit session of the running call (was a local of `renderConferenceScreen`). */
    var activeSession: LiveKitRoomSession? = null
        internal set

    internal var session: DockableSession? = null
        private set

    /** Only to compare with `#/conference/:roomId`, never shown, never logged. */
    internal var roomId: String? = null
        private set

    /** The ONE automatic re-join guard of the client (3 per 60 s for the whole dock). */
    val rejoinGuard = AutoRejoinGuard(now = { Date.now() })

    var lastDeviceIntent: ConferenceDeviceIntent? = null
        private set

    var announcedFirstDetach: Boolean = false

    /** Set by the bar's "Zur Konferenz": the next attach moves the focus into the call view. */
    var pendingFocusFromBar: Boolean = false

    /** Bumped on every join and on every end: an in-flight coroutine of an older call checks [isCurrent] before it touches devices. */
    var generation: Int = 0
        private set

    private var host: VPanel? = null

    /** The route screen that currently shows the view. A stale screen's late destroy hook must not detach a newer one. */
    private var viewToken: Any? = null

    /** V1.9.71: the pictures the floating window has on loan. A `val`, not state: it only mirrors elements that live in the call view. */
    val videoLedger = ConferenceVideoLedger()

    /** Owned by the dock; `enterCall` renders only into this panel. */
    val callPanel: VPanel =
        VPanel(spacing = 10) {
            addCssClass("lapis-conference-call-panel")
            setAttribute("tabindex", "-1")
            setAttribute("role", "region")
        }

    /** The permanent container the remote audio elements live in; media elements pause when they leave the document. */
    val audioContainer: HTMLElement
        get() = ensureConferenceAudioContainer()

    private val unloadGuard =
        ConferenceUnloadGuard(
            receiptVisible = { ConferenceReceiptGate.blocksUnload },
            disconnect = { AppScope.launch { runCatching { activeSession?.disconnect() } } },
        )

    val isAttached: Boolean get() = state.isAttached

    fun isCurrent(gen: Int): Boolean = gen == generation

    private var instanceCounter = 0
    private var currentInstance = 0

    /** Every `enterCall` claims the dock first: an older, replaced invocation (breakout hand-over) no longer speaks for it. */
    fun claimInstance(): Int {
        instanceCounter++
        currentInstance = instanceCounter
        return currentInstance
    }

    fun isCurrentInstance(id: Int): Boolean = id == currentInstance

    /** Called by `App.start()` at every (re)build of the root. A running call cannot survive a new root. */
    fun bindHost(
        newHost: VPanel,
        audio: HTMLElement = ensureConferenceAudioContainer(),
    ) {
        check(audio.id == CONFERENCE_AUDIO_CONTAINER_ID)
        if (state !is DockState.Idle) {
            AppScope.launch(start = CoroutineStart.UNDISPATCHED) { terminate(DockTerminateReason.ROOT_RESTART) }
        }
        callPanel.parent?.remove(callPanel)
        callPanel.setAttribute("aria-label", gettext("Besprechung"))
        host = newHost
        newHost.add(callPanel)
        applyHostVisibility()
    }

    /** "Verlassen" requested before the joining `enterCall` registered its session. */
    private var pendingLeave = false

    fun canJoin(): Boolean = state is DockState.Idle

    /** `false` if a call (or an end state) is already there: the second click of a double click, or a second join attempt. */
    fun beginJoin(room: String): Boolean {
        if (state !is DockState.Idle) return false
        generation++
        pendingLeave = false
        roomId = room
        rejoinGuard.reset()
        lastDeviceIntent = null
        announcedFirstDetach = false
        dispatch(DockEvent.JoinRequested)
        return true
    }

    /** The newest `enterCall` registers itself; a re-entry replaces the earlier registration. */
    fun register(newSession: DockableSession) {
        session = newSession
        // "Verlassen" was pressed in the mini bar while the join RPC was still in flight (no session yet): apply it now
        if (pendingLeave) {
            pendingLeave = false
            newSession.leave()
        }
    }

    fun dispatch(event: DockEvent) {
        val old = state
        val new = conferenceDockReduce(old, event)
        if (event is DockEvent.SnapshotChanged && old is DockState.Live) {
            lastDeviceIntent = ConferenceDeviceIntent(mic = event.snapshot.micOn, camera = event.snapshot.cameraOn)
        } else if (event is DockEvent.Connected) {
            lastDeviceIntent = ConferenceDeviceIntent(mic = event.snapshot.micOn, camera = event.snapshot.cameraOn)
        }
        if (new is DockState.Idle && old !is DockState.Idle) {
            // fields first, so that observers of the new state see a clean dock
            clearRun()
        }
        if (new == old) return
        state = new
        sideEffects(old, new)
        // undocked: no call view writes the tab title, so the "● " marker must follow a recording / stream change here
        if (event is DockEvent.SnapshotChanged && !new.isAttached) {
            val before = old.snapshotOrNull
            if (before?.recording != event.snapshot.recording || before?.streaming != event.snapshot.streaming) PageTitle.apply()
        }
        observers.toList().forEach { it(new) }
    }

    fun publish(snapshot: DockSnapshot) = dispatch(DockEvent.SnapshotChanged(snapshot))

    private fun clearRun() {
        videoLedger.returnAll()
        generation++
        currentInstance = 0
        ConferenceVoteRuntime.disposeActive()
        activeSession = null
        session = null
        pendingLeave = false
        roomId = null
        lastDeviceIntent = null
        announcedFirstDetach = false
        pendingFocusFromBar = false
        callPanel.removeAll()
        callPanel.hide()
        audioContainer.let { container -> while (container.firstChild != null) container.removeChild(container.firstChild!!) }
    }

    private fun sideEffects(
        old: DockState,
        new: DockState,
    ) {
        ConferenceCallPresence.set(live = new is DockState.Joining || new is DockState.Live || new is DockState.Resolving)
        if (new is DockState.Joining ||
            new is DockState.Live ||
            new is DockState.Resolving
        ) {
            unloadGuard.install()
        } else {
            unloadGuard.uninstall()
        }
        applyHostVisibility()
        // the space of the bar is reserved only while the BAR is what the person sees (not while the floating window is)
        val bar = new !is DockState.Idle && !new.isAttached && !ConferenceFloatController.showsFloat(new)
        toggleDocumentClass(bar)
        if (new is DockState.Idle) removeTitlePrefix()
        if (old is DockState.Idle && new !is DockState.Idle) callPanel.show()
    }

    /** The presentation (bar / window) changed without a state change: reserve or release the bar's space again. */
    fun reapplyChrome() {
        val s = state
        toggleDocumentClass(s !is DockState.Idle && !s.isAttached && !ConferenceFloatController.showsFloat(s))
    }

    /** The call's media changed (a tile, a picture, a share): the floating window repaints its pictures. */
    fun notifyMediaChanged() = ConferenceFloatController.onMediaChanged()

    private fun applyHostVisibility() {
        val h = host ?: return
        // a CSS class, NOT `hide()`: KVision does not render a hidden widget, and a call view that is dropped from the document would lose
        // its video elements and its raw-DOM tiles -- the host must stay in the document, only invisible
        if (state !is DockState.Idle && state.isAttached) h.removeCssClass(DOCK_HIDDEN_CLASS) else h.addCssClass(DOCK_HIDDEN_CLASS)
    }

    private fun toggleDocumentClass(on: Boolean) {
        val body = document.body
        val root = document.documentElement
        if (on) {
            body?.classList?.add(DOCK_BODY_CLASS)
            root?.classList?.add(DOCK_BODY_CLASS)
        } else {
            body?.classList?.remove(DOCK_BODY_CLASS)
            root?.classList?.remove(DOCK_BODY_CLASS)
            root?.classList?.remove(DOCK_TWO_ROWS_CLASS)
        }
    }

    /**
     * The route screen is shown ([token] identifies the screen instance). [fromBar]: the person came through "Zur Konferenz" (the focus
     * then goes into the view, after the route's own focus handling).
     */
    fun attachView(
        token: Any,
        fromBar: Boolean,
    ) {
        viewToken = token
        if (state is DockState.Idle) return
        dispatch(DockEvent.ViewAttached)
        session?.attach()
        if (fromBar) {
            // The route's header would take the focus when it is inserted (PageFocus): the person asked for the call view, so that request
            // is cancelled here, and the focus goes into the view once KVision has patched it into the document.
            PageFocus.consume()
            window.setTimeout({ callPanel.getElement()?.focus() }, FOCUS_AFTER_PATCH_MS)
        }
    }

    /** The route screen [token] goes away (its destroy hook, which can run AFTER a newer screen attached). Nothing is torn down. */
    fun detachView(token: Any) {
        if (viewToken !== token) return
        viewToken = null
        if (state is DockState.Idle) return
        dispatch(DockEvent.ViewDetached)
        session?.detach()
    }

    /** An end state nobody looked at has nothing to show but the lobby: it is dismissed when the route opens. */
    fun prepareForRoute() {
        val s = state
        if (s is DockState.Stopped && (s.reason == DockStopReason.ENDED || s.reason == DockStopReason.CONNECT_FAILED)) dismiss()
    }

    /** Reads and clears [pendingFocusFromBar]. */
    fun consumeFocusFromBar(): Boolean {
        val value = pendingFocusFromBar
        pendingFocusFromBar = false
        return value
    }

    fun leaveFromBar() {
        val s = session
        if (s != null) {
            s.leave()
        } else if (state is DockState.Joining) {
            // the join is still in flight: remember the wish, register() applies it (otherwise the call would connect after "Verlassen")
            pendingLeave = true
        }
    }

    /** "Schliessen" of the end state: back to nothing. */
    fun dismiss() {
        dispatch(DockEvent.Dismissed)
    }

    /** Hard end, idempotent. Every way out of the dock that is not a deliberate "Verlassen" ends here. */
    suspend fun terminate(reason: DockTerminateReason) {
        if (state is DockState.Idle) return
        generation++ // an in-flight join / re-entry of this call must not switch on a device any more
        // V1.9.71: every picture on loan goes back to the call view FIRST -- then its own teardown stops them where they belong
        videoLedger.returnAll()
        val current = session
        // bounded: a hung disconnect must not hold a sign-out or a language change hostage
        runCatching { withTimeoutOrNull(TERMINATE_TIMEOUT_MS) { current?.terminate(reason) } }
        // last guard: whatever the session did, no track may keep running
        runCatching { activeSession?.disconnect() }
        ConferenceVoteRuntime.disposeActive()
        dispatch(DockEvent.Terminated(reason))
    }

    fun onAuthSessionChanged(
        old: SessionInfoDto?,
        new: SessionInfoDto?,
    ) {
        if (state is DockState.Idle) return
        val oldKey = dockIdentityKey(old)
        val newKey = dockIdentityKey(new)
        val reason =
            when {
                newKey == null -> DockTerminateReason.LOGOUT
                oldKey != null && oldKey != newKey -> DockTerminateReason.ACCOUNT_SWITCH
                else -> return
            }
        AppScope.launch(start = CoroutineStart.UNDISPATCHED) { terminate(reason) }
    }

    fun observe(listener: (DockState) -> Unit): () -> Unit {
        observers += listener
        return { observers.remove(listener) }
    }

    /** The page title as the route wrote it, with the "● " marker while a recording or stream runs. */
    fun decorateTitle(base: String): String {
        val snapshot = state.snapshotOrNull ?: return base
        return conferenceMediaDocumentTitle(base, snapshot.recording, snapshot.streaming)
    }

    private fun removeTitlePrefix() {
        val title = document.title
        if (title.startsWith("● ")) document.title = title.removePrefix("● ")
    }

    internal fun resetForTest() {
        videoLedger.returnAll()
        observers.clear()
        generation++
        state = DockState.Idle
        activeSession = null
        session = null
        roomId = null
        lastDeviceIntent = null
        announcedFirstDetach = false
        pendingFocusFromBar = false
        viewToken = null
        rejoinGuard.reset()
        unloadGuard.uninstall()
        ConferenceCallPresence.set(live = false)
        toggleDocumentClass(false)
        callPanel.removeAll()
        host = null
        ConferenceFloatController.resetForTest()
    }
}

/** Idempotent: the container lives in `document.body`, outside the KVision root, and is created once. */
internal fun ensureConferenceAudioContainer(): HTMLElement {
    val existing = document.getElementById(CONFERENCE_AUDIO_CONTAINER_ID) as? HTMLElement
    if (existing != null) return existing
    val created = document.createElement("div") as HTMLElement
    created.id = CONFERENCE_AUDIO_CONTAINER_ID
    created.setAttribute("hidden", "")
    created.setAttribute("aria-hidden", "true")
    document.body?.appendChild(created)
    return created
}

/** Class of the `html` element while the narrow, two-row bar is shown (reserves more height, see theme.css). */
internal const val DOCK_TWO_ROWS_CLASS = "lapis-dock-two-rows"
