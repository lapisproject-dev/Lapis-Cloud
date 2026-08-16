package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.ConferenceStreamAvailabilityDto
import network.lapis.cloud.shared.domain.ConferenceStreamDestinationDto
import network.lapis.cloud.shared.domain.ConferenceStreamDto
import network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode
import network.lapis.cloud.shared.domain.ConferenceStreamLayout
import network.lapis.cloud.shared.domain.ConferenceStreamPlatform
import network.lapis.cloud.shared.domain.ConferenceStreamTargetDto

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 3 "Externes Streaming" -- RTMP-composited-egress
 * streaming to external platforms (YouTube/Twitch/PeerTube/Owncast/generic RTMP), layered on top of
 * Wave 1's [IConferenceService] and independent of Wave 2's [IConferenceRecordingService]. See the
 * concept document ("03 Bereiche/Lapis Cloud/Videokonferenzen.md", vault, the 2026-08-01
 * architecture decision) for the full fachlich model, `29-conference-streaming.kuml.kts` file
 * header for the persistence model, and
 * [network.lapis.cloud.server.conference.HttpLiveKitEgressClient]/
 * [network.lapis.cloud.server.conference.ConferenceStreamingConfig]/
 * [network.lapis.cloud.server.crypto.SecretBox] for the LiveKit-side integration and credential
 * encryption this interface's implementation coordinates.
 *
 * ## A SEPARATE service from [IConferenceService] AND [IConferenceRecordingService], deliberately
 *
 * This follows the exact precedent [IConferenceRecordingService]'s own KDoc sets out for itself
 * relative to [IConferenceService], and the case is stronger here, on THREE independent axes:
 *
 * 1. **A third, genuinely different authorization shape.** [IConferenceService] gates on "ACTIVE
 *    local member" (+ creator-or-BOARD/ADMIN for two methods); [IConferenceRecordingService] adds
 *    `DocumentAccessLevel`. This wave adds **ADMIN-only credential CRUD**
 *    ([listDestinations]/[createDestination]/[updateDestination]/[setDestinationEnabled]/
 *    [deleteDestination]) -- a predicate NEITHER existing service has any business learning. Folding
 *    ADMIN-only credential writes into a service whose other methods are member-readable is exactly
 *    the kind of mixed authorization surface that produces a privilege-escalation bug; see "Why
 *    ADMIN-only for destination CRUD but moderator-or-BOARD for start/stop" below for the full
 *    reasoning.
 * 2. **A third independent availability gate.** Streaming needs [IConferenceService.getAvailability]'s
 *    `ConferenceConfig.enabled` PLUS `ConferenceStreamingConfig.enabled` PLUS a valid
 *    `LAPIS_SECRET_ENCRYPTION_KEY` ([network.lapis.cloud.server.crypto.SecretBox]). It does **NOT**
 *    need `ffmpeg` ([IConferenceRecordingService]'s own second gate -- there is no local
 *    composition here, LiveKit's Egress container does 100% of the encoding) and does NOT need
 *    `ConferenceRecordingConfig.enabled`. A deployment can legitimately stream without being able to
 *    record, and vice versa -- see [getStreamingAvailability].
 * 3. **Different collaborators.** [network.lapis.cloud.server.crypto.SecretBox],
 *    `ConferenceStreamingConfig`, `StreamPoller` (a later wave step), and the three new tables
 *    (`conference_stream_destination`/`conference_stream`/`conference_stream_target`) are useful to
 *    NEITHER [network.lapis.cloud.server.rpc.ConferenceService] NOR
 *    `network.lapis.cloud.server.rpc.ConferenceRecordingService` -- folding them in would widen a
 *    live-verified constructor for zero benefit.
 *
 * ## Why ADMIN-only for destination CRUD but moderator-or-BOARD for start/stop
 *
 * Saving a stream key is not "operating a meeting" -- it is binding the organization's EXTERNAL
 * PUBLISHING IDENTITY (its official YouTube/PeerTube channel credentials) into the system, an
 * org-wide settings act. This matches the ADMIN-only posture `OrganizationSettingsService`/
 * `AuctionService.enableAuction` already establish for org-wide switches, NOT the room-scoped
 * moderator posture [IConferenceService.endRoom] establishes. Starting a stream to an
 * ALREADY-APPROVED destination, by contrast, IS a meeting-operational act -- exactly
 * [IConferenceRecordingService.startRecording]'s own authorization shape. Splitting the two means a
 * BOARD moderator can run the Mitgliederversammlung's stream without ever being able to read,
 * change, or exfiltrate the underlying credential, and without an ADMIN having to be personally
 * present in every meeting that streams.
 *
 * ## `startStream` DOES call LiveKit synchronously -- the ONE deliberate divergence from
 * [IConferenceRecordingService.startRecording]
 *
 * [IConferenceRecordingService.startRecording] only inserts a row; a later wave's `RecordingPoller`
 * is the ONLY thing that ever calls LiveKit for recording. [startStream] does NOT follow that
 * pattern -- it calls LiveKit SYNCHRONOUSLY, in the same request. This is deliberate, not an
 * inconsistency: there is exactly ONE egress per stream (never N-per-track the way recording has),
 * known up front, and a moderator needs IMMEDIATE feedback on the obvious failures (LiveKit
 * unreachable, malformed request) rather than discovering ten seconds later that nothing actually
 * started. Ordering is strict and verified against the live container's OWN async behaviour (a
 * `StartParticipantEgress` to a completely unresolvable host returned `EGRESS_STARTING`
 * SYNCHRONOUSLY, with the real failure only surfacing ~12 seconds later via `ListEgress` --
 * `network.lapis.cloud.server.conference.HttpLiveKitEgressClient` KDoc "Multi-destination partial
 * failure"): the implementation's first transaction inserts the row [network.lapis.cloud.shared.domain.ConferenceStreamStatus.STARTING];
 * OUTSIDE that transaction, it calls LiveKit; a SECOND transaction then stores the resulting
 * `livekit_egress_id` and flips the row to [network.lapis.cloud.shared.domain.ConferenceStreamStatus.LIVE],
 * or to [network.lapis.cloud.shared.domain.ConferenceStreamStatus.FAILED] with a sanitized reason.
 * NEVER a network call inside an open transaction. A crash between the two transactions is
 * reconciled by `StreamPoller` (a later wave step), which cross-checks `ListEgress` before failing
 * an orphan `STARTING` row so a genuinely-started egress is ADOPTED rather than leaked.
 *
 * ## `pauseStream`/`resumeStream` -- honest stop+restart, because LiveKit has NO pause primitive
 *
 * Verified live (2026-08-09): a `POST .../UpdateStream` removing every `output_url` from an ACTIVE
 * egress drives it to `EGRESS_ENDING` -> `egress_complete`, `"End reason: All streams stopped"` --
 * there is no `PauseEgress` Twirp method and nothing in the Twirp surface suspends output while
 * holding the media pipeline open. [pauseStream] is therefore `StopEgress` +
 * [network.lapis.cloud.shared.domain.ConferenceStreamStatus.PAUSED] -- the MEETING continues
 * untouched, only the RTMP output stops. [resumeStream] is a FRESH `Start...Egress` call to the SAME
 * destinations, writing a NEW `livekit_egress_id` onto the SAME `conference_stream` row and
 * incrementing `restart_count`. Neither method pretends this is seamless to the viewing platform --
 * see the client spec's mandatory pause-dialog copy ("YouTube kann die Übertragung dabei beenden").
 *
 * ## Out of scope this wave (deliberate, not gaps -- see the Wave 3 plan's own "Explicitly out of
 * scope" section for the full, authoritative list)
 *
 * - **No automatic stream pause during secret ballots.** The concept note's hard-wired,
 *   non-UI-disableable lock CANNOT be built this wave: the conference domain has NO coupling to
 *   `IElectionService`/`IGovernanceService`/`ISystemicConsensusService` in either direction, and
 *   building it needs a real cross-module "a secret ballot is open in this room" signal, which is a
 *   wave of its own. **A half-built version (e.g. a manual "Geheime Wahl beginnt"-Button) would be
 *   WORSE than nothing** -- it would look like the promised safeguard while providing none. The
 *   client's start-stream dialog instead carries a mandatory, static Hinweis that this protection
 *   does not exist yet and pausing during a secret ballot is entirely manual -- see the client spec.
 * - **No Restream/StreamYard integration.** [ConferenceStreamPlatform.GENERIC_RTMP][network.lapis.cloud.shared.domain.ConferenceStreamPlatform.GENERIC_RTMP]
 *   fully covers the manual case (paste their ingest URL/key like any other destination); a
 *   dedicated integration would mean their API, account linking, and token lifecycle, and directly
 *   contradicts the already-made 2026-08-01 Self-Hosted-Egress decision (no managed relay).
 * - **No YouTube Data API auto-create-live-event hook.** Manual stream-key entry only -- an
 *   auto-create flow needs OAuth against a Google account, refresh-token storage, and quota
 *   handling, an entire integration wave of its own.
 * - **No simulcast/quality-ladder tuning** beyond [ConferenceStreamLatencyMode]'s two fixed
 *   profiles. No adaptive bitrate, no per-destination resolution, no bandwidth probing.
 * - **No automatic backup-recording triggered by a stream drop.** [IConferenceRecordingService.startRecording]
 *   already exists as the manual mitigation and can be started alongside a stream today; whether a
 *   drop should AUTOMATICALLY start/keep a recording is a policy question with real DSGVO weight
 *   (an unconsented automatic recording is exactly the "versteckte Aufnahme" the concept note
 *   forbids) and belongs to a later wave, deliberately.
 * - **No mid-stream destination add/remove.** `UpdateStream`'s `add_output_urls`/`remove_output_urls`
 *   ARE implemented and verified live (adding a URL to a live egress works, the sink logged the new
 *   publish ~21s later) -- but Wave 3 uses this internally NEVER for the moderator-facing surface.
 *   The destination set is fixed at [startStream] time; "unterbrechen -> anders starten" covers the
 *   rare need to change it. No client control anywhere may imply this is possible.
 * - **No self-hosted Room-Composite template.** [ConferenceStreamLayout.GRID]/[ConferenceStreamLayout.SPEAKER][network.lapis.cloud.shared.domain.ConferenceStreamLayout]
 *   render through a Chrome-loaded web page LiveKit's egress container fetches from
 *   `https://template.livekit.io` by default -- this wave documents a `template_base` knob and the
 *   resulting `error_code 412` failure signature but does not build a Lapis-owned template (a
 *   genuine tension with the "Datenverarbeitung ist server-lokal" doctrine, flagged separately).
 * - Also carried forward, restated so nobody assumes streaming brought them along: no SRT output
 *   (RTMP/RTMPS only), no segmented/HLS output from LiveKit itself, no donation CTA/embedded public
 *   viewer page, no per-participant stream opt-out beyond camera-off/pseudonym, no platform stream
 *   chat relay, no federated-guest streaming permissions beyond the host server's own rules, no
 *   "Termin -> Konferenzraum" integration, no chapter markers, no multi-room/scheduled stream start.
 */
@RpcService
interface IConferenceStreamingService {
    // ── Availability ────────────────────────────────────────────────────────────────────────

    /**
     * Any authenticated member. Never throws for an unconfigured deployment -- mirrors
     * [IConferenceService.getAvailability]/[IConferenceRecordingService.getRecordingAvailability]'s
     * own "one place to check" contract, see class KDoc "a third independent availability gate".
     */
    suspend fun getStreamingAvailability(): ConferenceStreamAvailabilityDto

    // ── Destination configuration -- ADMIN ONLY (credential material) ─────────────────────────

    /**
     * Role: ADMIN. Masked representation only -- NEVER the stream key, see
     * [ConferenceStreamDestinationDto] KDoc and class KDoc "credential storage model".
     */
    suspend fun listDestinations(): List<ConferenceStreamDestinationDto>

    /**
     * Role: ADMIN. [streamKey] is WRITE-ONLY: accepted here, immediately encrypted via
     * [network.lapis.cloud.server.crypto.SecretBox] with the destination's own id as GCM AAD, and
     * NEVER readable again through any method on this interface. [label] must be unique (else
     * [ConflictException]) and [rtmpUrl]'s scheme must be `rtmp://`/`rtmps://` with a parseable host
     * (else a validation error) -- deliberately NOT run through this codebase's FULL, DNS-resolving
     * SSRF private-range/loopback blocklist (the one `FederationHttpClient.requireSafeFederationUrl`
     * uses), since [rtmpUrl] is ADMIN-supplied OPERATOR configuration (e.g. a legitimate on-prem
     * Owncast at `rtmp://owncast.internal:1935/live`, or `rtmp://192.168.1.50/live`), not
     * externally-influenced user input; see the Wave 3 scope-decisions doc for the full rationale. A
     * NARROWER, DNS-free literal-address check IS enforced, though: a bare loopback/link-local
     * (including the `169.254.169.254`-class cloud metadata range)/multicast/IPv6-unique-local IP
     * literal, or the hostname `localhost`, is rejected outright -- RFC1918 (site-local) literals and
     * ALL hostnames remain unchecked/allowed, since a compromised or socially-engineered ADMIN
     * session pasting a raw internal/metadata IP is the concrete risk this closes, not the documented
     * on-prem-private-network use case. See `ConferenceStreamingService.rejectIfUnsafeLiteralHost`
     * KDoc for the full mechanism and its limits.
     */
    suspend fun createDestination(
        label: String,
        platform: ConferenceStreamPlatform,
        rtmpUrl: String,
        streamKey: String,
    ): ConferenceStreamDestinationDto

    /**
     * Role: ADMIN. [newStreamKey] `== null` means "leave the stored key UNCHANGED" (password-field
     * semantics -- the edit form's key field starts empty with placeholder "unveraendert lassen"). A
     * non-null value REPLACES the stored ciphertext and stamps a fresh `streamKeySetAt`. A
     * blank/whitespace-only [newStreamKey] is REJECTED with a validation error, never silently
     * stored -- a stray form submit must never wipe a working key into an empty one.
     */
    suspend fun updateDestination(
        destinationId: String,
        label: String,
        rtmpUrl: String,
        newStreamKey: String?,
    ): ConferenceStreamDestinationDto

    /** Role: ADMIN. Soft on/off without deleting the stored, encrypted credential. A disabled destination is excluded from [listStreamTargets] and rejected (with [ConflictException]) if named in [startStream]'s `destinationIds`. */
    suspend fun setDestinationEnabled(
        destinationId: String,
        enabled: Boolean,
    ): ConferenceStreamDestinationDto

    /**
     * Role: ADMIN. Throws [ConflictException] while any
     * [network.lapis.cloud.shared.domain.ConferenceStreamStatus.LIVE]/[network.lapis.cloud.shared.domain.ConferenceStreamStatus.PAUSED]/[network.lapis.cloud.shared.domain.ConferenceStreamStatus.STARTING]
     * stream references it. Returns [Boolean], not `Unit` -- Kilua RPC's generated client `call()`
     * requires a `: Any`-satisfying return bound, the SAME constraint
     * [IConferenceRecordingService.getActiveRecording]'s own KDoc documents for its
     * List-instead-of-nullable return.
     */
    suspend fun deleteDestination(destinationId: String): Boolean

    // ── Moderator-facing target picker -- NO url, NO key ───────────────────────────────────────

    /**
     * Role: room creator OR global BOARD/ADMIN. Deliberately a DIFFERENT, NARROWER DTO than
     * [listDestinations] -- [ConferenceStreamTargetDto] carries no [ConferenceStreamDestinationDto.rtmpUrl]
     * and no key material, so a BOARD moderator choosing among approved destinations never sees the
     * ingest URL. Excludes disabled destinations.
     */
    suspend fun listStreamTargets(): List<ConferenceStreamTargetDto>

    // ── Stream lifecycle ────────────────────────────────────────────────────────────────────

    /**
     * Role: the room's creator, OR global BOARD/ADMIN (identical to
     * [IConferenceService.endRoom]/[IConferenceRecordingService.startRecording] -- see class KDoc
     * "Why ADMIN-only for destination CRUD but moderator-or-BOARD for start/stop"). The room must
     * exist with `endedAt == null` (else [NotFoundException]/[ConflictException]). Throws
     * [ConflictException] if: streaming is unconfigured ([getStreamingAvailability] returns
     * `enabled=false`); a stream is already active for this room (row-locked with `forUpdate()`,
     * mirroring `startRecording`'s own fix for the identical "one active X per room" race, see
     * [IConferenceRecordingService.startRecording] KDoc); `destinationIds` is empty; its size
     * exceeds [ConferenceStreamAvailabilityDto.maxDestinations]; it names a disabled or unknown
     * destination; a named destination is ALREADY targeted by another active (or starting/paused/
     * stopping) stream IN A DIFFERENT ROOM (destination rows are `forUpdate()`-locked to close this
     * check-then-act race the same way the room-row lock closes the same-room race -- this exclusivity
     * is cross-room and destination-scoped, layered on top of, not a replacement for, the
     * one-active-stream-per-room check); `layout == `[ConferenceStreamLayout.SINGLE_PARTICIPANT] with
     * `participantIdentity == null`; or the caller is over their throttle budget. Calls LiveKit
     * SYNCHRONOUSLY -- see class KDoc "startStream DOES call LiveKit synchronously" for the full
     * two-transaction ordering and crash-recovery story.
     */
    suspend fun startStream(
        roomId: String,
        destinationIds: List<String>,
        layout: ConferenceStreamLayout,
        latencyMode: ConferenceStreamLatencyMode,
        participantIdentity: String?,
    ): ConferenceStreamDto

    /**
     * Role: room creator OR global BOARD/ADMIN. Stops the RTMP egress; the MEETING continues
     * untouched. LiveKit has NO pause primitive (verified live) -- see class KDoc "pauseStream/
     * resumeStream". Idempotent -- calling on an already-[network.lapis.cloud.shared.domain.ConferenceStreamStatus.PAUSED]/
     * ended/failed stream is a no-op that returns the current row, never an error.
     *
     * Security-audit round-4 R4-3 fix -- internally a two-step transition, not a single write: the row
     * passes through [network.lapis.cloud.shared.domain.ConferenceStreamStatus.PAUSING] while the
     * `StopEgress` request is confirmed via `ListEgress` (same confirmation discipline `stopStream`
     * already applies), and only becomes
     * [network.lapis.cloud.shared.domain.ConferenceStreamStatus.PAUSED] once that confirmation lands --
     * PAUSED is security-load-bearing this wave (the secret-ballot fail-closed gate trusts it blindly to
     * mean "nothing is publishing"), so a merely-requested, unconfirmed stop is no longer sufficient. A
     * confirmation timeout leaves the row `PAUSING`; `StreamPoller`'s own `PAUSING` handling retries on
     * its next tick.
     */
    suspend fun pauseStream(streamId: String): ConferenceStreamDto

    /**
     * Role: room creator OR global BOARD/ADMIN. Starts a NEW egress to the SAME destinations,
     * increments `restartCount`, writes a NEW `livekit_egress_id` onto the SAME stream row -- see
     * class KDoc "pauseStream/resumeStream". Throws [ConflictException] if the stream is not
     * currently [network.lapis.cloud.shared.domain.ConferenceStreamStatus.PAUSED].
     */
    suspend fun resumeStream(streamId: String): ConferenceStreamDto

    /**
     * Role: room creator OR global BOARD/ADMIN -- deliberately NOT "only whoever started it", same
     * reasoning [IConferenceRecordingService.stopRecording] KDoc gives for its own method. Idempotent
     * once already ended/failed.
     */
    suspend fun stopStream(streamId: String): ConferenceStreamDto

    // ── Transparency read -- every participant has a legal right to this ──────────────────────

    /**
     * Role: MEMBER+, ACTIVE, [network.lapis.cloud.shared.domain.MemberStatus.GUEST], or (V0.11.0)
     * [network.lapis.cloud.shared.domain.MemberStatus.FRIEND] (Wave 5
     * "Föderations-Gastbeitritt", design review D13 -- same widening as
     * [IConferenceRecordingService.getActiveRecording], same reasoning: a federated guest (or
     * friend) inside the room has the same legal right to know as an ACTIVE member, and the same
     * `allowFederationGuests` + "has joined" narrowing applies). NEVER gated on any privilege --
     * DSGVO/Persoenlichkeitsrecht means EVERYONE in the room must be able to see THAT and WHERE it
     * is being streamed, same "everyone in the room has a legal right to know" rule
     * [IConferenceRecordingService.getActiveRecording] KDoc establishes for recording. Returns
     * destination LABELS + platform only via
     * [network.lapis.cloud.shared.domain.ConferenceStreamTargetStatusDto] -- NEVER url, NEVER key.
     * Returns EMPTY (not an error) when no stream is active for [roomId] -- the normal case, not
     * exceptional. `List`, at most one element, for the SAME Kilua `: Any` return-bound reason
     * [IConferenceRecordingService.getActiveRecording] KDoc documents -- callers use
     * `.singleOrNull()`.
     */
    suspend fun getActiveStream(roomId: String): List<ConferenceStreamDto>

    /**
     * Role: room creator OR global BOARD/ADMIN. History for the lobby/admin view. `roomId == null`
     * lists across ALL rooms. Capped at 200, newest first -- same DoS-cap class
     * [IConferenceService.listActiveRooms]/[IConferenceRecordingService.listRecordings]'s own limits
     * enforce.
     */
    suspend fun listStreams(roomId: String? = null): List<ConferenceStreamDto>
}
