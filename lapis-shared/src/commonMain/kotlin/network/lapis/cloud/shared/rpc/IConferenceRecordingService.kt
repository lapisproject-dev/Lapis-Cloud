package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.ConferenceRecordingAvailabilityDto
import network.lapis.cloud.shared.domain.ConferenceRecordingDto
import network.lapis.cloud.shared.domain.DocumentAccessLevel

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 2 "Aufzeichnung" -- Track-Egress-based meeting
 * recording, layered on top of Wave 1's [IConferenceService]. See the concept document
 * ("03 Bereiche/Lapis Cloud/Videokonferenzen.md", vault, the 2026-08-01 architecture decision) for
 * the full fachlich model, `28-conference-recording.kuml.kts` file header for the persistence
 * model, and [network.lapis.cloud.server.conference.LiveKitEgressClient]/
 * [network.lapis.cloud.server.conference.ConferenceRecordingConfig] for the LiveKit-side
 * integration this interface's implementation coordinates.
 *
 * ## A SEPARATE service from [IConferenceService], deliberately -- not new methods on it
 *
 * This follows the precedent `IPriceOracleService`/`ILtrLedgerService` already establish, and the
 * case is stronger here, on three independent axes:
 * 1. **Two different authorization axes, not one.** Every [IConferenceService] method gates on
 *    "AKTIV local member" plus, for two methods, "creator-or-BOARD/ADMIN". Recording WRITES
 *    ([startRecording]/[stopRecording]) gate on that same creator-or-BOARD/ADMIN shape, but
 *    recording READS ([listRecordings]) gate on `DocumentAccessLevel`
 *    ([network.lapis.cloud.server.security.canAccessDocumentAtLevel]) -- a completely different
 *    predicate [IConferenceService] never touches and should not learn about.
 * 2. **A second, independent availability gate.** `ConferenceConfig.enabled` (LiveKit URL/key/
 *    secret) and `ConferenceRecordingConfig.enabled` (+ Egress reachable + `ffmpeg` present) can
 *    each be `true`/`false` independently -- growing Wave 1's already-shipped
 *    [network.lapis.cloud.shared.domain.ConferenceAvailabilityDto] a `recordingEnabled` field would
 *    muddle "can I confer" with "can I record". See [getRecordingAvailability].
 * 3. **Different server-side collaborators** ([network.lapis.cloud.server.conference.LiveKitEgressClient],
 *    [network.lapis.cloud.server.conference.ConferenceRecordingConfig], `documentStorageRoot`, the
 *    raw-track-file root) that `ConferenceService` has no use for -- folding them in would widen a
 *    live-verified Wave 1 constructor for zero benefit.
 *
 * ## `startRecording` inserts a row only -- this wave's RPC layer never orchestrates LiveKit egress
 *
 * [startRecording] deliberately does NOT call [network.lapis.cloud.server.conference.LiveKitEgressClient]
 * at all -- it only validates authorization/state and inserts a `RECORDING`-status
 * `conference_recording` row. A later wave's `RecordingPoller` (a single application-scoped
 * coroutine, see that class's own KDoc for the full poll-not-webhook mechanism) is the ONLY thing
 * that ever calls `StartTrackEgress`, on its next tick after this row appears. This keeps
 * [network.lapis.cloud.server.rpc.ConferenceRecordingService] testable with a fake
 * [network.lapis.cloud.server.conference.LiveKitEgressClient] with zero timing dependency, and
 * matches [IConferenceService]'s own "no webhooks, lazy reconciliation" posture rather than
 * reversing it. The one exception: [stopRecording] transitions `RECORDING -> STOPPING` only --
 * even STOPPING never directly calls `StopEgress` itself, the poller does that too.
 *
 * ## Out of scope this wave (deliberate, not gaps -- see the Wave 2 plan's own "Explicitly out of
 * scope" section for the full, authoritative list)
 *
 * - **No auto-transcript / live subtitles**, no chapter markers, no WebM output, no external
 *   RTMP live-streaming (Wave 3's job -- this wave's Redis+Egress infrastructure is exactly Wave
 *   3's prerequisite), no "Termin -> Konferenzraum" integration, no S3-compatible object storage
 *   (local Dokumentenablage is this wave's only backend), no four-tier role model, no
 *   per-participant recording opt-out, no retention/auto-deletion policy, no federated-guest
 *   recording access, no webhooks of any kind (including `track_published`), no multiple
 *   simultaneous recordings per room, no pause/resume within one recording.
 * - **No `deleteRecording` method.** Deleting a recording is deleting a `document` --
 *   [IDocumentService.deleteDocument] (BOARD/ADMIN, soft-delete, versions kept for audit) already
 *   does exactly that, and the media route (a later wave) honours `isDeleted`. Adding a second
 *   deletion path would create two rules for one artifact.
 */
@RpcService
interface IConferenceRecordingService {
    /**
     * Any authenticated member. Never throws for an unconfigured deployment -- mirrors
     * [IConferenceService.getAvailability]'s own "one place to check" contract, see class KDoc "A
     * second, independent availability gate".
     */
    suspend fun getRecordingAvailability(): ConferenceRecordingAvailabilityDto

    /**
     * Role: the room's creator, OR global BOARD/ADMIN (same shape as
     * [IConferenceService.endRoom]/[IConferenceService.removeParticipant]). The room must exist and
     * have `endedAt == null` (else [NotFoundException]/[ConflictException]). Throws
     * [ConflictException] if a recording is already active ([network.lapis.cloud.shared.domain.ConferenceRecordingStatus.RECORDING]
     * or [network.lapis.cloud.shared.domain.ConferenceRecordingStatus.STOPPING]) for this room (at
     * most ONE active recording per room, enforced in-transaction -- see
     * `network.lapis.cloud.server.rpc.ConferenceRecordingService.startRecording` KDoc), if
     * recording is unconfigured ([getRecordingAvailability] returns `enabled=false`), or if the
     * caller is over their throttle budget. Inserts the row only -- see class KDoc "startRecording
     * inserts a row only".
     */
    suspend fun startRecording(
        roomId: String,
        accessLevel: DocumentAccessLevel,
    ): ConferenceRecordingDto

    /**
     * Role: the room's creator, OR global BOARD/ADMIN -- deliberately NOT "only whoever started
     * it": a moderator must be able to stop a recording another privileged user started, and the
     * starter may have disconnected. Idempotent once already stopped (calling on a non-`RECORDING`
     * recording is a no-op that just returns the current row, never an error). Transitions
     * `RECORDING -> STOPPING` only -- a later wave's `RecordingPoller` drives everything after
     * that, see class KDoc.
     */
    suspend fun stopRecording(recordingId: String): ConferenceRecordingDto

    /**
     * Role: MEMBER+, AKTIV **or** [network.lapis.cloud.shared.domain.MemberStatus.GAST] (Wave 5
     * "Föderations-Gastbeitritt", design review D13 -- widened from AKTIV-only: a federated guest
     * actually inside the room has the SAME legal right to know it is being recorded as an AKTIV
     * member; the disclaimer they consented to before joining explicitly promises this). A GAST
     * caller is admitted iff the room has `allowFederationGuests = true` AND the caller has joined
     * it at some point (same narrowing `listParticipants` applies, see
     * `network.lapis.cloud.server.rpc.requireGuestHasJoinedRoom`). The in-call view's authoritative
     * detail source for the recording banner/badge ("Aufzeichnung gestartet von X um HH:MM").
     * Returns EMPTY (not an error) if no
     * [network.lapis.cloud.shared.domain.ConferenceRecordingStatus.RECORDING]/[network.lapis.cloud.shared.domain.ConferenceRecordingStatus.STOPPING]
     * recording exists for [roomId] -- a room without an active recording is the normal case, not
     * exceptional. **`List`, not a nullable [ConferenceRecordingDto]**, purely because Kilua RPC's
     * generated client `call()` requires its return type to satisfy a `: Any` bound (confirmed
     * against the framework source, 2026-08-09 -- a nullable-DTO return fails to compile on the JS
     * target with an unresolvable `RET` type-parameter inference error); AT MOST ONE element,
     * because this wave forbids multiple simultaneous recordings per room -- callers use
     * `.singleOrNull()`. Never gated on `DocumentAccessLevel` -- everyone in the room has a legal
     * right to know it is being recorded, regardless of who may later be allowed to watch it back.
     */
    suspend fun getActiveRecording(roomId: String): List<ConferenceRecordingDto>

    /**
     * Role: MEMBER+, AKTIV. Filtered server-side to recordings the caller may see:
     * `current.canAccessDocumentAtLevel(recording.accessLevel) || current.memberId == recording.startedByMemberId`
     * -- see `network.lapis.cloud.server.security.canAccessDocumentAtLevel` and a later wave's
     * `ConferenceRecordingAccess.mayAccess` for where this exact predicate is centralized (used
     * identically here, in [ConferenceRecordingDto.mediaUrl]'s computation, and in the media
     * route). [roomId] `null` lists across ALL rooms (the Lobby's "Aufzeichnungen" section --
     * recordings OUTLIVE their rooms, so they must stay reachable long after the room itself is
     * gone). Capped at 200, newest first -- same DoS-cap class
     * [IConferenceService.listActiveRooms]'s own limit enforces.
     */
    suspend fun listRecordings(roomId: String? = null): List<ConferenceRecordingDto>
}
