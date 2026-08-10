package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.ConferenceBreakoutAssignmentDto
import network.lapis.cloud.shared.domain.ConferenceBreakoutAssignmentInput
import network.lapis.cloud.shared.domain.ConferenceBreakoutPlanInput
import network.lapis.cloud.shared.domain.ConferenceBreakoutRoomDto
import network.lapis.cloud.shared.domain.ConferenceJoinTokenDto

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 6 "Breakout-Räume" -- a moderator, while in an active
 * meeting, can split participants into N temporary sub-sessions ("Breakout-Räume") for small-group
 * work, and later bring everyone back to the main room with one action ("alle zurückholen"). See
 * `31-conference-breakout.kuml.kts` file header for the full persistence model and
 * [network.lapis.cloud.server.rpc.ConferenceBreakoutService] KDoc for the implementation.
 *
 * ## A SEPARATE service from [IConferenceService], deliberately -- not new methods on it
 *
 * Follows the precedent [IConferenceRecordingService] KDoc "A separate service" already
 * establishes, on the SAME three axes, with one deliberate DEVIATION on axis 2:
 * 1. **Distinct collaborators.** This service owns its own bounded-fan-out `createRoom`/
 *    `deleteRoom` loops and its own two tables (`conference_breakout_room`/
 *    `conference_breakout_assignment`); [IConferenceService] has no use for either.
 * 2. **Availability gate -- deliberately NOT duplicated, unlike recording/streaming.** Recording
 *    needs ffmpeg+Egress reachability; streaming needs RTMP+Egress. Breakout rooms need *nothing*
 *    beyond what the parent conference already requires (`ConferenceConfig.enabled`). Introducing a
 *    second, always-identical toggle would be pure duplication with no independent failure mode --
 *    this interface has NO `getBreakoutAvailability()` method, every method below reuses the exact
 *    same conference-enabled gate [IConferenceService] KDoc "The conference enabled gate"
 *    describes. This is a deliberate, explicit deviation from axis 2 of the
 *    [IConferenceRecordingService] precedent, stated here so a reviewer does not mistake the
 *    omission for a gap.
 * 3. **Surface-growth control.** [IConferenceService] already has 12 methods; adding 6 more for
 *    breakout would blur two genuinely different concerns (running one meeting vs. managing its
 *    sub-sessions), exactly the reasoning [IConferenceRecordingService]'s own KDoc gives for its own
 *    split.
 *
 * ## At most one open batch per parent room
 *
 * "The currently active breakout rooms for room X" is always `WHERE parent_room_id = X AND
 * closed_at IS NULL` -- [createBreakoutRooms] refuses (`ConflictException`) if any breakout room for
 * the target room is still open; the moderator must [recallAll] first. There is no separate
 * "batch"/"session" grouping table -- see `31-conference-breakout.kuml.kts` file header.
 *
 * ## The moderator is never auto-assigned
 *
 * [createBreakoutRooms]'s auto-distribution excludes the room's own creator -- a moderator who
 * wants to join a specific breakout room can do so manually via [assignParticipants], but the
 * default "Räume erstellen und verteilen" one-click flow never moves them out of the main room
 * automatically, so they retain full moderator visibility of the meeting by default.
 *
 * ## No breakout-room moderator
 *
 * A breakout room has no independent moderator concept -- [requestBreakoutJoinToken] always mints a
 * [network.lapis.cloud.shared.domain.ConferenceRole.PARTICIPANT] token, even for the parent room's
 * own moderator if they are ever manually assigned to one. Every moderating action (create, assign,
 * recall) is only ever exercised from the MAIN room, gated by the SAME
 * `requireModeratorOrPrivileged` check [IConferenceService.endRoom]/[IConferenceService
 * .removeParticipant]/[IConferenceService.renameRoom]/[IConferenceService.setRoomGuestAccess]
 * already use.
 *
 * ## `conference_participation` stays open across a breakout excursion
 *
 * Moving a member into, between, or back from a breakout room NEVER writes to
 * `conference_participation` -- that row (opened by [IConferenceService.joinRoom], closed by
 * [IConferenceService.leaveRoom]/[IConferenceService.endRoom]) represents membership in the PARENT
 * MEETING, which a participant never truly leaves while inside a breakout room. [rejoinMainRoomToken]
 * requires an OPEN `conference_participation` row precisely to prove this, and mints a fresh main-
 * room token WITHOUT inserting a second one.
 *
 * ## Guests in breakout rooms -- explicit decision
 *
 * **Yes**, a [network.lapis.cloud.shared.domain.MemberStatus.GAST] participant of a room with
 * `allowFederationGuests = true` CAN be assigned to and rejoin breakout rooms, on identical terms to
 * an AKTIV member. Reasoning: a breakout room's participant set is always a SUBSET of the parent
 * meeting's already-consented audience -- every eligible member, AKTIV or GAST, already passed
 * [IConferenceService.joinRoom]'s guest consent gate for the PARENT room before ever being live in
 * it. A breakout room only NARROWS who can see the guest, it never widens it beyond what
 * `network.lapis.cloud.server.rpc.ConferenceGuestConsentDisclaimer`'s existing "visible to all other
 * participants of THIS meeting" text already covers. No new consent flow, no new disclaimer text, no
 * new acknowledgment row -- the "must currently hold an open `conference_participation` row for the
 * parent room" check every mutating method below applies is exactly the same check that already
 * implies "went through the guest gate if they're a guest".
 *
 * ## DSGVO/transparency: breakout audio/video is NEVER captured by a main-room recording/stream
 *
 * A breakout room is a physically SEPARATE LiveKit room -- [network.lapis.cloud.server.conference
 * .RecordingPoller]/`StreamPoller` are both scoped to exactly ONE `livekit_room_name` (the room they
 * were started against), so neither ever touches a breakout room's own, different
 * `livekit_room_name`. The client discloses this explicitly at breakout-room creation time (if a
 * recording/stream is currently active) AND persistently inside every breakout call view -- see
 * `network.lapis.cloud.client.ConferenceScreen`'s own breakout-disclosure copy. This interface
 * itself carries no disclosure text (that is display-only, computed client-side from
 * [network.lapis.cloud.shared.rpc.IConferenceRecordingService.getActiveRecording]/
 * [network.lapis.cloud.shared.rpc.IConferenceStreamingService.getActiveStream] against the PARENT
 * room, which a breakout participant can still call since their `conference_participation` row
 * stays open).
 *
 * ## No new `AuditEntityType`
 *
 * Breakout create/assign/recall is ephemeral live-meeting stagecraft, not a governance/financial/
 * cross-org-trust fact -- matches the existing precedent that [IConferenceService.endRoom]/
 * [IConferenceService.removeParticipant]/[IConferenceService.renameRoom] are also unaudited (only
 * [IConferenceService.setRoomGuestAccess], a cross-org trust decision, is audited).
 *
 * ## Recall is a real disconnect, not a push/poll signal
 *
 * [recallAll] (and, per-member, [createBreakoutRooms]/[assignParticipants]) force-disconnects the
 * affected participant(s) from the relevant LiveKit room via `LiveKitAdminClient.removeParticipant`/
 * `deleteRoom` -- the client's `RoomEvent.Disconnected` firing IS the (near-real-time, no polling
 * latency) signal that something changed; the client then calls [getMyBreakoutAssignment] to learn
 * what. See `network.lapis.cloud.client.ConferenceScreen`'s `resolvePostDisconnectDestination` KDoc.
 * A LiveKit data-channel push was considered and rejected for this signal -- see
 * [network.lapis.cloud.server.rpc.ConferenceBreakoutService] KDoc "Why not a data-channel push".
 */
@RpcService
interface IConferenceBreakoutService {
    /**
     * Role: the room's creator, OR global BOARD/ADMIN (same `requireModeratorOrPrivileged` gate
     * [IConferenceService.endRoom]/[IConferenceService.removeParticipant]/[IConferenceService
     * .renameRoom]/[IConferenceService.setRoomGuestAccess] already use). [roomId] must be active
     * (`endedAt == null`) and must have NO currently-open breakout batch (every
     * `conference_breakout_room` for [roomId] already `closedAt != null`) -- else
     * [ConflictException] "recall the existing breakout rooms first". Creates
     * `plan.roomCount` real LiveKit rooms (own `lc-bo-<uuid4>` names) and assigns every CURRENTLY
     * LIVE participant of [roomId] (per `LiveKitAdminClient.listParticipants` against the PARENT
     * room, not the potentially-stale `conference_participation` log) that is not explicitly
     * excluded -- see class KDoc "The moderator is never auto-assigned". Force-disconnects every
     * newly-assigned member from the PARENT LiveKit room so the relocation happens promptly (see
     * class KDoc "Recall is a real disconnect").
     */
    suspend fun createBreakoutRooms(
        roomId: String,
        plan: ConferenceBreakoutPlanInput,
    ): List<ConferenceBreakoutRoomDto>

    /**
     * Role: the room's creator, OR global BOARD/ADMIN. Re-assigns specific, currently-live members
     * to a specific, currently-OPEN breakout room of [roomId]'s active batch -- usable both right
     * after [createBreakoutRooms] (manual override of the auto-distribution) and LATER, mid-session
     * (moving someone from breakout room A to B). Closes ([recalledAt]) each target member's prior
     * OPEN assignment row in ANY breakout room of this batch before opening the new one -- a member
     * is never assigned to two breakout rooms of the same batch simultaneously. Force-disconnects
     * each reassigned member from their PREVIOUS LiveKit room (main or breakout) so the relocation
     * happens promptly -- see class KDoc "Recall is a real disconnect".
     */
    suspend fun assignParticipants(
        roomId: String,
        assignments: List<ConferenceBreakoutAssignmentInput>,
    ): List<ConferenceBreakoutRoomDto>

    /**
     * Role: the room's creator, OR global BOARD/ADMIN. Closes EVERY open breakout room of [roomId]'s
     * active batch: deletes each real LiveKit room (disconnects everyone in it immediately -- THIS
     * is the "recall" signal, not a push/poll, see class KDoc "Recall is a real disconnect") and
     * stamps `closedAt`/`recalledAt`. Tolerates a breakout LiveKit room that is already gone (e.g.
     * everyone in it already voluntarily returned via [returnToMainRoom] and it self-emptied) as
     * success, not failure -- only a genuine, unexpected LiveKit failure surfaces as
     * [ConflictException]. Returns the number of breakout rooms that were open (`0` if none,
     * idempotent).
     */
    suspend fun recallAll(roomId: String): Int

    /**
     * Role: any caller holding (or having held) a `conference_participation` row for [roomId] --
     * i.e. any current or past participant of the PARENT meeting, AKTIV or GAST alike. Returns the
     * caller's currently OPEN breakout assignment for [roomId]'s active batch as a single-or-empty
     * list (`singleOrNull()` on the client side), never `null` directly -- same "at most one" list
     * shape [IConferenceRecordingService.getActiveRecording]/[IConferenceStreamingService.getActiveStream]
     * already use; a plain nullable single-DTO return trips up the kilua-rpc JS codegen (verified
     * empirically during this wave's implementation -- every other "maybe none" RPC method in this
     * codebase already follows this same list-shaped convention for exactly that reason). This is
     * the SOLE mechanism a client uses to learn "was I just assigned somewhere" / "was I just
     * recalled" -- see class KDoc "Recall is a real disconnect".
     */
    suspend fun getMyBreakoutAssignment(roomId: String): List<ConferenceBreakoutAssignmentDto>

    /**
     * Role: any caller holding an OPEN assignment to [breakoutRoomId] (verified:
     * `conference_breakout_assignment WHERE breakout_room_id = breakoutRoomId AND member_id =
     * caller AND recalled_at IS NULL`) -- the SINGLE enforcement point of "only a specifically-
     * assigned participant may obtain a join token for that specific breakout room", see
     * [network.lapis.cloud.server.rpc.ConferenceBreakoutService] KDoc for why this query must never
     * be weakened. Mints a fresh, room-pinned participant token via the SAME
     * `LiveKitAccessToken.mintParticipantToken` [IConferenceService.joinRoom] uses -- writes NO new
     * row (the assignment row already proves entitlement; this call is read-only from the DB's own
     * perspective). A GAST caller gets the SAME short guest TTL [IConferenceService.joinRoom] uses
     * for a guest -- see class KDoc "Guests in breakout rooms". Everyone (including the parent
     * room's own moderator, if ever manually assigned) gets
     * [network.lapis.cloud.shared.domain.ConferenceRole.PARTICIPANT] -- see class KDoc "No breakout-
     * room moderator".
     */
    suspend fun requestBreakoutJoinToken(breakoutRoomId: String): ConferenceJoinTokenDto

    /**
     * Self-service: closes ONLY the caller's own open assignment row for [breakoutRoomId] (mirrors
     * [IConferenceService.leaveRoom]'s own "closes only the caller's own open row(s), no IDOR
     * surface" shape) -- no LiveKit call here, the CLIENT disconnects its own session directly (same
     * shape the existing leave-button flow already uses); this RPC only updates the DB source of
     * truth so a subsequent [getMyBreakoutAssignment] correctly resolves to `null`. Idempotent -- a
     * no-op if the caller has no open assignment for [breakoutRoomId].
     */
    suspend fun returnToMainRoom(breakoutRoomId: String)

    /**
     * Role: any caller with a currently OPEN `conference_participation` row for [roomId] (i.e.
     * genuinely still "in" the meeting) -- mints a fresh main-room participant token WITHOUT
     * inserting a new `conference_participation` row (one is already open; the caller never truly
     * left the meeting record-wise while they were in a breakout room, see class KDoc
     * "`conference_participation` stays open across a breakout excursion"). Used after
     * [returnToMainRoom]/a moderator's [recallAll]/an assignment change to reconnect to the LiveKit
     * main room -- NOT a substitute for [IConferenceService.joinRoom], which remains the ONLY entry
     * point for a caller with no open participation row at all.
     */
    suspend fun rejoinMainRoomToken(roomId: String): ConferenceJoinTokenDto
}
