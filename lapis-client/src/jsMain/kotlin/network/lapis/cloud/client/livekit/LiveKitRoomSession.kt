package network.lapis.cloud.client.livekit

import io.kvision.utils.obj
import kotlinx.coroutines.await
import kotlinx.serialization.json.Json
import network.lapis.cloud.shared.domain.ConferenceChatMessage
import network.lapis.cloud.shared.domain.ConferenceTurnServer
import network.lapis.cloud.shared.domain.NoteBlockBroadcastDto
import network.lapis.cloud.shared.domain.WhiteboardStrokeWireDto
import network.lapis.cloud.shared.domain.isStructurallyValid
import kotlin.js.unsafeCast

private external interface PublishDataOptions {
    var reliable: Boolean?
    var topic: String?
}

/** V1.3.x Geräteauswahl -- the ONE place `livekit-client`'s three `MediaDeviceKind` string literals
 * ("audioinput"/"videoinput"/"audiooutput") are named on the Kotlin side, mirroring `LiveKitJs.kt`
 * file KDoc "device-kind strings, never MediaDeviceKind". [jsKind] is passed verbatim to every
 * [Room.getLocalDevices]/[Room.getActiveDevice]/[Room.switchActiveDevice] call. */
enum class ConferenceDeviceKind(
    val jsKind: String,
) {
    MICROPHONE("audioinput"),
    CAMERA("videoinput"),
    SPEAKER("audiooutput"),
}

/** V1.3.x Geräteauswahl -- Kotlin-side mirror of `livekit-client`'s `MediaDeviceFailure` string
 * enum (see `LiveKitJs.kt`'s `MediaDeviceFailure` KDoc), plus [OTHER] as the catch-all for both a
 * genuinely un-classifiable `getUserMedia`/`DOMException` AND [MediaDeviceFailure.getFailure]
 * returning `null` outright -- see [LiveKitRoomSession.classifyDeviceFailure]. */
enum class ConferenceDeviceFailure { PERMISSION_DENIED, NOT_FOUND, DEVICE_IN_USE, OTHER }

/** V1.3.x Geräteauswahl -- one enumerable device, as `ConferenceScreen.kt`'s device dropdowns need
 * it. [rawLabel] is the browser-reported device name (e.g. "Iraklis iPhone-Mikrofon") -- SECURITY:
 * this is display-only, it must NEVER be written to `localStorage`, a log, an RPC, or a chat/error
 * message, only [deviceId] is ever persisted (see
 * `ConferenceScreen.kt`'s own device-selection wiring, and the Security-Audit-Prüfliste in this
 * wave's plan). */
data class ConferenceDeviceOption(
    val deviceId: String,
    val rawLabel: String,
)

/**
 * Connection-level failure kinds `ConferenceScreen.kt` needs to distinguish, mirroring the
 * established [ConferenceDeviceFailure] pattern: the ONE place a raw LiveKit/WebRTC error becomes
 * a translatable, non-technical Kotlin value. Never carries the raw `e.message` onward --
 * SECURITY: the previous generic `guarded {}` fallback in `AppState.kt` surfaced an untranslated
 * SDK/browser internal string straight into a user-visible toast.
 */
enum class ConferenceConnectFailure {
    /** Media transport could not be established, NOT EVEN over a forced TURN relay -- the symptom
     * an ELB board member reported 2026-09-10. Most likely cause: a restrictive browser privacy
     * setting (Brave Shields / fingerprinting protection) or a network that blocks WebRTC. */
    RELAY_EXHAUSTED,

    /** Every other connection failure (auth rejected, signalling unreachable, aborted). */
    OTHER,
}

/**
 * `livekit-client`'s own `ConnectionErrorReason` numeric enum, read off a rejected
 * `Room.connect(...)` error via [connectionErrorReasonOf] -- the same "classify a raw JS error
 * locally" discipline [LiveKitRoomSession.classifyDeviceFailure] already established.
 * Verified against `dist/src/room/errors.d.ts`:
 * `NotAllowed=0, ServerUnreachable=1, InternalError=2, Cancelled=3, LeaveRequest=4, Timeout=5,
 * WebSocket=6, ServiceNotFound=7`.
 *
 * [conferenceShouldRetryOverRelay] is `true` ONLY for the two reasons a forced relay path could
 * plausibly repair:
 * - `InternalError (2)` -- what `ensurePCTransportConnection` throws on its 15 s
 *   `peerConnectionTimeout` (`ConnectionError.internal('could not establish pc connection')`),
 *   i.e. the ICE-never-established case this whole wave exists for.
 * - `Timeout (5)`.
 * - `null` (reason unreadable -- a non-`ConnectionError` throwable) is treated as retryable on
 *   purpose: a missed retry costs a still-broken call, a superfluous one costs 15 s.
 *
 * Deliberately `false` for `NotAllowed (0)` (bad/expired token -- relay cannot help, and retrying
 * would double the wait before the user sees a truthful error), `Cancelled (3)`/`LeaveRequest (4)`
 * (the user or the server already ended this attempt), and `ServerUnreachable (1)`/`WebSocket (6)`/
 * `ServiceNotFound (7)` (SIGNALLING-layer failures -- signalling runs over WSS through the reverse
 * proxy and never touches ICE at all, so an ICE transport policy is irrelevant to them).
 */
internal fun conferenceShouldRetryOverRelay(connectionErrorReason: Int?): Boolean =
    connectionErrorReason == null || connectionErrorReason == 2 || connectionErrorReason == 5

/** Reads `ConnectionError.reason` off a rejected `Room.connect` error, `null` if absent/non-numeric. */
private fun connectionErrorReasonOf(error: dynamic): Int? = (error?.reason as? Int)

/**
 * Result of one [LiveKitRoomSession.attemptConnect] call -- deliberately NOT `Int?`. A plain `Int?`
 * return collided `null`-for-success (the happy path) with `null`-for-"failed, reason unreadable"
 * (a rejection [connectionErrorReasonOf] cannot read a numeric `.reason` off, which real
 * `livekit-client` 2.21.0 throwables routinely are -- `UnsupportedServer`, `NegotiationError`,
 * `DeviceUnsupportedError`, `SignalReconnectError`, or any raw `TypeError`/`DOMException` a
 * privacy-hardened browser's WebRTC stack throws, exactly the class of failure this whole wave
 * exists for). That collision made every genuinely-unreadable failure silently read as success one
 * level up in [LiveKitRoomSession.connect] -- see this type's introduction, audit finding
 * "Sentinel-Kollision".
 */
internal sealed interface ConnectAttemptResult {
    /** `room.connect(...)` resolved; [LiveKitRoomSession.room] now points at the connected [Room]. */
    data object Success : ConnectAttemptResult

    /** `room.connect(...)` rejected. [reason] is `null` when the rejection carried no readable
     * numeric [ConnectionErrorReason] -- itself a real, expected outcome (see this interface's own
     * KDoc), never to be confused with [Success]. */
    data class Failed(
        val reason: Int?,
    ) : ConnectAttemptResult
}

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 1 -- owns exactly one [Room] and turns its
 * callback-shaped JS event stream into idiomatic Kotlin callbacks + `suspend` functions. This is the
 * ONLY place in the client where a raw `dynamic` value coming out of `livekit-client` is unwrapped
 * (via [kotlin.js.unsafeCast] -- see `LiveKitJs.kt` file KDoc for why plain `as`/`as?` cannot be used
 * against these `external interface` types) -- `ConferenceScreen.kt` only ever sees the typed
 * [Track]/[TrackPublication]/`String` identity values this class hands it.
 *
 * **Roster-seeding gotcha** (this wave's plan, "Three client gotchas"): `RoomEvent.ParticipantConnected`
 * does NOT fire for participants already in the room at the moment [connect] resolves. [connect]
 * therefore iterates [Room.remoteParticipants] itself, right after the underlying `room.connect(...)`
 * promise resolves, and fires [onParticipantJoined] once per already-present participant -- exactly
 * the same callback a LATER join fires through the ordinary `ParticipantConnected` event. Any already-
 * published track for those participants still arrives the ordinary way, through `TrackSubscribed`,
 * once LiveKit's default auto-subscribe completes -- this class does not special-case that.
 *
 * **Local self-view** ([onLocalVideoTrack]): `LocalParticipant.setCameraEnabled` resolves to a
 * `LocalTrackPublication` typed as `dynamic` in `LiveKitJs.kt` on purpose (see that file's KDoc "only
 * what a concrete call site needs") -- [setCamera] pulls the one field it needs (`.track`) off that
 * dynamic value directly, rather than growing the pinned external-interface surface for a single
 * field only this method touches.
 *
 * **Recording signal** (V1.0 Wave 2 "Aufzeichnung"): [onRecordingStatusChanged] is invoked BOTH (a)
 * on every `RoomEvent.RecordingStatusChanged` push from LiveKit for the lifetime of the connection,
 * AND (b) exactly once, synchronously, right after [connect]'s underlying `room.connect(...)`
 * promise resolves, with [Room.isRecording]'s value AT THAT MOMENT -- design review D4 "late joiners
 * get the banner too": `RecordingStatusChanged` only fires on a CHANGE, so a participant joining a
 * room that is ALREADY recording would otherwise see neither the persistent badge nor the one-time
 * notice banner until some LATER stop/start transition, if one ever happens before they leave. This
 * class never reads [Room.isRecording] itself outside these two call sites -- `ConferenceScreen.kt`
 * owns all resulting UI state (badge visibility, banner, `document.title`), this class only relays
 * the raw signal.
 *
 * **Chat trust boundary** (see [ConferenceChatMessage] KDoc): a `DataReceived` payload's own
 * `senderMemberId`/`senderDisplayName` fields are attacker-controllable by any room participant. This
 * class OVERWRITES both with the SDK-supplied [RemoteParticipant.identity]/`.name` of the participant
 * argument the event itself carries (server-verified via the signed join token's `sub`/`name` claims)
 * before ever calling [onChat] -- callers never see the untrusted, self-reported values. Own,
 * locally-sent messages are never delivered back to the sender by LiveKit's data channel, so
 * `ConferenceScreen.kt` renders its own outgoing chat messages itself, immediately on [sendChat]
 * returning, rather than waiting for an echo through [onChat].
 *
 * **Speaking-priority signal** (V1.0 Videokonferenzen Wave 4 "Politur", D3): [onActiveSpeakersChanged]
 * relays [RoomEvent.ActiveSpeakersChanged] verbatim as a list of identities -- this class does not
 * debounce or cache it. `ConferenceScreen.kt`'s own periodic sweep (not this class) is what turns raw,
 * sub-second speaking-level transitions into a calm, non-strobing grid reflow -- see that file's own
 * KDoc "D3" for why the debounce lives there, not here.
 *
 * **Reconnect signal** (V1.0 Videokonferenzen Wave 4 "Politur", D10): [onReconnecting]/[onReconnected]
 * relay [RoomEvent.Reconnecting]/[RoomEvent.Reconnected] verbatim -- both fire with zero JS arguments,
 * same "extra Kotlin lambda parameters bind to `undefined`" pattern already used for
 * [RoomEvent.Disconnected] below. `ConferenceScreen.kt`'s own named connection-state machine (not this
 * class) owns what a transition means for the UI -- this class only relays the raw LiveKit signal.
 *
 * **Local mute state is event-driven, never purely optimistic** (bug fix, GitHub issue #3 "Audio
 * Mute and Camera Toggle Controls Are Unreliable"). [onLocalTrackMuteChanged] relays
 * [RoomEvent.TrackMuted]/[RoomEvent.TrackUnmuted], filtered in [wireEvents] to events whose
 * `participant.identity` equals [Room.localParticipant]'s own -- LiveKit fires this event for BOTH
 * local and remote participants on the same room-wide listener, so this class, not
 * `ConferenceScreen.kt`, is the one place that already knows which `identity` is "me". Before this
 * fix, `ConferenceScreen.kt`'s `micEnabled`/`cameraEnabled` were written ONLY from the button
 * click handler's own optimistic "the call to [setMicrophone]/[setCamera] didn't throw" branch --
 * correct for a click-caused change, but silently stale for every OTHER cause LiveKit can mute/
 * unmute a local track on its own (a reconnect that re-publishes with a different enabled state, a
 * device error ending the track, a mid-call permission revocation). [onLocalTrackMuteChanged] is now
 * the single source of truth the button state derives from; the click handlers still flip
 * optimistically for instant feedback, but the very next event this class relays reconciles that
 * against LiveKit's own authoritative state regardless of what caused the change.
 *
 * **[setCamera]/[setMicrophone]/[setScreenShare] no longer silently do nothing on a null [room].**
 * The previous `room?.localParticipant?.setMicrophoneEnabled(enabled)?.await()` shape returned
 * `Unit` (a non-null success value) via the safe-call chain even when [room] was `null` and nothing
 * was ever asked of LiveKit -- the button click handler's `result != null` check then can't tell
 * "the toggle actually happened" from "there was no room to toggle anything on", and would flip the
 * UI to claim success for a call that silently did nothing. Each now requires a non-null [room]
 * explicitly. [setScreenShare] still throws [IllegalStateException] on a null [room], surfaced by
 * `guarded {}`'s catch-all as a real error toast instead of a false "on" -- correct for that method,
 * since its `guarded {}` call site still uses the OLD "non-null result == success" convention (see
 * that call site in `ConferenceScreen.kt`). [setCamera]/[setMicrophone] do NOT throw on a null
 * [room] any more (round-2 review fix, race condition) -- see their own KDoc for why: since the
 * round-1 review fix inverted their `guarded {}` call sites to the NEW "null == success" convention
 * (a `ConferenceDeviceFailure?` return value, not an exception, is now the failure signal), a
 * `throw` on a null [room] would collide with that convention -- `guarded {}` catches ANY
 * `Throwable` and returns `null` for it, indistinguishable from this method's own "null == success"
 * return. [room] can legitimately go `null` between a click starting the coroutine and this check
 * running (see [disconnect]), so this was a reachable false-success bug, not a theoretical one. Both
 * methods now return [ConferenceDeviceFailure.OTHER] directly for a null [room] instead.
 *
 * **Whiteboard trust boundary** (V1.0 Videokonferenzen Wave 7 "Whiteboard", see
 * [network.lapis.cloud.shared.rpc.IConferenceWhiteboardService] KDoc): [onWhiteboardPreview]/
 * [onWhiteboardCommit] mirror [onChat]'s own trust-boundary discipline -- the AUTHOR is always the
 * SDK-verified [RemoteParticipant.identity]/`.name` of the event's own participant argument, never
 * anything client-supplied. There is nothing to accidentally trust here anyway:
 * [WhiteboardStrokeWireDto] carries no author field at all (unlike [ConferenceChatMessage], which
 * has to overwrite self-reported fields), so this is a structural guarantee, not just a discipline.
 * [sendWhiteboardPreview]/[sendWhiteboardCommit] mirror [sendChat]'s shape exactly, differing only in
 * `reliable`/`topic` -- see [WHITEBOARD_PREVIEW_TOPIC]/[WHITEBOARD_COMMIT_TOPIC] KDoc for why the
 * preview channel is deliberately UNRELIABLE (`reliable = false`) while commit is RELIABLE, same as
 * chat.
 *
 * **Security-audit fix -- payload validation is NOT optional here, unlike chat.** Chat payloads are
 * bounded by nothing more than "whatever a text message needs" and get rendered as inert text. A
 * whiteboard stroke is different: `ConferenceWhiteboardController.drawStroke` replays `points` into
 * Canvas2D calls on every animation frame and assigns `color`/`strokeWidth` straight into
 * `ctx.strokeStyle`/`ctx.lineWidth`. Because this server never observes LiveKit data-channel traffic
 * at all (see [network.lapis.cloud.shared.rpc.IConferenceWhiteboardService] KDoc "double-write"), the
 * `commitStroke` RPC's own `validateStroke` (point-count cap, canvas-bounds check, width range, color
 * allowlist) NEVER runs against anything published on [WHITEBOARD_PREVIEW_TOPIC]/
 * [WHITEBOARD_COMMIT_TOPIC] -- any current room participant already holds a LiveKit token and can
 * publish an arbitrary payload on either topic directly, bypassing the UI entirely. This handler is
 * therefore the ONLY enforcement point for that path, and applies the SAME bounds via
 * [WhiteboardStrokeWireDto.isStructurallyValid] (shared with the server's `validateStroke`
 * specifically so the two can never drift apart) before ever calling [onWhiteboardPreview]/
 * [onWhiteboardCommit] -- an oversized/out-of-bounds stroke is silently dropped, exactly like a
 * malformed [ConferenceChatMessage] JSON payload already is via [runCatching] here.
 *
 * **Notes trust boundary** (V1.0 Videokonferenzen Wave 8 "Geteilte Notizen", see
 * [network.lapis.cloud.shared.rpc.IConferenceNotesService] KDoc). [onNotesCommit]'s AUTHOR
 * parameters are always the SDK-verified [RemoteParticipant.identity]/`.name`, never anything
 * client-supplied ([NoteBlockBroadcastDto] structurally carries no author field at all, same
 * guarantee as [WhiteboardStrokeWireDto]), and [NoteBlockBroadcastDto.isStructurallyValid] is still
 * mandatory before this even decodes/forwards a payload -- this server never observes data-channel
 * traffic, so an oversized/malformed packet must be dropped here, a decoding-safety concern.
 *
 * **Security-audit fix -- a forged packet is NOT harmless here, unlike the reasoning this KDoc
 * previously stated.** An earlier version of this KDoc argued a forged `lapis-notes-commit` packet
 * "grants an attacker nothing they could not already do for real via `commitBlockEdit`" -- that was
 * wrong. Nothing binds a broadcast's `content`/`version` to an actual server-CAS-accepted commit;
 * unlike a real `commitBlockEdit` call, a forged packet never touches `ConferenceNotesState` at all,
 * so it can DEFACE a block or inject a fake one in every other open panel while the true server state
 * (and `saveAsDocument`'s export of it) stays untouched, and it can POISON another participant's
 * locally-tracked base version into rejecting their own genuinely non-stale next save. See
 * `ConferenceNotesController`'s class KDoc "Required change #3" for the fix: this class still relays
 * every structurally-valid packet verbatim (unchanged below) -- the correction lives entirely on the
 * RECEIVING side, where `ConferenceNotesController.applyCommitBroadcast` now treats the packet purely
 * as a resync trigger and never writes its payload into local state directly.
 * [sendNotesCommit] mirrors [sendChat]/[sendWhiteboardCommit]'s shape.
 *
 * **Device selection during an active call** (V1.3.x Geräteauswahl, GitHub issue #2). [listDevices]/
 * [activeDeviceId]/[switchDevice] are the ONLY new surface this wave adds -- no pre-join device
 * picker exists (deliberately out of scope, same D2 deferral this class KDoc's own opening
 * paragraph already documents for camera/microphone permissions). [onActiveDeviceChanged]/
 * [onMediaDevicesChanged]/[onMediaDevicesError] relay LiveKit's own device-lifecycle events
 * verbatim, same "this class only relays the raw signal, `ConferenceScreen.kt` owns the UI"
 * discipline as [onReconnecting]/[onReconnected] above. See [classifyDeviceFailure] for the ONE
 * place a raw JS device error becomes a [ConferenceDeviceFailure].
 */
class LiveKitRoomSession(
    private val onRemoteTrack: (identity: String, displayName: String, track: Track, publication: TrackPublication) -> Unit,
    private val onRemoteTrackGone: (identity: String, track: Track, publication: TrackPublication) -> Unit,
    private val onParticipantJoined: (identity: String, displayName: String) -> Unit,
    private val onParticipantLeft: (identity: String) -> Unit,
    private val onLocalVideoTrack: (Track?) -> Unit,
    private val onLocalTrackMuteChanged: (source: String, muted: Boolean) -> Unit,
    private val onRecordingStatusChanged: (isRecording: Boolean) -> Unit,
    private val onActiveSpeakersChanged: (identities: List<String>) -> Unit,
    /** V1.3.x Geräteauswahl -- relays [RoomEvent.ActiveDeviceChanged] verbatim, see that constant's
     * own KDoc: fires ONLY as a consequence of THIS client's own [switchDevice] call, never from a
     * raw hotplug alone. */
    private val onActiveDeviceChanged: (kind: ConferenceDeviceKind, deviceId: String) -> Unit,
    /** V1.3.x Geräteauswahl -- relays [RoomEvent.MediaDevicesChanged] verbatim: an OS-level hotplug,
     * carries no kind information, caller must re-enumerate all device kinds it cares about. */
    private val onMediaDevicesChanged: () -> Unit,
    /** V1.3.x Geräteauswahl -- relays [RoomEvent.MediaDevicesError], see that constant's own KDoc:
     * [kind] is `null` whenever the underlying event's own `kind` argument is `undefined` (any
     * source other than microphone/camera -- see `LiveKitJs.kt`'s own KDoc). Never fires for a
     * speaker/[ConferenceDeviceKind.SPEAKER] failure -- see [switchDevice] for that path instead. */
    private val onMediaDevicesError: (kind: ConferenceDeviceKind?, failure: ConferenceDeviceFailure) -> Unit,
    private val onChat: (ConferenceChatMessage) -> Unit,
    private val onWhiteboardPreview: (authorMemberId: String, authorDisplayName: String, stroke: WhiteboardStrokeWireDto) -> Unit,
    private val onWhiteboardCommit: (authorMemberId: String, authorDisplayName: String, stroke: WhiteboardStrokeWireDto) -> Unit,
    private val onNotesCommit: (authorMemberId: String, authorDisplayName: String, broadcast: NoteBlockBroadcastDto) -> Unit,
    private val onReconnecting: () -> Unit,
    private val onReconnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    /** Fired exactly once per [connect] call, immediately BEFORE the single relay-fallback retry
     * starts -- purely so `ConferenceScreen.kt` can swap its connection status line to accurate copy
     * for the second, up-to-15-second attempt. Never fired when the first attempt succeeds, and never
     * more than once (there is no third attempt). */
    private val onRelayFallback: () -> Unit = {},
    /** Test seam (audit finding "Testabdeckung" -- [attemptConnect] previously hard-constructed
     * `Room(options)`, leaving [connect]'s own retry/orphan-guard/race-window orchestration
     * unreachable from a `jsTest` without a real browser/WebRTC stack). Defaults to the real
     * constructor; `jsTest` call sites inject a fake `Room`-shaped object instead (duck-typed --
     * `Room` is an `external class`, so any JS object exposing the same method/property names
     * works identically to the real thing at the call sites this class uses). */
    private val roomFactory: (RoomOptions) -> Room = { options -> Room(options) },
) {
    private var room: Room? = null

    /**
     * True only for the (normally brief) window inside [connect]'s relay-fallback retry where
     * [room] is intentionally `null` -- see [connect] KDoc "Orphaned first Room" -- while a
     * connection attempt is still logically in progress: the failed first [Room] is being torn
     * down, or the second (`iceTransportPolicy = "relay"`) attempt is already under way. [disconnect]
     * consults this to tell that window apart from every OTHER reason [room] can be `null` (never
     * connected yet, or already cleanly disconnected), for which it must remain the pre-existing
     * silent no-op -- audit finding "Race (disconnect() silent no-op during relay-retry teardown)".
     */
    private var connectInFlight = false

    /** Set by [disconnect] when it runs while [connectInFlight] is true -- there is no live [Room]
     * for it to act on at that instant. [connect] checks this immediately after the relay retry
     * resolves and, if set, tears down whatever [Room] the retry just established (if any) instead
     * of silently leaving a call running that the user (or the server, via a `beforeunload`/
     * `pagehide` teardown) already asked to leave. */
    private var disconnectRequestedWhileConnecting = false

    /**
     * @param turnServers audit-round-1 fix -- fresh, short-lived TURN relay credentials from
     *   `ConferenceJoinTokenDto.turnServers` (see that field's own KDoc), passed straight through as
     *   `RoomOptions.rtcConfig.iceServers`, a real WebRTC `RTCConfiguration` field `livekit-client`
     *   forwards to the underlying `RTCPeerConnection` unchanged. Empty (the default) iff the server
     *   has no TURN configured -- `rtcConfig` is then left `null` entirely, matching this method's
     *   pre-fix behaviour exactly (no ICE servers beyond whatever LiveKit itself provides).
     * @return `null` on success, a [ConferenceConnectFailure] describing why the connection could not
     *   be established -- this method deliberately NEVER throws (a `CancellationException` is the ONE
     *   exception re-thrown, so coroutine cancellation still works): every internal failure, INCLUDING
     *   one that escapes the relay-fallback retry below, collapses into this return value instead. This
     *   is the same "`null` means success" convention [setCamera]/[setMicrophone] already established
     *   -- see class KDoc "[setCamera]/[setMicrophone]/[setScreenShare] no longer silently do nothing
     *   on a null [room]" for why colliding this with `guarded {}`'s own `null`-on-any-`Throwable`
     *   catch-all would be a reachable false-success bug, not a theoretical one.
     *
     * **Relay fallback** (bug report from an ELB board member, 2026-09-10: "in some browsers audio and
     * video do not start at all"): `room.connect()` internally waits up to `peerConnectionTimeout`
     * (`livekit-client` 2.21.0 default: 15 000 ms) for the WebRTC `PeerConnection` to establish, and
     * REJECTS with a `ConnectionError` carrying a numeric `.reason` if it never does (verified against
     * the compiled `livekit-client.esm.mjs`'s `waitForPCInitialConnection`/`ensurePCTransportConnection`
     * chain) -- see [ConnectionErrorReason]/[conferenceShouldRetryOverRelay] KDoc for exactly which
     * reasons this method retries. On a retryable failure, this method attempts EXACTLY ONE further
     * `connect()`, this time with `RTCConfiguration.iceTransportPolicy = "relay"` (see `LiveKitJs.kt`'s
     * own KDoc on that field), forcing every media packet through a TURN relay -- a browser whose
     * privacy settings (Brave Shields) or network blocks the direct ICE path but permits a relay can
     * often connect this way even when the unconstrained first attempt cannot. There is no third
     * attempt and no loop: a `while`/recursive retry here would risk an unbounded wait and, worse, a
     * client-side amplification effect against this server's own signalling endpoint.
     *
     * **The retry does NOT re-mint a join token or call any RPC again** (T13 in this wave's plan) --
     * it reuses the exact same [token]/[turnServers] the caller already holds. A second `joinRoom`/
     * `requestBreakoutJoinToken` call here would create a second `conference_participation` row and
     * re-run the non-member-participant-cap gate for no reason; the retry is purely a client-side
     * WebRTC transport change, nothing server-visible changes between the two attempts.
     *
     * **Orphaned first `Room` on retry** -- see [onOwned] KDoc for why every listener registered
     * through [wireEvents] must go through that guard rather than a bare [Room.on]: without it, a late
     * `RoomEvent.Disconnected` from the abandoned first-attempt `Room` (whose `disconnect()` call below
     * is fire-and-forget, its own network teardown can still take a moment) would eject the participant
     * from the relay attempt that is still in progress.
     */
    suspend fun connect(
        serverUrl: String,
        token: String,
        turnServers: List<ConferenceTurnServer> = emptyList(),
    ): ConferenceConnectFailure? {
        connectInFlight = true
        try {
            return when (val firstAttempt = attemptConnect(serverUrl, token, turnServers, forceRelay = false)) {
                is ConnectAttemptResult.Success -> null
                is ConnectAttemptResult.Failed -> {
                    val firstReason = firstAttempt.reason
                    if (!conferenceShouldRetryOverRelay(firstReason)) {
                        ConferenceConnectFailure.OTHER
                    } else {
                        // SECURITY: only the numeric reason and a static string ever reach this log
                        // line -- NEVER turnServers (carries username/credential), NEVER token,
                        // NEVER serverUrl with its query, NEVER a raw exception message (may embed
                        // SDK/browser-internal URLs).
                        kotlin.js.console.warn(
                            "LiveKit connect failed (reason=$firstReason); retrying once with " +
                                "iceTransportPolicy=relay -- see LiveKitRoomSession.connect KDoc",
                        )
                        onRelayFallback()
                        // Orphan guard: from this line on, every listener the failed first Room
                        // registered through wireEvents/onOwned becomes a no-op -- see connect KDoc
                        // "Orphaned first Room". [room] is `null` from here until the next
                        // attemptConnect call sets it -- see [connectInFlight] KDoc for why
                        // [disconnect] must not treat that window as "nothing to do".
                        val failedRoom = room
                        room = null
                        if (failedRoom != null) {
                            runCatching { failedRoom.disconnect(true).await() }
                        }
                        val relayAttempt = attemptConnect(serverUrl, token, turnServers, forceRelay = true)
                        val leaveRequestedDuringRetry = disconnectRequestedWhileConnecting
                        disconnectRequestedWhileConnecting = false
                        if (leaveRequestedDuringRetry) {
                            // [disconnect] ran while [room] was transiently `null` above and could not
                            // act on anything then -- tear down whatever the relay attempt just
                            // established (if it succeeded) instead of leaving a live call running
                            // that the user/server already ended.
                            val connectedRoom = room
                            room = null
                            if (connectedRoom != null) {
                                runCatching { connectedRoom.disconnect(true).await() }
                            }
                            ConferenceConnectFailure.OTHER
                        } else {
                            when (relayAttempt) {
                                is ConnectAttemptResult.Success -> null
                                is ConnectAttemptResult.Failed -> {
                                    val relayReason = relayAttempt.reason
                                    // Same "the caller/server already ended this attempt, that is not
                                    // a relay failure" distinction the FIRST attempt already makes via
                                    // conferenceShouldRetryOverRelay above (ConnectionErrorReason
                                    // Cancelled=3 / LeaveRequest=4) -- audit finding "relay result
                                    // pauschal auf RELAY_EXHAUSTED ohne erneute Reason-Prüfung". A
                                    // participant who hits "Verlassen" (not gated on connection state)
                                    // or a server-side LeaveRequest DURING the up-to-15s relay retry
                                    // must see the ordinary "you left" outcome, never the alarming
                                    // "could not connect even via relay" toast.
                                    if (relayReason == 3 || relayReason == 4) {
                                        ConferenceConnectFailure.OTHER
                                    } else {
                                        ConferenceConnectFailure.RELAY_EXHAUSTED
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            return ConferenceConnectFailure.OTHER
        } finally {
            connectInFlight = false
        }
    }

    /**
     * One connection attempt -- builds a fresh [Room] (via [roomFactory]), wires its events, and
     * awaits `room.connect(...)`. Returns [ConnectAttemptResult.Success] on success (leaving [room]
     * pointing at the now-connected [Room], and performing the same "late-joiner seed"
     * [onRecordingStatusChanged]/[seedRoster] calls the pre-fix [connect] always made), or
     * [ConnectAttemptResult.Failed] carrying the failed attempt's [ConnectionErrorReason] (`null` if
     * unreadable) on failure -- see [ConnectAttemptResult]'s own KDoc for why this is a dedicated
     * type rather than a plain `Int?`. NEVER throws itself except for
     * [kotlinx.coroutines.CancellationException], which propagates verbatim so coroutine cancellation
     * still works.
     *
     * [forceRelay] set `RTCConfiguration.iceTransportPolicy = "relay"` with NO `iceServers` entry
     * whenever [turnServers] is empty -- `livekit-client`'s own `makeRTCConfiguration` only ever fills
     * `iceServers` from the server's `clientConfiguration` when the caller-supplied `rtcConfig` does
     * NOT already set it (verified against the compiled bundle), so the relay fallback still works even
     * for a deployment that has not configured this codebase's own TURN minting -- it falls back to
     * whatever ICE servers LiveKit's own server-side `clientConfiguration` provides.
     */
    private suspend fun attemptConnect(
        serverUrl: String,
        token: String,
        turnServers: List<ConferenceTurnServer>,
        forceRelay: Boolean,
    ): ConnectAttemptResult {
        val options =
            obj<RoomOptions> {
                // Disabled (2026-09-12, live bug report): `adaptiveStream` pauses/resizes a
                // subscribed video track's rendering based on its <video> element's visibility/size
                // via the browser's own ResizeObserver/IntersectionObserver -- a well-known source of
                // "video goes black until the track is re-attached" independent of any app-level DOM
                // change, because it reacts to ANY geometry/visibility perturbation of the element,
                // not just ones the app itself caused. Reported live by a board member: the picture
                // goes black both when opening the "..." more-sheet (a `position: fixed` overlay that
                // renders on top of the video area, briefly perturbing its effective visibility to the
                // browser's observers) and when starting a recording (`updateStatusBadgesAndTitle`
                // shows/hides `statusBadgesPanel`, a sibling ABOVE `videoArea` in DOM order, which
                // genuinely reflows/repositions the video area beneath it) -- two otherwise unrelated
                // UI actions whose only common thread is a visibility/geometry change near the video
                // tiles, exactly what `adaptiveStream` watches for. Toggling the camera off/on "fixes"
                // it only because that forces a fresh `onLocalVideoTrack`/`track.attach()`, not because
                // it addresses a DOM bug -- an extensive investigation of `ConferenceScreen.kt`'s
                // deliberately raw-DOM video-tile handling found no `removeAll()`/re-render touching
                // `gridElement`'s subtree for either trigger, which is otherwise the leading cause of
                // exactly this symptom class in this codebase. `dynacast` (a sender-side "don't encode
                // unwatched simulcast layers" bandwidth optimization, unrelated to receiver-side
                // rendering) stays on -- no evidence ties it to this symptom, and there is no user
                // report of anything going wrong on the SENDING side. This is a best-effort mitigation
                // for a hard-to-reproduce-outside-a-live-call issue, not a confirmed root-cause fix --
                // re-open if the black-frame symptom persists after this change ships.
                adaptiveStream = false
                dynacast = true
                if (turnServers.isNotEmpty() || forceRelay) {
                    rtcConfig =
                        obj<RTCConfiguration> {
                            if (turnServers.isNotEmpty()) {
                                iceServers =
                                    turnServers
                                        .map { server ->
                                            obj<RTCIceServer> {
                                                urls = server.urls.toTypedArray()
                                                username = server.username
                                                credential = server.credential
                                            }
                                        }.toTypedArray()
                            }
                            if (forceRelay) {
                                iceTransportPolicy = "relay"
                            }
                        }
                }
            }
        val newRoom = roomFactory(options)
        wireEvents(newRoom)
        room = newRoom
        return try {
            newRoom.connect(serverUrl, token).await()
            // Security-audit fix (MINOR, "attemptConnect klassifiziert Fehler der Nach-Erfolgs-Arbeit
            // als Verbindungsfehler und verwirft eine bereits erfolgreiche Verbindung") -- the
            // late-joiner seed (see class KDoc "Recording signal") now runs in its OWN try/catch,
            // separate from the one below that classifies `newRoom.connect(...)`'s own rejection.
            // Before this fix, both calls sat inside that same try: any exception either one threw
            // (e.g. `seedRoster`'s `room.remoteParticipants.forEach` on an unexpected shape, or an
            // `onParticipantJoined`/`onRecordingStatusChanged` consumer callback throwing) was
            // indistinguishable from a genuine `connect()` rejection, fell into
            // `connectionErrorReasonOf` (which cannot read a `.reason` off a non-`ConnectionError`,
            // so always `null`), and `conferenceShouldRetryOverRelay(null)` is deliberately `true` --
            // see that function's own KDoc "a missed retry costs a still-broken call". The result was
            // an ALREADY-CONNECTED session torn down (`connect()`'s `failedRoom.disconnect(true)`) and
            // a full, up-to-15s forced-relay retry triggered for a call that never actually failed to
            // connect. A failure here must never retroactively turn a resolved `connect()` into
            // `ConnectAttemptResult.Failed` -- the connection is real either way, so a seeding failure
            // is logged and swallowed (never rethrown, `CancellationException` aside so coroutine
            // cancellation still works) rather than reported as a connect failure the caller has no
            // correct action for.
            try {
                onRecordingStatusChanged(newRoom.isRecording)
                seedRoster(newRoom)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // SECURITY: same discipline as the warning in [connect] above -- never log more than
                // a static string here, the connection is live and stays live regardless.
                kotlin.js.console.warn(
                    "LiveKit post-connect seeding failed; connection stays up -- see attemptConnect KDoc",
                )
            }
            ConnectAttemptResult.Success
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ConnectAttemptResult.Failed(connectionErrorReasonOf(e.asDynamic()))
        }
    }

    /**
     * Registers [handler] for [event] on [this] ONLY for as long as this very [Room] instance is still
     * the session's [room]. Introduced with the relay-fallback retry (see [connect] KDoc "Orphaned
     * first Room"): a failed first `connect()` leaves an ORPHANED [Room] whose listeners are
     * unreachable for [Room.off] (no retained references, all registered as inline lambdas), and whose
     * late `RoomEvent.Disconnected` would otherwise drive `ConferenceScreen.kt`'s D10 connection-state
     * machine into `Resolving` and eject the participant from a relay attempt that is still in
     * progress. The identity check (`!==`, not `!=` -- these are `external interface`/`external class`
     * instances with no meaningful structural equality) makes every orphaned listener a no-op instead
     * of a functional no-op AND avoids ever disabling a listener on the CURRENTLY active [room].
     */
    private fun Room.onOwned(
        event: String,
        handler: (dynamic, dynamic, dynamic, dynamic) -> Unit,
    ) {
        val self = this
        on(event) { p0, p1, p2, p3 ->
            if (self !== this@LiveKitRoomSession.room) return@on
            handler(p0, p1, p2, p3)
        }
    }

    private fun wireEvents(room: Room) {
        room.onOwned(RoomEvent.ParticipantConnected) { p0, _, _, _ ->
            val participant = p0.unsafeCast<RemoteParticipant>()
            onParticipantJoined(participant.identity, participant.name ?: participant.identity)
        }
        room.onOwned(RoomEvent.ParticipantDisconnected) { p0, _, _, _ ->
            val participant = p0.unsafeCast<RemoteParticipant>()
            onParticipantLeft(participant.identity)
        }
        room.onOwned(RoomEvent.TrackSubscribed) { p0, p1, p2, _ ->
            val track = p0.unsafeCast<Track>()
            val publication = p1.unsafeCast<TrackPublication>()
            val participant = p2.unsafeCast<RemoteParticipant>()
            onRemoteTrack(participant.identity, participant.name ?: participant.identity, track, publication)
        }
        room.onOwned(RoomEvent.TrackUnsubscribed) { p0, p1, p2, _ ->
            val track = p0.unsafeCast<Track>()
            val publication = p1.unsafeCast<TrackPublication>()
            val participant = p2.unsafeCast<RemoteParticipant>()
            onRemoteTrackGone(participant.identity, track, publication)
        }
        room.onOwned(RoomEvent.TrackMuted) { p0, p1, _, _ ->
            val publication = p0.unsafeCast<TrackPublication>()
            // p1's static shape doesn't matter -- LocalParticipant/RemoteParticipant both carry
            // `identity` at runtime, see this class's own KDoc "Local mute state is event-driven".
            val identity = p1.unsafeCast<RemoteParticipant>().identity
            if (identity == room.localParticipant.identity) onLocalTrackMuteChanged(publication.source, true)
        }
        room.onOwned(RoomEvent.TrackUnmuted) { p0, p1, _, _ ->
            val publication = p0.unsafeCast<TrackPublication>()
            val identity = p1.unsafeCast<RemoteParticipant>().identity
            if (identity == room.localParticipant.identity) onLocalTrackMuteChanged(publication.source, false)
        }
        room.onOwned(RoomEvent.Disconnected) { _, _, _, _ -> onDisconnected() }
        room.onOwned(RoomEvent.Reconnecting) { _, _, _, _ -> onReconnecting() }
        room.onOwned(RoomEvent.Reconnected) { _, _, _, _ -> onReconnected() }
        room.onOwned(RoomEvent.RecordingStatusChanged) { p0, _, _, _ ->
            // p0 is a raw JS boolean primitive here (LiveKit calls the listener with exactly one
            // argument), not one of this file's own `external interface` types -- see
            // `LiveKitJs.kt` file KDoc "Values returned from a Promise<dynamic>..." for why
            // `unsafeCast` (not `as`/`as?`) is this codebase's uniform cast discipline regardless.
            onRecordingStatusChanged(p0.unsafeCast<Boolean>())
        }
        room.onOwned(RoomEvent.ActiveSpeakersChanged) { p0, _, _, _ ->
            // p0 is a raw JS array here (LiveKit calls the listener with exactly one argument, an
            // Array<Participant>) -- unsafeCast to Array<dynamic> first (no RTTI for the element
            // type either), then unsafeCast each element to this file's own ActiveSpeaker shape,
            // same two-step discipline `LiveKitJs.kt`'s file KDoc documents for every other
            // `external interface` value pulled out of a `dynamic` here.
            val speakers = p0.unsafeCast<Array<dynamic>>()
            onActiveSpeakersChanged(speakers.map { it.unsafeCast<ActiveSpeaker>().identity })
        }
        // V1.3.x Geräteauswahl -- fires `(kind: string, deviceId: string)`, see RoomEvent.ActiveDeviceChanged KDoc.
        // An unrecognized `kind` string (should not happen -- only the three literals this file's own
        // switchActiveDevice call sites ever pass can come back here) is silently ignored rather than
        // crashing, same "unlisted event shape never throws" discipline as conferenceConnectionReduce.
        room.onOwned(RoomEvent.ActiveDeviceChanged) { p0, p1, _, _ ->
            val kind = ConferenceDeviceKind.entries.firstOrNull { it.jsKind == p0.unsafeCast<String>() } ?: return@onOwned
            onActiveDeviceChanged(kind, p1.unsafeCast<String>())
        }
        // V1.3.x Geräteauswahl -- fires with zero JS arguments, see RoomEvent.MediaDevicesChanged KDoc.
        room.onOwned(RoomEvent.MediaDevicesChanged) { _, _, _, _ -> onMediaDevicesChanged() }
        // V1.3.x Geräteauswahl -- fires `(error, kind: string | undefined)`, see RoomEvent.MediaDevicesError
        // KDoc. `kind` is `undefined` (Kotlin `null`) for any source that isn't microphone/camera.
        room.onOwned(RoomEvent.MediaDevicesError) { p0, p1, _, _ ->
            // p1 is `undefined` (unsafeCast to nullable, same "undefined-as-null" idiom as the
            // DataReceived handler's own `p0.unsafeCast<Uint8Array?>()` below) for any source other
            // than microphone/camera -- see RoomEvent.MediaDevicesError KDoc.
            val rawKind = p1.unsafeCast<String?>()
            val kind = rawKind?.let { s -> ConferenceDeviceKind.entries.firstOrNull { it.jsKind == s } }
            onMediaDevicesError(kind, classifyDeviceFailure(p0))
        }
        room.onOwned(RoomEvent.DataReceived) { p0, p1, _, p3 ->
            val payload = p0.unsafeCast<org.khronos.webgl.Uint8Array?>() ?: return@onOwned
            val participant = p1.unsafeCast<RemoteParticipant?>() ?: return@onOwned
            val topic = p3
            when (topic) {
                CHAT_TOPIC ->
                    runCatching {
                        val json = TextDecoder().decode(payload)
                        val raw = Json.decodeFromString(ConferenceChatMessage.serializer(), json)
                        // Trust boundary, see class KDoc "Chat trust boundary" -- overwrite the
                        // self-reported sender fields with the SDK-verified participant identity/name.
                        onChat(
                            raw.copy(
                                senderMemberId = participant.identity,
                                senderDisplayName = participant.name ?: participant.identity,
                            ),
                        )
                    }
                WHITEBOARD_PREVIEW_TOPIC ->
                    runCatching {
                        val json = TextDecoder().decode(payload)
                        val stroke = Json.decodeFromString(WhiteboardStrokeWireDto.serializer(), json)
                        // See class KDoc "Security-audit fix" -- the ONLY enforcement point for this
                        // transport; a decoded-but-out-of-bounds stroke is silently dropped, never
                        // forwarded.
                        if (!stroke.isStructurallyValid()) return@onOwned
                        onWhiteboardPreview(participant.identity, participant.name ?: participant.identity, stroke)
                    }
                WHITEBOARD_COMMIT_TOPIC ->
                    runCatching {
                        val json = TextDecoder().decode(payload)
                        val stroke = Json.decodeFromString(WhiteboardStrokeWireDto.serializer(), json)
                        // See class KDoc "Security-audit fix".
                        if (!stroke.isStructurallyValid()) return@onOwned
                        onWhiteboardCommit(participant.identity, participant.name ?: participant.identity, stroke)
                    }
                NOTES_COMMIT_TOPIC ->
                    runCatching {
                        val json = TextDecoder().decode(payload)
                        val broadcast = Json.decodeFromString(NoteBlockBroadcastDto.serializer(), json)
                        // Same enforcement-point reasoning as the whiteboard topics above -- see class
                        // KDoc "Notes trust boundary".
                        if (!broadcast.isStructurallyValid()) return@onOwned
                        onNotesCommit(participant.identity, participant.name ?: participant.identity, broadcast)
                    }
                else -> return@onOwned
            }
        }
    }

    /** See class KDoc "Roster-seeding gotcha". */
    private fun seedRoster(room: Room) {
        val forEachCallback: (dynamic, dynamic) -> Unit = { value, _ ->
            val participant = value.unsafeCast<RemoteParticipant>()
            onParticipantJoined(participant.identity, participant.name ?: participant.identity)
        }
        room.remoteParticipants.forEach(forEachCallback)
    }

    /**
     * See class KDoc "Local self-view" and "[setCamera]/[setMicrophone]/[setScreenShare] now throw
     * on a null [room]".
     *
     * **Review fix (GitHub Issue #2 review, "dupliziertes + falsch formuliertes Fehler-Toast")** --
     * returns a [ConferenceDeviceFailure] instead of letting `setCameraEnabled`'s rejection
     * propagate, same "classify the failure locally, never let the caller's generic `guarded {}`
     * show an untranslated raw browser error" discipline [switchDevice] already established. The
     * underlying `RoomEvent.MediaDevicesError` this failure ALSO triggers (LiveKit's
     * `setTrackEnabled` emits it SYNCHRONOUSLY, before this `catch` ever runs -- verified against
     * the vendored `livekit-client.esm.mjs`, `setTrackEnabled` is the SOLE trigger of that event for
     * `microphone`/`camera`, `Room.switchActiveDevice` never routes through it) is deliberately left
     * un-toasted by `ConferenceScreen.kt`'s `onMediaDevicesError` handler now -- this return value is
     * the caller's sole, accurately-worded ("activation" failed, never "switch" failed, since this
     * method is never reached from a dropdown device switch) signal, both for the initial join-time
     * enable and every mic/camera button click.
     */
    suspend fun setCamera(enabled: Boolean): ConferenceDeviceFailure? {
        // Race-condition fix (review, GitHub Issue #2 review round 2): a null `room` is now returned
        // as an ordinary `ConferenceDeviceFailure.OTHER`, NOT thrown. Throwing here would escape this
        // method's own try/catch and be caught only by the call site's outer `guarded {}`
        // (`AppState.kt`), which returns `null` for EVERY caught `Throwable` -- the exact same `null`
        // this method's own contract uses to mean SUCCESS since the round-1 review fix (see class
        // KDoc "[setCamera]/[setMicrophone]/[setScreenShare] now throw on a null [room]"). `room` can
        // legitimately turn `null` between a button click starting this coroutine and this line
        // running, because `disconnect()` (see below) sets it AFTER awaiting -- e.g. a moderator ends
        // the call, or the participant clicks "leave" right after clicking the mic/camera button. The
        // old "throw unconditionally" shape made that race read as an accurate failure (any exception
        // meant "did not happen"); the round-1 fix inverted the meaning of `guarded {}`'s `null` for
        // the NORMAL error path but left THIS one exceptional throw still routed through the same
        // `guarded {}`, so it now collides with the new "null == success" convention and the
        // mic/camera button handlers in `ConferenceScreen.kt` flip to a false "on" state for a device
        // that was never touched. Returning a typed failure directly keeps this method's own
        // null-means-success contract true regardless of WHICH failure path is taken, and gives the
        // caller the same accurate, translated toast (`conferenceDeviceEnableErrorMessage`) a real
        // device error would.
        val currentRoom = room ?: return ConferenceDeviceFailure.OTHER
        return try {
            val result = currentRoom.localParticipant.setCameraEnabled(enabled).await()
            val rawTrack = if (enabled) result?.track else null
            onLocalVideoTrack(if (rawTrack != null) rawTrack.unsafeCast<Track>() else null)
            null
        } catch (e: Throwable) {
            classifyDeviceFailure(e.asDynamic())
        }
    }

    /** See class KDoc "[setCamera]/[setMicrophone]/[setScreenShare] now throw on a null [room]" and
     * [setCamera]'s own KDoc for the review fix this mirrors, including the round-2 race-condition
     * fix ("a null `room` is now returned...") -- identical reasoning applies here verbatim. */
    suspend fun setMicrophone(enabled: Boolean): ConferenceDeviceFailure? {
        val currentRoom = room ?: return ConferenceDeviceFailure.OTHER
        return try {
            currentRoom.localParticipant.setMicrophoneEnabled(enabled).await()
            null
        } catch (e: Throwable) {
            classifyDeviceFailure(e.asDynamic())
        }
    }

    /** See class KDoc "[setCamera]/[setMicrophone]/[setScreenShare] now throw on a null [room]". */
    suspend fun setScreenShare(enabled: Boolean) {
        val currentRoom = room ?: throw IllegalStateException("setScreenShare called with no active room")
        currentRoom.localParticipant.setScreenShareEnabled(enabled).await()
    }

    /**
     * V1.3.x Geräteauswahl -- enumerates every currently available [kind] device. Same "throws on a
     * null [room]" discipline as [setCamera]/[setMicrophone]/[setScreenShare] -- without an active
     * room, a device list serves no purpose in this class.
     *
     * [Room.getLocalDevices] is called with `requestPermissions = false` on every invocation --
     * SECURITY: by the time this is ever called (see `ConferenceScreen.kt`'s own call site, right
     * after the initial `setMicrophone(true)`/`setCamera(true)`), the one browser permission prompt
     * this screen needs has already resolved (or been denied). A `true` here would trigger a SECOND,
     * confusing permission prompt for no reason.
     *
     * [MediaDeviceInfo.kind] is read via [kotlin.js.unsafeCast], never `==`/`.toString()` against a
     * `String` -- see `LiveKitJs.kt`'s file KDoc "device-kind strings, never MediaDeviceKind" for
     * why: whatever Kotlin/JS's `org.w3c.dom.mediacapture.MediaDeviceKind` binding does internally,
     * `unsafeCast` reads the raw runtime value the browser itself produced (spec-guaranteed to be
     * exactly [kind]'s own [ConferenceDeviceKind.jsKind] literal), sidestepping that question
     * entirely.
     */
    suspend fun listDevices(kind: ConferenceDeviceKind): List<ConferenceDeviceOption> {
        val currentRoom = room ?: throw IllegalStateException("listDevices called with no active room")
        // Room.getLocalDevices is a static (companion object) member -- independent of currentRoom --
        // but the guard above stays: enumerating devices outside an active call serves no purpose for
        // this class, and mirrors setCamera/setMicrophone/setScreenShare's own discipline.
        val raw = Room.getLocalDevices(kind.jsKind, false).await()
        return raw
            .filter { it.kind.unsafeCast<String>() == kind.jsKind }
            .map { ConferenceDeviceOption(it.deviceId, it.label) }
    }

    /** V1.3.x Geräteauswahl -- pure synchronous read, `null` if [room] is absent or LiveKit does not
     * yet know an active device for [kind] (e.g. before the first publish attempt has resolved). */
    fun activeDeviceId(kind: ConferenceDeviceKind): String? = room?.getActiveDevice(kind.jsKind)

    /**
     * V1.3.x Geräteauswahl -- switches the currently active [kind] device to [deviceId]. Returns
     * `null` on success, a [ConferenceDeviceFailure] on failure -- deliberately NOT a `Boolean`, so
     * the caller (`ConferenceScreen.kt`'s `select.subscribe {}` handler) can show a kind-specific
     * German error message without a second round of failure classification.
     *
     * Two independent failure shapes exist, both handled here: (a) [Room.switchActiveDevice]'s
     * Promise REJECTS -- verified against the compiled `livekit-client.esm.mjs`, a mismatched
     * `exact`-constrained device (or a genuine `getUserMedia` failure such as permission revoked
     * mid-call) makes the underlying device-apply call throw, and `switchActiveDevice` re-throws it
     * verbatim rather than swallowing it into a `false` resolution; (b) the Promise resolves but with
     * `false` -- kept as an explicit branch for robustness even though no currently-known
     * `livekit-client` 2.21.0 code path produces it for an `exact = true` call (this method's own
     * default), mapped to [ConferenceDeviceFailure.OTHER] since there is no error object to classify
     * in that case.
     *
     * [exact] is always `true` (the JS-side default) -- deliberately not the "best-effort,
     * fall back to a different device silently" mode: a participant who explicitly picked a device
     * from the dropdown must either get exactly that device or a visible failure, never a silent
     * substitution.
     */
    suspend fun switchDevice(
        kind: ConferenceDeviceKind,
        deviceId: String,
    ): ConferenceDeviceFailure? {
        val currentRoom = room ?: throw IllegalStateException("switchDevice called with no active room")
        return try {
            val ok = currentRoom.switchActiveDevice(kind.jsKind, deviceId, true).await()
            if (ok) null else ConferenceDeviceFailure.OTHER
        } catch (e: Throwable) {
            classifyDeviceFailure(e.asDynamic())
        }
    }

    /** V1.3.x Geräteauswahl -- the ONE place `MediaDeviceFailure.getFailure(...)`'s four string
     * constants get mapped to [ConferenceDeviceFailure]. `getFailure` returning `null`/an
     * unrecognized value (a JS `Error` this classifier itself does not know how to categorize) falls
     * back to [ConferenceDeviceFailure.OTHER], never propagated as an exception -- this function is
     * called from [switchDevice]'s `catch` block, [setCamera]'s/[setMicrophone]'s `catch` blocks
     * (review fix, see their own KDoc), and the [RoomEvent.MediaDevicesError] handler in
     * [wireEvents], none of which has anywhere further to throw to. */
    private fun classifyDeviceFailure(error: dynamic): ConferenceDeviceFailure =
        when (MediaDeviceFailure.getFailure(error)) {
            MediaDeviceFailure.PermissionDenied -> ConferenceDeviceFailure.PERMISSION_DENIED
            MediaDeviceFailure.NotFound -> ConferenceDeviceFailure.NOT_FOUND
            MediaDeviceFailure.DeviceInUse -> ConferenceDeviceFailure.DEVICE_IN_USE
            else -> ConferenceDeviceFailure.OTHER
        }

    suspend fun sendChat(message: ConferenceChatMessage) {
        val currentRoom = room ?: return
        val json = Json.encodeToString(ConferenceChatMessage.serializer(), message)
        val bytes = TextEncoder().encode(json)
        // House convention for building a small JS-object argument (AuthHttp.kt's own `obj<T> {}`
        // usage) -- deliberately not `js("{...}")`, which needs a compile-time string literal and
        // cannot safely reference the [CHAT_TOPIC] constant.
        val options =
            obj<PublishDataOptions> {
                reliable = true
                topic = CHAT_TOPIC
            }
        currentRoom.localParticipant.publishData(bytes, options).await()
    }

    /**
     * V1.0 Wave 7 "Whiteboard" -- UNRELIABLE/lossy/unordered publish (`reliable = false`), the
     * real `livekit-client` SDK's own supported mode this codebase had simply never exercised
     * before this wave (research finding: [PublishDataOptions] already supported it). Correct here
     * because [stroke] is always the CUMULATIVE point list so far (see [WhiteboardStrokeWireDto]
     * KDoc) -- a lost or reordered preview packet self-heals on the very next one, no gap-tracking
     * needed. Fire-and-forget from the caller's perspective; a failure here only means one preview
     * frame did not reach peers, never a durability loss (the RPC `commitStroke` call is what makes
     * a stroke durable, see [network.lapis.cloud.shared.rpc.IConferenceWhiteboardService.commitStroke]
     * KDoc "double-write").
     */
    suspend fun sendWhiteboardPreview(stroke: WhiteboardStrokeWireDto) {
        val currentRoom = room ?: return
        val json = Json.encodeToString(WhiteboardStrokeWireDto.serializer(), stroke)
        val bytes = TextEncoder().encode(json)
        val options =
            obj<PublishDataOptions> {
                reliable = false
                topic = WHITEBOARD_PREVIEW_TOPIC
            }
        currentRoom.localParticipant.publishData(bytes, options).await()
    }

    /**
     * V1.0 Wave 7 "Whiteboard" -- RELIABLE publish, same `reliable = true` posture as [sendChat]:
     * a finished stroke must not silently vanish for other currently-connected participants. See
     * [network.lapis.cloud.shared.rpc.IConferenceWhiteboardService.commitStroke] KDoc
     * "double-write" for why the caller is expected to ALSO call the `commitStroke` RPC alongside
     * this broadcast -- the two are independent and both required.
     */
    suspend fun sendWhiteboardCommit(stroke: WhiteboardStrokeWireDto) {
        val currentRoom = room ?: return
        val json = Json.encodeToString(WhiteboardStrokeWireDto.serializer(), stroke)
        val bytes = TextEncoder().encode(json)
        val options =
            obj<PublishDataOptions> {
                reliable = true
                topic = WHITEBOARD_COMMIT_TOPIC
            }
        currentRoom.localParticipant.publishData(bytes, options).await()
    }

    /**
     * V1.0 Wave 8 "Geteilte Notizen" -- RELIABLE publish, same posture as [sendWhiteboardCommit]: a
     * successfully committed block edit must not silently vanish for other currently-connected
     * participants. Unlike whiteboard there is no unreliable preview sibling -- see
     * [network.lapis.cloud.shared.rpc.IConferenceNotesService] KDoc "Real-time propagation" for why
     * a block commit is a low-frequency, explicit-action event that does not need one.
     */
    suspend fun sendNotesCommit(broadcast: NoteBlockBroadcastDto) {
        val currentRoom = room ?: return
        val json = Json.encodeToString(NoteBlockBroadcastDto.serializer(), broadcast)
        val bytes = TextEncoder().encode(json)
        val options =
            obj<PublishDataOptions> {
                reliable = true
                topic = NOTES_COMMIT_TOPIC
            }
        currentRoom.localParticipant.publishData(bytes, options).await()
    }

    /**
     * Audit finding "Race (disconnect() silent no-op during relay-retry teardown)": before this fix,
     * [room] was the ONLY signal this method consulted, so a call landing in the brief window
     * [connect]'s relay-fallback retry holds it `null` (see [connectInFlight] KDoc) silently did
     * nothing -- a connection the caller believed they had just left could go on to establish itself
     * over the forced-relay retry regardless. This method now leaves [disconnectRequestedWhileConnecting]
     * for [connect] to notice for that specific window; every other reason [room] can be `null`
     * (never connected yet, or already cleanly disconnected) is still the pre-existing, correct
     * silent no-op.
     */
    suspend fun disconnect() {
        val currentRoom = room
        if (currentRoom != null) {
            // Deliberately awaited BEFORE nulling [room] (unchanged from the pre-fix ordering): the
            // RoomEvent.Disconnected this call itself provokes must still pass [onOwned]'s identity
            // guard (`self === room`) while it fires during this very await, so `onDisconnected()`
            // still reaches `ConferenceScreen.kt`'s connection-state machine for a caller-initiated
            // leave, exactly as before this fix.
            currentRoom.disconnect().await()
            room = null
            return
        }
        if (connectInFlight) {
            disconnectRequestedWhileConnecting = true
        }
    }

    companion object {
        /** Matches [ConferenceChatMessage] KDoc's "topic `lapis-chat`". */
        const val CHAT_TOPIC = "lapis-chat"

        /** V1.0 Wave 7 "Whiteboard" -- in-progress stroke preview, UNRELIABLE (loss is fine, latest-wins), see [sendWhiteboardPreview] KDoc. */
        const val WHITEBOARD_PREVIEW_TOPIC = "lapis-whiteboard-preview"

        /** V1.0 Wave 7 "Whiteboard" -- finished-stroke broadcast, RELIABLE, see [sendWhiteboardCommit] KDoc. */
        const val WHITEBOARD_COMMIT_TOPIC = "lapis-whiteboard-commit"

        /** V1.0 Wave 8 "Geteilte Notizen" -- committed block-edit broadcast, RELIABLE, see [sendNotesCommit] KDoc. */
        const val NOTES_COMMIT_TOPIC = "lapis-notes-commit"
    }
}
