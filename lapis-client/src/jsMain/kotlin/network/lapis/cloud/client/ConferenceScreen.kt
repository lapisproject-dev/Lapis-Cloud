package network.lapis.cloud.client

import io.kvision.core.Overflow
import io.kvision.form.check.checkBox
import io.kvision.form.select.select
import io.kvision.form.text.text
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.client.livekit.LiveKitRoomSession
import network.lapis.cloud.client.livekit.Track
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceChatMessage
import network.lapis.cloud.shared.domain.ConferenceJoinTokenDto
import network.lapis.cloud.shared.domain.ConferenceRecordingDto
import network.lapis.cloud.shared.domain.ConferenceRecordingStatus
import network.lapis.cloud.shared.domain.ConferenceRole
import network.lapis.cloud.shared.domain.ConferenceRoomDto
import network.lapis.cloud.shared.domain.ConferenceRoomInput
import network.lapis.cloud.shared.domain.ConferenceStreamDto
import network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode
import network.lapis.cloud.shared.domain.ConferenceStreamLayout
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
import network.lapis.cloud.shared.domain.ConferenceStreamTargetDto
import network.lapis.cloud.shared.domain.ConferenceStreamTargetStatus
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.rpc.IConferenceRecordingService
import network.lapis.cloud.shared.rpc.IConferenceService
import network.lapis.cloud.shared.rpc.IConferenceStreamingService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import kotlin.time.Clock

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 1 -- LiveKit-backed room list, plain create-room form,
 * and the real, working in-call view: a responsive CSS-grid video tile grid (name badge + inferred
 * mic/camera state per tile, a dedicated full-width stage for a `screen_share`-sourced track), a
 * persistent control bar (Mikrofon/Kamera/Bildschirm teilen/Chat/Verlassen), a collapsible chat side
 * panel, a live participant roster, and moderator-only "Für alle beenden"/"Entfernen" actions. See
 * [IConferenceService] class KDoc for the full role/gating model this screen surfaces, and
 * `network.lapis.cloud.client.livekit.LiveKitRoomSession` for the LiveKit interop this screen drives.
 *
 * **Still open from the Wave 1 design review** (root `CLAUDE.md` "UI/UX-Design-Team", decisions
 * D1-D11) -- this step builds out the IN-CALL view, not the lobby/pre-flight: D1's single-button
 * no-form creation flow ([renderLobby] below still asks for a title), D2's first-class camera/
 * microphone permission interstitial (device errors currently surface as a plain `guarded {}` toast,
 * see [enterCall]), D3's speaking-priority reflow for 13-25 participants (needs
 * `RoomEvent.ActiveSpeakersChanged`, not present in `LiveKitJs.kt`'s deliberately minimal external
 * surface), and D10's fully named `Idle -> RequestingMedia -> Connecting -> Connected -> Reconnecting
 * -> Disconnected/Ended` state machine (this step only distinguishes "not yet connected" and "ended",
 * see [enterCall]'s `guarded { session.connect(...) }` call -- `Reconnecting` has no event source yet,
 * since `RoomEvent.Reconnecting`/`.Reconnected` are not wired into `LiveKitRoomSession`'s callback
 * constructor). Each remains open work for a later step of this wave.
 *
 * **Per-tile mic/camera state is an honest approximation, not an exact LiveKit mute signal**: this
 * step infers "muted"/"camera off" purely from whether a subscribed audio/video track currently
 * exists for that identity ([ConferenceTileEntry.hasMic]/[ConferenceTileEntry.hasCamera], updated
 * from [LiveKitRoomSession]'s `onRemoteTrack`/`onRemoteTrackGone` callbacks) -- LiveKit's own
 * `TrackMuted`/`TrackUnmuted` events (which fire without unpublishing) are not wired into this
 * screen's [LiveKitRoomSession] callback surface. A participant who published-then-muted their mic
 * (rather than unpublishing) will still show as "Mikro an" here until a later step adds those events.
 * The LOCAL tile is exact, not an approximation -- its mic/camera badges are driven directly from the
 * `micEnabled`/`cameraEnabled` toggle state this screen itself owns.
 *
 * **Route/nav posture**: [Routes.CONFERENCE] uses `requireAuth`, not `requireRole` -- every method a
 * plain member needs to *reach* this screen at all ([IConferenceService.getAvailability]/
 * [IConferenceService.listActiveRooms]/[IConferenceService.createRoom]/[IConferenceService.joinRoom])
 * only calls `resolveCurrentMember(call)` + `requireActiveMembership` server-side, no `requireRole` --
 * same posture as [Routes.LTR_LEDGER]/[Routes.CROWDFUNDING]/[Routes.AUCTION]/[Routes.POLITICIANS], NOT
 * the Accounting UI wave's route-level `requireRole`. The narrower moderator-or-BOARD/ADMIN tier
 * ([IConferenceService.endRoom]/[IConferenceService.removeParticipant]) is gated inside this screen as
 * `canModerate`, exactly like those routes' own in-screen `canTreasury`/`canBoard`/`canAdmin` splits --
 * see [Routes.CONFERENCE] KDoc. This is a UX nicety on top of the server's real authority (the server
 * re-checks both calls independently) -- the same posture `DocumentsScreen.canManage` already
 * documents for its own domain.
 *
 * **The `getAvailability` gate**: unlike [IAuctionService.listAuctions] (which signals "disabled" via
 * a thrown [ConflictException] this screen would need to special-case, see `AuctionScreen.kt`'s own
 * file KDoc), [IConferenceService.getAvailability] never throws -- it is the ONE call this screen
 * makes outside a room, and an `enabled == false` result renders an explicit German notice panel in
 * place of the whole lobby ([renderDisabledPanel]), never a toast and never a failing call.
 *
 * **Chat's trust boundary is enforced entirely by [LiveKitRoomSession]**, not here -- by the time
 * [renderConferenceScreen] receives an inbound chat message via its `onChat` callback, the sender
 * identity already came from LiveKit's own SDK-verified `RemoteParticipant.identity`/`.name`, never
 * from the untrusted payload (see [ConferenceChatMessage] KDoc and `LiveKitRoomSession` KDoc "Chat
 * trust boundary"). Messages are rendered via KVision's default escaped `content` (never `rich =
 * true`), same posture every other screen in this app already takes.
 *
 * **Cleanup**: [Room.addAfterDestroyHook]/`window.addEventListener("beforeunload", ...)` are the ONLY
 * two hooks this codebase has for "the user is leaving this screen/tab" -- `Routing.kt`'s `show()`
 * just calls `pageContainer.removeAll()`, with no unmount callback of its own (see that file's KDoc).
 * Without one of these, the camera/microphone stay active after navigating away. The `beforeunload`
 * listener is explicitly removed again inside the destroy hook so repeated visits to this screen in
 * one page session don't accumulate stale listeners.
 *
 * ## V1.0 Videokonferenzen (Kleinsitzung), Wave 2 "Aufzeichnung" -- recording UI
 *
 * This step ran the mandatory UI/UX-Design-Team review (root `CLAUDE.md` "UI/UX-Design-Team")
 * BEFORE writing any code -- see the wave's own design-review document for the full discussion.
 * Load-bearing decisions (D-numbers match that review):
 *
 * - **D1/D2 -- the persistent recording badge is driven by [network.lapis.cloud.client.livekit.LiveKitRoomSession]'s
 *   `onRecordingStatusChanged` callback, i.e. by LiveKit's OWN [network.lapis.cloud.client.livekit.Room.isRecording],
 *   never by polling [IConferenceRecordingService].** Server-authoritative, pushed instantly to every
 *   connected client, correct for late joiners (D4), unspoofable by a participant. Rendered via the
 *   shared [statusBadge] grammar (`text-bg-danger` pill) -- chrome-level (built right after the
 *   moderator row, before the screen-share stage / video grid), so a layout-mode switch can never
 *   make it disappear.
 * - **D3/D4 -- the one-time notice banner** ("Diese Besprechung wird ab jetzt aufgezeichnet.") fires
 *   on the SAME `onRecordingStatusChanged` signal, both on a live `false -> true` transition AND on
 *   the synchronous late-joiner seed [network.lapis.cloud.client.livekit.LiveKitRoomSession] performs
 *   right after `connect()` resolves (see that class's KDoc "Recording signal"). Non-blocking,
 *   dual-action ("Verstanden" / "Besprechung verlassen", no auto-fade, no silent timeout) --
 *   deliberately NOT a click-gate modal, which would hide the speaker's video at the exact moment
 *   recording starts. "Besprechung verlassen" delegates to the real leave button's own DOM element
 *   (`leaveButton.getElement()?.click()`) rather than duplicating the leave flow, so there is exactly
 *   ONE place that implements "leave this call".
 * - **D5 -- no audit trail of "Verstanden".** Explicitly not logged (see design review D5
 *   rationale) -- the persistent badge is what establishes notice was structurally available.
 * - **D6 -- control placement/weight.** "Aufzeichnung starten"/"-beenden" lives in the SAME
 *   moderator row as "Für alle beenden" (never the personal Mikrofon/Kamera/Bildschirm/Chat/Verlassen
 *   bar, per Wave 1's own D5/D6 Tesler precedent), styled [ButtonStyle.WARNING] -- one step less
 *   alarming than "Für alle beenden"'s [ButtonStyle.OUTLINEDANGER], because starting a recording is
 *   *disclosive*, not *destructive*.
 * - **D7/D8 -- confirm dialogs.** [startRecordingConfirmDialog] matches the literal
 *   `PostalMailScreen.postalDispatchConfirmDialog` bar: named primary button ("Aufzeichnung jetzt
 *   starten", never "OK"), states irreversibility AND immediate room-wide visibility, and the
 *   [DocumentAccessLevel] select carries an explanatory line making the BOARD_ONLY default's
 *   consequence concrete. [stopRecordingConfirmDialog] is real but lighter, matching
 *   `removeParticipantConfirmDialog`'s "no danger-red body text, still a genuine confirm step" tier.
 * - **D9 -- the Lobby's "Aufzeichnungen" section** is built entirely in `ConferenceRecordingsPanel.kt`,
 *   wired into [renderLobby] -- recordings OUTLIVE their room, so they must stay reachable long after
 *   the room itself is gone. See that file's own KDoc for D9/D11/D12/D13.
 * - **D10 -- status copy**: [conferenceRecordingStatusLabel] is the ONE place this mapping exists;
 *   both this file and `ConferenceRecordingsPanel.kt` call it, never hand-write the German text.
 * - **D14 -- availability gating is invisible, not disabled-and-confusing.** [refreshRecordingState]
 *   (inside [enterCall]) hides the moderator's recording button entirely when
 *   [IConferenceRecordingService.getRecordingAvailability] reports `enabled=false`, rather than
 *   showing a disabled dead button. The SAME gate is why this screen never calls
 *   `getActiveRecording`/`listRecordings` before checking availability first -- both throw
 *   [network.lapis.cloud.shared.rpc.ConflictException] when recording is unconfigured (see
 *   `ConferenceRecordingService.requireRecordingEnabled` server-side), which `guarded {}` would
 *   otherwise surface as a spurious toast on every Lobby load / every call entry.
 * - **D15 -- terminology lock**: "Aufzeichnung" everywhere (badge, banner, dialogs, Lobby section) --
 *   never "Aufnahme", never "Recording".
 * - **D16 -- accessibility**: the notice banner's real DOM element gets `role="alert"` (implicit
 *   `aria-live="assertive"`) via `addAfterInsertHook`, so a screen-reader user is told recording has
 *   started with the same immediacy as a sighted user sees the banner appear.
 *
 * **D11's partial-composition flag is a KNOWN, NOT-YET-CLOSED gap carried over from this wave's
 * storage/poller step** -- [ConferenceRecordingDto] has no `composedFromPartialTracks`-shaped field
 * as of this step, so a recording composed "from the survivors" after an egress timeout renders with
 * the identical "Bereit" badge as a clean one. This step's file list is client-UI-only and does not
 * touch the shared DTO/server poller; flagged here (per the design review's own D11 must-fix, Jobs'
 * final verdict) rather than silently worked around, so a later step closes it deliberately instead
 * of it being rediscovered as a surprise.
 *
 * **`document.title` prefix**: [conferenceRecordingDocumentTitle] prepends "● " while
 * `onRecordingStatusChanged` reports `true`, restored via the ORIGINAL title captured once at the
 * very top of [enterCall] (`baseDocumentTitle`) -- both on a live stop transition AND, explicitly, on
 * every path back to the Lobby ([returnToLobby]'s `originalTitle` parameter), since disconnecting
 * does not reliably fire a final `RecordingStatusChanged(false)` push.
 *
 * ## V1.0 Videokonferenzen (Kleinsitzung), Wave 3 "Externes Streaming" -- streaming UI + the Wave 2
 * badge fix
 *
 * Ran the mandatory UI/UX-Design-Team review (root `CLAUDE.md`) before writing code, same as Wave 2
 * -- see that wave's own review document, decisions D1-D14, Jobs' conditional-go verdict. Load-bearing
 * decisions:
 *
 * - **D8, launch-blocking -- the Wave 2 badge was LYING.** Live-verified finding: LiveKit sets
 *   `Room.isRecording` (the flag [onRecordingStatusChanged] mirrors) to `true` for ANY active egress,
 *   including a STREAMING-only one with no recording at all. The pre-Wave-3 badge trusted that raw
 *   boolean directly, so starting a stream alone would have shown a false "Aufzeichnung läuft" on
 *   every participant's screen -- a DSGVO-relevant false statement in exactly the surface Wave 2 built
 *   for legal transparency. **The fix**: [onRecordingStatusChanged] is now used PURELY as an instant
 *   REFRESH TRIGGER (see [onMediaStatusPush]) -- the badge itself always renders from SERVER state
 *   ([IConferenceRecordingService.getActiveRecording] + [IConferenceStreamingService.getActiveStream],
 *   via [refreshRecordingState]/[refreshStreamState]), never from the pushed boolean. See
 *   [conferenceStatusBadgeRows]/[conferenceMediaDocumentTitle].
 * - **D3 -- recording and streaming render as DISTINCT badge rows, never merged.** Merging "3
 *   Plattformen live" into one line with the recording badge would lose exactly the information (which
 *   platforms) the concept note's transparency requirement demands. [conferenceStatusBadgeRows]
 *   returns 0-2 rows; both render simultaneously, stacked, when both are active. Distinct glyphs
 *   ("●" recording, "◆" streaming) so the distinction does not rely on red-vs-red alone.
 * - **D5, must-mock-before-code -- recording and streaming controls are SPATIALLY SEPARATE groups**,
 *   each under its own small "Aufzeichnung:"/"Live-Stream:" sub-header, never a shared row or dropdown
 *   -- see [recordingControlsRowRef]/[streamingControlsRowRef]. Every confirm dialog restates the noun
 *   it acts on ("Stream **beenden**?", never a bare "Wirklich beenden?") -- see
 *   [pauseStreamConfirmDialog]/[resumeStreamConfirmDialog]/[stopStreamConfirmDialog]. "Für alle
 *   beenden" (ending the whole meeting) stays in its own pre-existing `moderatorRow`, spatially
 *   separated from both media control groups, per Wave 1's own precedent.
 * - **D2 -- no re-typing on the start-stream dialog.** [startStreamDialog]'s destination checklist
 *   doubles as the confirm surface itself: a live summary line ([conferenceStreamStartSummary]) names
 *   the SELECTED destinations by LABEL (never url/key) and restates irrevocability in plain German as
 *   the selection changes, and the primary button reads "Jetzt live gehen" -- never "OK"/"Bestätigen".
 * - **The mandatory secret-ballot Hinweis** ([CONFERENCE_STREAM_SECRET_BALLOT_HINWEIS]) is static,
 *   unconditional text in [startStreamDialog] -- no UI copy anywhere claims automatic pause protection
 *   exists, per [IConferenceStreamingService] KDoc "No automatic stream pause during secret ballots".
 * - **D7 -- honest per-destination status, never a binary "streaming: yes".**
 *   [ConferenceStreamTargetStatusDto.status] renders as three distinct states via
 *   [conferenceStreamTargetStatusLabel] ("Verbindung wird hergestellt…"/"Live"/"Beendet"/
 *   "Fehlgeschlagen"), one row per destination ([updateStreamTargetsPanel]) -- a partial failure (one
 *   of three platforms down) stays visible instead of being averaged into one aggregate signal.
 *   [pollInFlightStreamStatus] re-polls [IConferenceStreamingService.getActiveStream] while any target
 *   is still `PENDING` (the real ~12s async LiveKit-connect window, live-verified, see
 *   [IConferenceStreamingService.startStream] KDoc), or the top-level status is `STARTING`/`STOPPING`
 *   -- see [conferenceStreamNeedsPoll].
 * - **D6 -- pause is honestly stop+restart, never implied seamless.** [pauseStreamConfirmDialog]'s
 *   copy states plainly that the platform sees an interruption and may end the broadcast; resume shows
 *   a real button-disabled "in flight" state while the new egress connects (rule 2, double-submit
 *   protection), never a silent instant jump back to "Live-Stream läuft".
 * - **D11 -- unconfigured is invisible, not disabled.** [refreshStreamState] hides
 *   [streamingControlsRowRef] entirely when [IConferenceStreamingService.getStreamingAvailability]
 *   reports `enabled=false`, and never calls [IConferenceStreamingService.getActiveStream]/
 *   `.listStreamTargets` before that check (both throw `ConflictException` when streaming is
 *   unconfigured) -- exactly Wave 2's own `refreshRecordingState`/D14 posture, independently applied.
 * - **Terminology lock**: "Live-Stream"/"Stream-Ziel"/"Stream-Schlüssel" everywhere, matching
 *   [IConferenceStreamingService]'s own vocabulary -- never "Broadcast", never "Übertragung" as a
 *   button label (only as body prose).
 *
 * Credential material (`rtmpUrl`, the stream key) never appears anywhere in this file --
 * [ConferenceStreamTargetDto] (the ONLY destination shape this screen ever receives, via
 * [IConferenceStreamingService.listStreamTargets]) carries no such fields at all; see
 * `ConferenceStreamDestinationsScreen.kt` for the ADMIN-only credential CRUD surface, which is a
 * completely separate screen/route.
 */
fun renderConferenceScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 960.px
            marginTop = 24.px
        }
    root.h1("Videokonferenz")
    val statusLine = root.div("Wird geladen …") { addCssClasses("text-muted small") }
    val lobbyPanel = root.vPanel(spacing = 10)
    val callPanel = root.vPanel(spacing = 10)
    callPanel.hide()

    var activeSession: LiveKitRoomSession? = null
    val setActiveSession: (LiveKitRoomSession?) -> Unit = { activeSession = it }

    val beforeUnloadListener: (Event) -> Unit = {
        AppScope.launch { runCatching { activeSession?.disconnect() } }
    }
    window.addEventListener("beforeunload", beforeUnloadListener)
    root.addAfterDestroyHook {
        window.removeEventListener("beforeunload", beforeUnloadListener)
        AppScope.launch { runCatching { activeSession?.disconnect() } }
    }

    AppScope.launch {
        val availability =
            guarded { rpcService<IConferenceService>().getAvailability() }
                ?: run {
                    statusLine.content = "Videokonferenzen konnten nicht geladen werden."
                    return@launch
                }
        statusLine.hide()
        if (!availability.enabled) {
            renderDisabledPanel(lobbyPanel)
            return@launch
        }
        renderLobby(lobbyPanel, callPanel, setActiveSession)
    }
}

/**
 * Jobs' design-review verdict, D-item "Handle the getAvailability enabled=false case": an explicit
 * German notice panel, not a failing call and not a bare muted text line -- see file KDoc "The
 * `getAvailability` gate". Text matches the wave plan's own literal copy.
 */
private fun renderDisabledPanel(panel: SimplePanel) {
    panel.removeAll()
    panel.show()
    val alert = panel.div { addCssClasses("alert alert-secondary") }
    alert.div("Videokonferenz ist auf diesem Server nicht konfiguriert.") { addCssClass("fw-bold") }
    alert.div("Bitte wenden Sie sich an Ihre Administration, falls Sie diese Funktion benötigen.") {
        addCssClasses("small text-muted mb-0")
    }
}

// ================================================================================================
// Lobby: create-room form + active-room list
// ================================================================================================

private fun renderLobby(
    lobbyPanel: SimplePanel,
    callPanel: SimplePanel,
    setActiveSession: (LiveKitRoomSession?) -> Unit,
) {
    lobbyPanel.removeAll()
    lobbyPanel.show()

    lobbyPanel.h2("Neue Besprechung starten")
    val createPanel = lobbyPanel.vPanel(spacing = 6)
    val titleInput = createPanel.text(label = "Titel (optional)")
    val createButton = createPanel.button("Besprechung erstellen", style = ButtonStyle.PRIMARY)

    lobbyPanel.h2("Aktive Besprechungen")
    val refreshRow = lobbyPanel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val refreshButton = refreshRow.button("Aktualisieren", style = ButtonStyle.OUTLINESECONDARY)
    val roomsPanel = lobbyPanel.vPanel(spacing = 8)

    // Wave 2 "Aufzeichnung", D9: recordings OUTLIVE their room, so this section belongs in the
    // LOBBY, not only inside a live call -- see ConferenceRecordingsPanel.kt's own file KDoc.
    val recordingsSection = lobbyPanel.vPanel(spacing = 8)

    fun loadRooms() {
        roomsPanel.removeAll()
        roomsPanel.div("Wird geladen …") { addCssClasses("text-muted small") }
        AppScope.launch {
            val rooms = guarded { rpcService<IConferenceService>().listActiveRooms() } ?: return@launch
            roomsPanel.removeAll()
            if (rooms.isEmpty()) {
                roomsPanel.div("Derzeit keine aktive Besprechung.") { addCssClasses("text-muted small") }
            } else {
                rooms.forEach { room ->
                    renderRoomCard(roomsPanel, room, lobbyPanel, callPanel, setActiveSession) {
                        loadRooms()
                        renderConferenceRecordingsPanel(recordingsSection)
                    }
                }
            }
        }
    }

    refreshButton.onClick {
        loadRooms()
        renderConferenceRecordingsPanel(recordingsSection)
    }
    renderConferenceRecordingsPanel(recordingsSection)

    createButton.onClick {
        val title =
            titleInput.value
                .orEmpty()
                .trim()
                .ifBlank { "Besprechung" }
        createButton.disabled = true
        AppScope.launch {
            val result = guarded { rpcService<IConferenceService>().createRoom(ConferenceRoomInput(title = title)) }
            createButton.disabled = false
            if (result != null) {
                notifySuccess("Besprechung \"${result.title}\" erstellt.")
                titleInput.value = null
                loadRooms()
            }
        }
    }

    loadRooms()
}

private fun renderRoomCard(
    panel: SimplePanel,
    room: ConferenceRoomDto,
    lobbyPanel: SimplePanel,
    callPanel: SimplePanel,
    setActiveSession: (LiveKitRoomSession?) -> Unit,
    onReturnedToLobby: () -> Unit,
) {
    val card = panel.hPanel(spacing = 8) { addCssClasses("border rounded p-2 align-items-center flex-wrap") }
    val infoCell = card.vPanel(spacing = 2) { addCssClasses("flex-grow-1") }
    infoCell.div(room.title) { addCssClasses("fw-bold") }
    infoCell.div("Erstellt von ${room.createdByDisplayName} · ${room.liveParticipantCount} Teilnehmende") {
        addCssClasses("text-muted small")
    }
    if (room.myRole == ConferenceRole.MODERATOR) {
        infoCell.statusBadge("Sie sind Moderator", "primary")
    }
    val joinButton = card.button("Beitreten", style = ButtonStyle.PRIMARY)
    joinButton.onClick {
        joinButton.disabled = true
        AppScope.launch {
            val token = guarded { rpcService<IConferenceService>().joinRoom(room.id) }
            joinButton.disabled = false
            if (token != null) {
                enterCall(room, token, lobbyPanel, callPanel, setActiveSession, onReturnedToLobby)
            }
        }
    }
}

// ================================================================================================
// In-call view: video grid + control bar + chat + roster + moderator actions
// ================================================================================================

/** One video tile's raw-DOM structure -- see [enterCall] "Raw DOM for the tile grid" for why this
 * is plain `HTMLElement` manipulation rather than KVision widgets. */
private class ConferenceTileEntry(
    val identity: String,
    var displayName: String,
    val isLocal: Boolean,
    val element: HTMLElement,
    val mediaSlot: HTMLElement,
    val nameBadge: HTMLElement,
    val micBadge: HTMLElement,
    var hasCamera: Boolean = false,
    var hasMic: Boolean = false,
)

private fun enterCall(
    room: ConferenceRoomDto,
    joinToken: ConferenceJoinTokenDto,
    lobbyPanel: SimplePanel,
    callPanel: SimplePanel,
    setActiveSession: (LiveKitRoomSession?) -> Unit,
    onReturnedToLobby: () -> Unit,
) {
    lobbyPanel.hide()
    callPanel.removeAll()
    callPanel.show()

    // Wave 2 "Aufzeichnung": captured BEFORE anything can prefix it, restored on every path back to
    // the Lobby -- see file KDoc "`document.title` prefix".
    val baseDocumentTitle = document.title

    val localMemberId = AppState.session?.memberId
    // D-item "moderator-only actions": a UX nicety over the server's own re-checked authority (see
    // file KDoc "Route/nav posture") -- compares the CALLER's own member id to the room's creator,
    // OR a global BOARD/ADMIN escalation, exactly matching `IConferenceService.endRoom`/
    // `.removeParticipant`'s server-side gate (see [IConferenceService] KDoc "Two-tier role model").
    val canModerate =
        conferenceIsModerator(
            localMemberId = localMemberId,
            creatorMemberId = room.createdByMemberId,
            isBoardOrAdmin = AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN),
        )

    // Raw DOM for the tile grid + screen-share stage: KVision renders through snabbdom, which
    // discards `<video>`/`<audio>` elements appended manually into a widget it later re-renders (see
    // this file's original plan). The fix, used throughout this function: grab each KVision `Div`
    // container's real element ONCE via `addAfterInsertHook`, then manage every child of THAT element
    // with plain `document.createElement`/`appendChild`/`removeChild` -- never call `removeAll()` or
    // re-render on `gridElement`/`stageElement` themselves.
    val tiles = LinkedHashMap<String, ConferenceTileEntry>()
    var gridElement: HTMLElement? = null
    var stageElement: HTMLElement? = null
    var activeScreenShare: Pair<String, Track>? = null
    var leftCall = false
    var micEnabled = true
    var cameraEnabled = true
    var screenShareEnabled = false
    var chatOpen = false
    var unreadChatCount = 0

    callPanel.h2(room.title)
    callPanel.div(
        if (canModerate) "Sie sind Moderator dieser Besprechung." else "Sie nehmen als Teilnehmer teil.",
    ) { addCssClasses("text-muted small") }

    // --- Control bar (persistent, D5: never hover-only) -----------------------------------------
    val controlsRow = callPanel.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    val micButton = controlsRow.button("Mikrofon aus", style = ButtonStyle.OUTLINESECONDARY)
    val cameraButton = controlsRow.button("Kamera aus", style = ButtonStyle.OUTLINESECONDARY)
    val screenShareButton = controlsRow.button("Bildschirm teilen", style = ButtonStyle.OUTLINESECONDARY)
    val chatToggleButton = controlsRow.button("Chat", style = ButtonStyle.OUTLINESECONDARY)
    val leaveButton = controlsRow.button("Verlassen", style = ButtonStyle.SECONDARY)
    leaveButton.addCssClass("ms-2")

    // D5/D6: "end for everyone" gets its own, spatially separate row -- never adjacent to "Verlassen"
    // (Tesler: near-identical destructive actions placed next to each other is a classic slip-inducing
    // layout). Not rendered at all for a plain participant, same "don't tease an action the server
    // will reject" posture `AuctionScreen.kt`'s own ADMIN-only Verwaltung panel documents. Wave 3, D5:
    // this row stays reserved for "Für alle beenden" ONLY -- recording/streaming controls each get
    // their OWN, further spatially separate row below (see [recordingControlsRowRef]/
    // [streamingControlsRowRef]), never sharing this one.
    val endButton =
        if (canModerate) {
            val moderatorRow = callPanel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
            moderatorRow.div("Moderator:") { addCssClasses("text-muted small") }
            moderatorRow.button("Für alle beenden", style = ButtonStyle.OUTLINEDANGER)
        } else {
            null
        }

    // --- Recording + streaming indicator/controls (Wave 2 "Aufzeichnung" + Wave 3 "Externes
    // Streaming", see file KDoc for the full D-item list) -- chrome-level, built right after the
    // moderator row and BEFORE the screen-share stage / video grid, so a layout-mode switch can
    // never make either badge disappear (design review D1, reconfirmed Wave 3). Everything here
    // must be declared BEFORE `session = LiveKitRoomSession(...)` below, since
    // `onRecordingStatusChanged` closes over it (Wave 3: now purely as a REFRESH TRIGGER, see
    // [onMediaStatusPush] and file KDoc "D8") -- see file KDoc's own reasoning on local-declaration
    // ordering; conversely `leaveButton`'s real DOM element (already built above, in the control
    // bar) is used instead of the `session`-dependent leave flow (declared further down) for
    // exactly the same reason, see [bannerLeaveButton]/[streamBannerLeaveButton] below.
    var recordingAvailable = false
    var activeRecordingDto: ConferenceRecordingDto? = null
    var recordButton: Button? = null
    var recordingBannerAcknowledged = false

    var streamingAvailable = false
    var streamMaxDestinations = 3
    var activeStreamDto: ConferenceStreamDto? = null
    var streamStartButton: Button? = null
    var streamPauseButton: Button? = null
    var streamResumeButton: Button? = null
    var streamStopButton: Button? = null
    var streamBannerAcknowledged = false

    // D5: recording and streaming get their own, spatially separate control groups -- never a
    // shared row, never a shared dropdown -- each with a small labeled sub-header, mirroring the
    // pre-existing `moderatorRow`'s own "Moderator:" label pattern. Hidden/shown per-feature by
    // [refreshRecordingState]/[refreshStreamState] once availability is known (D11).
    var recordingControlsRowRef: SimplePanel? = null
    var streamingControlsRowRef: SimplePanel? = null
    if (canModerate) {
        val recordingRow = callPanel.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
        recordingRow.div("Aufzeichnung:") { addCssClasses("text-muted small") }
        recordingControlsRowRef = recordingRow

        val streamingRow = callPanel.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
        streamingRow.div("Live-Stream:") { addCssClasses("text-muted small") }
        streamingControlsRowRef = streamingRow
    }

    // D8/D3: a combined container for 0-2 badge rows (recording/streaming render as DISTINCT rows,
    // never merged), so the fix for finding 7 (LiveKit's active_recording flag is true for a
    // streaming-only egress too) has somewhere honest to render -- see [conferenceStatusBadgeRows].
    val statusBadgesPanel = callPanel.vPanel(spacing = 4)
    statusBadgesPanel.hide()
    val recordingDetailLine = callPanel.div { addCssClasses("text-muted small") }
    recordingDetailLine.hide()
    val streamDetailLine = callPanel.div { addCssClasses("text-muted small") }
    streamDetailLine.hide()
    // D7: one row per destination, honest interim/terminal states -- see [updateStreamTargetsPanel].
    val streamTargetsPanel = callPanel.vPanel(spacing = 2) { addCssClasses("ms-2") }
    streamTargetsPanel.hide()

    // D3/D4: non-blocking, dual-action, no auto-fade, no silent timeout -- see file KDoc.
    val recordingBanner = callPanel.vPanel(spacing = 6) { addCssClasses("border border-danger rounded p-2") }
    recordingBanner.hide()
    // D16: a screen-reader user must be told with the same immediacy a sighted user sees the banner.
    recordingBanner.addAfterInsertHook { vnode -> (vnode.elm as? HTMLElement)?.setAttribute("role", "alert") }
    recordingBanner.div(CONFERENCE_RECORDING_BANNER_TEXT) { addCssClass("fw-bold") }
    val recordingBannerButtons = recordingBanner.hPanel(spacing = 8) { addCssClasses("flex-wrap") }
    val recordingBannerAckButton = recordingBannerButtons.button("Verstanden", style = ButtonStyle.PRIMARY)
    val recordingBannerLeaveButton = recordingBannerButtons.button("Besprechung verlassen", style = ButtonStyle.OUTLINESECONDARY)
    recordingBannerAckButton.onClick {
        recordingBannerAcknowledged = true
        recordingBanner.hide()
    }
    // Delegates to the REAL leave button's own DOM element rather than duplicating the leave flow
    // (which lives on `session`, declared further down this function) -- see block comment above.
    recordingBannerLeaveButton.onClick { leaveButton.getElement()?.click() }

    // Wave 3 -- same shape/discipline as the recording banner above, own text/own acknowledge state
    // (D3: the two facts -- "is being recorded" / "is being streamed" -- must stay independently
    // dismissable, never coupled).
    val streamBanner = callPanel.vPanel(spacing = 6) { addCssClasses("border border-danger rounded p-2") }
    streamBanner.hide()
    streamBanner.addAfterInsertHook { vnode -> (vnode.elm as? HTMLElement)?.setAttribute("role", "alert") }
    streamBanner.div(CONFERENCE_STREAM_BANNER_TEXT) { addCssClass("fw-bold") }
    val streamBannerButtons = streamBanner.hPanel(spacing = 8) { addCssClasses("flex-wrap") }
    val streamBannerAckButton = streamBannerButtons.button("Verstanden", style = ButtonStyle.PRIMARY)
    val streamBannerLeaveButton = streamBannerButtons.button("Besprechung verlassen", style = ButtonStyle.OUTLINESECONDARY)
    streamBannerAckButton.onClick {
        streamBannerAcknowledged = true
        streamBanner.hide()
    }
    streamBannerLeaveButton.onClick { leaveButton.getElement()?.click() }

    // D8: the ONE place either badge row or the document.title prefix is ever rendered -- always
    // from `activeRecordingDto`/`activeStreamDto` (server state), never from a raw LiveKit push
    // boolean. Declared first so every updater below can end by calling it.
    fun updateStatusBadgesAndTitle() {
        statusBadgesPanel.removeAll()
        val rows = conferenceStatusBadgeRows(activeRecordingDto, activeStreamDto)
        if (rows.isEmpty()) {
            statusBadgesPanel.hide()
        } else {
            statusBadgesPanel.show()
            rows.forEach { row -> statusBadgesPanel.statusBadge(row.text, row.color) }
        }
        document.title = conferenceMediaDocumentTitle(baseDocumentTitle, activeRecordingDto != null, activeStreamDto != null)
    }

    fun updateRecordingDetailLine() {
        val active = activeRecordingDto
        if (active != null) {
            recordingDetailLine.content = conferenceRecordingStartedLabel(active.startedByDisplayName, active.startedAt)
            recordingDetailLine.show()
        } else {
            recordingDetailLine.hide()
        }
        updateStatusBadgesAndTitle()
    }

    /**
     * Live-verification fix (2026-08-09, Wave 3 verification step) -- while [activeStreamDto] is
     * [ConferenceStreamStatus.PAUSED], the per-target `PENDING`/`ACTIVE`/`FINISHED`/`FAILED` chips
     * are hidden rather than rendered from [ConferenceStreamDto.targets]' last-known values. Found
     * live: [StreamPoller][network.lapis.cloud.server.conference.StreamPoller]'s own `handlePaused`
     * deliberately does NOT touch `conference_stream_target` rows while paused (there is no egress
     * left to poll, see that method's own KDoc) -- so every target row keeps reporting whatever
     * status it had the instant BEFORE `pauseStream` stopped the egress (typically `ACTIVE`, i.e.
     * "Live"). Rendering that stale value verbatim produced a real, reproducible bug: pausing a
     * genuinely live two-destination stream against the real Colima stack left both per-destination
     * chips reading "Live" indefinitely, even though `rtmp-sink`'s own logs showed a real RTMP EOF
     * on both connections. The honest fix is the same "we don't know right now" discipline D7 already
     * established for the `PENDING` interim state -- while paused there genuinely is no live
     * per-target signal to show, so none is shown; the top badge's "ist unterbrochen" (see
     * [conferenceStreamBadgeVerbPhrase]) and the moderator's "Stream fortsetzen" button already
     * communicate the state without a stale, contradicting "Live" chip underneath.
     */
    fun updateStreamTargetsPanel() {
        streamTargetsPanel.removeAll()
        val active = activeStreamDto
        val targets = active?.targets.orEmpty()
        if (targets.isEmpty() || active?.status == ConferenceStreamStatus.PAUSED) {
            streamTargetsPanel.hide()
        } else {
            streamTargetsPanel.show()
            targets.forEach { target ->
                val row = streamTargetsPanel.hPanel(spacing = 6) { addCssClasses("align-items-center flex-wrap") }
                row.statusBadge(conferenceStreamTargetStatusLabel(target.status), conferenceStreamTargetStatusColor(target.status))
                row.div(target.label) { addCssClasses("small") }
                target.failureReason?.let { reason -> row.div(reason) { addCssClasses("text-danger small") } }
            }
        }
    }

    fun updateStreamDetailLine() {
        val active = activeStreamDto
        if (active != null) {
            streamDetailLine.content = conferenceStreamStartedLabel(active.startedByDisplayName, active.startedAt, active.layout)
            streamDetailLine.show()
        } else {
            streamDetailLine.hide()
        }
        updateStreamTargetsPanel()
        updateStatusBadgesAndTitle()
    }

    fun updateRecordButtonLabel() {
        val btn = recordButton ?: return
        when (activeRecordingDto?.status) {
            ConferenceRecordingStatus.RECORDING -> {
                btn.text = "Aufzeichnung beenden"
                btn.disabled = false
            }
            ConferenceRecordingStatus.STOPPING -> {
                btn.text = "Aufzeichnung wird beendet …"
                btn.disabled = true
            }
            ConferenceRecordingStatus.PROCESSING -> {
                // Review-round-1 fix (2026-08-09): PROCESSING can now reach this button too, via
                // `pollInFlightRecordingStatus` below -- without this branch it fell into the
                // `else` case and showed an ENABLED "Aufzeichnung starten" label whose click handler
                // then silently did nothing ([onRecordButtonClicked] only ever handles a `null` or
                // `RECORDING` active recording), a live-looking but dead button. Same disabled tier
                // as STOPPING.
                btn.text = "Aufzeichnung wird zusammengeführt …"
                btn.disabled = true
            }
            else -> {
                btn.text = "Aufzeichnung starten"
                btn.disabled = false
            }
        }
    }

    // Wave 3, D6: resume shows a real "in flight" transitional state (button-disabled while the
    // NEW egress connects, see [onStreamResumeClicked]) rather than jumping straight back to
    // steady-state "Live-Stream läuft" -- so this function only ever governs which of the four
    // buttons is VISIBLE per [ConferenceStreamStatus], never their disabled-while-in-flight state
    // (each click handler owns that directly around its own `guarded {}` call, rule 2).
    fun updateStreamButtonsVisibility() {
        val startBtn = streamStartButton
        val pauseBtn = streamPauseButton
        val resumeBtn = streamResumeButton
        val stopBtn = streamStopButton
        if (startBtn == null || pauseBtn == null || resumeBtn == null || stopBtn == null) return
        stopBtn.text = "Stream beenden"
        when (activeStreamDto?.status) {
            null, ConferenceStreamStatus.ENDED, ConferenceStreamStatus.FAILED -> {
                startBtn.show()
                startBtn.disabled = !conferenceStreamCanStart(canModerate, streamingAvailable, activeStreamDto)
                pauseBtn.hide()
                resumeBtn.hide()
                stopBtn.hide()
            }
            ConferenceStreamStatus.STARTING -> {
                startBtn.hide()
                pauseBtn.hide()
                resumeBtn.hide()
                stopBtn.show()
                stopBtn.disabled = false
            }
            ConferenceStreamStatus.LIVE -> {
                startBtn.hide()
                resumeBtn.hide()
                pauseBtn.show()
                pauseBtn.disabled = false
                stopBtn.show()
                stopBtn.disabled = false
            }
            ConferenceStreamStatus.PAUSED -> {
                startBtn.hide()
                pauseBtn.hide()
                resumeBtn.show()
                resumeBtn.disabled = false
                stopBtn.show()
                stopBtn.disabled = false
            }
            ConferenceStreamStatus.STOPPING -> {
                startBtn.hide()
                pauseBtn.hide()
                resumeBtn.hide()
                stopBtn.show()
                stopBtn.disabled = true
                stopBtn.text = "Stream wird beendet …"
            }
        }
    }

    fun onRecordButtonClicked() {
        val btn = recordButton ?: return
        val active = activeRecordingDto
        if (active == null) {
            if (!recordingCanStart(canModerate = canModerate, recordingAvailable = recordingAvailable, activeRecording = active)) return
            startRecordingConfirmDialog { accessLevel ->
                btn.disabled = true
                AppScope.launch {
                    val result = guarded { rpcService<IConferenceRecordingService>().startRecording(room.id, accessLevel) }
                    btn.disabled = false
                    // Rule: only update UI state once the guarded {} call's result confirms success --
                    // never optimistically before the RPC resolves.
                    if (result != null) {
                        activeRecordingDto = result
                        notifySuccess("Aufzeichnung gestartet.")
                        updateRecordingDetailLine()
                        updateRecordButtonLabel()
                    }
                }
            }
        } else if (active.status == ConferenceRecordingStatus.RECORDING) {
            stopRecordingConfirmDialog {
                btn.disabled = true
                AppScope.launch {
                    val result = guarded { rpcService<IConferenceRecordingService>().stopRecording(active.id) }
                    btn.disabled = false
                    if (result != null) {
                        activeRecordingDto = result
                        notifySuccess("Aufzeichnung wird beendet.")
                        updateRecordingDetailLine()
                        updateRecordButtonLabel()
                    }
                }
            }
        }
    }

    // Wave 3: opens [startStreamDialog] -- the destination checklist doubles as the confirm surface
    // itself (D2, no re-typing). `listStreamTargets` is only ever called from here, after
    // `streamingAvailable`/`canModerate` are already known true (the button is disabled/hidden
    // otherwise, see [updateStreamButtonsVisibility]/[conferenceStreamCanStart]).
    fun onStreamStartClicked() {
        if (!conferenceStreamCanStart(canModerate, streamingAvailable, activeStreamDto)) return
        val startBtn = streamStartButton ?: return
        startBtn.disabled = true
        AppScope.launch {
            val targets = guarded { rpcService<IConferenceStreamingService>().listStreamTargets() }
            startBtn.disabled = false
            if (targets.isNullOrEmpty()) {
                notifyError(
                    "Keine freigegebenen Stream-Ziele vorhanden -- bitte eine Administratorin oder " +
                        "einen Administrator kontaktieren.",
                )
                return@launch
            }
            val participantOptions =
                tiles.values.map { entry -> entry.identity to (entry.displayName + if (entry.isLocal) " (Sie)" else "") }
            startStreamDialog(
                targets,
                streamMaxDestinations,
                participantOptions,
            ) { destinationIds, layout, latencyMode, participantIdentity ->
                startBtn.disabled = true
                AppScope.launch {
                    val result =
                        guarded {
                            rpcService<IConferenceStreamingService>().startStream(
                                room.id,
                                destinationIds,
                                layout,
                                latencyMode,
                                participantIdentity,
                            )
                        }
                    startBtn.disabled = false
                    // Rule: only update UI state once the guarded {} call's result confirms success --
                    // never optimistically before the RPC resolves.
                    if (result != null) {
                        val wasStreaming = activeStreamDto != null
                        activeStreamDto = result
                        updateStreamDetailLine()
                        updateStreamButtonsVisibility()
                        // The actor's own action is a deterministic "streaming just started" signal --
                        // shown immediately rather than waiting for the LiveKit push (which, per finding
                        // 7, WILL also arrive, but timing is not guaranteed, see file KDoc "D8").
                        if (!wasStreaming && !streamBannerAcknowledged) streamBanner.show()
                        notifySuccess("Live-Stream wird gestartet.")
                    }
                }
            }
        }
    }

    fun onStreamPauseClicked() {
        val stream = activeStreamDto ?: return
        val labels = stream.targets.joinToString(", ") { it.label }
        pauseStreamConfirmDialog(labels) {
            val btn = streamPauseButton ?: return@pauseStreamConfirmDialog
            btn.disabled = true
            AppScope.launch {
                val result = guarded { rpcService<IConferenceStreamingService>().pauseStream(stream.id) }
                btn.disabled = false
                if (result != null) {
                    activeStreamDto = result
                    updateStreamDetailLine()
                    updateStreamButtonsVisibility()
                    notifyInfo("Stream unterbrochen.")
                }
            }
        }
    }

    fun onStreamResumeClicked() {
        val stream = activeStreamDto ?: return
        resumeStreamConfirmDialog {
            val btn = streamResumeButton ?: return@resumeStreamConfirmDialog
            // D6: a real, visible "in flight" state while the NEW egress connects -- never an
            // instant, silently-optimistic jump back to "Live-Stream läuft".
            btn.disabled = true
            btn.text = "Stream wird fortgesetzt …"
            AppScope.launch {
                val result = guarded { rpcService<IConferenceStreamingService>().resumeStream(stream.id) }
                btn.disabled = false
                btn.text = "Stream fortsetzen"
                if (result != null) {
                    activeStreamDto = result
                    updateStreamDetailLine()
                    updateStreamButtonsVisibility()
                    notifySuccess("Stream wird fortgesetzt …")
                }
            }
        }
    }

    fun onStreamStopClicked() {
        val stream = activeStreamDto ?: return
        val labels = stream.targets.joinToString(", ") { it.label }
        stopStreamConfirmDialog(labels) {
            val btn = streamStopButton ?: return@stopStreamConfirmDialog
            btn.disabled = true
            AppScope.launch {
                val result = guarded { rpcService<IConferenceStreamingService>().stopStream(stream.id) }
                btn.disabled = false
                if (result != null) {
                    activeStreamDto =
                        if (result.status == ConferenceStreamStatus.ENDED || result.status == ConferenceStreamStatus.FAILED) {
                            null
                        } else {
                            result
                        }
                    updateStreamDetailLine()
                    updateStreamButtonsVisibility()
                    if (activeStreamDto == null) {
                        streamBanner.hide()
                        streamBannerAcknowledged = false
                    }
                    notifySuccess("Live-Stream wird beendet.")
                }
            }
        }
    }

    fun ensureRecordButton() {
        val existing = recordButton
        if (existing != null) {
            // Re-shows a button that a PREVIOUS refresh hid because availability had flipped false in
            // between (e.g. a transient LiveKit/ffmpeg hiccup) -- creating it once and never touching
            // visibility again would otherwise strand it hidden forever once that happens.
            existing.show()
            return
        }
        val row = recordingControlsRowRef ?: return
        val btn = row.button("Aufzeichnung starten", style = ButtonStyle.WARNING)
        btn.onClick { onRecordButtonClicked() }
        recordButton = btn
    }

    // Wave 3: D5's own dedicated control group -- see [recordingControlsRowRef]/[ensureRecordButton]
    // sibling reasoning. All four buttons are created together (their VISIBILITY, not existence, is
    // what [updateStreamButtonsVisibility] governs per [ConferenceStreamStatus]).
    fun ensureStreamControls() {
        if (streamStartButton != null) {
            updateStreamButtonsVisibility()
            return
        }
        val row = streamingControlsRowRef ?: return
        val startBtn = row.button("Live-Stream starten …", style = ButtonStyle.WARNING)
        startBtn.onClick { onStreamStartClicked() }
        streamStartButton = startBtn

        val pauseBtn = row.button("Stream unterbrechen", style = ButtonStyle.OUTLINEWARNING)
        pauseBtn.onClick { onStreamPauseClicked() }
        streamPauseButton = pauseBtn

        val resumeBtn = row.button("Stream fortsetzen", style = ButtonStyle.WARNING)
        resumeBtn.onClick { onStreamResumeClicked() }
        streamResumeButton = resumeBtn

        val stopBtn = row.button("Stream beenden", style = ButtonStyle.OUTLINEDANGER)
        stopBtn.onClick { onStreamStopClicked() }
        streamStopButton = stopBtn

        updateStreamButtonsVisibility()
    }

    // D14/D11: invisible, not disabled-and-confusing, when unconfigured -- and the reason
    // getActiveRecording is never called before this check: it THROWS ConflictException when
    // recording is unconfigured server-side (see file KDoc "D14"). Also hides/shows the WHOLE
    // "Aufzeichnung:" control group ([recordingControlsRowRef]), not just the button, so a plain
    // participant (for whom that row was never created, `canModerate == false`) is unaffected.
    suspend fun refreshRecordingState() {
        val availability = guarded { rpcService<IConferenceRecordingService>().getRecordingAvailability() }
        recordingAvailable = availability?.enabled == true
        if (canModerate && recordingAvailable) {
            recordingControlsRowRef?.show()
            ensureRecordButton()
        } else {
            recordingControlsRowRef?.hide()
            recordButton?.hide()
        }
        if (!recordingAvailable) {
            activeRecordingDto = null
            updateRecordingDetailLine()
            return
        }
        // Role: MEMBER+ -- every participant, not only the moderator, may see who is recording and
        // since when (see IConferenceRecordingService.getActiveRecording KDoc "everyone in the room
        // has a legal right to know").
        val recordings = guarded { rpcService<IConferenceRecordingService>().getActiveRecording(room.id) }
        activeRecordingDto = recordings?.singleOrNull()
        updateRecordingDetailLine()
        updateRecordButtonLabel()
    }

    // Wave 3 -- mirrors [refreshRecordingState] exactly, for the independent streaming availability
    // gate (D11): [IConferenceStreamingService.getStreamingAvailability] never throws, but
    // `getActiveStream`/`listStreamTargets` DO throw `ConflictException` when streaming is
    // unconfigured -- so this function checks availability FIRST, same discipline.
    suspend fun refreshStreamState() {
        val availability = guarded { rpcService<IConferenceStreamingService>().getStreamingAvailability() }
        streamingAvailable = availability?.enabled == true
        streamMaxDestinations = availability?.maxDestinations ?: streamMaxDestinations
        if (canModerate && streamingAvailable) {
            streamingControlsRowRef?.show()
            ensureStreamControls()
        } else {
            streamingControlsRowRef?.hide()
            streamStartButton?.hide()
            streamPauseButton?.hide()
            streamResumeButton?.hide()
            streamStopButton?.hide()
        }
        if (!streamingAvailable) {
            activeStreamDto = null
            updateStreamDetailLine()
            return
        }
        // Role: MEMBER+, AKTIV -- NEVER privilege-gated, same "everyone in the room has a legal
        // right to know" rule [IConferenceStreamingService.getActiveStream] KDoc establishes.
        val streams = guarded { rpcService<IConferenceStreamingService>().getActiveStream(room.id) }
        activeStreamDto = streams?.singleOrNull()
        updateStreamDetailLine()
        updateStreamButtonsVisibility()
    }

    /**
     * Wave 3, D8 -- the SOLE place [onRecordingStatusChanged]'s raw LiveKit push is acted on. The
     * pushed boolean is used PURELY as an instant "something changed, go check" trigger; it is
     * NEVER trusted as the badge's own source of truth (finding 7: LiveKit sets
     * `Room.isRecording` for a STREAMING-only egress too, which would otherwise show a false
     * "Aufzeichnung läuft" on every screen). Both [refreshRecordingState] and [refreshStreamState]
     * re-read SERVER state on every push -- including the synchronous "late-joiner seed" push
     * `LiveKitRoomSession.connect()` performs right after connecting (see that class's own KDoc),
     * which is also how a late joiner ends up seeing an already-accurate banner immediately (D4).
     *
     * The one-time notice banners fire on a LOCAL `null -> non-null` transition detected here
     * (before/after this function's own refresh calls) -- NOT on the raw pushed boolean, since one
     * push can be caused by either subsystem (or, rarely, both) changing at once.
     */
    suspend fun onMediaStatusPush() {
        val wasRecording = activeRecordingDto != null
        val wasStreaming = activeStreamDto != null
        refreshRecordingState()
        refreshStreamState()
        if (!wasRecording && activeRecordingDto != null && !recordingBannerAcknowledged) recordingBanner.show()
        if (wasRecording && activeRecordingDto == null) {
            recordingBanner.hide()
            recordingBannerAcknowledged = false
        }
        if (!wasStreaming && activeStreamDto != null && !streamBannerAcknowledged) streamBanner.show()
        if (wasStreaming && activeStreamDto == null) {
            streamBanner.hide()
            streamBannerAcknowledged = false
        }
    }

    /**
     * Review-round-1 fix (2026-08-09) for the disclosed gap `CHANGELOG.md`'s Wave 2 entry and
     * `deploy/local/README.adoc`'s Troubleshooting table both flag ("in-call moderator button gets
     * stuck on a permanently disabled 'Aufzeichnung wird beendet …' label past the recording's
     * actual terminal state"), live-verified 2026-08-09.
     *
     * Root cause (see file KDoc "D1/D2"): [refreshRecordingState] only ever runs from inside the
     * `onRecordingStatusChanged` LiveKit-push handler below, which fires once per `RECORDING.
     * isRecording` true<->false transition. That flag flips `false` the moment the LAST track's
     * egress ends -- well before `RecordingPoller`'s own `STOPPING -> PROCESSING -> READY`/`FAILED`
     * composition phase (seconds to minutes) actually finishes. Nothing ever refreshes the button
     * again after that one push, so whatever snapshot it captured (often still `STOPPING`) sticks
     * forever.
     *
     * This periodic poll is the follow-up signal the design review's own README candidate fix asked
     * for ("a light periodic `refreshRecordingState()` poll while a recording is non-terminal,
     * stopped once terminal"), refined in one respect: [refreshRecordingState]'s own
     * `getActiveRecording` call is not enough on its own, because it (deliberately, server-side --
     * see `ACTIVE_RECORDING_STATUSES`) only ever returns `RECORDING`/`STOPPING` rows; once the
     * poller advances a row to `PROCESSING`, `getActiveRecording` silently stops returning it at
     * all. This loop instead falls back to `listRecordings(room.id)` (no status filter server-side)
     * to find the SAME recording id and read its true, possibly-terminal status.
     *
     * Deliberately NOT run through `guarded {}` -- that wrapper shows a user-facing error toast per
     * failed call (see `AppState.kt` KDoc "every screen's data-loading/mutating coroutine goes
     * through this wrapper"), correct for a user-initiated action but wrong for a silent background
     * poll: a transient network hiccup would otherwise re-toast on every tick for as long as the
     * hiccup lasts. Failures here are swallowed and simply retried on the next tick.
     *
     * Interval matches the README's own suggested 15-30s cadence (chosen at the lower end -- the
     * server-side poller's own default tick is 10s, see `ConferenceRecordingConfig
     * .DEFAULT_POLL_INTERVAL_SECONDS`, so polling much slower than that would just add needless
     * extra latency on top of it). Runs for the coroutine's own un-cancelled lifetime (same posture
     * as every other `AppScope.launch` in this function) but self-terminates the moment `leftCall`
     * flips, on any exit path (Verlassen, Für alle beenden, `onDisconnected`).
     *
     * Known residual gap, not closed by this fix: `listRecordings`' access-level filter
     * (`ConferenceRecordingAccess.mayAccess`) can hide a recording from a moderator who is neither
     * its starter nor privileged enough for its `accessLevel` (e.g. a plain room-creator moderator
     * stopping another BOARD/ADMIN member's `ADMIN_ONLY` recording) -- for that narrow case the
     * fallback lookup keeps coming back empty and the button stays exactly as stuck as before this
     * fix, never worse. Not root-caused/closed here, same "flagged, not silently worked around"
     * posture the rest of this wave's disclosed gaps already follow.
     */
    suspend fun pollInFlightRecordingStatus() {
        while (!leftCall) {
            delay(CONFERENCE_RECORDING_POLL_INTERVAL_MS)
            if (leftCall) break
            val stalled = activeRecordingDto?.takeIf { conferenceRecordingNeedsPoll(it.status) } ?: continue
            val resolved =
                try {
                    conferenceFindRecordingById(
                        rpcService<IConferenceRecordingService>().listRecordings(room.id),
                        stalled.id,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    null
                }
            if (resolved == null || resolved.status == stalled.status) continue
            activeRecordingDto =
                when (resolved.status) {
                    ConferenceRecordingStatus.READY -> {
                        notifySuccess("Aufzeichnung ist bereit.")
                        null
                    }
                    ConferenceRecordingStatus.FAILED -> {
                        notifyError("Aufzeichnung fehlgeschlagen.")
                        null
                    }
                    else -> resolved
                }
            updateRecordingDetailLine()
            updateRecordButtonLabel()
        }
    }

    /**
     * Wave 3 -- the [pollInFlightRecordingStatus] pattern, applied to streaming's OWN, different
     * need: unlike recording's `getActiveRecording` (which stops returning a row once it advances
     * past `RECORDING`/`STOPPING`), [IConferenceStreamingService.getActiveStream] keeps returning
     * the row through the ENTIRE non-terminal lifecycle (`STARTING`/`LIVE`/`PAUSED`/`STOPPING`), so
     * a plain re-fetch of it is enough while polling is needed -- no `listStreams` fallback lookup
     * required for that half. [conferenceStreamNeedsPoll] decides "needed" from the WHOLE DTO, not
     * just the top-level status: per-target `PENDING` rows (D7's honest "Verbindung wird
     * hergestellt…" state, live-verified ~12s async LiveKit-connect window, see
     * [IConferenceStreamingService.startStream] KDoc) must keep polling even while the top-level
     * status is already steady-state `LIVE`.
     *
     * The ONE case [getActiveStream] cannot answer is "the stream just went ENDED/FAILED" -- once
     * terminal, [ACTIVE_STREAM_STATUSES][network.lapis.cloud.server.rpc.ConferenceStreamingService]
     * excludes the row entirely, so `getActiveStream` correctly starts returning EMPTY. That empty
     * result IS the terminal signal this loop needs -- but to also surface a sanitized
     * `failureReason` (rather than the stream simply vanishing with no explanation), this loop then
     * falls back to `listStreams(room.id)` ONE time, exactly mirroring
     * [conferenceFindRecordingById]'s own precedent via [conferenceFindStreamById].
     *
     * Runs for EVERY participant, not only the moderator (D7's honesty requirement is a
     * transparency guarantee for whoever is watching, not a moderator-only convenience) --
     * unconditionally launched below, same as [pollInFlightRecordingStatus]. Deliberately NOT run
     * through `guarded {}` for the same reason that function documents (a transient network hiccup
     * must not re-toast on every tick); self-terminates on `leftCall`, same lifecycle.
     */
    suspend fun pollInFlightStreamStatus() {
        while (!leftCall) {
            delay(CONFERENCE_STREAM_POLL_INTERVAL_MS)
            if (leftCall) break
            val current = activeStreamDto?.takeIf { conferenceStreamNeedsPoll(it) } ?: continue
            val refreshed =
                try {
                    rpcService<IConferenceStreamingService>().getActiveStream(room.id).singleOrNull()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    null
                }
            if (refreshed != null) {
                if (refreshed != current) {
                    activeStreamDto = refreshed
                    updateStreamDetailLine()
                    updateStreamButtonsVisibility()
                }
                continue
            }
            // getActiveStream returned empty -- the stream reached ENDED/FAILED. One fallback
            // lookup to learn WHICH, and (if FAILED) the sanitized failureReason.
            val resolved =
                try {
                    conferenceFindStreamById(rpcService<IConferenceStreamingService>().listStreams(room.id), current.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    null
                }
            activeStreamDto = null
            streamBanner.hide()
            streamBannerAcknowledged = false
            updateStreamDetailLine()
            updateStreamButtonsVisibility()
            if (resolved?.status == ConferenceStreamStatus.FAILED) {
                notifyError(resolved.failureReason ?: "Live-Stream fehlgeschlagen.")
            } else {
                notifyInfo("Live-Stream beendet.")
            }
        }
    }

    AppScope.launch { pollInFlightRecordingStatus() }
    AppScope.launch { pollInFlightStreamStatus() }

    // --- Screen-share stage (hidden until a "screen_share"-sourced track subscribes) --------------
    val stageDiv =
        callPanel.div {
            addCssClasses("border rounded p-2 text-center")
            display = io.kvision.core.Display.NONE
        }
    stageDiv.addAfterInsertHook { vnode -> stageElement = vnode.elm as? HTMLElement }

    // --- Video tile grid (responsive CSS grid, D3) -------------------------------------------------
    val gridDiv =
        callPanel.div {
            addCssClasses("border rounded p-2")
            minHeight = 220.px
            maxHeight = 480.px
            overflow = Overflow.AUTO
        }
    gridDiv.addAfterInsertHook { vnode ->
        gridElement =
            (vnode.elm as? HTMLElement)?.also {
                it.style.cssText +=
                    "display:grid;grid-template-columns:repeat(auto-fit, minmax(200px, 1fr));gap:8px;"
            }
    }

    // --- Participant roster (live, driven by the same RoomEvent stream as the tiles) --------------
    callPanel.h2("Teilnehmende") { addCssClasses("h6 mt-2") }
    val rosterList = callPanel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }

    // --- Collapsible chat side panel (D7: off by default) ------------------------------------------
    val chatPanel = callPanel.vPanel(spacing = 6) { addCssClasses("border rounded p-2") }
    chatPanel.hide()
    chatPanel.h2("Chat") { addCssClass("h6") }
    val chatLog =
        chatPanel.div {
            addCssClasses("small")
            height = 160.px
            overflow = Overflow.AUTO
        }
    val chatRow = chatPanel.hPanel(spacing = 6)
    val chatInput = chatRow.text(label = "Nachricht") { addCssClasses("flex-grow-1") }
    val chatSendButton = chatRow.button("Senden", style = ButtonStyle.OUTLINEPRIMARY)

    fun updateChatToggleLabel() {
        chatToggleButton.text = if (unreadChatCount > 0) "Chat ($unreadChatCount)" else "Chat"
    }

    fun refreshRoster() {
        rosterList.removeAll()
        if (tiles.isEmpty()) {
            rosterList.div("Noch niemand verbunden.") { addCssClasses("text-muted small") }
            return
        }
        tiles.values.sortedWith(compareBy({ !it.isLocal }, { it.displayName })).forEach { entry ->
            val row = rosterList.hPanel(spacing = 6) { addCssClasses("align-items-center flex-wrap") }
            row.div(entry.displayName + if (entry.isLocal) " (Sie)" else "") { addCssClasses("flex-grow-1 small") }
            if (entry.identity == room.createdByMemberId) {
                row.statusBadge("Moderator", "primary")
            }
            row.div(if (entry.hasMic) "Mikro an" else "Stumm") { addCssClasses("text-muted small") }
            if (conferenceCanRemove(entry.identity, localMemberId, room.createdByMemberId, canModerate)) {
                val removeButton = row.button("Entfernen", style = ButtonStyle.OUTLINEDANGER)
                removeButton.addCssClass("btn-sm")
                removeButton.onClick {
                    removeParticipantConfirmDialog(entry.displayName) {
                        removeButton.disabled = true
                        AppScope.launch {
                            guarded { rpcService<IConferenceService>().removeParticipant(room.id, entry.identity) }
                            removeButton.disabled = false
                        }
                    }
                }
            }
        }
    }

    fun setTileVideo(
        entry: ConferenceTileEntry,
        mediaElement: org.w3c.dom.HTMLMediaElement?,
    ) {
        clearElement(entry.mediaSlot)
        entry.hasCamera = mediaElement != null
        if (mediaElement != null) {
            mediaElement.style.cssText = "width:100%;height:100%;object-fit:cover;"
            entry.mediaSlot.appendChild(mediaElement)
        } else {
            // D4: camera off shows an avatar/initials placeholder, never a black rectangle -- this
            // distinction matters for a first-time user's trust in the tool (design review D4).
            entry.mediaSlot.textContent = conferenceInitials(entry.displayName)
        }
    }

    fun setTileMic(
        entry: ConferenceTileEntry,
        hasMic: Boolean,
    ) {
        entry.hasMic = hasMic
        entry.micBadge.style.display = if (hasMic) "none" else "block"
        refreshRoster()
    }

    fun buildTile(
        identity: String,
        displayName: String,
        isLocal: Boolean,
    ): ConferenceTileEntry {
        val tile = document.createElement("div") as HTMLElement
        tile.style.cssText =
            "position:relative;background:#111;border:1px solid #444;border-radius:6px;" +
            "overflow:hidden;min-height:150px;display:flex;align-items:center;justify-content:center;"

        val mediaSlot = document.createElement("div") as HTMLElement
        mediaSlot.style.cssText =
            "width:100%;height:100%;display:flex;align-items:center;justify-content:center;" +
            "color:#eee;font-size:28px;font-weight:600;"
        mediaSlot.textContent = conferenceInitials(displayName)
        tile.appendChild(mediaSlot)

        val nameBadge = document.createElement("div") as HTMLElement
        nameBadge.style.cssText =
            "position:absolute;left:6px;bottom:6px;background:rgba(0,0,0,0.55);color:#fff;" +
            "font-size:12px;padding:2px 6px;border-radius:3px;max-width:85%;overflow:hidden;" +
            "text-overflow:ellipsis;white-space:nowrap;"
        tile.appendChild(nameBadge)

        val micBadge = document.createElement("div") as HTMLElement
        micBadge.style.cssText =
            "position:absolute;right:6px;top:6px;background:rgba(0,0,0,0.55);color:#fff;" +
            "font-size:11px;padding:2px 5px;border-radius:3px;display:none;"
        micBadge.textContent = "Stumm"
        tile.appendChild(micBadge)

        gridElement?.appendChild(tile)
        return ConferenceTileEntry(identity, displayName, isLocal, tile, mediaSlot, nameBadge, micBadge)
    }

    // Tile/roster label composition lives HERE, not baked into `displayName` at call sites -- every
    // caller passes the RAW `joinToken.displayName`/`RemoteParticipant.name`, never a pre-suffixed
    // string, so [ConferenceTileEntry.displayName] stays the single source of truth for
    // [conferenceInitials] and never accumulates a repeated "(Sie)" suffix (a real bug an earlier
    // version of this function had -- see the wave's own testing routine).
    fun tileLabel(entry: ConferenceTileEntry): String {
        val suffix = if (entry.isLocal) " (Sie)" else ""
        val moderatorSuffix = if (entry.identity == room.createdByMemberId) " · Moderator" else ""
        return entry.displayName + suffix + moderatorSuffix
    }

    fun ensureTile(
        identity: String,
        displayName: String,
        isLocal: Boolean = false,
    ): ConferenceTileEntry {
        tiles[identity]?.let { existing ->
            existing.displayName = displayName
            existing.nameBadge.textContent = tileLabel(existing)
            return existing
        }
        val entry = buildTile(identity, displayName, isLocal)
        entry.nameBadge.textContent = tileLabel(entry)
        tiles[identity] = entry
        refreshRoster()
        return entry
    }

    fun removeTile(identity: String) {
        val entry = tiles.remove(identity) ?: return
        entry.element.parentNode?.removeChild(entry.element)
        refreshRoster()
    }

    fun showScreenShareStage(
        identity: String,
        displayName: String,
        track: Track,
    ) {
        val stage = stageElement ?: return
        // Wave 1 keeps only the most-recently-started share on stage -- multiple simultaneous shares
        // stacking/tabbing is out of scope (design review D4 "follow the standard convention, don't
        // invent a novel layout").
        clearElement(stage)
        val mediaElement = track.attach()
        mediaElement.style.cssText = "width:100%;max-height:60vh;border-radius:6px;background:#000;"
        stage.appendChild(mediaElement)
        val label = document.createElement("div") as HTMLElement
        label.style.cssText = "font-size:12px;color:#666;margin-top:4px;"
        label.textContent = "$displayName teilt den Bildschirm"
        stage.appendChild(label)
        stage.style.display = "block"
        activeScreenShare = identity to track
    }

    fun hideScreenShareStageIfCurrent(
        identity: String,
        track: Track,
    ) {
        val current = activeScreenShare ?: return
        if (current.first != identity) return
        track.detach().forEach { el -> el.parentNode?.removeChild(el) }
        stageElement?.let {
            clearElement(it)
            it.style.display = "none"
        }
        activeScreenShare = null
    }

    fun appendChatLine(
        sender: String,
        text: String,
        isOwn: Boolean,
    ) {
        chatLog.div("$sender: $text") {
            if (isOwn) addCssClasses("fw-bold text-end")
        }
        chatLog.getElement()?.let { el -> el.scrollTop = el.scrollHeight.toDouble() }
        if (!chatOpen && !isOwn) {
            unreadChatCount += 1
            updateChatToggleLabel()
        }
    }

    lateinit var session: LiveKitRoomSession
    session =
        LiveKitRoomSession(
            onRemoteTrack = { identity, displayName, track, publication ->
                when (conferenceTileKind(publication.source)) {
                    ConferenceTileKind.SCREEN_SHARE -> showScreenShareStage(identity, displayName, track)
                    ConferenceTileKind.CAMERA, ConferenceTileKind.OTHER -> {
                        val entry = ensureTile(identity, displayName)
                        val mediaElement = track.attach()
                        if (track.kind == "video") {
                            setTileVideo(entry, mediaElement)
                        } else {
                            mediaElement.style.cssText = "display:none;"
                            entry.element.appendChild(mediaElement)
                            setTileMic(entry, true)
                        }
                    }
                }
            },
            onRemoteTrackGone = { identity, track, publication ->
                when (conferenceTileKind(publication.source)) {
                    ConferenceTileKind.SCREEN_SHARE -> hideScreenShareStageIfCurrent(identity, track)
                    ConferenceTileKind.CAMERA, ConferenceTileKind.OTHER -> {
                        track.detach().forEach { el -> el.parentNode?.removeChild(el) }
                        val entry = tiles[identity]
                        if (entry != null) {
                            if (track.kind == "video") setTileVideo(entry, null)
                            if (track.kind == "audio") setTileMic(entry, false)
                        }
                    }
                }
            },
            onParticipantJoined = { identity, displayName -> ensureTile(identity, displayName) },
            onParticipantLeft = { identity -> removeTile(identity) },
            onLocalVideoTrack = { track ->
                val entry = ensureTile(joinToken.identity, joinToken.displayName, isLocal = true)
                setTileVideo(entry, track?.attach())
            },
            // Wave 3, D8: the raw pushed boolean is used PURELY as an instant "something changed, go
            // check" trigger -- see [onMediaStatusPush] KDoc for the full rationale (finding 7:
            // LiveKit's `active_recording` flag is true for a STREAMING-only egress too, so the
            // pre-Wave-3 version of this callback, which trusted the boolean directly, would show a
            // false "Aufzeichnung läuft" the moment a stream-only egress started). Fires both on
            // every live transition AND once, synchronously, right after connect
            // (`LiveKitRoomSession.connect`'s own "late-joiner seed", D4).
            onRecordingStatusChanged = { _ -> AppScope.launch { onMediaStatusPush() } },
            onChat = { message -> appendChatLine(message.senderDisplayName, message.text, isOwn = false) },
            onDisconnected = {
                if (!leftCall) {
                    leftCall = true
                    notifyInfo("Die Besprechung wurde beendet oder die Verbindung getrennt.")
                    returnToLobby(callPanel, lobbyPanel, setActiveSession, onReturnedToLobby, baseDocumentTitle)
                }
            },
        )
    setActiveSession(session)

    // Seed the local tile immediately (D8: the initial roster must render instantaneously and
    // completely, not "populate" one-by-one after landing) so the caller sees their own tile even
    // before the camera/microphone requests below resolve.
    ensureTile(joinToken.identity, joinToken.displayName, isLocal = true)
    setTileMic(tiles.getValue(joinToken.identity), micEnabled)

    AppScope.launch {
        val connected = guarded { session.connect(joinToken.serverUrl, joinToken.token, joinToken.turnServers) }
        if (connected == null) {
            setActiveSession(null)
            returnToLobby(callPanel, lobbyPanel, setActiveSession, onReturnedToLobby, baseDocumentTitle)
            return@launch
        }
        // Each device independently, each wrapped in its own `guarded {}` -- a missing/denied camera
        // or microphone must not abort the whole call (a member with no working device can still
        // join and watch/listen). The full non-technical D2 permission-preflight UI is explicitly
        // deferred to this wave's next, polish-focused step -- see file KDoc.
        //
        // Bug found and fixed during this wave's own live-browser verification (2026-08-09,
        // reproduced against a real getUserMedia rejection, not merely reasoned about): both
        // `micEnabled`/`cameraEnabled` above default to `true` and the buttons below are created
        // with that same "on" label BEFORE this block ever runs -- if the initial publish attempt
        // fails (denied permission, no device), that default stays wrong forever unless corrected
        // here. `guarded {}` returning `null` is exactly the "it failed" signal; only a non-null
        // result means the track is actually publishing.
        val micOk = guarded { session.setMicrophone(true) } != null
        val cameraOk = guarded { session.setCamera(true) } != null
        if (!micOk) {
            micEnabled = false
            setTileMic(tiles.getValue(joinToken.identity), micEnabled)
            micButton.text = "Mikrofon an"
        }
        if (!cameraOk) {
            cameraEnabled = false
            cameraButton.text = "Kamera an"
        }
    }

    micButton.onClick {
        micButton.disabled = true
        AppScope.launch {
            val desired = !micEnabled
            // Bug found and fixed during this wave's own live-browser verification (2026-08-09): the
            // previous version flipped `micEnabled`/the button label BEFORE awaiting `setMicrophone`
            // and never checked its result -- a permission-denied click made the button claim "on"
            // with nothing actually publishing, no error visible anywhere. `guarded {}` already shows
            // a toast on failure (see AppState.kt); this handler now additionally reverts to the
            // truthful state instead of pretending the click succeeded.
            val result = guarded { session.setMicrophone(desired) }
            micButton.disabled = false
            if (result != null) {
                micEnabled = desired
                setTileMic(tiles.getValue(joinToken.identity), micEnabled)
                micButton.text = if (micEnabled) "Mikrofon aus" else "Mikrofon an"
            }
        }
    }
    cameraButton.onClick {
        cameraButton.disabled = true
        AppScope.launch {
            val desired = !cameraEnabled
            // Same fix as micButton.onClick above -- see that handler's comment.
            val result = guarded { session.setCamera(desired) }
            cameraButton.disabled = false
            if (result != null) {
                cameraEnabled = desired
                cameraButton.text = if (cameraEnabled) "Kamera aus" else "Kamera an"
            }
        }
    }
    screenShareButton.onClick {
        screenShareButton.disabled = true
        AppScope.launch {
            val desired = !screenShareEnabled
            // Same fix as micButton.onClick above -- see that handler's comment.
            val result = guarded { session.setScreenShare(desired) }
            screenShareButton.disabled = false
            if (result != null) {
                screenShareEnabled = desired
                screenShareButton.text = if (screenShareEnabled) "Bildschirm-Teilen beenden" else "Bildschirm teilen"
            }
        }
    }
    chatToggleButton.onClick {
        chatOpen = !chatOpen
        if (chatOpen) {
            chatPanel.show()
            unreadChatCount = 0
            updateChatToggleLabel()
        } else {
            chatPanel.hide()
        }
    }

    fun sendChatMessage() {
        val text = chatInput.value.orEmpty().trim()
        if (text.isBlank()) return
        val ownMessage =
            ConferenceChatMessage(
                senderMemberId = joinToken.identity,
                senderDisplayName = joinToken.displayName,
                text = text,
                sentAtEpochMs = Clock.System.now().toEpochMilliseconds(),
            )
        chatInput.value = null
        AppScope.launch {
            // LiveKit never echoes a locally published data message back to its own sender (see
            // LiveKitRoomSession KDoc "Chat trust boundary") -- this screen renders its own outgoing
            // line immediately, rather than waiting for onChat.
            guarded { session.sendChat(ownMessage) }
            appendChatLine("Sie", text, isOwn = true)
        }
    }
    chatSendButton.onClick { sendChatMessage() }
    // D7 "Enter-to-send": KVision's own event-binding surface is not used here -- the chat row's
    // real `<input>` element is grabbed once via `addAfterInsertHook` (same raw-DOM posture the
    // video grid/stage above already use) and wired with a plain `keydown` listener, mirroring this
    // file's own `beforeunload` listener pattern.
    chatRow.addAfterInsertHook { vnode ->
        val rowElement = vnode.elm as? HTMLElement
        val inputElement = rowElement?.querySelector("input") as? HTMLInputElement
        inputElement?.addEventListener("keydown", { event ->
            val keyEvent = event as? KeyboardEvent
            if (keyEvent?.key == "Enter") {
                keyEvent.preventDefault()
                sendChatMessage()
            }
        })
    }

    leaveButton.onClick {
        leaveButton.disabled = true
        leftCall = true
        AppScope.launch {
            guarded { session.disconnect() }
            guarded { rpcService<IConferenceService>().leaveRoom(room.id) }
            setActiveSession(null)
            returnToLobby(callPanel, lobbyPanel, setActiveSession, onReturnedToLobby, baseDocumentTitle)
        }
    }

    endButton?.onClick {
        endRoomConfirmDialog(room.title) {
            endButton.disabled = true
            leftCall = true
            AppScope.launch {
                guarded { session.disconnect() }
                val result = guarded { rpcService<IConferenceService>().endRoom(room.id) }
                if (result != null) {
                    notifySuccess("Besprechung \"${result.title}\" wurde für alle beendet.")
                }
                setActiveSession(null)
                returnToLobby(callPanel, lobbyPanel, setActiveSession, onReturnedToLobby, baseDocumentTitle)
            }
        }
    }
}

private fun clearElement(element: HTMLElement) {
    while (element.firstChild != null) {
        element.removeChild(element.firstChild!!)
    }
}

/**
 * @param originalTitle Wave 2 "Aufzeichnung" -- restores `document.title` on every path back to the
 *   Lobby. Necessary in addition to `onRecordingStatusChanged(false)`'s own reset (see that
 *   callback in [enterCall]) because disconnecting does not reliably fire a final
 *   `RecordingStatusChanged(false)` push -- `null` is only ever passed by a caller outside
 *   [enterCall], which does not exist today (kept nullable/defaulted so this function's contract
 *   does not silently assume every future caller is recording-aware).
 */
private fun returnToLobby(
    callPanel: SimplePanel,
    lobbyPanel: SimplePanel,
    setActiveSession: (LiveKitRoomSession?) -> Unit,
    onReturnedToLobby: () -> Unit,
    originalTitle: String? = null,
) {
    setActiveSession(null)
    callPanel.removeAll()
    callPanel.hide()
    lobbyPanel.show()
    if (originalTitle != null) document.title = originalTitle
    onReturnedToLobby()
}

/** Rule 3 (irreversible action -> bespoke confirm modal): matches `AuctionScreen.kt`'s own
 * `auctionDisableConfirmDialog` tier -- danger-framed, states the concrete consequence in plain
 * language (design review D6's "Diese Besprechung wird für alle Teilnehmenden beendet. Fortfahren?"
 * copy). */
private fun endRoomConfirmDialog(
    roomTitle: String,
    onConfirm: () -> Unit,
) {
    val modal = Modal(caption = "Besprechung für alle beenden")
    modal.div("Diese Besprechung wird für alle Teilnehmenden beendet. Fortfahren?") { addCssClasses("fw-bold text-danger") }
    modal.div("\"$roomTitle\" wird sofort geschlossen -- alle Verbindungen werden getrennt.") { addCssClasses("text-muted small") }
    modal.addButton(Button("Abbrechen", style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button("Für alle beenden", style = ButtonStyle.DANGER).apply {
            onClick {
                modal.hide()
                onConfirm()
            }
        },
    )
    modal.show()
}

/** Design review D9: removing a participant is an action taken against another person without their
 * consent -- not the heavy end-room dialog, but not a silent one-click either. Lighter framing than
 * [endRoomConfirmDialog] (no danger-red body text), still a genuine confirm step. */
private fun removeParticipantConfirmDialog(
    displayName: String,
    onConfirm: () -> Unit,
) {
    val modal = Modal(caption = "Teilnehmer entfernen")
    modal.div("\"$displayName\" aus der Besprechung entfernen?") { addCssClass("fw-bold") }
    modal.addButton(Button("Abbrechen", style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button("Entfernen", style = ButtonStyle.DANGER).apply {
            onClick {
                modal.hide()
                onConfirm()
            }
        },
    )
    modal.show()
}

/**
 * Design review D7 -- matches the LITERAL `PostalMailScreen.postalDispatchConfirmDialog` bar: a
 * named primary button (never "OK"/"Bestätigen"), body copy stating both facts a moderator needs
 * before committing (irreversibility once people have spoken, immediate room-wide visibility), and
 * the [DocumentAccessLevel] select carries its own explanatory line so the BOARD_ONLY default's
 * consequence is legible AT THE MOMENT of the choice, not discovered weeks later. [onConfirm]
 * receives the chosen access level; cancelling calls nothing.
 */
private fun startRecordingConfirmDialog(onConfirm: (DocumentAccessLevel) -> Unit) {
    val modal = Modal(caption = "Aufzeichnung starten")
    modal.div(
        "Diese Aufzeichnung kann NICHT rückgängig gemacht werden, sobald Teilnehmende gesprochen haben.",
    ) { addCssClasses("fw-bold text-danger") }
    modal.div(
        "Alle aktuell anwesenden Teilnehmenden sehen sofort, dass aufgezeichnet wird -- unabhängig " +
            "davon, wer die Aufzeichnung startet.",
    ) { addCssClasses("small mb-2") }
    val accessOptions = DocumentAccessLevel.entries.map { it.name to conferenceRecordingAccessLevelLabel(it) }
    val accessSelect =
        modal.select(options = accessOptions, value = DocumentAccessLevel.BOARD_ONLY.name, label = "Zugriffsebene")
    modal.div(
        "Bei \"Vorstand\" können anwesende Mitglieder, die nicht dem Vorstand angehören, die " +
            "Aufzeichnung später NICHT ansehen -- wählen Sie \"Mitglieder\", wenn die Aufnahme allen " +
            "Teilnehmenden zugänglich sein soll.",
    ) { addCssClasses("text-muted small mb-2") }
    modal.addButton(Button("Abbrechen", style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button("Aufzeichnung jetzt starten", style = ButtonStyle.WARNING).apply {
            onClick {
                val level = accessSelect.value?.let { DocumentAccessLevel.valueOf(it) } ?: DocumentAccessLevel.BOARD_ONLY
                modal.hide()
                onConfirm(level)
            }
        },
    )
    modal.show()
}

/**
 * Design review D8 -- "lighter but real": stopping is not destructive by itself, but it IS the
 * irreversible trigger into composition (bounded `compose_attempts`, raw-file deletion on success).
 * Matches [removeParticipantConfirmDialog]'s tier (no danger-red body text), not
 * [endRoomConfirmDialog]'s full weight -- still a genuine confirm step, never a bare single click.
 */
private fun stopRecordingConfirmDialog(onConfirm: () -> Unit) {
    val modal = Modal(caption = "Aufzeichnung beenden")
    modal.div(
        "Die Aufzeichnung wird beendet und danach automatisch zu einer Videodatei zusammengeführt.",
    ) { addCssClass("fw-bold") }
    modal.div(
        "Dieser Vorgang lässt sich nicht wiederholen -- schlägt er fehl, bleiben die Rohaufnahmen " +
            "erhalten und ein Administrator kann helfen.",
    ) { addCssClasses("text-muted small") }
    modal.addButton(Button("Abbrechen", style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button("Aufzeichnung beenden", style = ButtonStyle.DANGER).apply {
            onClick {
                modal.hide()
                onConfirm()
            }
        },
    )
    modal.show()
}

// ================================================================================================
// Wave 3 "Externes Streaming" -- confirm dialogs. D5: every one of these restates the NOUN it acts
// on ("Stream **beenden**?"), never a bare "Wirklich beenden?" -- so a moderator tabbing through
// them (sighted or via screen reader) can never confuse which of "Stream"/"Aufzeichnung"/
// "Besprechung" a given dialog is about to act on.
// ================================================================================================

/**
 * D2 (Norman/Tesler/Forstall) -- NO re-typing. The destination checklist IS the confirm surface:
 * [summaryBox] names the SELECTED destinations by [ConferenceStreamTargetDto.label] (never url/key
 * -- this DTO carries neither) and restates irrevocability in plain German as the selection changes
 * ([conferenceStreamStartSummary]); the primary button reads "Jetzt live gehen", never "OK"/
 * "Bestätigen" -- the label itself is the last line of defense against a misclick. The secret-ballot
 * Hinweis ([CONFERENCE_STREAM_SECRET_BALLOT_HINWEIS]) is static and unconditional, never implying a
 * protection that does not exist this wave (see [IConferenceStreamingService] KDoc "Out of scope").
 */
private fun startStreamDialog(
    targets: List<ConferenceStreamTargetDto>,
    maxDestinations: Int,
    participantOptions: List<Pair<String, String>>,
    onConfirm: (
        destinationIds: List<String>,
        layout: ConferenceStreamLayout,
        latencyMode: ConferenceStreamLatencyMode,
        participantIdentity: String?,
    ) -> Unit,
) {
    val modal = Modal(caption = "Live-Stream starten")
    val summaryBox = modal.div(conferenceStreamStartSummary(emptyList())) { addCssClasses("fw-bold text-danger") }

    modal.div("Ziele auswählen (max. $maxDestinations):") { addCssClasses("fw-bold mt-2") }
    val targetsPanel = modal.vPanel(spacing = 2)
    val checkboxesByTarget =
        targets.associateWith { target ->
            targetsPanel.checkBox(label = "${target.label} (${conferenceStreamPlatformLabel(target.platform)})")
        }
    val selectionErrorBox =
        modal.div().apply {
            addCssClasses("text-danger small")
            hide()
        }

    fun selectedTargets(): List<ConferenceStreamTargetDto> = checkboxesByTarget.filterValues { it.value }.keys.toList()

    fun updateSummary() {
        summaryBox.content = conferenceStreamStartSummary(selectedTargets().map { it.label })
    }
    checkboxesByTarget.values.forEach { checkbox -> checkbox.onClick { updateSummary() } }

    val layoutOptions = ConferenceStreamLayout.entries.map { it.name to conferenceStreamLayoutLabel(it) }
    val layoutSelect = modal.select(options = layoutOptions, value = ConferenceStreamLayout.GRID.name, label = "Layout")
    val participantSelect =
        modal.select(
            options = participantOptions,
            value = participantOptions.firstOrNull()?.first,
            label = "Person (nur bei \"Einzelne Person\")",
        )
    participantSelect.hide()
    layoutSelect.subscribe {
        if (layoutSelect.value == ConferenceStreamLayout.SINGLE_PARTICIPANT.name) participantSelect.show() else participantSelect.hide()
    }

    val latencyOptions = ConferenceStreamLatencyMode.entries.map { it.name to conferenceStreamLatencyModeLabel(it) }
    val latencySelect =
        modal.select(options = latencyOptions, value = ConferenceStreamLatencyMode.STANDARD.name, label = "Latenz")

    modal.div(CONFERENCE_STREAM_SECRET_BALLOT_HINWEIS) { addCssClasses("text-muted small mt-2") }

    modal.addButton(Button("Abbrechen", style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button("Jetzt live gehen", style = ButtonStyle.DANGER).apply {
            onClick {
                selectionErrorBox.hide()
                val selected = selectedTargets()
                if (!conferenceStreamSelectionValid(selected.size, maxDestinations)) {
                    selectionErrorBox.content = "Bitte 1 bis $maxDestinations Ziele auswählen."
                    selectionErrorBox.show()
                    return@onClick
                }
                val layout = ConferenceStreamLayout.valueOf(layoutSelect.value ?: ConferenceStreamLayout.GRID.name)
                val participantIdentity = if (layout == ConferenceStreamLayout.SINGLE_PARTICIPANT) participantSelect.value else null
                if (layout == ConferenceStreamLayout.SINGLE_PARTICIPANT && participantIdentity.isNullOrBlank()) {
                    selectionErrorBox.content = "Bitte eine Person für \"Einzelne Person\" auswählen."
                    selectionErrorBox.show()
                    return@onClick
                }
                val latencyMode = ConferenceStreamLatencyMode.valueOf(latencySelect.value ?: ConferenceStreamLatencyMode.STANDARD.name)
                modal.hide()
                onConfirm(selected.map { it.id }, layout, latencyMode, participantIdentity)
            }
        },
    )
    modal.show()
}

/**
 * D6 (Norman/Raskin) -- blunt, not falsely reassuring: LiveKit has NO pause primitive (verified
 * live, see [IConferenceStreamingService] KDoc "pauseStream/resumeStream"), so this copy says
 * plainly that the platform sees an interruption and MAY end the broadcast entirely. No "Pause"
 * wording anywhere that implies a seamless resume.
 */
private fun pauseStreamConfirmDialog(
    destinationLabels: String,
    onConfirm: () -> Unit,
) {
    val modal = Modal(caption = "Stream unterbrechen")
    modal.div(
        "Die Besprechung läuft weiter. Die Zielplattform sieht eine Unterbrechung -- YouTube kann die " +
            "Übertragung dabei beenden, sodass ein neuer Link nötig wird.",
    ) { addCssClass("fw-bold") }
    modal.div("Betroffene Ziele: $destinationLabels") { addCssClasses("text-muted small") }
    modal.addButton(Button("Abbrechen", style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button("Stream unterbrechen", style = ButtonStyle.WARNING).apply {
            onClick {
                modal.hide()
                onConfirm()
            }
        },
    )
    modal.show()
}

/** D6 -- resume is a FRESH egress to the same destinations (a new `livekit_egress_id` on the same
 * row), never a seamless continuation -- this copy says so plainly, matching [pauseStreamConfirmDialog]'s
 * honesty. Lighter tier than [pauseStreamConfirmDialog]/[stopStreamConfirmDialog] (resuming is not
 * itself destructive or disclosive beyond what starting already disclosed) but still a genuine
 * confirm step, per rule 3. */
private fun resumeStreamConfirmDialog(onConfirm: () -> Unit) {
    val modal = Modal(caption = "Stream fortsetzen")
    modal.div(
        "Der Stream wird neu verbunden -- die Zielplattform sieht dies unter Umständen erneut als " +
            "neue Übertragung.",
    ) { addCssClass("fw-bold") }
    modal.addButton(Button("Abbrechen", style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button("Stream fortsetzen", style = ButtonStyle.WARNING).apply {
            onClick {
                modal.hide()
                onConfirm()
            }
        },
    )
    modal.show()
}

/** D5 -- restates the noun ("Stream **beenden**?"), names the affected platforms by label, states
 * the meeting itself is unaffected -- matches [endRoomConfirmDialog]'s weight (danger-red body
 * text), since ending a public broadcast to external viewers is a genuinely irreversible,
 * high-stakes act, distinct from [stopRecordingConfirmDialog]'s lighter tier. */
private fun stopStreamConfirmDialog(
    destinationLabels: String,
    onConfirm: () -> Unit,
) {
    val modal = Modal(caption = "Live-Stream beenden")
    modal.div("Stream beenden? Die Übertragung zu $destinationLabels endet sofort.") { addCssClasses("fw-bold text-danger") }
    modal.div("Die Besprechung selbst läuft für alle Teilnehmenden unverändert weiter.") { addCssClasses("text-muted small") }
    modal.addButton(Button("Abbrechen", style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button("Live-Stream beenden", style = ButtonStyle.DANGER).apply {
            onClick {
                modal.hide()
                onConfirm()
            }
        },
    )
    modal.show()
}

// ================================================================================================
// Pure, DOM-independent logic -- see [ConferenceScreenTest] for coverage (no rendering harness
// exists in this module, same posture every other screen's `*ScreenTest.kt` documents).
// ================================================================================================

/** Mirrors `LiveKitJs.kt`'s file KDoc: `"screen_share"` is discriminated by string comparison,
 * deliberately no dedicated enum type on the JS-interop side -- this enum exists purely on the
 * screen's OWN side, to decide grid-tile vs. full-width-stage placement. */
internal enum class ConferenceTileKind { CAMERA, SCREEN_SHARE, OTHER }

internal fun conferenceTileKind(source: String): ConferenceTileKind =
    when (source) {
        "screen_share" -> ConferenceTileKind.SCREEN_SHARE
        "camera" -> ConferenceTileKind.CAMERA
        else -> ConferenceTileKind.OTHER
    }

/** Pure client-side mirror of `IConferenceService.endRoom`/`.removeParticipant`'s server-side
 * "creator OR global BOARD/ADMIN" gate (see that interface's class KDoc "Two-tier role model") --
 * a UX nicety, not the real authority (the server re-checks independently). `localMemberId == null`
 * (session not yet loaded) never grants moderator standing. */
internal fun conferenceIsModerator(
    localMemberId: String?,
    creatorMemberId: String,
    isBoardOrAdmin: Boolean,
): Boolean = localMemberId != null && (localMemberId == creatorMemberId || isBoardOrAdmin)

/** A moderator can remove anyone in the roster except the room's own creator (mirrors
 * `IConferenceService.removeParticipant`'s own server-side refusal, [IConferenceService] KDoc) and
 * except themselves (no self-removal button -- use "Verlassen" instead). */
internal fun conferenceCanRemove(
    targetMemberId: String,
    localMemberId: String?,
    creatorMemberId: String,
    canModerate: Boolean,
): Boolean =
    canModerate &&
        targetMemberId != creatorMemberId &&
        targetMemberId != localMemberId

/** Two-letter initials for the D4 camera-off avatar placeholder -- "Anna Muster" -> "AM", a single
 * name "Anna" -> "AN", blank/whitespace-only -> "?". */
internal fun conferenceInitials(displayName: String): String {
    val parts = displayName.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts.first().take(2).uppercase()
        else -> (parts.first().take(1) + parts.last().take(1)).uppercase()
    }
}

// ------------------------------------------------------------------------------------------------
// Wave 2 "Aufzeichnung" -- pure recording helpers. `internal`, not `private`, so
// `ConferenceRecordingsPanel.kt` (same package) reuses the SAME copy/formatting rather than
// duplicating it -- design review D10 "status copy: plain German, mapped once, used everywhere".
// ------------------------------------------------------------------------------------------------

/** Design review D3 -- the one-time notice banner's exact, terminology-locked (D15) copy. A
 * top-level constant, not inlined at the one call site, so [ConferenceScreenTest] can assert
 * against the SAME literal the UI actually renders. */
internal const val CONFERENCE_RECORDING_BANNER_TEXT = "Diese Besprechung wird ab jetzt aufgezeichnet."

/** Review-round-1 fix (2026-08-09) -- poll interval for `enterCall`'s local
 * `pollInFlightRecordingStatus` loop. See that function's own KDoc for why this exists and why 15s
 * (the README's own suggested lower bound, close to the server-side poller's 10s default tick). */
internal const val CONFERENCE_RECORDING_POLL_INTERVAL_MS = 15_000L

/** Review-round-1 fix (2026-08-09) -- whether [enterCall]'s local `pollInFlightRecordingStatus`
 * loop still needs to keep polling for the currently-tracked recording. `STOPPING` is included,
 * not only `PROCESSING`: the LiveKit-push-triggered [refreshRecordingState] can itself observe a
 * still-`STOPPING` row if the server-side poller has not yet ticked, and that row still needs to
 * be watched until it moves on. `null`/`RECORDING`/`READY`/`FAILED` all mean "nothing to poll for"
 * -- `null` because there is no tracked recording, `RECORDING` because the control is already
 * correctly enabled and LiveKit's own push covers it, `READY`/`FAILED` because those are terminal. */
internal fun conferenceRecordingNeedsPoll(status: ConferenceRecordingStatus?): Boolean =
    status == ConferenceRecordingStatus.STOPPING || status == ConferenceRecordingStatus.PROCESSING

/** Review-round-1 fix (2026-08-09) -- pure lookup used by [enterCall]'s local
 * `pollInFlightRecordingStatus`: finds the recording matching [stalledId] inside a
 * `listRecordings()` batch. Needed because
 * `IConferenceRecordingService.getActiveRecording` only ever returns `RECORDING`/`STOPPING` rows
 * (`ACTIVE_RECORDING_STATUSES` server-side) -- once the poller advances a row to `PROCESSING`/
 * `READY`/`FAILED`, `getActiveRecording` silently stops returning it, and `listRecordings` (which
 * has no status filter) is the only way left to find out what actually happened to it. */
internal fun conferenceFindRecordingById(
    recordings: List<ConferenceRecordingDto>,
    stalledId: String,
): ConferenceRecordingDto? = recordings.firstOrNull { it.id == stalledId }

/** Design review D10's status-copy table -- the ONE place this mapping exists. Never "processing"/
 * other technical jargon (D10's own explicit example). */
internal fun conferenceRecordingStatusLabel(status: ConferenceRecordingStatus): String =
    when (status) {
        ConferenceRecordingStatus.RECORDING -> "Wird aufgezeichnet"
        ConferenceRecordingStatus.STOPPING -> "Wird beendet …"
        ConferenceRecordingStatus.PROCESSING -> "Wird zusammengeführt …"
        ConferenceRecordingStatus.READY -> "Bereit"
        ConferenceRecordingStatus.FAILED -> "Fehlgeschlagen"
    }

/** Bootstrap hue per [ConferenceRecordingStatus], for [statusBadge] in `ConferenceRecordingsPanel.kt`
 * (see that file's own D10/D12 reasoning for why FAILED reuses "danger" but is ALSO sorted to the
 * top by [conferenceRecordingListSorted] -- color alone is never the only signal here). */
internal fun conferenceRecordingStatusColor(status: ConferenceRecordingStatus): String =
    when (status) {
        ConferenceRecordingStatus.RECORDING -> "danger"
        ConferenceRecordingStatus.STOPPING -> "warning"
        ConferenceRecordingStatus.PROCESSING -> "info"
        ConferenceRecordingStatus.READY -> "success"
        ConferenceRecordingStatus.FAILED -> "danger"
    }

/** D7's Zugriffsebene select / the Lobby list's own access-level display -- German labels for
 * [DocumentAccessLevel], deliberately spelling out the CONSEQUENCE-bearing name ("Vorstand", not the
 * enum literal `BOARD_ONLY`) since D7 requires the choice to be legible at the moment it is made. */
internal fun conferenceRecordingAccessLevelLabel(level: DocumentAccessLevel): String =
    when (level) {
        DocumentAccessLevel.PUBLIC_MEMBERS -> "Mitglieder"
        DocumentAccessLevel.BOARD_ONLY -> "Vorstand"
        DocumentAccessLevel.ADMIN_ONLY -> "Administration"
    }

/**
 * Gates the moderator's "Aufzeichnung starten" control -- mirrors the server's OWN gate
 * (`ConferenceRecordingService.startRecording`: creator-or-BOARD/ADMIN, recording configured, no
 * already-active recording for this room) as a UX nicety, exactly like [conferenceIsModerator]/
 * [conferenceCanRemove] already do for Wave 1's own moderator actions -- the server re-checks
 * independently regardless. [activeRecording] non-`null` (RECORDING or STOPPING, the only statuses
 * [IConferenceRecordingService.getActiveRecording] ever returns) blocks a second recording -- this
 * wave forbids multiple simultaneous recordings per room.
 */
internal fun recordingCanStart(
    canModerate: Boolean,
    recordingAvailable: Boolean,
    activeRecording: ConferenceRecordingDto?,
): Boolean = canModerate && recordingAvailable && activeRecording == null

/** The in-call detail line's "Aufzeichnung gestartet von X um HH:MM" copy -- zero-padded 24h time,
 * matching this codebase's plain-German, non-technical copy posture throughout this screen. */
internal fun conferenceRecordingStartedLabel(
    startedByDisplayName: String,
    startedAt: LocalDateTime,
): String {
    val hour = startedAt.hour.toString().padStart(2, '0')
    val minute = startedAt.minute.toString().padStart(2, '0')
    return "Aufzeichnung gestartet von $startedByDisplayName um $hour:$minute"
}

/** `mm:ss` under an hour, `h:mm:ss` at/above -- `null` (composition not finished yet, or the value
 * is simply unknown) renders as an em dash, never a blank string or "0:00" (which would misleadingly
 * imply a known, zero-length recording). */
internal fun conferenceRecordingDurationLabel(seconds: Long?): String {
    if (seconds == null) return "–"
    val totalSeconds = seconds.coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}"
    } else {
        "$minutes:${secs.toString().padStart(2, '0')}"
    }
}

/** `document.title` prefix while `isRecording` -- a plain "● " marker (not an emoji, to avoid
 * platform-inconsistent tab-title rendering) so a BACKGROUNDED tab still signals recording, per the
 * design review's own "optionally prefix document.title" note. */
internal fun conferenceRecordingDocumentTitle(
    baseTitle: String,
    isRecording: Boolean,
): String = if (isRecording) "● $baseTitle" else baseTitle

/**
 * Design review D12: a FAILED recording must not wait to be discovered by someone browsing a long
 * list -- sorted to the front, ahead of everything else, WITHOUT disturbing the server's own
 * newest-first ordering within either partition (Kotlin's `sortedByDescending` is a STABLE sort, so
 * "FAILED first" is the only change this makes; relative order inside "FAILED" and inside
 * "everything else" is preserved exactly as the server returned it).
 */
internal fun conferenceRecordingListSorted(recordings: List<ConferenceRecordingDto>): List<ConferenceRecordingDto> =
    recordings.sortedByDescending { it.status == ConferenceRecordingStatus.FAILED }

/** Rough, human-scale file-size label for the Lobby's download link (D9: "given file sizes running
 * into hundreds of MB on metered/mobile connections") -- deliberately coarse (whole megabytes, no
 * decimal), this is a heads-up before a large download, not a precise byte count. */
internal fun conferenceRecordingFileSizeLabel(bytes: Long): String {
    val megabytes = (bytes / 1_000_000L).coerceAtLeast(if (bytes > 0) 1L else 0L)
    return "≈ $megabytes MB"
}

// ------------------------------------------------------------------------------------------------
// Wave 3 "Externes Streaming" -- pure streaming helpers. `internal`, not `private`, so
// [ConferenceStreamingUiTest] (this package) covers them directly without a rendering harness, same
// posture Wave 2's own pure helpers above already establish.
// ------------------------------------------------------------------------------------------------

/** Design review D3 -- one row per active subsystem, never merged. See [conferenceStatusBadgeRows]. */
internal data class ConferenceStatusBadgeRow(
    val text: String,
    val color: String,
)

/**
 * Live-verification fix (2026-08-09, Wave 3 verification step) -- the verb phrase [conferenceStatusBadgeRows]
 * uses for its streaming row, branched on [ConferenceStreamStatus] instead of the single hardcoded
 * "läuft" the badge originally always rendered. Found live: pausing a stream correctly flips
 * `activeStreamDto.status` to `PAUSED` (the moderator control row and [updateStreamTargetsPanel]
 * both react to that correctly), but the top badge kept reading "◆ Live-Stream läuft → ..." the
 * entire time the stream was paused -- a real, reproducible false statement in exactly the
 * transparency surface D8/finding-7 exists to keep honest, discovered by actually pausing a real
 * stream against the live Colima stack, not merely by reading the code. [conferenceStreamStatusLabel]
 * (D7/D10's own German status-copy table) already had the right words for every non-LIVE status but
 * was, until this fix, dead code from the badge's own perspective -- only ever exercised by its own
 * unit test, never wired into [conferenceStatusBadgeRows]. This function is the wiring fix, kept
 * separate from [conferenceStreamStatusLabel] (rather than reusing its bare "Live"/"Unterbrochen"
 * nouns directly) because the badge needs a full verb phrase ("läuft"/"ist unterbrochen") to read as
 * a sentence together with the "→ labels" suffix, not a status noun.
 */
internal fun conferenceStreamBadgeVerbPhrase(status: ConferenceStreamStatus): String =
    when (status) {
        ConferenceStreamStatus.STARTING -> "wird gestartet"
        ConferenceStreamStatus.LIVE -> "läuft"
        ConferenceStreamStatus.PAUSED -> "ist unterbrochen"
        ConferenceStreamStatus.STOPPING -> "wird beendet"
        ConferenceStreamStatus.ENDED, ConferenceStreamStatus.FAILED -> "ist beendet"
    }

/**
 * The Wave 2 badge fix (finding 7, design review D8, launch-blocking) -- renders from SERVER state
 * ([activeRecording]/[activeStream]), never from the raw LiveKit `Room.isRecording` push boolean,
 * which LiveKit sets `true` for ANY active egress including a streaming-only one. Recording and
 * streaming get DISTINCT rows with distinct leading glyphs ("●" vs "◆", not relying on red-vs-red
 * alone, Kare's colorblind-legibility note) -- both render simultaneously, stacked, never merged
 * into one aggregate line, so "which platforms are live" stays legible even with both active.
 * [activeStream]'s destination LABELS only, never url/key (matches [ConferenceStreamTargetStatusDto]'s
 * own narrower shape).
 *
 * **The streaming row's verb phrase and color both track [ConferenceStreamDto.status]** (see
 * [conferenceStreamBadgeVerbPhrase] KDoc for the live-discovered bug this closes) -- `PAUSED` reads
 * "ist unterbrochen" in `secondary` (calm, not alarm-red), matching [conferenceStreamStatusColor]'s
 * own PAUSED color exactly, rather than the alarm-red "läuft" a paused stream is NOT currently doing.
 */
internal fun conferenceStatusBadgeRows(
    activeRecording: ConferenceRecordingDto?,
    activeStream: ConferenceStreamDto?,
): List<ConferenceStatusBadgeRow> {
    val rows = mutableListOf<ConferenceStatusBadgeRow>()
    if (activeRecording != null) {
        rows += ConferenceStatusBadgeRow("● Aufzeichnung läuft", "danger")
    }
    if (activeStream != null) {
        val labels = activeStream.targets.joinToString(", ") { it.label }.ifBlank { "unbekanntes Ziel" }
        val verbPhrase = conferenceStreamBadgeVerbPhrase(activeStream.status)
        rows += ConferenceStatusBadgeRow("◆ Live-Stream $verbPhrase → $labels", conferenceStreamStatusColor(activeStream.status))
    }
    return rows
}

/** `document.title` prefix while EITHER subsystem is active -- extends [conferenceRecordingDocumentTitle]'s
 * own "● " marker (not an emoji, same cross-platform-tab-rendering reasoning) to cover streaming too,
 * per the Wave 3 badge fix. [conferenceRecordingDocumentTitle] itself is left untouched (still used
 * by its own pre-existing unit test) -- this is the function [enterCall] actually calls now. */
internal fun conferenceMediaDocumentTitle(
    baseTitle: String,
    isRecording: Boolean,
    isStreaming: Boolean,
): String = if (isRecording || isStreaming) "● $baseTitle" else baseTitle

/** Design review D10's status-copy table, streaming's own version -- the ONE place this mapping
 * exists. `STARTING` reads "Verbindung wird hergestellt…", matching [conferenceStreamTargetStatusLabel]'s
 * `PENDING` copy, since both describe the same live-verified ~12s async LiveKit-connect window. */
internal fun conferenceStreamStatusLabel(status: ConferenceStreamStatus): String =
    when (status) {
        ConferenceStreamStatus.STARTING -> "Verbindung wird hergestellt …"
        ConferenceStreamStatus.LIVE -> "Live"
        ConferenceStreamStatus.PAUSED -> "Unterbrochen"
        ConferenceStreamStatus.STOPPING -> "Wird beendet …"
        ConferenceStreamStatus.ENDED -> "Beendet"
        ConferenceStreamStatus.FAILED -> "Fehlgeschlagen"
    }

internal fun conferenceStreamStatusColor(status: ConferenceStreamStatus): String =
    when (status) {
        ConferenceStreamStatus.STARTING -> "warning"
        ConferenceStreamStatus.LIVE -> "danger"
        ConferenceStreamStatus.PAUSED -> "secondary"
        ConferenceStreamStatus.STOPPING -> "warning"
        ConferenceStreamStatus.ENDED -> "secondary"
        ConferenceStreamStatus.FAILED -> "danger"
    }

/**
 * Design review D7 -- three DISTINCT states, never collapsed into a binary "streaming: yes/no":
 * `PENDING` ("Verbindung wird hergestellt…", the honest interim state Atkinson/Kay insisted on --
 * see [updateStreamTargetsPanel]), `ACTIVE` ("Live"), `FINISHED`/`FAILED` (ended/failed, with the
 * sanitized [ConferenceStreamTargetStatusDto.failureReason] shown alongside when present).
 */
internal fun conferenceStreamTargetStatusLabel(status: ConferenceStreamTargetStatus): String =
    when (status) {
        ConferenceStreamTargetStatus.PENDING -> "Verbindung wird hergestellt …"
        ConferenceStreamTargetStatus.ACTIVE -> "Live"
        ConferenceStreamTargetStatus.FINISHED -> "Beendet"
        ConferenceStreamTargetStatus.FAILED -> "Fehlgeschlagen"
    }

internal fun conferenceStreamTargetStatusColor(status: ConferenceStreamTargetStatus): String =
    when (status) {
        ConferenceStreamTargetStatus.PENDING -> "warning"
        ConferenceStreamTargetStatus.ACTIVE -> "success"
        ConferenceStreamTargetStatus.FINISHED -> "secondary"
        ConferenceStreamTargetStatus.FAILED -> "danger"
    }

/** [startStreamDialog]'s Layout-Auswahl -- German labels, terminology matching
 * [network.lapis.cloud.shared.domain.ConferenceStreamLayout]'s own KDoc ("Galerie"/"Sprecher" render
 * through LiveKit's Room-Composite web template; "Einzelne Person" is the Chrome-free, template-free
 * `StartParticipantEgress` path). */
internal fun conferenceStreamLayoutLabel(layout: ConferenceStreamLayout): String =
    when (layout) {
        ConferenceStreamLayout.GRID -> "Galerie"
        ConferenceStreamLayout.SPEAKER -> "Sprecher"
        ConferenceStreamLayout.SINGLE_PARTICIPANT -> "Einzelne Person"
    }

/** [startStreamDialog]'s Latenz-Auswahl -- BOTH branches verified live against the real container
 * (see [network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode] KDoc "GO decision"), so
 * unlike a not-yet-verified control this one ships without a go/no-go caveat (design review D10). */
internal fun conferenceStreamLatencyModeLabel(mode: ConferenceStreamLatencyMode): String =
    when (mode) {
        ConferenceStreamLatencyMode.STANDARD -> "Standard"
        ConferenceStreamLatencyMode.LOW_LATENCY -> "Niedrige Latenz"
    }

/**
 * Gates the moderator's "Live-Stream starten …" control -- mirrors the server's OWN gate
 * (`ConferenceStreamingService.startStream`: creator-or-BOARD/ADMIN, streaming configured, no
 * already-active stream for this room) as a UX nicety, exactly like [recordingCanStart] already
 * does for Wave 2's own moderator action -- the server re-checks independently regardless.
 */
internal fun conferenceStreamCanStart(
    canModerate: Boolean,
    streamingAvailable: Boolean,
    activeStream: ConferenceStreamDto?,
): Boolean = canModerate && streamingAvailable && activeStream == null

/** [startStreamDialog]'s destination-checklist bound -- `IConferenceStreamingService.startStream`
 * throws `ConflictException` on an empty or over-cap `destinationIds`; this is the client-side UX
 * pre-check mirroring that bound, never the security boundary itself. */
internal fun conferenceStreamSelectionValid(
    selectedCount: Int,
    maxDestinations: Int,
): Boolean = selectedCount in 1..maxDestinations

/**
 * Design review D2 -- the destination checklist's OWN live summary line, updated on every checkbox
 * toggle: names the SELECTED destinations by LABEL and restates irrevocability in plain German, so
 * the confirm surface stays legible (Norman: "the friction that matters is making sure you know
 * exactly who's about to see this", not proving intent via a retype step, see [startStreamDialog]).
 */
internal fun conferenceStreamStartSummary(selectedLabels: List<String>): String =
    if (selectedLabels.isEmpty()) {
        "Bitte wählen Sie mindestens ein Ziel aus."
    } else {
        "Sie starten jetzt einen Live-Stream zu: ${selectedLabels.joinToString(", ")}. Diese Ziele sind " +
            "sofort öffentlich sichtbar -- die Übertragung kann NICHT zurückgeholt werden, sobald " +
            "Teilnehmende gesprochen haben."
    }

/** [startStreamDialog]'s mandatory, static Hinweis (design review D12/Jobs' verdict item 2 --
 * [IConferenceStreamingService] KDoc "No automatic stream pause during secret ballots"): no UI copy
 * anywhere in this screen claims automatic protection exists. */
internal const val CONFERENCE_STREAM_SECRET_BALLOT_HINWEIS =
    "Bei geheimen Abstimmungen muss der Stream manuell unterbrochen werden. Eine automatische " +
        "Unterbrechung gibt es in dieser Version noch nicht."

/** [streamBanner]'s exact, terminology-locked copy -- mirrors [CONFERENCE_RECORDING_BANNER_TEXT]'s
 * own precedent (a top-level constant so [ConferenceStreamingUiTest] can assert against the SAME
 * literal the UI actually renders). */
internal const val CONFERENCE_STREAM_BANNER_TEXT = "Diese Besprechung wird ab jetzt live gestreamt."

/** [pollInFlightStreamStatus]'s poll interval -- same reasoning as [CONFERENCE_RECORDING_POLL_INTERVAL_MS]
 * (the README's own suggested 15-30s cadence, close to but not faster than `StreamPoller`'s own
 * server-side 10s default tick, `ConferenceStreamingConfig.DEFAULT_POLL_INTERVAL_SECONDS`). */
internal const val CONFERENCE_STREAM_POLL_INTERVAL_MS = 15_000L

/**
 * [pollInFlightStreamStatus]'s "still needs polling?" predicate -- takes the WHOLE DTO, not just
 * [ConferenceStreamDto.status], because the honest interim signal design review D7 requires
 * (per-target `PENDING`, the live-verified ~12s async LiveKit-connect window) can still be true
 * while the top-level status has ALREADY settled to steady-state `LIVE` -- see
 * [network.lapis.cloud.shared.rpc.IConferenceStreamingService.startStream] KDoc for why the
 * top-level `STARTING` state itself is usually too brief to observe (this codebase's `startStream`/
 * `resumeStream` call LiveKit synchronously and return the settled `LIVE`/`FAILED` result in one
 * round trip; `STARTING`/`STOPPING` mainly matter for crash-recovery reconciliation).
 */
internal fun conferenceStreamNeedsPoll(stream: ConferenceStreamDto?): Boolean {
    if (stream == null) return false
    if (stream.status == ConferenceStreamStatus.STARTING || stream.status == ConferenceStreamStatus.STOPPING) return true
    return stream.targets.any { it.status == ConferenceStreamTargetStatus.PENDING }
}

/** [pollInFlightStreamStatus]'s terminal-state fallback lookup -- mirrors [conferenceFindRecordingById]'s
 * own precedent exactly, applied to `listStreams` instead of `listRecordings`. */
internal fun conferenceFindStreamById(
    streams: List<ConferenceStreamDto>,
    streamId: String,
): ConferenceStreamDto? = streams.firstOrNull { it.id == streamId }

/** The in-call detail line's "Live-Stream gestartet von X um HH:MM · Layout" copy -- mirrors
 * [conferenceRecordingStartedLabel]'s own zero-padded 24h time format, with the chosen
 * [ConferenceStreamLayout] appended since (unlike recording) it is a moderator-chosen setting worth
 * surfacing to every participant reading this line. */
internal fun conferenceStreamStartedLabel(
    startedByDisplayName: String,
    startedAt: LocalDateTime,
    layout: ConferenceStreamLayout,
): String {
    val hour = startedAt.hour.toString().padStart(2, '0')
    val minute = startedAt.minute.toString().padStart(2, '0')
    return "Live-Stream gestartet von $startedByDisplayName um $hour:$minute · ${conferenceStreamLayoutLabel(layout)}"
}
