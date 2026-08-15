package network.lapis.cloud.client

import io.kvision.core.Overflow
import io.kvision.form.check.checkBox
import io.kvision.form.select.select
import io.kvision.form.text.text
import io.kvision.form.text.textArea
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.client.livekit.LiveKitRoomSession
import network.lapis.cloud.client.livekit.Track
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceBreakoutAssignmentDto
import network.lapis.cloud.shared.domain.ConferenceBreakoutAssignmentInput
import network.lapis.cloud.shared.domain.ConferenceBreakoutPlanInput
import network.lapis.cloud.shared.domain.ConferenceBreakoutRoomDto
import network.lapis.cloud.shared.domain.ConferenceChatMessage
import network.lapis.cloud.shared.domain.ConferenceGuestConsentAcknowledgmentInput
import network.lapis.cloud.shared.domain.ConferenceGuestConsentDisclaimerDto
import network.lapis.cloud.shared.domain.ConferenceGuestJoinInfoDto
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
import network.lapis.cloud.shared.rpc.IConferenceBreakoutService
import network.lapis.cloud.shared.rpc.IConferenceNotesService
import network.lapis.cloud.shared.rpc.IConferenceRecordingService
import network.lapis.cloud.shared.rpc.IConferenceService
import network.lapis.cloud.shared.rpc.IConferenceStreamingService
import network.lapis.cloud.shared.rpc.IConferenceWhiteboardService
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
 * D1-D11) -- D1/D3/D10 are CLOSED as of Wave 4 "Politur" below; the ONE item still open is D2's
 * first-class camera/microphone permission interstitial (device errors still surface as a plain
 * `guarded {}` toast, see [enterCall]) -- deferred again, not in this wave's scope.
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
 *
 * ## V1.0 Videokonferenzen (Kleinsitzung), Wave 4 "Politur" -- closes D1/D3/D10 from the Wave 1
 * design review
 *
 * Closes three items deliberately deferred during Wave 1's mandatory UI/UX-Design-Team review (root
 * `CLAUDE.md`), per that wave's own review document conditional-go verdict:
 *
 * - **D1 -- single-button "Besprechung jetzt starten".** [renderLobby] no longer asks for a title
 *   up front -- [conferenceDefaultRoomTitle] generates a sensible German date/time default
 *   (`"Besprechung vom DD.MM.YYYY, HH:MM"`), the single button chains `createRoom` -> `joinRoom` ->
 *   [enterCall] in one click, and a moderator-only inline "Bearbeiten" affordance in the in-call
 *   header (not a modal -- renaming is trivially reversible, unlike every OTHER `Modal(...)` in this
 *   file) lets the title be fixed up afterward via the new
 *   [network.lapis.cloud.shared.rpc.IConferenceService.renameRoom] RPC. `baseDocumentTitle` (captured
 *   once at the top of [enterCall]) had to become a `var` so a mid-call rename immediately propagates
 *   into the "● "-prefixed backgrounded-tab title [updateStatusBadgesAndTitle] renders.
 * - **D3 -- speaking-priority grid reflow above [CONFERENCE_GRID_REFLOW_THRESHOLD] (12) participants.**
 *   [conferenceGridLayout] is the pure partition (join-order priority zone, capped at
 *   [CONFERENCE_PRIORITY_ZONE_MAX], overflow + everyone else in a compact filmstrip). The DOM side
 *   (`applyConferenceGridReflow` inside [enterCall]) re-parents existing tile elements between two
 *   zone containers built ONCE (same "grab once, mutate forever" raw-DOM discipline this file already
 *   documents for `gridElement`/`stageElement`) -- never recreated. **Reflow cadence is deliberately
 *   decoupled from raw `RoomEvent.ActiveSpeakersChanged` pushes**: those pushes only update a
 *   `lastSpokeAtMs` timestamp map; a periodic ~2s sweep (mirroring `pollInFlightRecordingStatus`'s own
 *   shape) is the SOLE trigger that actually calls the reflow -- LiveKit's speaker-change events fire
 *   on sub-second speaking-level transitions, and reflowing the grid on every one of them would
 *   produce a strobing, unusable layout at 25-person scale, exactly the failure mode D3 exists to
 *   prevent. Below the threshold, the layout is byte-for-byte Wave 1's original single flat grid --
 *   the priority zone's CSS is reset to the original `minmax(200px, 1fr)` rule, never left at the
 *   larger priority-tile size.
 * - **D10 -- named connection-state machine.** [ConferenceConnectionState] (`Disconnected`/
 *   `Connecting`/`Connected`/`Reconnecting`/`Failed`/`Ended`) replaces the Wave 1-3 ad-hoc `leftCall`
 *   boolean entirely (zero remaining references) -- [conferenceConnectionReduce] is the ONE place a
 *   transition happens, unlisted (state, event) pairs are ignored (return the same state) rather than
 *   throwing, and `Ended` is terminal. `renderConnectionState` (inside [enterCall]) is what makes this
 *   an ACTUALLY UI-driving state machine, not just an internal label: it disables
 *   `micButton`/`cameraButton`/`screenShareButton`/`chatSendButton` while not `Connected`, and shows a
 *   calm, non-alarming "Verbindung unterbrochen -- wird automatisch neu verbunden …" status line while
 *   `Reconnecting` (LiveKit's own `RoomEvent.Reconnecting`/`.Reconnected`, now wired into
 *   `LiveKitRoomSession`). **Security-relevant**: a forcibly-terminated/kicked session (server-side
 *   `endRoom`/`removeParticipant`) reaches `Ended` from BOTH `Connected` and `Reconnecting` via the
 *   same `onDisconnected -> DisconnectedSignal` path Wave 1-3 already had -- there is no reachable
 *   state where the UI still shows "connected" after the server has actually closed the room.
 *
 * ## V1.0 Videokonferenzen (Kleinsitzung), Wave 5 "Föderations-Gastbeitritt" -- federated guest join
 *
 * Load-bearing decisions (D-numbers match the Wave 5 design review):
 *
 * - **D1/D2/D3 -- moderator "Gastzugang:" row, its own spatially separate control group** (never a
 *   checkbox -- no precedent in this client for a checkbox that fires a server mutation, and it
 *   would force an optimistic-UI violation this file's own rule forbids). Built UNCONDITIONALLY in
 *   [enterCall] (not `if (canModerate)`, unlike the recording/streaming rows) -- the status badge
 *   ("Gäste zugelassen"/"Nur Mitglieder") is visible to EVERY participant, only the toggle/invite
 *   buttons are moderator-gated.
 * - **D4 -- no creation-time toggle.** [network.lapis.cloud.shared.domain.ConferenceRoomInput
 *   .allowFederationGuests] stays in the DTO for API completeness/tests; the client never sets it
 *   at [renderLobby]'s single-button creation flow (Wave 4 D1 deliberately deleted the lobby
 *   creation form). Guest access is always enabled from INSIDE a running room.
 * - **D5 -- lobby room cards show `"Gastzugang offen"`** ([renderRoomCard]) so an AKTIV member can
 *   see outsiders may be present before joining, same vocabulary as the in-call D3 badge.
 * - **D6 -- guest entry via a plain room-id field** ([renderGuestLobby]), client-side UUID-shape
 *   validated ([Validation.looksLikeRoomId]) before any RPC fires. The moderator's own "Einladung
 *   kopieren" affordance ([conferenceInviteText]) is the out-of-band invite path -- no
 *   `#/conference/:roomId` deep link, see [Routes.CONFERENCE] KDoc.
 * - **D7-D10/D17 -- the two-layer consent modal** ([conferenceGuestConsentModal]): layer 1
 *   (org line + [network.lapis.cloud.shared.domain.ConferenceGuestConsentDisclaimerDto.headline]/
 *   `.keyPoints`, `role="note"`) above the fold, layer 2 (`.text`) in an always-visible,
 *   `tabindex="0"` scroll box beneath it. `version`/`sha256` are read from the JUST-fetched DTO,
 *   never hardcoded, resent verbatim via [conferenceGuestConsentInputOf].
 * - **D11 -- roster guest badge BEFORE the name**, matching the navbar's own `GuestBadge.kt` call
 *   site (`App.kt`) -- the name div carries `flex-grow-1`, so a badge appended after it would be
 *   flung to the right edge, detached from the name it qualifies. See [refreshRoster].
 * - **D12 -- tile guest marker is a DEDICATED top-left pill** (`ConferenceTileEntry.guestBadgeEl`),
 *   never a text suffix on [conferenceTileLabel] -- the tile's own ellipsis-truncated name badge
 *   would eat a `" · Gast"` suffix first for exactly the federated guests whose display name comes
 *   from a foreign server's unclamped claim.
 * - **D14 -- a guest can see WHO the moderator is.** [renderGuestLobby] synthesizes a local
 *   [ConferenceRoomDto] from [network.lapis.cloud.shared.domain.ConferenceGuestJoinInfoDto]'s real
 *   `createdByMemberId`/`createdByDisplayName` (never a blanked-out placeholder) -- `canModerate`
 *   still structurally evaluates `false` for the guest ([conferenceIsModerator] compares the
 *   CALLER's own id, which a GAST can never equal since `createRoom` is AKTIV-only), so no
 *   moderator affordance leaks.
 * - **D16 -- revoking guest access asks first.** [confirmDialog] warns that already-connected
 *   guests will be disconnected immediately before the moderator confirms (Norman: visible system
 *   status) -- see the "Gastzugang beenden" click handler in [enterCall].
 */
fun renderConferenceScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 960.px
            marginTop = 24.px
        }
    root.h1(tr("Videokonferenz"))
    val statusLine = root.div(tr("Wird geladen …")) { addCssClasses("text-muted small") }
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
                    statusLine.content = tr("Videokonferenzen konnten nicht geladen werden.")
                    return@launch
                }
        statusLine.hide()
        if (!availability.enabled) {
            renderDisabledPanel(lobbyPanel)
            return@launch
        }
        // V1.0 Videokonferenzen, Wave 5 "Föderations-Gastbeitritt" -- a federated guest gets an
        // entirely different lobby: no "Besprechung jetzt starten" (createRoom is AKTIV-only) and
        // no "Aktive Besprechungen" list (listActiveRooms is AKTIV-only and would 403 on every
        // load, exactly the confusing generic toast this wave exists to avoid -- see design review
        // "Accepted as planned"). See [renderGuestLobby].
        if (AppState.session?.isGuest == true) {
            renderGuestLobby(lobbyPanel, callPanel, setActiveSession)
        } else {
            renderLobby(lobbyPanel, callPanel, setActiveSession)
        }
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
    alert.div(tr("Videokonferenz ist auf diesem Server nicht konfiguriert.")) { addCssClass("fw-bold") }
    alert.div(tr("Bitte wenden Sie sich an Ihre Administration, falls Sie diese Funktion benötigen.")) {
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

    // Wave 4 "Politur", D1: single-button "start now" flow -- no title-entry form for the common,
    // spontaneous case. See file KDoc "Wave 4 -- D1".
    lobbyPanel.h2(tr("Neue Besprechung"))
    val startButton = lobbyPanel.button(tr("Besprechung jetzt starten"), style = ButtonStyle.PRIMARY)

    lobbyPanel.h2(tr("Aktive Besprechungen"))
    val refreshRow = lobbyPanel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val refreshButton = refreshRow.button(tr("Aktualisieren"), style = ButtonStyle.OUTLINESECONDARY)
    val roomsPanel = lobbyPanel.vPanel(spacing = 8)

    // Wave 2 "Aufzeichnung", D9: recordings OUTLIVE their room, so this section belongs in the
    // LOBBY, not only inside a live call -- see ConferenceRecordingsPanel.kt's own file KDoc.
    val recordingsSection = lobbyPanel.vPanel(spacing = 8)

    fun loadRooms() {
        roomsPanel.removeAll()
        roomsPanel.div(tr("Wird geladen …")) { addCssClasses("text-muted small") }
        AppScope.launch {
            val rooms = guarded { rpcService<IConferenceService>().listActiveRooms() } ?: return@launch
            roomsPanel.removeAll()
            if (rooms.isEmpty()) {
                roomsPanel.div(tr("Derzeit keine aktive Besprechung.")) { addCssClasses("text-muted small") }
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

    // Reused by BOTH the room-card join flow (unchanged behaviour) and the new single button below
    // -- one place, not duplicated at each call site.
    val refreshLobby: () -> Unit = {
        loadRooms()
        renderConferenceRecordingsPanel(recordingsSection)
    }

    refreshButton.onClick { refreshLobby() }
    renderConferenceRecordingsPanel(recordingsSection)

    // D1: create -> join -> enterCall in one click. Uses the exact SAME two, already-authorization-
    // gated RPC calls the old form-based flow used (createRoom/joinRoom, both server-side
    // requireActiveMembership-gated, see IConferenceService KDoc) -- no new, weaker RPC surface.
    startButton.onClick {
        startButton.disabled = true
        startButton.text = tr("Wird gestartet …")
        AppScope.launch {
            val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            val room =
                guarded {
                    rpcService<IConferenceService>().createRoom(ConferenceRoomInput(title = conferenceDefaultRoomTitle(now)))
                }
            if (room == null) {
                startButton.disabled = false
                startButton.text = tr("Besprechung jetzt starten")
                return@launch
            }
            // The room now exists and is visible in "Aktive Besprechungen" regardless of what
            // joinRoom does next -- Norman: visible system status, recoverable if the next step
            // fails (see file KDoc "Wave 4 -- D1").
            refreshLobby()
            val token = guarded { rpcService<IConferenceService>().joinRoom(room.id) }
            startButton.disabled = false
            startButton.text = tr("Besprechung jetzt starten")
            if (token != null) {
                enterCall(ConferenceCallTarget.MainRoom(room), token, lobbyPanel, callPanel, setActiveSession, refreshLobby)
            }
            // else: guarded {} already toasted the error; the room stays in the already-refreshed
            // list for a manual "Beitreten" retry -- never silently lost.
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
    infoCell.div(gettext("Erstellt von %1 · %2 Teilnehmende", room.createdByDisplayName, room.liveParticipantCount)) {
        addCssClasses("text-muted small")
    }
    if (room.myRole == ConferenceRole.MODERATOR) {
        infoCell.statusBadge(tr("Sie sind Moderator"), "primary")
    }
    // V1.0 Videokonferenzen, Wave 5 "Föderations-Gastbeitritt", design review D5 -- an AKTIV member
    // deciding whether to join deserves to know outsiders may be present BEFORE joining, not only
    // after. Same "Gastzugang"/"info" vocabulary the in-call status badge (D3) uses.
    if (room.allowFederationGuests) {
        infoCell.statusBadge(tr("Gastzugang offen"), "info")
    }
    val joinButton = card.button(tr("Beitreten"), style = ButtonStyle.PRIMARY)
    joinButton.onClick {
        joinButton.disabled = true
        AppScope.launch {
            val token = guarded { rpcService<IConferenceService>().joinRoom(room.id) }
            joinButton.disabled = false
            if (token != null) {
                enterCall(ConferenceCallTarget.MainRoom(room), token, lobbyPanel, callPanel, setActiveSession, onReturnedToLobby)
            }
        }
    }
}

// ================================================================================================
// Guest lobby: V1.0 Videokonferenzen, Wave 5 "Föderations-Gastbeitritt"
// ================================================================================================

/**
 * A federated guest's own lobby -- deliberately NOT [renderLobby] with a few items hidden: no
 * "Besprechung jetzt starten" button ([IConferenceService.createRoom] is AKTIV-only) and no
 * "Aktive Besprechungen" list ([IConferenceService.listActiveRooms] is AKTIV-only and would 403
 * on every load, producing exactly the confusing generic "Keine Berechtigung" toast this whole
 * wave exists to avoid -- see [AppState.guarded] KDoc). A guest instead pastes a room id from an
 * out-of-band invitation (see [conferenceInviteText]) into a plain text field.
 *
 * No `#/conference/:roomId` deep link -- deliberately deferred, see [IConferenceService] KDoc
 * "Federated guest join": this codebase has zero parameterized Navigo routes today, and
 * introducing one was judged the wrong risk to add inside a trust-boundary wave.
 */
private fun renderGuestLobby(
    lobbyPanel: SimplePanel,
    callPanel: SimplePanel,
    setActiveSession: (LiveKitRoomSession?) -> Unit,
) {
    lobbyPanel.removeAll()
    lobbyPanel.show()

    lobbyPanel.h2(tr("Als Gast beitreten"))
    lobbyPanel.div(
        tr(
            "Sie sind über Ihren eigenen Heimserver angemeldet. Geben Sie die Raum-Kennung aus Ihrer " +
                "Einladung ein, um einer Besprechung auf diesem Server beizutreten.",
        ),
    ) { addCssClasses("text-muted small") }

    val roomIdInput = lobbyPanel.text(label = tr("Raum-Kennung aus Ihrer Einladung"))
    val errorBox =
        lobbyPanel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val blockedBox =
        lobbyPanel.div {
            addCssClasses("alert alert-warning")
            hide()
        }
    val continueButton = lobbyPanel.button(tr("Weiter"), style = ButtonStyle.PRIMARY)

    continueButton.onClick {
        errorBox.hide()
        blockedBox.hide()
        val raw = roomIdInput.value.orEmpty().trim()
        if (!Validation.looksLikeRoomId(raw)) {
            errorBox.content = tr("Diese Raum-Kennung ist ungültig. Bitte kopieren Sie sie vollständig aus Ihrer Einladung.")
            errorBox.show()
            return@onClick
        }
        continueButton.disabled = true
        AppScope.launch {
            val info = guarded { rpcService<IConferenceService>().getGuestJoinInfo(raw) }
            continueButton.disabled = false
            if (info == null) return@launch // guarded {} already toasted (unknown id, rate limit, ...)

            val reason = conferenceGuestJoinBlockedReason(info)
            if (reason != null) {
                blockedBox.content = reason
                blockedBox.show()
                // The entered id stays in the field, button stays live -- a retry (e.g. after the
                // moderator flips the toggle) is one click, not a re-paste.
                return@launch
            }

            conferenceGuestConsentModal(info) { consent ->
                AppScope.launch {
                    val token = guarded { rpcService<IConferenceService>().joinRoom(info.roomId, consent) }
                    if (token != null) {
                        // Design review D14: a guest cannot call getRoom (AKTIV-only), so a minimal
                        // local ConferenceRoomDto is synthesized from the two calls just made. It
                        // carries the REAL createdByMemberId/createdByDisplayName from
                        // getGuestJoinInfo -- the guest can see WHO the moderator is (D14), even
                        // though `canModerate` still structurally evaluates false for them
                        // (conferenceIsModerator compares the caller's OWN id, which a GAST can
                        // never equal, since createRoom is AKTIV-only -- see that function's KDoc).
                        val syntheticRoom =
                            ConferenceRoomDto(
                                id = info.roomId,
                                title = info.title,
                                description = "",
                                livekitRoomName = token.livekitRoomName,
                                createdByMemberId = info.createdByMemberId,
                                createdByDisplayName = info.createdByDisplayName,
                                createdAt = token.expiresAt,
                                endedAt = null,
                                active = true,
                                maxParticipants = 0,
                                liveParticipantCount = 0,
                                myRole = token.role,
                                allowFederationGuests = info.allowsFederationGuests,
                            )
                        enterCall(ConferenceCallTarget.MainRoom(syntheticRoom), token, lobbyPanel, callPanel, setActiveSession) {
                            renderGuestLobby(lobbyPanel, callPanel, setActiveSession)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Pure, jsTest-covered: the honest, specific German reason a guest cannot join, or `null` if they
 * can. The reason comes from DTO DATA ([info]'s own fields), never from a server exception message
 * -- kilua-rpc never transmits those, see [AppState.guarded] KDoc. See
 * [ConferenceGuestJoinInfoDto] KDoc for the full rationale.
 */
internal fun conferenceGuestJoinBlockedReason(info: ConferenceGuestJoinInfoDto): String? =
    when {
        !info.roomActive -> gettext("Die Besprechung „%1\" ist bereits beendet.", info.title)
        !info.allowsFederationGuests ->
            gettext(
                "Die Besprechung „%1\" lässt derzeit keine Gäste anderer Server zu. " +
                    "Bitten Sie die Moderation, den Gastzugang für diesen Raum freizuschalten.",
                info.title,
            )
        else -> null
    }

/**
 * Wave 5's most security-sensitive new interaction. [ConferenceGuestJoinInfoDto.disclaimer]'s
 * version/sha256 are held as read-only local values captured from the JUST-fetched DTO -- never
 * hardcoded, never rendered as editable fields, never re-derived client-side -- and resent
 * verbatim to `joinRoom` (via [conferenceGuestConsentInputOf]) so the server can constant-time-
 * verify the guest saw the CURRENT text. A stale client that displays an old text therefore cannot
 * silently bypass a text update: it resends the OLD version/hash it actually displayed, and the
 * server rejects it.
 *
 * Design review D7/D9: two layers -- layer 1 ([ConferenceGuestConsentDisclaimerDto.headline]/
 * `.keyPoints`, exactly two entries) rendered above the fold, unscrollable-past; layer 2 (the full
 * `.text`) in a scroll box beneath it, always present, never hidden behind a "Details anzeigen"
 * link. The org line ("Gastgeberin dieser Besprechung: {organizationName}") sits directly above
 * layer 1 -- self-evidently in view, no sentence needed explaining where to look for it (D8).
 * `role="note"` on the layer-1 block, `tabindex="0"` on the scroll box -- design review D17.
 */
private fun conferenceGuestConsentModal(
    info: ConferenceGuestJoinInfoDto,
    onConfirm: (ConferenceGuestConsentAcknowledgmentInput) -> Unit,
) {
    val d = info.disclaimer
    val modal = Modal(caption = tr("Als Gast beitreten"))
    modal.div(gettext("Besprechung: %1", info.title)) { addCssClasses("fw-bold") }
    modal.div(gettext("Gastgeberin dieser Besprechung: %1", info.organizationName)) { addCssClasses("fw-bold mb-1") }
    modal.div(
        tr(
            "Diese Organisation ist für die Verarbeitung Ihrer Audio-, Video- und Chatdaten in dieser " +
                "Besprechung verantwortlich -- nicht Ihr eigener Heimserver.",
        ),
    ) { addCssClasses("text-muted small mb-2") }

    val layerOne = modal.div { setAttribute("role", "note") }
    layerOne.div(d.headline) { addCssClasses("fw-bold mb-2") }
    d.keyPoints.forEach { point -> layerOne.div(point) { addCssClasses("mb-2") } }

    val scrollBox =
        modal.div {
            addCssClasses("border rounded p-2 mb-2")
            maxHeight = 320.px
            overflow = Overflow.AUTO
            setAttribute("tabindex", "0")
        }
    scrollBox.content = d.text

    modal.div(gettext("Hinweistext-Version %1", d.version)) { addCssClasses("text-muted small mb-2") }

    modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button(tr("Hinweis gelesen -- als Gast beitreten"), style = ButtonStyle.PRIMARY).apply {
            onClick {
                modal.hide()
                onConfirm(conferenceGuestConsentInputOf(d))
            }
        },
    )
    modal.show()
}

/**
 * Extracted so a jsTest can PROVE the input sent to `joinRoom` is derived from the fetched DTO and
 * never hardcoded -- see [conferenceGuestConsentModal] KDoc.
 */
internal fun conferenceGuestConsentInputOf(d: ConferenceGuestConsentDisclaimerDto): ConferenceGuestConsentAcknowledgmentInput =
    ConferenceGuestConsentAcknowledgmentInput(consentVersion = d.version, consentSha256 = d.sha256)

/**
 * D6 fallback: clipboard `writeText` rejected, was blocked, or the API is absent entirely -- never
 * a silent no-op. A small modal with a read-only, pre-selected text area the moderator can still
 * copy by hand (Ctrl/Cmd+C).
 */
private fun showInviteTextFallback(
    parent: SimplePanel,
    inviteText: String,
) {
    val modal = Modal(caption = tr("Einladung kopieren"))
    modal.div(tr("Automatisches Kopieren war nicht möglich. Markieren Sie den Text unten und kopieren Sie ihn manuell.")) {
        addCssClasses("text-muted small mb-2")
    }
    val field = modal.textArea(value = inviteText, rows = 5) { addCssClasses("font-monospace") }
    field.getElement()?.setAttribute("readonly", "readonly")
    modal.addButton(Button(tr("Schließen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.show()
    field.getElement()?.let { el -> (el.asDynamic()).select() }
}

/**
 * Design review D6, decided copy. `origin` is `window.location.origin`, never hardcoded -- see the
 * moderator "Einladung kopieren" call site.
 *
 * Security-audit fix: [organizationName] is nullable -- the caller has no organization name in
 * scope at the moderator "Einladung kopieren" call site by default (unlike the guest pre-join
 * lobby, which already has it from [network.lapis.cloud.shared.domain.ConferenceGuestJoinInfoDto]).
 * Substituting [roomTitle] for a missing organization name (the original bug: "Sie sind zur
 * Besprechung „X" bei X eingeladen.") is worse than omitting the clause entirely, so a `null`/blank
 * [organizationName] simply drops "bei {org}" rather than fabricating a wrong one.
 */
internal fun conferenceInviteText(
    origin: String,
    roomId: String,
    roomTitle: String,
    organizationName: String?,
): String {
    val hostedByClause = if (organizationName.isNullOrBlank()) "" else gettext(" bei %1", organizationName)
    return gettext(
        "Sie sind zur Besprechung „%1\"%2 eingeladen.\n" +
            "1. Melden Sie sich über Ihren eigenen Heimserver an.\n" +
            "2. Öffnen Sie %3/#/conference\n" +
            "3. Geben Sie dort diese Raum-Kennung ein: %4",
        roomTitle,
        hostedByClause,
        origin,
        roomId,
    )
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
    /**
     * V1.0 Videokonferenzen, Wave 5 "Föderations-Gastbeitritt", design review D12 -- a DEDICATED,
     * separately-positioned marker (top-left corner, `micBadge` owns top-right, `nameBadge` owns
     * bottom-left), never a suffix appended to `nameBadge`'s own text. `nameBadge` truncates with
     * ellipsis at 85% tile width, so a `" · Gast"` suffix would be the FIRST thing eaten for any
     * long display name -- exactly the federated guests whose `display_name` comes from a foreign
     * server's unclamped `name` claim. Hidden by default, shown/populated by `setTileGuest`.
     */
    val guestBadgeEl: HTMLElement,
    var hasCamera: Boolean = false,
    var hasMic: Boolean = false,
)

/** Wave 4, D3 -- which zone a tile's DOM styling currently reflects. [FLAT] is the <= threshold,
 * unreflowed case (byte-for-byte Wave 1-3 sizing); [PRIORITY_REFLOWED]/[COMPACT] are the two zones
 * once reflowed. See [enterCall]'s `setTileZoneStyle`. */
private enum class ConferenceTileZone { FLAT, PRIORITY_REFLOWED, COMPACT }

/** Wave 4, D3 -- the concrete pixel/padding values [enterCall]'s `setTileZoneStyle` applies per
 * [ConferenceTileZone], per the design review's own "Decided" sizing table. */
private data class ConferenceTileZoneStyle(
    val minHeightPx: Int,
    val initialsFontPx: Int,
    val badgeFontPx: Int,
    val badgePadding: String,
)

private fun enterCall(
    target: ConferenceCallTarget,
    joinToken: ConferenceJoinTokenDto,
    lobbyPanel: SimplePanel,
    callPanel: SimplePanel,
    setActiveSession: (LiveKitRoomSession?) -> Unit,
    onReturnedToLobby: () -> Unit,
) {
    lobbyPanel.hide()
    callPanel.removeAll()
    callPanel.show()

    // V1.0 Videokonferenzen, Wave 6 "Breakout-Räume" -- every IConferenceService/
    // IConferenceRecordingService/IConferenceStreamingService call below targets the PARENT room's
    // id either way (see [ConferenceCallTarget] KDoc); `room` keeps its pre-Wave-6 meaning
    // throughout the rest of this function so the bulk of this function's body needs no further
    // change. `isBreakout`/`breakoutRoomId` are the two places behavior actually diverges.
    val room =
        when (target) {
            is ConferenceCallTarget.MainRoom -> target.room
            is ConferenceCallTarget.BreakoutRoom -> target.parentRoom
        }
    val isBreakout = target is ConferenceCallTarget.BreakoutRoom
    val breakoutRoomId = (target as? ConferenceCallTarget.BreakoutRoom)?.breakoutRoomId
    val breakoutLabel = (target as? ConferenceCallTarget.BreakoutRoom)?.label

    // Wave 2 "Aufzeichnung": captured BEFORE anything can prefix it, restored on every path back to
    // the Lobby -- see file KDoc "`document.title` prefix". Wave 4, D1: now a `var` -- a mid-call
    // rename ([titleEditButton] below) must update the base the "● " prefix builds on top of.
    var baseDocumentTitle = document.title

    val localMemberId = AppState.session?.memberId
    // D-item "moderator-only actions": a UX nicety over the server's own re-checked authority (see
    // file KDoc "Route/nav posture") -- compares the CALLER's own member id to the room's creator,
    // OR a global BOARD/ADMIN escalation, exactly matching `IConferenceService.endRoom`/
    // `.removeParticipant`'s server-side gate (see [IConferenceService] KDoc "Two-tier role model").
    // Wave 6 -- unconditionally `false` inside a breakout call: there is no breakout-room-scoped
    // moderator concept (see [network.lapis.cloud.shared.rpc.IConferenceBreakoutService] KDoc "No
    // breakout-room moderator"). Every moderating action (create/assign/recall breakout rooms, "Für
    // alle beenden", recording/streaming, guest access, rename) is only ever exercised from the MAIN
    // room -- a moderator physically inside a breakout room has no way to end the meeting or recall
    // without first clicking "Zurück zum Hauptraum". Deliberate V1 scope cut, stated here so a
    // reviewer does not mistake it for an oversight.
    val canModerate =
        !isBreakout &&
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
    // V1.0 Videokonferenzen, Wave 5 "Föderations-Gastbeitritt" -- identity -> homeserverUrl for
    // every CURRENTLY KNOWN guest participant, populated by `refreshGuestHomeservers` (a deliberate
    // new fetch: `listParticipants` is otherwise called nowhere in this client, the in-call roster
    // is built from `tiles`/LiveKit events, not from it). Drives both the roster badge (D11) and
    // the tile pill (D12).
    val guestHomeserverByIdentity = mutableMapOf<String, String>()
    // Security-audit fix -- see `scheduleGuestHomeserverRefresh` KDoc: coalesces a burst of
    // `onParticipantJoined` events (e.g. filling a room to the participant cap) into a single
    // trailing `refreshGuestHomeservers` call instead of one `listParticipants` RPC (itself fanning
    // out into an outbound LiveKit admin call) PER joining participant.
    var guestHomeserverRefreshJob: Job? = null
    var gridElement: HTMLElement? = null
    var stageElement: HTMLElement? = null
    // Wave 4, D3: the two grid sub-containers -- created ONCE inside `gridDiv`'s own
    // `addAfterInsertHook` (see below), never recreated by `applyConferenceGridReflow`, same
    // "grab once, mutate forever" discipline as `gridElement`/`stageElement` themselves.
    var priorityZoneElement: HTMLElement? = null
    var compactZoneElement: HTMLElement? = null
    // Wave 4, D3: last time (epoch ms) each identity was reported as an active speaker -- updated on
    // EVERY `onActiveSpeakersChanged` push, but deliberately never itself triggers a reflow (see file
    // KDoc "D3" for why the periodic sweep, not this map's mutation, is the sole trigger).
    val lastSpokeAtMs = mutableMapOf<String, Long>()
    var activeScreenShare: Pair<String, Track>? = null
    // Wave 4, D10: replaces the Wave 1-3 ad-hoc `leftCall` boolean entirely -- see file KDoc "D10"
    // and [ConferenceConnectionState]/[conferenceConnectionReduce].
    var connectionState: ConferenceConnectionState = ConferenceConnectionState.Disconnected
    var micEnabled = true
    var cameraEnabled = true
    var screenShareEnabled = false
    var chatOpen = false
    var unreadChatCount = 0

    // V1.0 Videokonferenzen, Wave 6 "Breakout-Räume" -- persistent, UNCONDITIONAL disclosure at the
    // very top of the call panel whenever this IS a breakout call. Design review verdict (locked
    // copy): built in the shape of `recordingDetailLine`/`streamDetailLine` (always-rendered
    // `text-muted small`), NEVER in the shape of `recordingBanner` (bordered box + "Verstanden"
    // button) -- an acked/dismissible banner would defeat the purpose the moment a participant
    // clicks it away and a recording starts on the main room five minutes later. Rendered
    // regardless of the main room's CURRENT recording/streaming state (a moderator could start
    // recording in Main AFTER some participants are already in a breakout, and those participants
    // have no other way to learn about it) -- see
    // [network.lapis.cloud.shared.rpc.IConferenceBreakoutService] KDoc
    // "DSGVO/transparency: breakout audio/video is NEVER captured by a main-room recording/stream".
    if (isBreakout) {
        callPanel.div(
            tr("Hinweis: Eine im Hauptraum laufende Aufzeichnung oder ein Live-Stream erfasst dieses Gespräch nicht."),
        ) { addCssClasses("text-muted small") }
    }

    // --- Title row + Wave 4 D1 inline rename (moderator-only) -------------------------------------
    // Container created HERE for correct DOM position (must render above the control bar); its
    // actual content is filled in further below by [renderTitleViewMode], once
    // [updateStatusBadgesAndTitle] exists for the rename's own document.title refresh -- see that
    // call site's own comment for why this two-step split is deliberate, not an oversight.
    // Wave 6: a breakout call's title carries the breakout room's own label, so a participant
    // switched between rooms always sees at a glance which one they are currently in.
    var roomTitle = if (isBreakout) gettext("%1 – %2", room.title, breakoutLabel.orEmpty()) else room.title
    val titleRow = callPanel.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    callPanel.div(
        when {
            isBreakout -> tr("Sie sind in einem Breakout-Raum.")
            canModerate -> tr("Sie sind Moderator dieser Besprechung.")
            else -> tr("Sie nehmen als Teilnehmer teil.")
        },
    ) { addCssClasses("text-muted small") }

    // Wave 4, D10: a calm, non-alarming status line for Connecting/Reconnecting -- see
    // [renderConnectionState] (declared further below, once every button it disables/enables
    // exists) and file KDoc "D10".
    val connectionStatusLine = callPanel.div { addCssClasses("text-muted small") }
    connectionStatusLine.hide()

    // --- Control bar (persistent, D5: never hover-only) -----------------------------------------
    val controlsRow = callPanel.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    val micButton = controlsRow.button(tr("Mikrofon aus"), style = ButtonStyle.OUTLINESECONDARY)
    val cameraButton = controlsRow.button(tr("Kamera aus"), style = ButtonStyle.OUTLINESECONDARY)
    val screenShareButton = controlsRow.button(tr("Bildschirm teilen"), style = ButtonStyle.OUTLINESECONDARY)
    val chatToggleButton = controlsRow.button(tr("Chat"), style = ButtonStyle.OUTLINESECONDARY)
    // V1.0 Videokonferenzen, Wave 7 "Whiteboard" -- same toggle-button-in-controlsRow shape as
    // chatToggleButton, wired further below once whiteboardPanel/whiteboardController exist.
    // Review fix: gated on `!isBreakout`, same as `canModerate`/`pollInFlightRecordingStatus` above.
    // Every IConferenceWhiteboardService RPC below is keyed on `room.id` -- the PARENT room's id,
    // never a breakout room's own id (see this function's own top-of-function comment on `room`) --
    // and `requireOpenParticipation` on the server checks that id against ConferenceParticipationTable,
    // which a breakout participant still satisfies for the MAIN room. Without this gate, participants
    // physically inside DIFFERENT breakout rooms (or back in the main room) would silently share and
    // mutate the exact same server-side whiteboard bucket despite only ever seeing each other's LIVE
    // strokes if co-located in the same breakout room. No breakout-room-scoped whiteboard exists in V1
    // (deliberate scope cut, mirrors Wave 6's "No breakout-room moderator").
    val whiteboardToggleButton =
        if (!isBreakout) controlsRow.button(tr("Whiteboard"), style = ButtonStyle.OUTLINESECONDARY) else null
    // V1.0 Videokonferenzen, Wave 8 "Geteilte Notizen" -- same `!isBreakout` gate as
    // whiteboardToggleButton immediately above, and for the identical reason (Wave 7's own audited
    // "breakout whiteboard bleeds into main room" fix, task-list item #42 -- see
    // ConferenceNotesController KDoc / IConferenceNotesService KDoc for why this closes off, by
    // construction, the same class of bug for notes). No breakout-room-scoped notes exist in V1.
    val notesToggleButton =
        if (!isBreakout) controlsRow.button(tr("Notizen"), style = ButtonStyle.OUTLINESECONDARY) else null
    // Wave 6: inside a breakout room, "Zurück zum Hauptraum" is the everyday, low-stakes, FREQUENT
    // action and reads as the confident default (PRIMARY); "Besprechung ganz verlassen" is the
    // rarer, heavier one (SECONDARY) -- a deliberate INVERSION of the main room's own button-weight
    // convention, where "Verlassen" alone is the everyday action. Flagged explicitly here so a
    // future reviewer does not "fix" this back to match the main room by reflex (design review
    // verdict).
    val backToMainButton =
        if (isBreakout) {
            controlsRow.button(tr("Zurück zum Hauptraum"), style = ButtonStyle.PRIMARY).apply { addCssClass("ms-2") }
        } else {
            null
        }
    val leaveButton =
        controlsRow.button(if (isBreakout) tr("Besprechung ganz verlassen") else tr("Verlassen"), style = ButtonStyle.SECONDARY)
    leaveButton.addCssClass("ms-2")

    // D5/D6: "end for everyone" gets its own, spatially separate row -- never adjacent to "Verlassen"
    // (Tesler: near-identical destructive actions placed next to each other is a classic slip-inducing
    // layout). Not rendered at all for a plain participant, same "don't tease an action the server
    // will reject" posture `AuctionScreen.kt`'s own ADMIN-only Verwaltung panel documents. Wave 3, D5:
    // this row stays reserved for "Für alle beenden" ONLY -- recording/streaming controls each get
    // their OWN, further spatially separate row below (see [recordingControlsRowRef]/
    // [streamingControlsRowRef]), never sharing this one. Wave 6: breakout-room create/recall gets
    // its OWN row too (see [breakoutControlsRowRef] below) -- never this one either. `canModerate` is
    // always `false` inside a breakout call (see its own KDoc above), so this naturally never
    // renders there.
    val endButton =
        if (canModerate) {
            val moderatorRow = callPanel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
            moderatorRow.div(tr("Moderator:")) { addCssClasses("text-muted small") }
            moderatorRow.button(tr("Für alle beenden"), style = ButtonStyle.OUTLINEDANGER)
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
        recordingRow.div(tr("Aufzeichnung:")) { addCssClasses("text-muted small") }
        recordingControlsRowRef = recordingRow

        val streamingRow = callPanel.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
        streamingRow.div(tr("Live-Stream:")) { addCssClasses("text-muted small") }
        streamingControlsRowRef = streamingRow
    }

    // V1.0 Videokonferenzen, Wave 5 "Föderations-Gastbeitritt" -- design review D1/D3: its OWN,
    // third spatially separate control row (never a checkbox -- D1 rejects the plan's checkbox
    // proposal: no precedent in this client for a checkbox that fires a server mutation on change,
    // and it would force an optimistic-UI violation this file's own "only update UI state once the
    // guarded {} call's result confirms success" rule forbids). Built UNCONDITIONALLY -- the status
    // badge is visible to every participant (D3: an ordinary AKTIV member deserves to know the room
    // is open to another organization's members too); only the buttons are moderator-gated.
    val guestAccessRow = callPanel.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    guestAccessRow.div(tr("Gastzugang:")) { addCssClasses("text-muted small") }
    var guestAccessOpen = room.allowFederationGuests
    val guestAccessBadge = guestAccessRow.statusBadge("", "secondary")
    var guestAccessToggleButton: Button? = null
    var guestAccessInviteButton: Button? = null

    fun updateGuestAccessUi() {
        guestAccessBadge.content = if (guestAccessOpen) tr("Gäste zugelassen") else tr("Nur Mitglieder")
        guestAccessBadge.removeCssClass("text-bg-secondary")
        guestAccessBadge.removeCssClass("text-bg-info")
        guestAccessBadge.addCssClass(if (guestAccessOpen) "text-bg-info" else "text-bg-secondary")
        guestAccessToggleButton?.text = if (guestAccessOpen) tr("Gastzugang beenden") else tr("Gäste anderer Server zulassen")
        guestAccessToggleButton?.removeCssClass("btn-warning")
        guestAccessToggleButton?.removeCssClass("btn-outline-danger")
        guestAccessToggleButton?.addCssClass(if (guestAccessOpen) "btn-outline-danger" else "btn-warning")
        if (guestAccessOpen) guestAccessInviteButton?.show() else guestAccessInviteButton?.hide()
    }
    updateGuestAccessUi()

    if (canModerate) {
        val toggleButton = guestAccessRow.button("", style = ButtonStyle.WARNING)
        guestAccessToggleButton = toggleButton
        val inviteButton = guestAccessRow.button(tr("Einladung kopieren"), style = ButtonStyle.OUTLINESECONDARY)
        inviteButton.addCssClass("btn-sm")
        guestAccessInviteButton = inviteButton
        updateGuestAccessUi()

        fun applyGuestAccess(newValue: Boolean) {
            toggleButton.disabled = true
            AppScope.launch {
                val result = guarded { rpcService<IConferenceService>().setRoomGuestAccess(room.id, newValue) }
                toggleButton.disabled = false
                // Rule: only update UI state once the guarded {} call's result confirms success --
                // never optimistically before the RPC resolves (same discipline the recording/
                // streaming buttons already follow, see file KDoc).
                if (result != null) {
                    guestAccessOpen = result.allowFederationGuests
                    updateGuestAccessUi()
                    notifySuccess(if (guestAccessOpen) tr("Gastzugang aktiviert.") else tr("Gastzugang beendet."))
                }
            }
        }

        toggleButton.onClick {
            if (guestAccessOpen) {
                // Design review D16: revoking must not silently leave already-connected guests
                // inside the room -- the server disconnects them, but the moderator must be told
                // that BEFORE confirming, not be surprised by it afterwards (Norman: visible
                // system status).
                confirmDialog(
                    title = tr("Gastzugang beenden"),
                    message =
                        tr(
                            "Bereits verbundene Gäste anderer Server werden sofort aus der Besprechung entfernt. " +
                                "Neue Gäste können nicht mehr beitreten. Mitglieder Ihrer eigenen Organisation sind " +
                                "nicht betroffen.",
                        ),
                    confirmLabel = tr("Gastzugang beenden"),
                ) { applyGuestAccess(false) }
            } else {
                applyGuestAccess(true)
            }
        }

        inviteButton.onClick {
            // Security-audit fix: the organization name is not in scope at this call site by
            // default (unlike the guest pre-join lobby) -- fetch it via getGuestJoinInfo, the same
            // RPC that already exposes it to any AKTIV member on this room (never the
            // BOARD/ADMIN/TREASURER-only OrganizationSettingsService), rather than substituting the
            // room's own title. On failure (or if the org name comes back blank), conferenceInviteText
            // simply drops the "bei {org}" clause -- never fabricates a wrong one.
            AppScope.launch {
                val info = guarded { rpcService<IConferenceService>().getGuestJoinInfo(room.id) }
                val inviteText = conferenceInviteText(window.location.origin, room.id, roomTitle, info?.organizationName)
                // D6: no precedent for `navigator.clipboard` anywhere in this client -- `writeText`
                // rejects in non-secure contexts and can be permission-blocked, so this is never a
                // silent no-op: on success a toast, on failure/absence a read-only, pre-selected text
                // field the moderator can still copy by hand.
                val clipboard: dynamic = window.navigator.asDynamic().clipboard
                if (clipboard == null || clipboard == undefined) {
                    showInviteTextFallback(callPanel, inviteText)
                } else {
                    val promise: dynamic = clipboard.writeText(inviteText)
                    promise.then(
                        { notifySuccess(tr("Einladung in die Zwischenablage kopiert.")) },
                        { showInviteTextFallback(callPanel, inviteText) },
                    )
                }
            }
        }
    }

    // V1.0 Videokonferenzen, Wave 6 "Breakout-Räume" -- its OWN, further spatially separate control
    // row, moderator-only. Design review verdict #1 (must-fix): this row must NOT share the
    // "Moderator:"/endButton row -- placing "Alle zurückholen" next to "Für alle beenden" would
    // violate this file's own repeated rule that near-identical destructive-looking actions must
    // never sit adjacent (Tesler). `canModerate` is always `false` inside a breakout call, so this
    // never renders there -- see that val's own KDoc "no breakout-room moderator".
    // Forward reference -- `refreshRoster` (the roster's breakout-reassignment select column reads
    // `currentBreakoutRooms`) is declared further below in this function, same "DOM position vs.
    // declaration order" split `showTitleEditMode` already establishes for `renderTitleViewMode`/
    // `renderTitleEditMode`. Assigned right after `refreshRoster` itself is declared; only ever
    // CALLED from click handlers below, which fire long after that assignment has happened.
    lateinit var refreshRosterRef: () -> Unit
    var breakoutCreateButton: Button? = null
    var breakoutRecallButton: Button? = null
    val breakoutOverviewPanel = callPanel.vPanel(spacing = 2) { addCssClasses("ms-2") }
    breakoutOverviewPanel.hide()
    // The moderator's own local mirror of the currently open batch -- kept in sync purely from the
    // RETURN VALUE of createBreakoutRooms/assignParticipants/recallAll (event-driven, no dedicated
    // "list open breakout rooms" RPC exists -- see IConferenceBreakoutService KDoc). Always in the
    // SAME order the server computes `breakoutIndex` against (createdAt ASC, id ASC -- see that
    // service's own KDoc "stable meaning across the two RPCs"), so this list's own index can be
    // reused verbatim as `breakoutIndex` when the roster's per-row reassignment select fires below.
    var currentBreakoutRooms: List<ConferenceBreakoutRoomDto> = emptyList()

    fun renderBreakoutOverview() {
        breakoutOverviewPanel.removeAll()
        if (currentBreakoutRooms.isEmpty()) {
            breakoutOverviewPanel.hide()
            return
        }
        breakoutOverviewPanel.show()
        currentBreakoutRooms.forEach { breakoutRoomDto ->
            val names = breakoutRoomDto.assignedDisplayNames.ifEmpty { listOf(gettext("niemand zugewiesen")) }
            breakoutOverviewPanel.div(gettext("%1: %2", breakoutRoomDto.label, names.joinToString(", "))) {
                addCssClasses("text-muted small")
            }
        }
    }

    fun updateBreakoutControlsVisibility() {
        if (currentBreakoutRooms.isNotEmpty()) {
            breakoutCreateButton?.hide()
            breakoutRecallButton?.show()
        } else {
            breakoutCreateButton?.show()
            breakoutRecallButton?.hide()
        }
        renderBreakoutOverview()
    }

    if (canModerate) {
        val breakoutRow = callPanel.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
        breakoutRow.div(tr("Breakout-Räume:")) { addCssClasses("text-muted small") }
        val createBtn = breakoutRow.button(tr("Räume erstellen und verteilen"), style = ButtonStyle.OUTLINEPRIMARY)
        breakoutCreateButton = createBtn
        val recallBtn = breakoutRow.button(tr("Alle zurückholen"), style = ButtonStyle.WARNING)
        recallBtn.hide()
        breakoutRecallButton = recallBtn
        updateBreakoutControlsVisibility()

        createBtn.onClick {
            // Design review, locked copy -- shown ONLY if a recording/stream is currently active on
            // the main room at the moment the dialog opens; omitted otherwise (D11/D14 "invisible
            // when not relevant" posture).
            breakoutCreateDialog(mediaActive = activeRecordingDto != null || activeStreamDto != null) { roomCount ->
                createBtn.disabled = true
                AppScope.launch {
                    val result =
                        guarded {
                            rpcService<IConferenceBreakoutService>().createBreakoutRooms(
                                room.id,
                                ConferenceBreakoutPlanInput(roomCount = roomCount),
                            )
                        }
                    createBtn.disabled = false
                    if (result != null) {
                        currentBreakoutRooms = result
                        updateBreakoutControlsVisibility()
                        refreshRosterRef()
                        notifySuccess(gettext("%1 Breakout-Räume erstellt und Teilnehmende verteilt.", roomCount))
                    }
                }
            }
        }

        recallBtn.onClick {
            breakoutRecallConfirmDialog {
                recallBtn.disabled = true
                AppScope.launch {
                    val count = guarded { rpcService<IConferenceBreakoutService>().recallAll(room.id) }
                    recallBtn.disabled = false
                    if (count != null) {
                        currentBreakoutRooms = emptyList()
                        updateBreakoutControlsVisibility()
                        refreshRosterRef()
                        notifySuccess(tr("Alle Teilnehmenden wurden in den Hauptraum zurückgeholt."))
                    }
                }
            }
        }
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
    val recordingBannerAckButton = recordingBannerButtons.button(tr("Verstanden"), style = ButtonStyle.PRIMARY)
    val recordingBannerLeaveButton =
        recordingBannerButtons.button(tr("Besprechung verlassen"), style = ButtonStyle.OUTLINESECONDARY)
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
    val streamBannerAckButton = streamBannerButtons.button(tr("Verstanden"), style = ButtonStyle.PRIMARY)
    val streamBannerLeaveButton = streamBannerButtons.button(tr("Besprechung verlassen"), style = ButtonStyle.OUTLINESECONDARY)
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

    // Wave 4, D1 -- inline rename in the in-call header, moderator-only. Placed HERE (not right next
    // to [titleRow]'s own creation above) because [submitRename] needs [updateStatusBadgesAndTitle]
    // already declared, so a successful rename can refresh the "● "-prefixed background-tab title
    // immediately -- see file KDoc "D1" and [baseDocumentTitle]'s own `var` comment. No modal:
    // renaming is trivially reversible, unlike every other `Modal(...)` in this file.
    //
    // `renderTitleViewMode`/`renderTitleEditMode` call each other (mutual recursion), which plain
    // sequential local `fun` declarations cannot express in either order -- one direction always
    // forward-references the other. `showTitleEditMode` is the indirection that breaks the cycle:
    // assigned once, right after `renderTitleEditMode` is declared, below.
    lateinit var showTitleEditMode: () -> Unit

    fun renderTitleViewMode() {
        titleRow.removeAll()
        titleRow.h2(roomTitle)
        if (canModerate) {
            val editButton = titleRow.button(tr("Bearbeiten"), style = ButtonStyle.OUTLINESECONDARY)
            editButton.addCssClass("btn-sm")
            editButton.onClick { showTitleEditMode() }
        }
    }

    fun renderTitleEditMode() {
        titleRow.removeAll()
        val editInput = titleRow.text(value = roomTitle) { addCssClasses("flex-grow-1") }
        val saveButton = titleRow.button(tr("Speichern"), style = ButtonStyle.PRIMARY)
        val cancelButton = titleRow.button(tr("Abbrechen"), style = ButtonStyle.SECONDARY)
        cancelButton.onClick { renderTitleViewMode() }

        fun submitRename() {
            val newTitle = editInput.value.orEmpty().trim()
            if (newTitle.isBlank() || newTitle.length > MAX_ROOM_TITLE_LENGTH_CLIENT) {
                notifyError(gettext("Titel darf nicht leer sein und höchstens %1 Zeichen haben.", MAX_ROOM_TITLE_LENGTH_CLIENT))
                return
            }
            saveButton.disabled = true
            AppScope.launch {
                val result = guarded { rpcService<IConferenceService>().renameRoom(room.id, newTitle) }
                saveButton.disabled = false
                // Rule: only update UI state once the guarded {} call's result confirms success --
                // never optimistically before the RPC resolves.
                if (result != null) {
                    roomTitle = result.title
                    baseDocumentTitle = roomTitle
                    updateStatusBadgesAndTitle()
                    renderTitleViewMode()
                }
            }
        }
        saveButton.onClick { submitRename() }
        // Should-fix, non-blocking per the design review: Enter-key submit + autofocus -- same raw-DOM
        // discipline `chatRow`'s own Enter-to-send hook already uses further below in this function.
        editInput.addAfterInsertHook { vnode ->
            val root = vnode.elm as? HTMLElement
            val inputElement = (root as? HTMLInputElement) ?: root?.querySelector("input") as? HTMLInputElement
            inputElement?.focus()
            inputElement?.addEventListener("keydown", { event ->
                val keyEvent = event as? KeyboardEvent
                if (keyEvent?.key == "Enter") {
                    keyEvent.preventDefault()
                    submitRename()
                }
            })
        }
    }
    showTitleEditMode = ::renderTitleEditMode
    renderTitleViewMode()

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
                btn.text = tr("Aufzeichnung beenden")
                btn.disabled = false
            }
            ConferenceRecordingStatus.STOPPING -> {
                btn.text = tr("Aufzeichnung wird beendet …")
                btn.disabled = true
            }
            ConferenceRecordingStatus.PROCESSING -> {
                // Review-round-1 fix (2026-08-09): PROCESSING can now reach this button too, via
                // `pollInFlightRecordingStatus` below -- without this branch it fell into the
                // `else` case and showed an ENABLED "Aufzeichnung starten" label whose click handler
                // then silently did nothing ([onRecordButtonClicked] only ever handles a `null` or
                // `RECORDING` active recording), a live-looking but dead button. Same disabled tier
                // as STOPPING.
                btn.text = tr("Aufzeichnung wird zusammengeführt …")
                btn.disabled = true
            }
            else -> {
                btn.text = tr("Aufzeichnung starten")
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
        stopBtn.text = tr("Stream beenden")
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
                stopBtn.text = tr("Stream wird beendet …")
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
                        notifySuccess(tr("Aufzeichnung gestartet."))
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
                        notifySuccess(tr("Aufzeichnung wird beendet."))
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
                    tr(
                        "Keine freigegebenen Stream-Ziele vorhanden -- bitte eine Administratorin oder " +
                            "einen Administrator kontaktieren.",
                    ),
                )
                return@launch
            }
            val participantOptions =
                tiles.values.map { entry ->
                    entry.identity to (if (entry.isLocal) gettext("%1 (Sie)", entry.displayName) else entry.displayName)
                }
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
                        notifySuccess(tr("Live-Stream wird gestartet."))
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
                    notifyInfo(tr("Stream unterbrochen."))
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
            btn.text = tr("Stream wird fortgesetzt …")
            AppScope.launch {
                val result = guarded { rpcService<IConferenceStreamingService>().resumeStream(stream.id) }
                btn.disabled = false
                btn.text = tr("Stream fortsetzen")
                if (result != null) {
                    activeStreamDto = result
                    updateStreamDetailLine()
                    updateStreamButtonsVisibility()
                    notifySuccess(tr("Stream wird fortgesetzt …"))
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
                    notifySuccess(tr("Live-Stream wird beendet."))
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
        val btn = row.button(tr("Aufzeichnung starten"), style = ButtonStyle.WARNING)
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
        val startBtn = row.button(tr("Live-Stream starten …"), style = ButtonStyle.WARNING)
        startBtn.onClick { onStreamStartClicked() }
        streamStartButton = startBtn

        val pauseBtn = row.button(tr("Stream unterbrechen"), style = ButtonStyle.OUTLINEWARNING)
        pauseBtn.onClick { onStreamPauseClicked() }
        streamPauseButton = pauseBtn

        val resumeBtn = row.button(tr("Stream fortsetzen"), style = ButtonStyle.WARNING)
        resumeBtn.onClick { onStreamResumeClicked() }
        streamResumeButton = resumeBtn

        val stopBtn = row.button(tr("Stream beenden"), style = ButtonStyle.OUTLINEDANGER)
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
        // V1.0 Videokonferenzen, Wave 6 "Breakout-Räume" -- a breakout room is a physically SEPARATE
        // LiveKit room from the one recording/streaming ever runs against (see
        // network.lapis.cloud.shared.rpc.IConferenceBreakoutService KDoc "DSGVO/transparency"), so
        // this callback firing at all inside a breakout call would only ever reflect the (empty)
        // egress state of THAT breakout room -- refreshing recording/streaming state from it would
        // be meaningless at best. Recording/streaming badges/controls stay hidden entirely inside a
        // breakout call (the persistent disclosure line above is the honest substitute); the
        // moderator returns to Main to see/act on the real state.
        if (isBreakout) return
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
     * as every other `AppScope.launch` in this function) but self-terminates the moment
     * `connectionState` reaches [ConferenceConnectionState.Ended] (Wave 4, D10 -- replaces the Wave
     * 1-3 `leftCall` boolean), on any exit path (Verlassen, Für alle beenden, `onDisconnected`).
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
        while (connectionState.isLive()) {
            delay(CONFERENCE_RECORDING_POLL_INTERVAL_MS)
            if (!connectionState.isLive()) break
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
                        notifySuccess(tr("Aufzeichnung ist bereit."))
                        null
                    }
                    ConferenceRecordingStatus.FAILED -> {
                        notifyError(tr("Aufzeichnung fehlgeschlagen."))
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
     * must not re-toast on every tick); self-terminates once `connectionState` reaches
     * [ConferenceConnectionState.Ended] (Wave 4, D10), same lifecycle.
     */
    suspend fun pollInFlightStreamStatus() {
        while (connectionState.isLive()) {
            delay(CONFERENCE_STREAM_POLL_INTERVAL_MS)
            if (!connectionState.isLive()) break
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
                notifyError(resolved.failureReason ?: tr("Live-Stream fehlgeschlagen."))
            } else {
                notifyInfo(tr("Live-Stream beendet."))
            }
        }
    }

    // Wave 6: pointless inside a breakout call -- see [onMediaStatusPush]'s own early-return comment.
    // `activeRecordingDto`/`activeStreamDto` stay `null` there forever, so both loops would just spin
    // doing nothing until `Ended`.
    if (!isBreakout) {
        AppScope.launch { pollInFlightRecordingStatus() }
        AppScope.launch { pollInFlightStreamStatus() }
    }

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
    // Wave 4, D3: `gridElement` now holds TWO sub-containers, built ONCE here (never recreated by
    // `applyConferenceGridReflow` below -- same "grab once, mutate forever" discipline this file
    // already documents for `gridElement`/`stageElement` themselves, see [enterCall] KDoc). Below the
    // reflow threshold, `priorityZoneElement` alone renders every tile in the ORIGINAL
    // `minmax(200px, 1fr)` flat grid -- byte-for-byte Wave 1-3 behaviour -- and `compactZoneElement`
    // stays empty/hidden.
    var compactLabelElement: HTMLElement? = null
    gridDiv.addAfterInsertHook { vnode ->
        val root = (vnode.elm as? HTMLElement) ?: return@addAfterInsertHook
        gridElement = root
        val priority = document.createElement("div") as HTMLElement
        priority.style.cssText = "display:grid;grid-template-columns:repeat(auto-fit, minmax(200px, 1fr));gap:8px;"
        root.appendChild(priority)
        priorityZoneElement = priority

        val compactLabel = document.createElement("div") as HTMLElement
        compactLabel.style.cssText = "font-size:12px;color:#666;margin-top:8px;display:none;"
        root.appendChild(compactLabel)
        compactLabelElement = compactLabel

        val compact = document.createElement("div") as HTMLElement
        compact.style.cssText = "display:flex;overflow-x:auto;overflow-y:hidden;gap:8px;margin-top:4px;display:none;"
        root.appendChild(compact)
        compactZoneElement = compact
    }

    // Wave 4, D3 -- speaking-priority zone/local identity, union computed here so the pure
    // [conferenceGridLayout] partition itself stays a plain set-membership function, no
    // time/local-participant special-casing inside it (see that function's own KDoc).
    fun currentPriorityIdentities(): Set<String> {
        val now = Clock.System.now().toEpochMilliseconds()
        return lastSpokeAtMs.filterValues { now - it <= CONFERENCE_SPEAKING_PRIORITY_WINDOW_MS }.keys + joinToken.identity
    }

    fun setTileZoneStyle(
        entry: ConferenceTileEntry,
        zone: ConferenceTileZone,
    ) {
        val (minHeightPx, initialsFontPx, badgeFontPx, badgePadding) =
            when (zone) {
                ConferenceTileZone.COMPACT -> ConferenceTileZoneStyle(82, 16, 10, "1px 4px")
                ConferenceTileZone.PRIORITY_REFLOWED -> ConferenceTileZoneStyle(195, 28, 12, "2px 6px")
                ConferenceTileZone.FLAT -> ConferenceTileZoneStyle(150, 28, 12, "2px 6px")
            }
        entry.element.style.setProperty("min-height", "${minHeightPx}px")
        entry.mediaSlot.style.setProperty("font-size", "${initialsFontPx}px")
        entry.nameBadge.style.setProperty("font-size", "${badgeFontPx}px")
        entry.nameBadge.style.setProperty("padding", badgePadding)
        // micBadge (D3 decided scope): no compact-specific treatment -- its existing 11px/2px-5px
        // sizing is already small enough at the 110px compact tile width, see file KDoc "D3".
    }

    /**
     * Wave 4, D3 -- the ONE place tiles are actually re-parented/restyled. Re-parenting an EXISTING
     * node moves it (no clone/recreate, no video/audio interruption) -- guarded by a `parentNode`
     * check so a tile already in its correct zone is left untouched on every sweep tick (repeated
     * `appendChild` on an already-correctly-placed `<video>`/`<audio>` element risks a real playback
     * hiccup in some browsers, not just wasted work). Called from [ensureTile]/[removeTile] (so a
     * join/leave immediately reflows) and from the periodic sweep below -- NEVER directly from the
     * raw `onActiveSpeakersChanged` push (see file KDoc "D3" for why that would strobe the grid).
     */
    fun applyConferenceGridReflow() {
        val priority = priorityZoneElement ?: return
        val compact = compactZoneElement ?: return
        val label = compactLabelElement ?: return
        val layout = conferenceGridLayout(tiles.keys.toList(), currentPriorityIdentities())
        if (layout.reflowed) {
            priority.style.setProperty("grid-template-columns", "repeat(auto-fit, minmax(260px, 1fr))")
            compact.style.setProperty("display", "flex")
            label.style.setProperty("display", "block")
            label.textContent = gettext("Weitere Teilnehmende (%1)", layout.compactIdentities.size)
        } else {
            // Required change 2 (design review): the <= threshold case stays BYTE-FOR-BYTE Wave 1-3's
            // original single flat grid -- reset to the ORIGINAL minmax(200px, 1fr) rule, never left
            // at the larger reflowed-priority size.
            priority.style.setProperty("grid-template-columns", "repeat(auto-fit, minmax(200px, 1fr))")
            compact.style.setProperty("display", "none")
            label.style.setProperty("display", "none")
        }
        layout.priorityIdentities.forEach { identity ->
            val entry = tiles[identity] ?: return@forEach
            if (entry.element.parentNode !== priority) priority.appendChild(entry.element)
            setTileZoneStyle(entry, if (layout.reflowed) ConferenceTileZone.PRIORITY_REFLOWED else ConferenceTileZone.FLAT)
        }
        layout.compactIdentities.forEach { identity ->
            val entry = tiles[identity] ?: return@forEach
            if (entry.element.parentNode !== compact) compact.appendChild(entry.element)
            setTileZoneStyle(entry, ConferenceTileZone.COMPACT)
        }
    }

    // Required change 1 (design review): the SOLE trigger for `applyConferenceGridReflow` on a
    // steady cadence -- decoupled from raw `RoomEvent.ActiveSpeakersChanged` pushes, which fire on
    // sub-second speaking-level transitions and would otherwise strobe the grid at 25-person scale
    // (see file KDoc "D3"). Mirrors [pollInFlightRecordingStatus]'s own shape/lifecycle exactly.
    suspend fun sweepGridReflow() {
        while (connectionState.isLive()) {
            delay(CONFERENCE_GRID_REFLOW_SWEEP_INTERVAL_MS)
            if (!connectionState.isLive()) break
            applyConferenceGridReflow()
        }
    }
    AppScope.launch { sweepGridReflow() }

    // --- Participant roster (live, driven by the same RoomEvent stream as the tiles) --------------
    callPanel.h2(tr("Teilnehmende")) { addCssClasses("h6 mt-2") }
    val rosterList = callPanel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }

    // --- Collapsible chat side panel (D7: off by default) ------------------------------------------
    val chatPanel = callPanel.vPanel(spacing = 6) { addCssClasses("border rounded p-2") }
    chatPanel.hide()
    chatPanel.h2(tr("Chat")) { addCssClass("h6") }
    val chatLog =
        chatPanel.div {
            addCssClasses("small")
            height = 160.px
            overflow = Overflow.AUTO
        }
    val chatRow = chatPanel.hPanel(spacing = 6)
    val chatInput = chatRow.text(label = tr("Nachricht")) { addCssClasses("flex-grow-1") }
    val chatSendButton = chatRow.button(tr("Senden"), style = ButtonStyle.OUTLINEPRIMARY)

    // --- Collapsible whiteboard side panel (V1.0 Wave 7 "Whiteboard", off by default) --------------
    // Same `vPanel`/`hide()`/toggle-button pattern as chatPanel above -- Kay/Tesler/Raskin: modeless,
    // coexisting, never a modal/grid-replacement -- see ConferenceWhiteboardPanel.kt class KDoc.
    val whiteboardPanel = callPanel.vPanel(spacing = 6) { addCssClasses("border rounded p-2") }
    whiteboardPanel.hide()
    var whiteboardOpen = false
    // Constructed once per connect (see the connect-success block below), NOT lazily on first open --
    // getWhiteboardState's own late-joiner seed is unconditional, see IConferenceWhiteboardService
    // .getWhiteboardState KDoc.
    var whiteboardController: ConferenceWhiteboardController? = null

    // --- Collapsible shared-notes side panel (V1.0 Wave 8 "Geteilte Notizen", off by default) -----
    // Same `vPanel`/`hide()`/toggle-button pattern as chatPanel/whiteboardPanel above -- see
    // ConferenceNotesController KDoc "Placement".
    val notesPanel = callPanel.vPanel(spacing = 6) { addCssClasses("border rounded p-2") }
    notesPanel.hide()
    var notesOpen = false
    // Constructed once per connect (mirrors whiteboardController's own "seed once per connect"
    // pattern) -- see IConferenceNotesService.getNotesState KDoc "late-joiner seed".
    var notesController: ConferenceNotesController? = null

    fun updateChatToggleLabel() {
        chatToggleButton.text = if (unreadChatCount > 0) gettext("Chat (%1)", unreadChatCount) else tr("Chat")
    }

    fun refreshRoster() {
        rosterList.removeAll()
        if (tiles.isEmpty()) {
            rosterList.div(tr("Noch niemand verbunden.")) { addCssClasses("text-muted small") }
            return
        }
        tiles.values.sortedWith(compareBy({ !it.isLocal }, { it.displayName })).forEach { entry ->
            val row = rosterList.hPanel(spacing = 6) { addCssClasses("align-items-center flex-wrap") }
            // V1.0 Videokonferenzen, Wave 5, design review D11 -- badge BEFORE the name, matching
            // the one shipped call site (`App.kt`'s navbar: `guestBadge(...)` then the name). The
            // name div carries `flex-grow-1`, so a badge appended AFTER it would be flung to the
            // right edge next to "Moderator"/"Mikro an"/"Entfernen" -- visually detached from the
            // name it qualifies. No "(Gast)" text suffix here either -- the badge alone is the
            // marker in the roster (the navbar only adds text because its badge sits inside a
            // `.disabled` wrapper where the badge alone would read as decoration).
            guestHomeserverByIdentity[entry.identity]?.let { homeserverUrl -> row.guestBadge(homeserverUrl) }
            row.div(
                if (entry.isLocal) gettext("%1 (Sie)", entry.displayName) else entry.displayName,
            ) { addCssClasses("flex-grow-1 small") }
            if (entry.identity == room.createdByMemberId) {
                row.statusBadge(tr("Moderator"), "primary")
            }
            row.div(if (entry.hasMic) tr("Mikro an") else tr("Stumm")) { addCssClasses("text-muted small") }
            if (conferenceCanRemove(entry.identity, localMemberId, room.createdByMemberId, canModerate)) {
                val removeButton = row.button(tr("Entfernen"), style = ButtonStyle.OUTLINEDANGER)
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
            // V1.0 Videokonferenzen, Wave 6 "Breakout-Räume" -- manual override / mid-session
            // reassignment, moderator-only, only rendered while a batch is open. Design review
            // verdict #2 (must-fix): the select lists ONLY currently-open breakout rooms -- no
            // "Hauptraum" option, since IConferenceBreakoutService has no "move this one member back
            // to Main" method (only `assignParticipants`, which assigns TO a breakout room, and
            // `recallAll`, which recalls EVERYONE). Moving one specific person back to Main is
            // therefore not supported except via "Alle zurückholen" -- a stated V1 scope cut.
            if (canModerate && currentBreakoutRooms.isNotEmpty()) {
                val currentAssignmentId = currentBreakoutRooms.firstOrNull { entry.identity in it.assignedMemberIds }?.id
                val breakoutSelect =
                    row.select(options = currentBreakoutRooms.map { it.id to it.label }, value = currentAssignmentId)
                breakoutSelect.addCssClass("btn-sm")
                breakoutSelect.subscribe { selectedId ->
                    if (selectedId == null || selectedId == currentAssignmentId) return@subscribe
                    // The list's OWN index is reused verbatim as `breakoutIndex` -- see
                    // `currentBreakoutRooms`' own KDoc "stable meaning across the two RPCs".
                    val targetIndex = currentBreakoutRooms.indexOfFirst { it.id == selectedId }
                    if (targetIndex < 0) return@subscribe
                    AppScope.launch {
                        val result =
                            guarded {
                                rpcService<IConferenceBreakoutService>().assignParticipants(
                                    room.id,
                                    listOf(ConferenceBreakoutAssignmentInput(entry.identity, targetIndex)),
                                )
                            }
                        if (result != null) {
                            currentBreakoutRooms = result
                            updateBreakoutControlsVisibility()
                            refreshRosterRef()
                        }
                    }
                }
            }
        }
    }
    refreshRosterRef = ::refreshRoster

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
        micBadge.textContent = tr("Stumm")
        tile.appendChild(micBadge)

        // V1.0 Videokonferenzen, Wave 5 "Föderations-Gastbeitritt", design review D12 -- top-left
        // pill: white text on rgba(0,0,0,0.55) (proven AA-4.5:1 combination the "Stumm" badge above
        // already ships, needed because the pill carries 11px TEXT) plus an 8px round dot filled
        // with GuestBadgeColors.FILL (3:1-class non-text signal, ~4.7:1 on this #111 tile). Colour
        // is never the sole signal -- the word "Gast" is always present (WCAG 1.4.1), same rule
        // StatusBadge.kt states app-wide. Hidden by default -- see `setTileGuest`.
        val guestBadgeEl = document.createElement("div") as HTMLElement
        guestBadgeEl.style.cssText =
            "position:absolute;left:6px;top:6px;background:rgba(0,0,0,0.55);color:#fff;" +
            "font-size:11px;padding:2px 5px;border-radius:3px;display:none;" +
            "align-items:center;gap:4px;"
        val dot = document.createElement("span") as HTMLElement
        dot.style.cssText =
            "display:inline-block;width:8px;height:8px;border-radius:50%;background:${GuestBadgeColors.FILL};"
        guestBadgeEl.appendChild(dot)
        val guestLabel = document.createElement("span") as HTMLElement
        guestLabel.textContent = tr("Gast")
        guestBadgeEl.appendChild(guestLabel)
        tile.appendChild(guestBadgeEl)

        // Wave 4, D3: NO LONGER appends to `gridElement` here -- placement into the priority/compact
        // zone is [applyConferenceGridReflow]'s job now, called by [ensureTile] right after this
        // returns (see file KDoc "D3").
        return ConferenceTileEntry(identity, displayName, isLocal, tile, mediaSlot, nameBadge, micBadge, guestBadgeEl)
    }

    /**
     * V1.0 Videokonferenzen, Wave 5, design review D12: set/updated from
     * `guestHomeserverByIdentity`, itself populated by `refreshGuestHomeservers` -- see that
     * function's own KDoc. `homeserverUrl == null` hides the pill (an ordinary member, or a guest
     * row not yet resolved).
     */
    fun setTileGuest(
        entry: ConferenceTileEntry,
        homeserverUrl: String?,
    ) {
        if (homeserverUrl != null) {
            entry.guestBadgeEl.style.display = "flex"
            entry.guestBadgeEl.title = guestBadgeAriaLabel(homeserverUrl)
            entry.guestBadgeEl.setAttribute("aria-label", guestBadgeAriaLabel(homeserverUrl))
        } else {
            entry.guestBadgeEl.style.display = "none"
        }
    }

    // Tile/roster label composition lives HERE, not baked into `displayName` at call sites -- every
    // caller passes the RAW `joinToken.displayName`/`RemoteParticipant.name`, never a pre-suffixed
    // string, so [ConferenceTileEntry.displayName] stays the single source of truth for
    // [conferenceInitials] and never accumulates a repeated "(Sie)" suffix (a real bug an earlier
    // version of this function had -- see the wave's own testing routine). Design review D12:
    // extracted into the pure, jsTest-covered `conferenceTileLabel` -- deliberately WITHOUT an
    // `isGuest` parameter, since guest status is now the dedicated `guestBadgeEl` pill above, never
    // a text suffix (see that field's own KDoc).
    fun tileLabel(entry: ConferenceTileEntry): String =
        conferenceTileLabel(
            displayName = entry.displayName,
            isLocal = entry.isLocal,
            isModerator = entry.identity == room.createdByMemberId,
        )

    fun ensureTile(
        identity: String,
        displayName: String,
        isLocal: Boolean = false,
    ): ConferenceTileEntry {
        tiles[identity]?.let { existing ->
            existing.displayName = displayName
            existing.nameBadge.textContent = tileLabel(existing)
            setTileGuest(existing, guestHomeserverByIdentity[identity])
            return existing
        }
        val entry = buildTile(identity, displayName, isLocal)
        entry.nameBadge.textContent = tileLabel(entry)
        setTileGuest(entry, guestHomeserverByIdentity[identity])
        tiles[identity] = entry
        refreshRoster()
        applyConferenceGridReflow()
        return entry
    }

    fun removeTile(identity: String) {
        val entry = tiles.remove(identity) ?: return
        entry.element.parentNode?.removeChild(entry.element)
        refreshRoster()
        applyConferenceGridReflow()
    }

    /**
     * V1.0 Videokonferenzen, Wave 5 "Föderations-Gastbeitritt" -- deliberate new fetch (see
     * `guestHomeserverByIdentity`'s own KDoc). Called once right after LiveKit connect succeeds
     * (so an already-guest-populated roster is correct immediately, D4-style "the initial roster
     * must render instantaneously and completely") and again on `onParticipantJoined`, via
     * [scheduleGuestHomeserverRefresh]'s debounce -- a fresh joiner already triggers
     * `ensureTile`/`refreshRoster`, this just adds the roundtrip needed to learn whether they are a
     * guest. Deliberately NOT run on a timer -- unlike the recording/streaming polls, there is no
     * server-push signal for "a new guest's `oidc_guest_profile` became known", but each call does
     * fan out into an outbound LiveKit `ListParticipants` admin call (see
     * `ConferenceService.listParticipants`'s own body -- this KDoc previously, incorrectly, claimed
     * otherwise), which is exactly why [scheduleGuestHomeserverRefresh] exists: security-audit fix,
     * see that function's own KDoc "DoS amplification".
     */
    suspend fun refreshGuestHomeservers() {
        val participants = guarded { rpcService<IConferenceService>().listParticipants(room.id) } ?: return
        guestHomeserverByIdentity.clear()
        participants.forEach { p -> p.homeserverUrl?.let { url -> guestHomeserverByIdentity[p.memberId] = url } }
        tiles.values.forEach { entry -> setTileGuest(entry, guestHomeserverByIdentity[entry.identity]) }
        refreshRoster()
    }

    /**
     * Security-audit fix (DoS amplification): calling [refreshGuestHomeservers] directly from every
     * `onParticipantJoined` event meant filling a room to the participant cap cost on the order of
     * N² LiveKit admin round-trips concentrated in a short window (each connected client issuing its
     * own `listParticipants` RPC per join event), which could also exhaust the shared
     * `listRateLimiter` budget `getRoom`/`listActiveRooms` draw on too, making those fail with
     * `ConflictException` for a participant who did nothing wrong. This coalesces any burst of join
     * events within [GUEST_HOMESERVER_REFRESH_DEBOUNCE_MS] into a SINGLE trailing
     * [refreshGuestHomeservers] call: each new call cancels the still-pending previous one (if the
     * debounce window has not yet elapsed) before scheduling its own, so N joins in quick succession
     * still only ever produce ONE actual RPC once the roster settles down.
     */
    fun scheduleGuestHomeserverRefresh() {
        guestHomeserverRefreshJob?.cancel()
        guestHomeserverRefreshJob =
            AppScope.launch {
                delay(GUEST_HOMESERVER_REFRESH_DEBOUNCE_MS)
                refreshGuestHomeservers()
            }
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
        label.textContent = gettext("%1 teilt den Bildschirm", displayName)
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
        chatLog.div(gettext("%1: %2", sender, text)) {
            if (isOwn) addCssClasses("fw-bold text-end")
        }
        chatLog.getElement()?.let { el -> el.scrollTop = el.scrollHeight.toDouble() }
        if (!chatOpen && !isOwn) {
            unreadChatCount += 1
            updateChatToggleLabel()
        }
    }

    // Wave 4, D10 -- the ONE place `connectionState` is ever mutated, and the function that makes it
    // an ACTUALLY UI-driving state machine, not just an internal label: disables the local
    // media/chat controls while not `Connected`, and shows a calm, non-alarming status line while
    // `Connecting`/`Reconnecting` (never danger-red -- LiveKit is actively retrying, matching this
    // file's own established "don't falsely alarm" tone, see `pauseStreamConfirmDialog`'s copy).
    fun renderConnectionState() {
        when (connectionState) {
            is ConferenceConnectionState.Connecting -> {
                connectionStatusLine.content = tr("Verbindung wird hergestellt …")
                connectionStatusLine.show()
            }
            is ConferenceConnectionState.Reconnecting -> {
                connectionStatusLine.content = tr("Verbindung unterbrochen -- wird automatisch neu verbunden …")
                connectionStatusLine.show()
            }
            // V1.0 Videokonferenzen, Wave 6 -- design review, locked copy. Deliberately noncommittal
            // (must not claim "Sie werden verschoben" before the resolution RPC confirms that -- it
            // might just as easily resolve to Ended) and deliberately distinct from both the
            // `Connecting`/`Reconnecting` copy above, so the sequence a participant actually sees
            // ("Verbindung wird geprüft …" -> brief gap while this old invocation is abandoned ->
            // "Verbindung wird hergestellt …" of the fresh session) reads as one continuous,
            // intentional room change, not a crash. Same calm, non-alarming tone as the other two
            // in-progress states -- never danger-red.
            is ConferenceConnectionState.Resolving -> {
                connectionStatusLine.content = tr("Verbindung wird geprüft …")
                connectionStatusLine.show()
            }
            is ConferenceConnectionState.Connected,
            is ConferenceConnectionState.Failed,
            is ConferenceConnectionState.Ended,
            is ConferenceConnectionState.Disconnected,
            -> connectionStatusLine.hide()
        }
        val interactive = connectionState is ConferenceConnectionState.Connected
        micButton.disabled = !interactive
        cameraButton.disabled = !interactive
        screenShareButton.disabled = !interactive
        chatSendButton.disabled = !interactive
    }

    /** See [conferenceConnectionReduce] KDoc -- unlisted (state, event) pairs are ignored, never
     * thrown, so a duplicate/out-of-order LiveKit push can never crash this screen. */
    fun transition(event: ConferenceConnectionEvent) {
        connectionState = conferenceConnectionReduce(connectionState, event)
        renderConnectionState()
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
            onParticipantJoined = { identity, displayName ->
                ensureTile(identity, displayName)
                // Wave 5, security-audit fix -- see scheduleGuestHomeserverRefresh KDoc "DoS
                // amplification": debounced/coalesced, NOT one direct RPC per join event.
                scheduleGuestHomeserverRefresh()
            },
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
            // Wave 4, D3 -- ONLY updates the timestamp map, never calls `applyConferenceGridReflow`
            // directly: see file KDoc "D3" for why the periodic sweep (not this raw, sub-second-firing
            // push) is the sole reflow trigger.
            onActiveSpeakersChanged = { identities ->
                val now = Clock.System.now().toEpochMilliseconds()
                identities.forEach { identity -> lastSpokeAtMs[identity] = now }
            },
            onChat = { message -> appendChatLine(message.senderDisplayName, message.text, isOwn = false) },
            // V1.0 Wave 7 "Whiteboard" -- see LiveKitRoomSession KDoc "Whiteboard trust boundary".
            // `whiteboardController` is `null` only in the brief window before the connect-success
            // block below constructs it -- no preview/commit packet can arrive before then, since
            // the data channel does not exist until `session.connect(...)` itself has resolved.
            onWhiteboardPreview = { authorMemberId, _, stroke -> whiteboardController?.applyPreview(authorMemberId, stroke) },
            onWhiteboardCommit = { authorMemberId, _, stroke -> whiteboardController?.applyCommit(authorMemberId, stroke) },
            // V1.0 Wave 8 "Geteilte Notizen" -- see LiveKitRoomSession KDoc "Notes trust boundary".
            // `notesController` is `null` only in the brief window before the connect-success block
            // below constructs it -- same reasoning as whiteboardController above.
            onNotesCommit = { authorMemberId, authorDisplayName, broadcast ->
                notesController?.applyCommitBroadcast(authorMemberId, authorDisplayName, broadcast)
            },
            // Wave 4, D10 -- LiveKit's own reconnect signal, relayed verbatim by LiveKitRoomSession
            // (see that class's own KDoc "Reconnect signal").
            onReconnecting = { transition(ConferenceConnectionEvent.ReconnectingSignal) },
            onReconnected = { transition(ConferenceConnectionEvent.ReconnectedSignal) },
            // V1.0 Videokonferenzen, Wave 6 "Breakout-Räume" -- the SOLE place a "why did I just
            // disconnect" question is answered. `RoomEvent.Disconnected` fires identically whether
            // the CAUSE was a kick, the meeting ending, a moderator assigning this caller to a
            // breakout room, or a moderator recalling them from one -- all four are
            // INDISTINGUISHABLE at this raw LiveKit transport layer, so this callback re-derives the
            // truth from the server on every firing rather than trusting any local guess (the same
            // "never trust a cached authorization/state flag" discipline this codebase applies
            // everywhere else). See [resolvePostDisconnectDestination] KDoc for how the four causes
            // are disambiguated with two cheap RPC calls.
            onDisconnected = {
                // Security-relevant (D10, unchanged): reachable from BOTH `Connected` and
                // `Reconnecting`. The idempotency guard below now also excludes `Resolving` (a second
                // `RoomEvent.Disconnected` firing while the first one's resolution is still in
                // flight must not launch a SECOND resolution race).
                if (connectionState !is ConferenceConnectionState.Ended && connectionState !is ConferenceConnectionState.Resolving) {
                    transition(ConferenceConnectionEvent.DisconnectedSignal)
                    AppScope.launch {
                        when (val destination = resolvePostDisconnectDestination(room.id)) {
                            is PostDisconnectDestination.Ended -> {
                                transition(ConferenceConnectionEvent.ResolvedAsEnded)
                                notifyInfo(tr("Die Besprechung wurde beendet oder die Verbindung getrennt."))
                                returnToLobby(callPanel, lobbyPanel, setActiveSession, onReturnedToLobby, baseDocumentTitle)
                            }
                            is PostDisconnectDestination.Breakout -> {
                                val breakoutToken =
                                    guarded {
                                        rpcService<IConferenceBreakoutService>()
                                            .requestBreakoutJoinToken(destination.assignment.breakoutRoomId)
                                    }
                                if (breakoutToken == null) {
                                    // Assignment already recalled again / no longer valid by the time
                                    // this call landed -- same "server says no" -> Ended fallback
                                    // every other guarded {} failure in this function already takes.
                                    transition(ConferenceConnectionEvent.ResolvedAsEnded)
                                    returnToLobby(callPanel, lobbyPanel, setActiveSession, onReturnedToLobby, baseDocumentTitle)
                                } else {
                                    notifyInfo(
                                        gettext(
                                            "Sie wurden dem Breakout-Raum \"%1\" zugewiesen.",
                                            destination.assignment.breakoutRoomLabel,
                                        ),
                                    )
                                    setActiveSession(null)
                                    enterCall(
                                        ConferenceCallTarget.BreakoutRoom(
                                            breakoutRoomId = destination.assignment.breakoutRoomId,
                                            label = destination.assignment.breakoutRoomLabel,
                                            parentRoom = destination.parentRoom,
                                        ),
                                        breakoutToken,
                                        lobbyPanel,
                                        callPanel,
                                        setActiveSession,
                                        onReturnedToLobby,
                                    )
                                }
                            }
                            is PostDisconnectDestination.Main -> {
                                val mainToken =
                                    guarded { rpcService<IConferenceBreakoutService>().rejoinMainRoomToken(destination.parentRoom.id) }
                                if (mainToken == null) {
                                    // No longer holds an open participation (e.g. was actually
                                    // kicked, not recalled) -- same fallback as above.
                                    transition(ConferenceConnectionEvent.ResolvedAsEnded)
                                    returnToLobby(callPanel, lobbyPanel, setActiveSession, onReturnedToLobby, baseDocumentTitle)
                                } else {
                                    notifyInfo(tr("Sie wurden in den Hauptraum zurückgeholt."))
                                    setActiveSession(null)
                                    enterCall(
                                        ConferenceCallTarget.MainRoom(destination.parentRoom),
                                        mainToken,
                                        lobbyPanel,
                                        callPanel,
                                        setActiveSession,
                                        onReturnedToLobby,
                                    )
                                }
                            }
                        }
                    }
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
        transition(ConferenceConnectionEvent.ConnectRequested)
        val connected = guarded { session.connect(joinToken.serverUrl, joinToken.token, joinToken.turnServers) }
        if (connected == null) {
            transition(ConferenceConnectionEvent.ConnectFailed("connect failed"))
            setActiveSession(null)
            returnToLobby(callPanel, lobbyPanel, setActiveSession, onReturnedToLobby, baseDocumentTitle)
            return@launch
        }
        transition(ConferenceConnectionEvent.ConnectSucceeded)
        // Wave 5 -- see refreshGuestHomeservers KDoc "called once right after connect succeeds".
        refreshGuestHomeservers()
        // V1.0 Wave 7 "Whiteboard" -- constructed once per connect (mirrors the roster/recording-
        // status "seed once per connect" pattern), NOT gated on the panel being open, so it is
        // instantly ready the moment a participant opens it -- see
        // IConferenceWhiteboardService.getWhiteboardState KDoc "late-joiner seed".
        // Review fix: gated on `!isBreakout`, matching `whiteboardToggleButton`'s own gate above --
        // `whiteboardController` simply stays `null` for the lifetime of a breakout call, which the
        // `onWhiteboardPreview`/`onWhiteboardCommit` callbacks above already handle safely via `?.`.
        if (!isBreakout) {
            whiteboardController =
                ConferenceWhiteboardController(whiteboardPanel, room.id, canModerate, joinToken.identity, session)
            val whiteboardState = guarded { rpcService<IConferenceWhiteboardService>().getWhiteboardState(room.id) }
            if (whiteboardState != null) whiteboardController.applyState(whiteboardState)
            // V1.0 Wave 8 "Geteilte Notizen" -- same "seed once per connect" pattern as
            // whiteboardController immediately above.
            notesController =
                ConferenceNotesController(notesPanel, room.id, canModerate, joinToken.identity, session)
            val notesState = guarded { rpcService<IConferenceNotesService>().getNotesState(room.id) }
            if (notesState != null) notesController.applyState(notesState)
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
            micButton.text = tr("Mikrofon an")
        }
        if (!cameraOk) {
            cameraEnabled = false
            cameraButton.text = tr("Kamera an")
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
                micButton.text = if (micEnabled) tr("Mikrofon aus") else tr("Mikrofon an")
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
                cameraButton.text = if (cameraEnabled) tr("Kamera aus") else tr("Kamera an")
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
                screenShareButton.text = if (screenShareEnabled) tr("Bildschirm-Teilen beenden") else tr("Bildschirm teilen")
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
    // Review fix: `whiteboardToggleButton` is `null` inside a breakout call -- see its own
    // construction comment above -- so this wiring is a no-op there and the panel stays hidden.
    whiteboardToggleButton?.onClick {
        whiteboardOpen = !whiteboardOpen
        if (whiteboardOpen) whiteboardPanel.show() else whiteboardPanel.hide()
    }
    // Review fix: `notesToggleButton` is `null` inside a breakout call -- see its own construction
    // comment above -- so this wiring is a no-op there and the panel stays hidden.
    notesToggleButton?.onClick {
        notesOpen = !notesOpen
        if (notesOpen) notesPanel.show() else notesPanel.hide()
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
            appendChatLine(gettext("Sie"), text, isOwn = true)
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

    // V1.0 Videokonferenzen, Wave 6 -- "Zurück zum Hauptraum", only present inside a breakout call
    // (see [backToMainButton]'s own declaration). Deliberately calls `returnToMainRoom` BEFORE
    // `session.disconnect()`: closing the DB assignment row first means that even if
    // `session.disconnect()`'s own `RoomEvent.Disconnected` still somehow fires and reaches the
    // (already-`Ended`, so ignored per `onDisconnected`'s own guard) `transition(UserLeft)` state,
    // there is no ambiguity window -- and a concurrent moderator `recallAll` racing this exact
    // moment just finds nothing left to recall for this member (harmless either order). Bypasses
    // `onDisconnected`'s resolution ambiguity entirely, same as `leaveButton`'s pre-existing flow
    // always has -- a direct, deliberate client-initiated transition, not something this screen
    // needs to ask the server "what does this mean" about.
    backToMainButton?.onClick {
        backToMainButton.disabled = true
        transition(ConferenceConnectionEvent.UserLeft)
        AppScope.launch {
            // Non-null by construction -- backToMainButton only exists (see its own declaration)
            // when `target is ConferenceCallTarget.BreakoutRoom`, which is exactly when
            // `breakoutRoomId` was assigned non-null too.
            guarded { rpcService<IConferenceBreakoutService>().returnToMainRoom(breakoutRoomId!!) }
            guarded { session.disconnect() }
            val mainToken = guarded { rpcService<IConferenceBreakoutService>().rejoinMainRoomToken(room.id) }
            setActiveSession(null)
            if (mainToken != null) {
                enterCall(ConferenceCallTarget.MainRoom(room), mainToken, lobbyPanel, callPanel, setActiveSession, onReturnedToLobby)
            } else {
                returnToLobby(callPanel, lobbyPanel, setActiveSession, onReturnedToLobby, baseDocumentTitle)
            }
        }
    }

    // Wave 6: `room.id` here is ALWAYS the PARENT room's id (see `room`'s own alias declaration at
    // the top of this function) -- inside a breakout call this button is relabeled "Besprechung ganz
    // verlassen" (see its own declaration) but its RPC target is unchanged: leaving the breakout
    // room's own LiveKit connection plus leaving the PARENT meeting's `conference_participation`
    // record, exactly like leaving from Main always has.
    leaveButton.onClick {
        leaveButton.disabled = true
        transition(ConferenceConnectionEvent.UserLeft)
        AppScope.launch {
            guarded { session.disconnect() }
            guarded { rpcService<IConferenceService>().leaveRoom(room.id) }
            setActiveSession(null)
            returnToLobby(callPanel, lobbyPanel, setActiveSession, onReturnedToLobby, baseDocumentTitle)
        }
    }

    endButton?.onClick {
        endRoomConfirmDialog(roomTitle) {
            endButton.disabled = true
            transition(ConferenceConnectionEvent.UserLeft)
            AppScope.launch {
                guarded { session.disconnect() }
                val result = guarded { rpcService<IConferenceService>().endRoom(room.id) }
                if (result != null) {
                    notifySuccess(gettext("Besprechung \"%1\" wurde für alle beendet.", result.title))
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

/** V1.0 Videokonferenzen, Wave 6 "Breakout-Räume" -- what [enterCall]'s `onDisconnected` callback
 * should do next, resolved by [resolvePostDisconnectDestination]/[resolvePostDisconnectDestinationOf].
 * `internal`, not `private` -- [resolvePostDisconnectDestinationOf]'s own pure-core unit tests live in
 * a separate file (`ConferencePostDisconnectDestinationTest.kt`) and need to reference these cases. */
internal sealed class PostDisconnectDestination {
    data object Ended : PostDisconnectDestination()

    data class Breakout(
        val assignment: ConferenceBreakoutAssignmentDto,
        val parentRoom: ConferenceRoomDto,
    ) : PostDisconnectDestination()

    data class Main(
        val parentRoom: ConferenceRoomDto,
    ) : PostDisconnectDestination()
}

/**
 * V1.0 Videokonferenzen, Wave 6 "Breakout-Räume" -- the SOLE place a "why did I just disconnect"
 * question is answered -- re-derives the truth from the server on every call rather than trusting
 * any local guess, exactly the "never trust a cached authorization/state flag" discipline this
 * codebase applies everywhere else (`requireModeratorOrPrivileged`, `listActiveRooms`' lazy
 * reconciliation). Covers FOUR causes with ONE mechanism: kicked, meeting ended, assigned to a
 * breakout room, recalled from one -- all indistinguishable at the raw LiveKit
 * `RoomEvent.Disconnected` layer, disambiguated here via two cheap RPC calls.
 *
 * [ConferenceRoomDto.active] `== false` catches both "moderator ended the whole meeting" and Wave 1's
 * own lazy-reconciliation timeout. A non-null [network.lapis.cloud.shared.domain.ConferenceBreakoutAssignmentDto]
 * catches "you were (re)assigned to a breakout room" -- reached whether the disconnect fired from Main
 * (first assignment) or from a DIFFERENT breakout room (re-assignment via `assignParticipants`). A
 * `null` assignment while the parent is still active catches "you were recalled" -- reached whenever
 * the *previous* target was a breakout room. This function does NOT itself distinguish "genuinely
 * kicked while in Main" from "a plain network hiccup LiveKit gave up retrying" -- both resolve to
 * [PostDisconnectDestination.Main] here; the CALLER's own `rejoinMainRoomToken` attempt is what tells
 * them apart in practice (a truly kicked/removed caller no longer holds an open
 * `conference_participation` row, so that call fails server-side and the caller's own `null`-result
 * fallback reaches [PostDisconnectDestination.Ended] exactly as before this wave). A caller merely
 * reconnecting after a transient drop gets a fresh token and rejoins automatically instead of being
 * dropped back to the Lobby -- a deliberate, welcome side effect of this wave's own mechanism, not
 * a scope creep bug.
 */
private suspend fun resolvePostDisconnectDestination(parentRoomId: String): PostDisconnectDestination {
    val parentRoom = guarded { rpcService<IConferenceService>().getRoom(parentRoomId) }
    val assignment = guarded { rpcService<IConferenceBreakoutService>().getMyBreakoutAssignment(parentRoomId) }?.singleOrNull()
    return resolvePostDisconnectDestinationOf(parentRoom, assignment)
}

/**
 * Pure branch logic behind [resolvePostDisconnectDestination], split out so it is unit-testable
 * without mocking the RPC layer -- same "pure core, thin RPC-touching wrapper" split this file
 * already establishes for [conferenceConnectionReduce]/[conferenceGridLayout]/
 * [conferenceStreamNeedsPoll]. [parentRoom] is `null` iff the `getRoom` call itself failed (network
 * error or the room no longer exists) -- treated the same as an inactive room. See
 * [resolvePostDisconnectDestination] KDoc for the full four-cause disambiguation this implements.
 */
internal fun resolvePostDisconnectDestinationOf(
    parentRoom: ConferenceRoomDto?,
    assignment: ConferenceBreakoutAssignmentDto?,
): PostDisconnectDestination =
    when {
        parentRoom == null || !parentRoom.active -> PostDisconnectDestination.Ended
        assignment != null -> PostDisconnectDestination.Breakout(assignment, parentRoom)
        else -> PostDisconnectDestination.Main(parentRoom)
    }

/** Rule 3 (irreversible action -> bespoke confirm modal): matches `AuctionScreen.kt`'s own
 * `auctionDisableConfirmDialog` tier -- danger-framed, states the concrete consequence in plain
 * language (design review D6's "Diese Besprechung wird für alle Teilnehmenden beendet. Fortfahren?"
 * copy). */
private fun endRoomConfirmDialog(
    roomTitle: String,
    onConfirm: () -> Unit,
) {
    val modal = Modal(caption = tr("Besprechung für alle beenden"))
    modal.div(tr("Diese Besprechung wird für alle Teilnehmenden beendet. Fortfahren?")) { addCssClasses("fw-bold text-danger") }
    modal.div(gettext("\"%1\" wird sofort geschlossen -- alle Verbindungen werden getrennt.", roomTitle)) {
        addCssClasses("text-muted small")
    }
    modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button(tr("Für alle beenden"), style = ButtonStyle.DANGER).apply {
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
    val modal = Modal(caption = tr("Teilnehmer entfernen"))
    modal.div(gettext("\"%1\" aus der Besprechung entfernen?", displayName)) { addCssClass("fw-bold") }
    modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button(tr("Entfernen"), style = ButtonStyle.DANGER).apply {
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
    val modal = Modal(caption = tr("Aufzeichnung starten"))
    modal.div(
        tr("Diese Aufzeichnung kann NICHT rückgängig gemacht werden, sobald Teilnehmende gesprochen haben."),
    ) { addCssClasses("fw-bold text-danger") }
    modal.div(
        tr(
            "Alle aktuell anwesenden Teilnehmenden sehen sofort, dass aufgezeichnet wird -- unabhängig " +
                "davon, wer die Aufzeichnung startet.",
        ),
    ) { addCssClasses("small mb-2") }
    val accessOptions = DocumentAccessLevel.entries.map { it.name to conferenceRecordingAccessLevelLabel(it) }
    val accessSelect =
        modal.select(options = accessOptions, value = DocumentAccessLevel.BOARD_ONLY.name, label = tr("Zugriffsebene"))
    modal.div(
        tr(
            "Bei \"Vorstand\" können anwesende Mitglieder, die nicht dem Vorstand angehören, die " +
                "Aufzeichnung später NICHT ansehen -- wählen Sie \"Mitglieder\", wenn die Aufnahme allen " +
                "Teilnehmenden zugänglich sein soll.",
        ),
    ) { addCssClasses("text-muted small mb-2") }
    modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button(tr("Aufzeichnung jetzt starten"), style = ButtonStyle.WARNING).apply {
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
    val modal = Modal(caption = tr("Aufzeichnung beenden"))
    modal.div(
        tr("Die Aufzeichnung wird beendet und danach automatisch zu einer Videodatei zusammengeführt."),
    ) { addCssClass("fw-bold") }
    modal.div(
        tr(
            "Dieser Vorgang lässt sich nicht wiederholen -- schlägt er fehl, bleiben die Rohaufnahmen " +
                "erhalten und ein Administrator kann helfen.",
        ),
    ) { addCssClasses("text-muted small") }
    modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button(tr("Aufzeichnung beenden"), style = ButtonStyle.DANGER).apply {
            onClick {
                modal.hide()
                onConfirm()
            }
        },
    )
    modal.show()
}

// ================================================================================================
// Wave 6 "Breakout-Räume" -- dialogs. Design review verdicts, locked copy.
// ================================================================================================

/**
 * Design review verdict #1/#2 -- one number input (default 2, capped at [MAX_BREAKOUT_ROOMS_CLIENT]),
 * primary button "Räume erstellen und verteilen" (never "OK"), auto-distribute-evenly as the only V1
 * action inside the modal (manual override happens afterward via the roster, see
 * `refreshRoster`'s own per-row select). [mediaActive] gates the disclosure line -- shown ONLY if a
 * recording/stream is currently active on the main room at the moment the dialog opens, per the
 * "invisible when not relevant" posture [refreshRecordingState]/[refreshStreamState] already apply
 * elsewhere in this file.
 */
private fun breakoutCreateDialog(
    mediaActive: Boolean,
    onConfirm: (roomCount: Int) -> Unit,
) {
    val modal = Modal(caption = tr("Breakout-Räume erstellen"))
    if (mediaActive) {
        modal.div(
            tr("Die laufende Aufzeichnung/der Live-Stream im Hauptraum erfasst kein Audio/Video in Breakout-Räumen."),
        ) { addCssClasses("text-muted small") }
    }
    val countInput = modal.text(value = "2", label = tr("Anzahl der Breakout-Räume"))
    modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button(tr("Räume erstellen und verteilen"), style = ButtonStyle.PRIMARY).apply {
            onClick {
                val count =
                    countInput.value
                        .orEmpty()
                        .trim()
                        .toIntOrNull()
                if (count == null || count !in 1..MAX_BREAKOUT_ROOMS_CLIENT) {
                    notifyError(gettext("Bitte eine Zahl zwischen 1 und %1 angeben.", MAX_BREAKOUT_ROOMS_CLIENT))
                    return@onClick
                }
                modal.hide()
                onConfirm(count)
            }
        },
    )
    modal.show()
}

/**
 * Design review verdict #3 -- one tier below [endRoomConfirmDialog] (recall abruptly interrupts up
 * to [MAX_BREAKOUT_ROOMS_CLIENT] concurrent conversations, so NOT zero-friction, but it is fully
 * reversible and nobody leaves the meeting, so it does not deserve `endRoom`'s danger-red "cannot be
 * undone" framing) -- no `text-danger` body text, primary button "Alle zurückholen" (never
 * "Bestätigen"), [ButtonStyle.WARNING] (matches [stopStreamConfirmDialog]-tier "disruptive but
 * expected", one step below [ButtonStyle.OUTLINEDANGER]/[ButtonStyle.DANGER]).
 */
private fun breakoutRecallConfirmDialog(onConfirm: () -> Unit) {
    val modal = Modal(caption = tr("Alle zurückholen"))
    modal.div(
        tr("Alle Teilnehmenden werden sofort aus ihren Breakout-Räumen in den Hauptraum zurückgeholt."),
    ) { addCssClass("fw-bold") }
    modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button(tr("Alle zurückholen"), style = ButtonStyle.WARNING).apply {
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
    val modal = Modal(caption = tr("Live-Stream starten"))
    val summaryBox = modal.div(conferenceStreamStartSummary(emptyList())) { addCssClasses("fw-bold text-danger") }

    modal.div(gettext("Ziele auswählen (max. %1):", maxDestinations)) { addCssClasses("fw-bold mt-2") }
    val targetsPanel = modal.vPanel(spacing = 2)
    val checkboxesByTarget =
        targets.associateWith { target ->
            targetsPanel.checkBox(label = gettext("%1 (%2)", target.label, conferenceStreamPlatformLabel(target.platform)))
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
    val layoutSelect = modal.select(options = layoutOptions, value = ConferenceStreamLayout.GRID.name, label = tr("Layout"))
    val participantSelect =
        modal.select(
            options = participantOptions,
            value = participantOptions.firstOrNull()?.first,
            label = tr("Person (nur bei \"Einzelne Person\")"),
        )
    participantSelect.hide()
    layoutSelect.subscribe {
        if (layoutSelect.value == ConferenceStreamLayout.SINGLE_PARTICIPANT.name) participantSelect.show() else participantSelect.hide()
    }

    val latencyOptions = ConferenceStreamLatencyMode.entries.map { it.name to conferenceStreamLatencyModeLabel(it) }
    val latencySelect =
        modal.select(options = latencyOptions, value = ConferenceStreamLatencyMode.STANDARD.name, label = tr("Latenz"))

    modal.div(CONFERENCE_STREAM_SECRET_BALLOT_HINWEIS) { addCssClasses("text-muted small mt-2") }

    modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button(tr("Jetzt live gehen"), style = ButtonStyle.DANGER).apply {
            onClick {
                selectionErrorBox.hide()
                val selected = selectedTargets()
                if (!conferenceStreamSelectionValid(selected.size, maxDestinations)) {
                    selectionErrorBox.content = gettext("Bitte 1 bis %1 Ziele auswählen.", maxDestinations)
                    selectionErrorBox.show()
                    return@onClick
                }
                val layout = ConferenceStreamLayout.valueOf(layoutSelect.value ?: ConferenceStreamLayout.GRID.name)
                val participantIdentity = if (layout == ConferenceStreamLayout.SINGLE_PARTICIPANT) participantSelect.value else null
                if (layout == ConferenceStreamLayout.SINGLE_PARTICIPANT && participantIdentity.isNullOrBlank()) {
                    selectionErrorBox.content = tr("Bitte eine Person für \"Einzelne Person\" auswählen.")
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
    val modal = Modal(caption = tr("Stream unterbrechen"))
    modal.div(
        tr(
            "Die Besprechung läuft weiter. Die Zielplattform sieht eine Unterbrechung -- YouTube kann die " +
                "Übertragung dabei beenden, sodass ein neuer Link nötig wird.",
        ),
    ) { addCssClass("fw-bold") }
    modal.div(gettext("Betroffene Ziele: %1", destinationLabels)) { addCssClasses("text-muted small") }
    modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button(tr("Stream unterbrechen"), style = ButtonStyle.WARNING).apply {
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
    val modal = Modal(caption = tr("Stream fortsetzen"))
    modal.div(
        tr(
            "Der Stream wird neu verbunden -- die Zielplattform sieht dies unter Umständen erneut als " +
                "neue Übertragung.",
        ),
    ) { addCssClass("fw-bold") }
    modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button(tr("Stream fortsetzen"), style = ButtonStyle.WARNING).apply {
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
    val modal = Modal(caption = tr("Live-Stream beenden"))
    modal.div(gettext("Stream beenden? Die Übertragung zu %1 endet sofort.", destinationLabels)) {
        addCssClasses("fw-bold text-danger")
    }
    modal.div(tr("Die Besprechung selbst läuft für alle Teilnehmenden unverändert weiter.")) { addCssClasses("text-muted small") }
    modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button(tr("Live-Stream beenden"), style = ButtonStyle.DANGER).apply {
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

/** Wave 4 "Politur", D1 -- the single-button flow's auto-generated default title. German date order,
 * zero-padded, mirrors `PdfMailmergeSupport.formatGermanDate`'s non-deprecated `.day`/`.month.number`/
 * `.year` API (NOT `.dayOfMonth`/`.monthNumber`, deprecated in kotlinx-datetime 0.8.0) -- via
 * `.padStart`, not `String.format` (JVM-only, unavailable in this jsMain target), matching this
 * file's own established `padStart` idiom for [conferenceRecordingStartedLabel]/
 * [conferenceStreamStartedLabel]. Deliberately includes the date, not just the time, since a room in
 * "Aktive Besprechungen" can persist across days. */
internal fun conferenceDefaultRoomTitle(now: LocalDateTime): String {
    val day = now.day.toString().padStart(2, '0')
    val monthNumber = now.month.number
    val month = monthNumber.toString().padStart(2, '0')
    val hour = now.hour.toString().padStart(2, '0')
    val minute = now.minute.toString().padStart(2, '0')
    return gettext("Besprechung vom %1.%2.%3, %4:%5", day, month, now.year, hour, minute)
}

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

/**
 * V1.0 Videokonferenzen, Wave 5, design review D12 -- pure, jsTest-covered tile-label composer.
 * Deliberately NO `isGuest` parameter: guest status is a dedicated pill (`ConferenceTileEntry
 * .guestBadgeEl`), never a text suffix here -- a suffix would be the first thing the tile's own
 * ellipsis-truncated name badge eats for a long display name, exactly the federated guests whose
 * name comes from a foreign server's unclamped claim.
 */
internal fun conferenceTileLabel(
    displayName: String,
    isLocal: Boolean,
    isModerator: Boolean,
): String {
    val suffix = if (isLocal) gettext(" (Sie)") else ""
    val moderatorSuffix = if (isModerator) gettext(" · Moderator") else ""
    return displayName + suffix + moderatorSuffix
}

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
 * against the SAME literal the UI actually renders.
 *
 * gettext() (not tr()) -- read directly by ConferenceScreenTest and by recordingBanner.div(...)
 * below, not re-resolved on every widget render; see CONFERENCE_STREAM_BANNER_TEXT's own KDoc for
 * the full reasoning (same pattern, same narrow language-switch-after-first-access limitation). */
internal val CONFERENCE_RECORDING_BANNER_TEXT: String by lazy { gettext("Diese Besprechung wird ab jetzt aufgezeichnet.") }

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
        ConferenceRecordingStatus.RECORDING -> gettext("Wird aufgezeichnet")
        ConferenceRecordingStatus.STOPPING -> gettext("Wird beendet …")
        ConferenceRecordingStatus.PROCESSING -> gettext("Wird zusammengeführt …")
        ConferenceRecordingStatus.READY -> gettext("Bereit")
        ConferenceRecordingStatus.FAILED -> gettext("Fehlgeschlagen")
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
        DocumentAccessLevel.PUBLIC_MEMBERS -> gettext("Mitglieder")
        DocumentAccessLevel.BOARD_ONLY -> gettext("Vorstand")
        DocumentAccessLevel.ADMIN_ONLY -> gettext("Administration")
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
    return gettext("Aufzeichnung gestartet von %1 um %2:%3", startedByDisplayName, hour, minute)
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
        ConferenceStreamStatus.STARTING -> gettext("wird gestartet")
        ConferenceStreamStatus.LIVE -> gettext("läuft")
        ConferenceStreamStatus.PAUSED -> gettext("ist unterbrochen")
        ConferenceStreamStatus.STOPPING -> gettext("wird beendet")
        ConferenceStreamStatus.ENDED, ConferenceStreamStatus.FAILED -> gettext("ist beendet")
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
        rows += ConferenceStatusBadgeRow(gettext("● Aufzeichnung läuft"), "danger")
    }
    if (activeStream != null) {
        val labels = activeStream.targets.joinToString(", ") { it.label }.ifBlank { gettext("unbekanntes Ziel") }
        val verbPhrase = conferenceStreamBadgeVerbPhrase(activeStream.status)
        rows +=
            ConferenceStatusBadgeRow(
                gettext("◆ Live-Stream %1 → %2", verbPhrase, labels),
                conferenceStreamStatusColor(activeStream.status),
            )
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
        ConferenceStreamStatus.STARTING -> gettext("Verbindung wird hergestellt …")
        ConferenceStreamStatus.LIVE -> gettext("Live")
        ConferenceStreamStatus.PAUSED -> gettext("Unterbrochen")
        ConferenceStreamStatus.STOPPING -> gettext("Wird beendet …")
        ConferenceStreamStatus.ENDED -> gettext("Beendet")
        ConferenceStreamStatus.FAILED -> gettext("Fehlgeschlagen")
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
        ConferenceStreamTargetStatus.PENDING -> gettext("Verbindung wird hergestellt …")
        ConferenceStreamTargetStatus.ACTIVE -> gettext("Live")
        ConferenceStreamTargetStatus.FINISHED -> gettext("Beendet")
        ConferenceStreamTargetStatus.FAILED -> gettext("Fehlgeschlagen")
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
        ConferenceStreamLayout.GRID -> gettext("Galerie")
        ConferenceStreamLayout.SPEAKER -> gettext("Sprecher")
        ConferenceStreamLayout.SINGLE_PARTICIPANT -> gettext("Einzelne Person")
    }

/** [startStreamDialog]'s Latenz-Auswahl -- BOTH branches verified live against the real container
 * (see [network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode] KDoc "GO decision"), so
 * unlike a not-yet-verified control this one ships without a go/no-go caveat (design review D10). */
internal fun conferenceStreamLatencyModeLabel(mode: ConferenceStreamLatencyMode): String =
    when (mode) {
        ConferenceStreamLatencyMode.STANDARD -> gettext("Standard")
        ConferenceStreamLatencyMode.LOW_LATENCY -> gettext("Niedrige Latenz")
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
        gettext("Bitte wählen Sie mindestens ein Ziel aus.")
    } else {
        gettext(
            "Sie starten jetzt einen Live-Stream zu: %1. Diese Ziele sind sofort öffentlich sichtbar -- die " +
                "Übertragung kann NICHT zurückgeholt werden, sobald Teilnehmende gesprochen haben.",
            selectedLabels.joinToString(", "),
        )
    }

/** [startStreamDialog]'s mandatory, static Hinweis (design review D12/Jobs' verdict item 2 --
 * [IConferenceStreamingService] KDoc "No automatic stream pause during secret ballots"): no UI copy
 * anywhere in this screen claims automatic protection exists.
 *
 * gettext() (not tr()), and evaluated once via `by lazy` rather than per-render -- both KDoc'd
 * on CONFERENCE_STREAM_BANNER_TEXT below apply here identically. */
internal val CONFERENCE_STREAM_SECRET_BALLOT_HINWEIS: String by lazy {
    gettext(
        "Bei geheimen Abstimmungen muss der Stream manuell unterbrochen werden. Eine automatische " +
            "Unterbrechung gibt es in dieser Version noch nicht.",
    )
}

/** [streamBanner]'s exact, terminology-locked copy -- mirrors [CONFERENCE_RECORDING_BANNER_TEXT]'s
 * own precedent (a top-level constant so [ConferenceStreamingUiTest] can assert against the SAME
 * literal the UI actually renders).
 *
 * gettext() (not tr()) -- this `val` is read directly (e.g. by ConferenceStreamingUiTest and by
 * streamBanner.div(...) below), not passed live through a widget's render pass every time, so
 * tr()'s deferred-marker resolution never fires; gettext() resolves once, immediately, at the
 * `by lazy` block's first access (deferred past module-load specifically so I18n.manager is
 * already initialized by then -- see this property's own site history/KDoc precedent on
 * CONFERENCE_RECORDING_BANNER_TEXT). Note this means a language switch AFTER first access won't
 * retranslate this one specific banner without a page reload -- an accepted, narrow limitation of
 * the `by lazy` pattern itself, not something this i18n wave introduces new. */
internal val CONFERENCE_STREAM_BANNER_TEXT: String by lazy { gettext("Diese Besprechung wird ab jetzt live gestreamt.") }

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
    return gettext(
        "Live-Stream gestartet von %1 um %2:%3 · %4",
        startedByDisplayName,
        hour,
        minute,
        conferenceStreamLayoutLabel(layout),
    )
}

// ================================================================================================
// Wave 4 "Politur" -- D1/D3/D10 pure helpers. Same DOM-free unit-test posture as the rest of this
// file's pure-logic sections -- [enterCall]'s own raw-DOM wiring (`applyConferenceGridReflow`,
// `renderConnectionState`, the inline rename row) is out of scope here, covered only structurally by
// this wave's live-browser verification, not a unit test.
// ================================================================================================

/** D1 -- client-side mirror of `ConferenceService.renameRoom`'s own `MAX_TITLE_LENGTH` (200) --
 * duplicated here only as a client-side UX pre-check (same "not the real boundary" posture
 * [conferenceStreamSelectionValid] already documents for its own server-side mirror), the server
 * re-validates independently regardless. */
internal const val MAX_ROOM_TITLE_LENGTH_CLIENT = 200

/** V1.0 Videokonferenzen, Wave 6 -- client-side mirror of `ConferenceBreakoutService`'s own
 * `MAX_BREAKOUT_ROOMS` (20) -- same "not the real boundary" posture as [MAX_ROOM_TITLE_LENGTH_CLIENT]. */
internal const val MAX_BREAKOUT_ROOMS_CLIENT = 20

/** D3 -- above this many participants, the flat single grid stops reflowing sensibly and the
 * speaking-priority reflow kicks in. Approved as specified in the design review. */
internal const val CONFERENCE_GRID_REFLOW_THRESHOLD = 12

/** D3 -- the priority zone's own capacity once reflowed. Bounded by design: if MORE identities are
 * simultaneously prioritized than this, the overflow is pushed to the compact strip too -- a
 * genuinely-speaking 7th person can be momentarily compact, a deliberate trade-off (design review
 * "Decided" list), not a bug. */
internal const val CONFERENCE_PRIORITY_ZONE_MAX = 6

/** D3 -- how long (ms) an identity stays in [enterCall]'s `lastSpokeAtMs`-derived priority set after
 * its last `ActiveSpeakersChanged` mention. Approved as specified. */
internal const val CONFERENCE_SPEAKING_PRIORITY_WINDOW_MS = 8_000L

/** D3 -- [enterCall]'s periodic `sweepGridReflow` cadence; the SOLE trigger for
 * `applyConferenceGridReflow` (see that function's own KDoc "Required change 1"). Approved as
 * specified -- matches the debounce cadence real conferencing UIs (Zoom/Meet) use for speaker-view
 * switching. */
internal const val CONFERENCE_GRID_REFLOW_SWEEP_INTERVAL_MS = 2_000L

/**
 * Security-audit fix -- debounce window [enterCall]'s `scheduleGuestHomeserverRefresh` waits after
 * the LAST `onParticipantJoined` event before actually calling `refreshGuestHomeservers`, coalescing
 * a burst of joins (e.g. a room filling to its participant cap) into one trailing RPC instead of one
 * per join -- see that function's own KDoc "DoS amplification".
 */
internal const val GUEST_HOMESERVER_REFRESH_DEBOUNCE_MS = 1_500L

/** D3 -- the pure partition [enterCall]'s `applyConferenceGridReflow` renders from. */
internal data class ConferenceGridLayout(
    val reflowed: Boolean,
    val priorityIdentities: List<String>,
    val compactIdentities: List<String>,
)

/**
 * D3 -- pure partition, no DOM/time/local-participant special-casing inside it (the caller composes
 * [priorityIdentities] from currently-speaking + "recently active within the window" + the local
 * participant's own identity, see [enterCall]'s `currentPriorityIdentities`). [orderedIdentities] is
 * JOIN order (`tiles.keys`, insertion-ordered `LinkedHashMap`) -- deliberately NEVER the
 * alphabetically-sorted roster order [refreshRoster] uses, so tile POSITIONS don't jump every time
 * the roster re-sorts.
 *
 * `<= threshold`: unreflowed, everyone in [priorityIdentities] in join order, [compactIdentities]
 * empty -- today's Wave 1-3 behaviour, byte-for-byte (see [enterCall]'s own "Required change 2").
 * `> threshold`: up to [maxPriorityTiles] slots, filled FIRST from [priorityIdentities] (in join
 * order), then padded with the next join-order identities if fewer than [maxPriorityTiles] are
 * actually prioritized -- so the priority zone is never oddly empty when nobody is currently
 * speaking. Overflow beyond [maxPriorityTiles] (more people prioritized than fit) is pushed to the
 * compact strip too -- a bounded priority zone by design (design review "Decided" list), even if
 * that momentarily compacts a genuinely-speaking participant.
 */
internal fun conferenceGridLayout(
    orderedIdentities: List<String>,
    priorityIdentities: Set<String>,
    threshold: Int = CONFERENCE_GRID_REFLOW_THRESHOLD,
    maxPriorityTiles: Int = CONFERENCE_PRIORITY_ZONE_MAX,
): ConferenceGridLayout {
    if (orderedIdentities.size <= threshold) {
        return ConferenceGridLayout(reflowed = false, priorityIdentities = orderedIdentities, compactIdentities = emptyList())
    }
    val prioritizedInOrder = orderedIdentities.filter { it in priorityIdentities }
    val fallbackFill = orderedIdentities.filterNot { it in priorityIdentities }
    val priority = (prioritizedInOrder + fallbackFill).take(maxPriorityTiles.coerceAtLeast(1))
    val prioritySet = priority.toSet()
    val compact = orderedIdentities.filterNot { it in prioritySet }
    return ConferenceGridLayout(reflowed = true, priorityIdentities = priority, compactIdentities = compact)
}

/**
 * V1.0 Videokonferenzen, Wave 6 "Breakout-Räume" -- what [enterCall] is CURRENTLY connected to: the
 * meeting's main LiveKit room, or a specific breakout room of it. Every RPC call [enterCall] makes
 * against [network.lapis.cloud.shared.rpc.IConferenceService]/
 * [network.lapis.cloud.shared.rpc.IConferenceRecordingService]/
 * [network.lapis.cloud.shared.rpc.IConferenceStreamingService] (roster, rename, recording, streaming,
 * guest access, end-room, remove-participant, leave-room) targets [MainRoom.room]/
 * [BreakoutRoom.parentRoom]'s id either way -- those RPC surfaces know nothing about breakout rooms
 * at all (see [network.lapis.cloud.shared.rpc.IConferenceBreakoutService] KDoc
 * "`conference_participation` stays open across a breakout excursion"). Only the ACTUAL LiveKit
 * connection (via the already-server-resolved [network.lapis.cloud.shared.domain.ConferenceJoinTokenDto])
 * differs between the two.
 */
internal sealed class ConferenceCallTarget {
    internal data class MainRoom(
        val room: ConferenceRoomDto,
    ) : ConferenceCallTarget()

    internal data class BreakoutRoom(
        val breakoutRoomId: String,
        val label: String,
        val parentRoom: ConferenceRoomDto,
    ) : ConferenceCallTarget()
}

/**
 * D10 -- the client-side connection-state machine [enterCall] renders from. [Ended] is terminal: no
 * event moves out of it (a fresh `enterCall` constructs a brand-new instance for the next call). A
 * forcibly-terminated/kicked session (server-side `endRoom`/`removeParticipant`) reaches [Ended] via
 * [ConferenceConnectionEvent.DisconnectedSignal] from BOTH [Connected] and [Reconnecting] -- see
 * [conferenceConnectionReduce] and file KDoc "D10" for the security-relevant framing. Wave 6
 * "Breakout-Räume" adds [Resolving] between those two states and [Ended] -- see that state's own
 * KDoc.
 */
internal sealed class ConferenceConnectionState {
    internal data object Disconnected : ConferenceConnectionState()

    internal data object Connecting : ConferenceConnectionState()

    internal data object Connected : ConferenceConnectionState()

    internal data object Reconnecting : ConferenceConnectionState()

    internal data class Failed(
        val reason: String,
    ) : ConferenceConnectionState()

    /**
     * V1.0 Videokonferenzen, Wave 6 "Breakout-Räume" -- entered the instant a genuine
     * `RoomEvent.Disconnected` fires (kick, meeting-end, breakout assignment, or recall -- all
     * INDISTINGUISHABLE at the LiveKit transport layer) while this screen asks the server, via
     * `resolvePostDisconnectDestination`, what this disconnect actually means. Non-terminal in the
     * type system but effectively a dead end for THIS `enterCall` invocation: the only two things
     * that ever happen next are (a) `transition(ResolvedAsEnded)` -> [Ended], the pre-Wave-6 kicked/
     * ended path unchanged, or (b) this invocation is abandoned outright in favor of a BRAND NEW
     * `enterCall(...)` call for the resolved destination (main room or a specific breakout room),
     * which owns its OWN fresh state machine starting at [Disconnected]. Deliberately ONE new state
     * rather than a second "SwitchingRoom" state -- once an invocation decides "this disconnect means
     * relocate, not end", it hands off to a brand-new `enterCall` call; the OLD invocation's state
     * machine has no further use for a "switching" state of its own (its polling loops already
     * stopped, see the `isLive()` guard those loops share, and nothing reads its `connectionState`
     * again once a new `enterCall` has taken over the panel).
     */
    internal data object Resolving : ConferenceConnectionState()

    internal data object Ended : ConferenceConnectionState()
}

/** D10 -- events [enterCall] feeds into [conferenceConnectionReduce]. */
internal sealed class ConferenceConnectionEvent {
    internal data object ConnectRequested : ConferenceConnectionEvent()

    internal data object ConnectSucceeded : ConferenceConnectionEvent()

    internal data class ConnectFailed(
        val reason: String,
    ) : ConferenceConnectionEvent()

    /** `RoomEvent.Reconnecting`. */
    internal data object ReconnectingSignal : ConferenceConnectionEvent()

    /** `RoomEvent.Reconnected`. */
    internal data object ReconnectedSignal : ConferenceConnectionEvent()

    /** `RoomEvent.Disconnected` -- kick, room-end, or genuine network death; this is the ONE event
     * that must be able to reach [ConferenceConnectionState.Ended] from every non-terminal state. */
    internal data object DisconnectedSignal : ConferenceConnectionEvent()

    /** Local "Verlassen"/"Für alle beenden" click. */
    internal data object UserLeft : ConferenceConnectionEvent()

    /**
     * V1.0 Videokonferenzen, Wave 6 -- fired once `resolvePostDisconnectDestination` has determined a
     * [ConferenceConnectionState.Resolving] instance's disconnect really does mean "the meeting is
     * over for this screen" (parent room inactive, or no live breakout/main-room re-entry possible)
     * -- reaches the SAME [ConferenceConnectionState.Ended] state and the SAME `returnToLobby`/
     * `notifyInfo` path [DisconnectedSignal] reached directly before this wave.
     */
    internal data object ResolvedAsEnded : ConferenceConnectionEvent()
}

/**
 * V1.0 Videokonferenzen, Wave 6 -- `true` only for [ConferenceConnectionState.Connected]/
 * [ConferenceConnectionState.Reconnecting], the two states in which a background poll against THIS
 * `enterCall` invocation's own room is still meaningful. Required, minimal-diff fix for
 * `pollInFlightRecordingStatus`/`pollInFlightStreamStatus`/`sweepGridReflow`'s own loop guards, which
 * pre-Wave-6 read `connectionState !is Ended` -- with [ConferenceConnectionState.Resolving] now
 * sitting between `Connected`/`Reconnecting` and `Ended`, that old guard would keep those loops
 * spinning (wasted RPC calls, and semantically wrong for the recording/streaming pollers specifically
 * -- a breakout room's target LiveKit room is never recorded/streamed at all, see
 * [network.lapis.cloud.shared.rpc.IConferenceBreakoutService] KDoc "DSGVO/transparency") for a call
 * that is already mid-relocation.
 */
internal fun ConferenceConnectionState.isLive(): Boolean =
    this is ConferenceConnectionState.Connected || this is ConferenceConnectionState.Reconnecting

/**
 * D10 -- the ONE place a transition happens. Unlisted (state, event) pairs are ignored (return the
 * SAME state) rather than throwing -- a duplicate/out-of-order LiveKit push (e.g. a late
 * `DisconnectedSignal` arriving after the user already clicked "Verlassen") must never crash this
 * screen. [ConferenceConnectionState.Ended] is terminal: every branch of its own `when` returns
 * `current` unconditionally.
 */
internal fun conferenceConnectionReduce(
    current: ConferenceConnectionState,
    event: ConferenceConnectionEvent,
): ConferenceConnectionState =
    when (current) {
        is ConferenceConnectionState.Disconnected ->
            when (event) {
                is ConferenceConnectionEvent.ConnectRequested -> ConferenceConnectionState.Connecting
                else -> current
            }
        is ConferenceConnectionState.Connecting ->
            when (event) {
                is ConferenceConnectionEvent.ConnectSucceeded -> ConferenceConnectionState.Connected
                is ConferenceConnectionEvent.ConnectFailed -> ConferenceConnectionState.Failed(event.reason)
                is ConferenceConnectionEvent.UserLeft -> ConferenceConnectionState.Ended
                else -> current
            }
        is ConferenceConnectionState.Connected ->
            when (event) {
                is ConferenceConnectionEvent.ReconnectingSignal -> ConferenceConnectionState.Reconnecting
                // Wave 6: CHANGED from a direct -> Ended transition -- see
                // [ConferenceConnectionState.Resolving] KDoc. `UserLeft` (a local "Verlassen"/"Für
                // alle beenden"/"Zurück zum Hauptraum" click) is DELIBERATELY untouched: those
                // handlers call `session.disconnect()` + the relevant RPC + `transition(UserLeft)`
                // themselves, synchronously, bypassing `onDisconnected`'s ambiguity entirely by
                // design -- a local leave is never something this screen needs to ask the server
                // "what does this mean" about.
                is ConferenceConnectionEvent.DisconnectedSignal -> ConferenceConnectionState.Resolving
                is ConferenceConnectionEvent.UserLeft -> ConferenceConnectionState.Ended
                else -> current
            }
        is ConferenceConnectionState.Reconnecting ->
            when (event) {
                is ConferenceConnectionEvent.ReconnectedSignal -> ConferenceConnectionState.Connected
                // Wave 6: CHANGED from a direct -> Ended transition -- see the identical comment on
                // the `Connected` branch above.
                is ConferenceConnectionEvent.DisconnectedSignal -> ConferenceConnectionState.Resolving
                is ConferenceConnectionEvent.UserLeft -> ConferenceConnectionState.Ended
                else -> current
            }
        is ConferenceConnectionState.Failed ->
            when (event) {
                is ConferenceConnectionEvent.UserLeft -> ConferenceConnectionState.Ended
                else -> current
            }
        // Wave 6: the ONLY way out of Resolving -- see [ConferenceConnectionState.Resolving] KDoc.
        // Any other event (e.g. a stray ReconnectingSignal arriving late) is ignored, same "unlisted
        // pairs are ignored" discipline every other branch here already follows.
        is ConferenceConnectionState.Resolving ->
            when (event) {
                is ConferenceConnectionEvent.ResolvedAsEnded -> ConferenceConnectionState.Ended
                else -> current
            }
        is ConferenceConnectionState.Ended -> current
    }
