package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.ConferenceRecordingAvailabilityDto
import network.lapis.cloud.shared.domain.ConferenceRecordingDto
import network.lapis.cloud.shared.domain.ConferenceRecordingListQuery
import network.lapis.cloud.shared.domain.ConferenceRecordingPageDto
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
 *    "ACTIVE local member" plus, for two methods, "creator-or-BOARD/ADMIN". Recording WRITES
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
 *
 * ## [deleteRecording] is its OWN method, deliberately -- not [IDocumentService.deleteDocument]
 *
 * This interface originally argued the opposite ("deleting a recording is deleting a `document`,
 * [IDocumentService.deleteDocument] already does exactly that"). That argument does not survive
 * contact with the actual row shapes, on three independent counts:
 * 1. **It leaves the `conference_recording` row behind.** `deleteDocument` only flips
 *    `document.is_deleted` -- the recording row stays `READY` and keeps rendering in the Lobby with
 *    a `mediaUrl` that now 404s (the media route honours `isDeleted`, see
 *    `network.lapis.cloud.server.routes.registerConferenceRecordingRoutes`). A deletion the user
 *    asked for must not leave a visibly broken row behind.
 * 2. **It has no path at all for a FAILED (or still-processing) recording.** `document_id` is
 *    `null` until composition succeeds, so exactly the recordings a moderator most wants to clear
 *    away -- the failed ones -- have no `document` to delete in the first place.
 * 3. **It writes no audit trail.** `deleteDocument` records nothing, unlike every other destructive
 *    RPC in this codebase (see `ConferenceStreamingService.deleteDestination`). A recording is a
 *    «Sitzungsobjekt»; its removal is exactly the kind of act the GoBD audit chain exists for.
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
     * Role: the room's creator, OR global BOARD/ADMIN -- deliberately the SAME predicate
     * [startRecording]/[stopRecording] use (`requireModeratorOrPrivileged`), not a new, stricter
     * ADMIN-only rule: whoever may start and stop a room's recording is exactly who may discard it
     * afterwards, and inventing a second authorization shape for one more verb on the same artifact
     * is precisely the "two rules for one artifact" this method's own class-KDoc section warns
     * about.
     *
     * **One narrowing, and only for a recording that HAS an archived document** (`documentId !=
     * null`, i.e. a READY one): the caller must ADDITIONALLY satisfy
     * `canAccessDocumentAtLevel(recording.accessLevel) || isPrivileged`, else [ForbiddenException]
     * -- and then NOTHING is deleted at all, not even the parts that would have been allowed.
     * Without this narrowing, moderator standing alone would be a privilege escalation: a plain
     * MEMBER who created a room stays its moderator while a BOARD participant starts the recording
     * at [DocumentAccessLevel.ADMIN_ONLY] (any privileged starter may choose any level), and
     * deleting it would soft-delete a `document` that MEMBER can neither read ([listRecordings] and
     * the media route both refuse them) nor delete directly ([IDocumentService.deleteDocument] is
     * BOARD/ADMIN-only). The extra predicate is deliberately `deleteDocument`'s own rule OR the
     * watch predicate, mirrored rather than re-invented. A FAILED recording has no `documentId` at
     * all and is therefore untouched by this: it stays governed by `requireModeratorOrPrivileged`
     * alone. [DocumentAccessLevel] still never governs who may administer the ROOM -- only who may
     * touch the archived FILE.
     *
     * Only a TERMINAL recording can be deleted ([network.lapis.cloud.shared.domain.ConferenceRecordingStatus.READY]
     * or [network.lapis.cloud.shared.domain.ConferenceRecordingStatus.FAILED]) -- a `RECORDING`/
     * `STOPPING`/`PROCESSING` row is still being driven by `RecordingPoller` (which may be mid-tick
     * on exactly this row, holding LiveKit egress handles and an ffmpeg subprocess against it), so
     * deleting it is rejected with [ConflictException]. Stop it first, then delete it once it
     * settles. [NotFoundException] for an unknown [recordingId], [ForbiddenException] for a caller
     * who is neither the room's creator nor BOARD/ADMIN (or who fails the archived-document
     * narrowing above), [ConflictException] if the caller is over their throttle budget or recording
     * is unconfigured on this server.
     *
     * **What is actually removed** (all database work in ONE transaction): the
     * `conference_recording_track` rows and the `conference_recording` row itself are HARD-deleted
     * -- `28-conference-recording.kuml.kts`'s own file header forbids "a second access-control
     * column on this table", which rules out a soft-delete flag there, so removing the row is the
     * only option consistent with that constraint. The composed file's `document` row is only
     * SOFT-deleted (`is_deleted = true`, exactly what Dokumentenablage does everywhere else);
     * neither the stored blob nor any `document_version` row is touched. The raw per-track directory
     * IS removed from disk, because unlike `RecordingPoller`'s automatic retention (which protects
     * an unannounced, silent deletion) this is an explicit, confirmed deletion the user asked for --
     * as a best-effort step AFTER that transaction has committed, never inside it (a filesystem
     * delete cannot be rolled back, so a transaction that still might abort must not have performed
     * one; see `ConferenceRecordingService.deleteRecording` KDoc fact 4). Returns `true`.
     */
    suspend fun deleteRecording(recordingId: String): Boolean

    /**
     * Role: MEMBER+, ACTIVE, [network.lapis.cloud.shared.domain.MemberStatus.GUEST], or (V0.11.0)
     * [network.lapis.cloud.shared.domain.MemberStatus.FRIEND] (Wave 5
     * "Föderations-Gastbeitritt", design review D13 -- widened from ACTIVE-only: a federated guest
     * (or friend) actually inside the room has the SAME legal right to know it is being recorded as
     * an ACTIVE member; the disclaimer they consented to before joining explicitly promises this). A
     * GUEST or FRIEND caller is admitted iff the room has `allowFederationGuests = true` AND the caller has joined
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
     * Role: MEMBER+, ACTIVE. Filtered server-side to recordings the caller may see:
     * `current.canAccessDocumentAtLevel(recording.accessLevel) || current.memberId == recording.startedByMemberId`
     * -- see `network.lapis.cloud.server.security.canAccessDocumentAtLevel` and a later wave's
     * `ConferenceRecordingAccess.mayAccess` for where this exact predicate is centralized (used
     * identically here, in [ConferenceRecordingDto.mediaUrl]'s computation, and in the media
     * route). [ConferenceRecordingListQuery.roomId] `null` lists across ALL rooms (the Lobby's
     * "Aufzeichnungen" section -- recordings OUTLIVE their rooms, so they must stay reachable long
     * after the room itself is gone). Newest first.
     *
     * **Offset-paginated, and the access filter runs in SQL, not in Kotlin afterwards.** That
     * distinction is load-bearing rather than an implementation detail: a filter applied AFTER
     * `LIMIT` would let a row the caller may not see silently consume a page slot (short, ragged
     * pages) and would make [ConferenceRecordingPageDto.totalCount] disagree with what the caller
     * can actually reach. `WHERE`, `COUNT(*)`, `LIMIT` and `OFFSET` therefore all run against the
     * same already-filtered row set. [ConferenceRecordingListQuery.limit]/`offset` are re-clamped
     * server-side (`1..`[ConferenceRecordingListQuery.MAX_LIMIT] / `>= 0`) -- the client's values
     * are a request, not a promise -- and the applied values are echoed back on the page DTO.
     */
    suspend fun listRecordings(query: ConferenceRecordingListQuery): ConferenceRecordingPageDto
}
