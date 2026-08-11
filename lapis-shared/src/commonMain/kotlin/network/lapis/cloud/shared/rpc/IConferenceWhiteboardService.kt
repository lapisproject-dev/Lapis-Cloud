package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.ConferenceWhiteboardSaveResultDto
import network.lapis.cloud.shared.domain.ConferenceWhiteboardStateDto
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.WhiteboardStrokeDto
import network.lapis.cloud.shared.domain.WhiteboardStrokeWireDto

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 7 "Whiteboard" -- a shared real-time drawing canvas
 * participants in the main conference room can draw on together during a meeting. A FIFTH,
 * separate conference RPC service (not folded into [IConferenceService]), justified on the same
 * three axes [IConferenceBreakoutService] KDoc uses for its own separateness:
 *
 * 1. Distinct collaborators -- `network.lapis.cloud.server.conference.ConferenceWhiteboardState`
 *    (new, bounded, in-memory) and the document-storage archiving helpers -- [IConferenceService]
 *    has no use for either.
 * 2. **Deliberately no independent availability gate** -- same deviation
 *    [IConferenceBreakoutService] KDoc already documents and justifies: whiteboard needs nothing
 *    beyond `ConferenceConfig.enabled` (the LiveKit data channel and the document store both
 *    already exist; there is no new ffmpeg/Egress-shaped external dependency this wave adds). No
 *    `getWhiteboardAvailability()` method.
 * 3. Surface-growth control -- same reasoning as every prior wave's own separate service.
 *
 * ## No new schema this wave -- deliberately, not an oversight
 *
 * Whiteboard state is: (a) LIVE/ephemeral -- a bounded per-JVM-instance in-memory map
 * (`ConferenceWhiteboardState`, mirrors `FederationInboxRateLimiter`'s own `ConcurrentHashMap`
 * idiom and documented single-instance scope-cut), explicitly never persisted; and (b) DURABLE --
 * an ordinary row in the ALREADY-EXISTING `document`/`document_version` tables, created via
 * [saveAsDocument]. No new table, no new access-control model -- it inherits [DocumentAccessLevel]
 * wholesale, exactly like Wave 2's recording-to-document bridge.
 *
 * ## Why clearing is moderator-gated, but drawing/saving are not
 *
 * Drawing is additive -- it never destroys another participant's work, the same low-stakes shape
 * chat already has (open to every current participant). Saving is additive too -- it creates a new
 * document, touches nobody else's state, and is fully repeatable. Clearing ([clearBoard]) is the
 * ONE destructive, irreversible action in this surface -- symmetric with `endRoom`/
 * `removeParticipant`/`recallAll`'s own moderator gate, this module's established "moderator gates
 * disruptive/irreversible actions" pattern.
 *
 * ## Trust asymmetry between commit and clear broadcasts
 *
 * A forged/spoofed `lapis-whiteboard-commit` data-channel message (a participant broadcasting
 * without ever calling [commitStroke]) is low-stakes and self-correcting -- other clients render an
 * extra phantom stroke that silently disappears the next time they re-sync via [getWhiteboardState]
 * (it was never durably committed, does not count toward the cap). By contrast, the server can never
 * itself publish onto the LiveKit data channel (it is not a room participant) -- so [clearBoard]'s
 * real-time propagation to already-connected peers works by having the CALLING client publish a
 * lightweight, best-effort "clear happened, please re-sync" hint over the reliable channel, but
 * receivers must never treat that hint as authoritative: on receipt they always re-fetch via
 * [getWhiteboardState] and replace their local state with whatever the server now says. A forged
 * "clear" hint from a non-moderator therefore does nothing (the moderator-only RPC never ran, so
 * the re-fetch finds nothing changed) -- mirrors [IConferenceBreakoutService] KDoc "Why not a
 * data-channel push", reason 1, applied to the opposite direction (broadcast triggers
 * re-verification, is never itself trusted).
 */
@RpcService
interface IConferenceWhiteboardService {
    /**
     * Role: any caller with a currently OPEN `conference_participation` row for [roomId] (same
     * "actual current participant" gate as [commitStroke]/[clearBoard]/[saveAsDocument] -- stricter
     * than [IConferenceBreakoutService.getMyBreakoutAssignment]'s own "ever participated" gate,
     * which would be wrong here: a whiteboard is live collaboration state, not a historical fact a
     * former participant should still be able to query). Returns every currently-committed stroke
     * for the room, bounded by
     * `network.lapis.cloud.server.conference.ConferenceWhiteboardState`'s own caps. The ONE
     * mechanism a client uses to seed its local model: (a) right after connect resolves (late-
     * joiner seed, mirroring the roster/recording-status "seed once per connect" pattern), and (b)
     * after any reconnect (a fresh session -- the in-memory local model from the previous
     * connection is gone). Deltas after the seed travel over the data channel -- see [commitStroke]
     * KDoc "double-write".
     */
    suspend fun getWhiteboardState(roomId: String): ConferenceWhiteboardStateDto

    /**
     * Role: same "actual current participant" gate as [getWhiteboardState]. Validates [stroke]
     * (point count, coordinate bounds, color allowlist, stroke width bounds), then durably records
     * it into the room's bounded in-memory state, stamping
     * [WhiteboardStrokeDto.authorMemberId]/[WhiteboardStrokeDto.authorDisplayName] from the
     * CALLER'S OWN verified identity -- never from anything client-supplied (there is no author
     * field on [WhiteboardStrokeWireDto] at all). Throws
     * [network.lapis.cloud.shared.rpc.ConflictException] if the room's whiteboard is already at its
     * point/stroke cap.
     *
     * **Double-write, deliberate, not redundant**: the caller's OWN client is expected to ALSO
     * publish this same stroke over the LiveKit RELIABLE data channel (topic
     * `lapis-whiteboard-commit`) for near-instant peer rendering -- this RPC call and that broadcast
     * are independent and BOTH required, because this server never observes LiveKit data-channel
     * traffic at all (no webhook/egress subscribes to it -- data channel is client-to-client only).
     * The RPC call is what makes the stroke durable for [getWhiteboardState] (a later joiner, or
     * this same client after a reconnect); the broadcast is what makes it appear on OTHER
     * currently-connected clients' screens without a server round-trip. A stroke that reaches peers
     * via the broadcast but never lands via this RPC (e.g. the RPC call failed) is a harmless,
     * self-correcting inconsistency -- see class KDoc "Trust asymmetry between commit and clear".
     */
    suspend fun commitStroke(
        roomId: String,
        stroke: WhiteboardStrokeWireDto,
    ): WhiteboardStrokeDto

    /**
     * Role: the room's creator, OR global BOARD/ADMIN -- the SAME `requireModeratorOrPrivileged`
     * gate [IConferenceService.endRoom]/[IConferenceBreakoutService.createBreakoutRooms] use.
     * Deliberately NOT open to every participant (unlike draw/view/save) -- see class KDoc "Why
     * clearing is moderator-gated". Removes ALL of the room's committed strokes from server state --
     * irreversible (no undo), which is exactly why it is moderator-gated per this module's
     * established "moderator gates disruptive/irreversible actions" pattern (`endRoom`,
     * `removeParticipant`, `recallAll`).
     *
     * This is the SOLE authoritative "clear" action -- there is no data-channel-only clear, see
     * class KDoc "Trust asymmetry between commit and clear broadcasts".
     */
    suspend fun clearBoard(roomId: String)

    /**
     * Role: same "actual current participant" gate as [getWhiteboardState]/[commitStroke] --
     * deliberately NOT moderator-gated (see class KDoc "Why clearing is moderator-gated, but
     * drawing/saving are not"). Renders the room's current committed strokes into a flat PNG
     * (`network.lapis.cloud.server.conference.WhiteboardRasterizer`) and archives it into the SAME
     * Document/DocumentVersion store Wave 2's recordings already use (folder `"Whiteboards"`), with
     * caller-chosen [accessLevel] -- same shape as
     * [IConferenceRecordingService.startRecording]'s own `accessLevel` parameter. Throws
     * [network.lapis.cloud.shared.rpc.ConflictException] if the board currently has zero strokes
     * (nothing to save). Never clears the live board as a side effect -- saving and clearing are two
     * independent actions with two independent (and different) authorization gates.
     */
    suspend fun saveAsDocument(
        roomId: String,
        accessLevel: DocumentAccessLevel,
    ): ConferenceWhiteboardSaveResultDto
}
