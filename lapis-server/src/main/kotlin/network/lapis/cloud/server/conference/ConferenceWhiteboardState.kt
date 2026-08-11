package network.lapis.cloud.server.conference

import network.lapis.cloud.shared.domain.WhiteboardStrokeDto
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val DEFAULT_MAX_STROKES_PER_ROOM = 5_000
private const val DEFAULT_MAX_TOTAL_POINTS_PER_ROOM = 50_000

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 7 "Whiteboard" -- bounded, per-JVM-instance, in-memory
 * whiteboard state. Same `ConcurrentHashMap` + atomic `compute()` + captured-var-for-the-decision
 * idiom as [network.lapis.cloud.server.federation.FederationInboxRateLimiter.checkAndRecord], same
 * documented single-instance scope-cut (a multi-server deployment would need a shared store, e.g.
 * Redis, for consistency across instances -- out of scope, matches this codebase's established
 * posture for `LoginRateLimiter`/`FederationInboxRateLimiter`/`FederationReplayGuard`).
 *
 * [maxStrokesPerRoom]/[maxTotalPointsPerRoom] are BOTH enforced (not just total points) -- total
 * points alone does not bound STROKE COUNT (e.g. thousands of single-point dots), and each stroke
 * carries fixed per-object metadata (author/color/tool/timestamp) regardless of point count, so
 * stroke count must be capped independently to bound both memory and [snapshot]'s serialization
 * cost. Whichever cap is hit first rejects the commit with an explicit, actionable error (see
 * `network.lapis.cloud.server.rpc.ConferenceWhiteboardService.commitStroke`) rather than silently
 * evicting/discarding a participant's already-committed work.
 *
 * Appending to the per-room strokes list is `current.strokes + stroke` -- O(n) per commit, i.e.
 * O(n^2) worst case over a room's full lifetime up to the cap. Accepted, deliberate simplicity-
 * over-micro-optimization at "Kleinsitzung" scale (<=25 participants, [maxStrokesPerRoom]'s default
 * keeps the worst case sub-millisecond in practice) -- same posture this codebase already takes
 * elsewhere (e.g. `ConferenceBreakoutService`'s own in-memory map merges).
 */
class ConferenceWhiteboardState(
    private val maxStrokesPerRoom: Int = DEFAULT_MAX_STROKES_PER_ROOM,
    private val maxTotalPointsPerRoom: Int = DEFAULT_MAX_TOTAL_POINTS_PER_ROOM,
) {
    private data class RoomState(
        val strokes: List<WhiteboardStrokeDto>,
        val totalPoints: Int,
    )

    private val stateByRoom = ConcurrentHashMap<Uuid, RoomState>()

    fun snapshot(roomId: Uuid): List<WhiteboardStrokeDto> = stateByRoom[roomId]?.strokes.orEmpty()

    /**
     * `true` iff [stroke] was committed, OR [stroke]'s `strokeId` was ALREADY present in this
     * room's committed strokes -- treated as an idempotent no-op, not a rejection (a client
     * resubmitting the same `commitStroke` call after a flaky response, a client bug, or a
     * deliberately repeated call must not be able to duplicate a stroke, consume the shared
     * per-room cap budget twice for the same drawing action, or pad the archived PNG with a
     * redundant overlapping draw call -- review fix, minor). `false` iff rejected because either
     * cap would be exceeded -- caller (`ConferenceWhiteboardService.commitStroke`) throws
     * `ConflictException` in that case. Atomic: two concurrent commits for the same room can never
     * both slip past the cap, and a duplicate `strokeId` can never be appended twice.
     */
    fun tryCommit(
        roomId: Uuid,
        stroke: WhiteboardStrokeDto,
    ): Boolean {
        var accepted = true
        stateByRoom.compute(roomId) { _, existing ->
            val current = existing ?: RoomState(emptyList(), 0)
            val newTotalPoints = current.totalPoints + stroke.points.size
            when {
                current.strokes.any { it.strokeId == stroke.strokeId } -> current
                current.strokes.size + 1 > maxStrokesPerRoom || newTotalPoints > maxTotalPointsPerRoom -> {
                    accepted = false
                    current
                }
                else -> RoomState(current.strokes + stroke, newTotalPoints)
            }
        }
        return accepted
    }

    /**
     * Used by BOTH `ConferenceWhiteboardService.clearBoard` (mid-meeting, moderator action) AND
     * `ConferenceService`'s `endRoom`/lazy `reconcileRoomIfDue` (room-end teardown -- see that
     * class's own KDoc addition for why BOTH paths call this, unlike breakout/recording's own
     * coordinator objects which only hook `endRoom`: this map is a plain, side-effect-free,
     * thread-safe removal with no DB-transaction/deadlock-ordering discipline to respect, so there
     * is no reason to leave the lazy path unclean). A no-op if [roomId] has no entry.
     */
    fun clear(roomId: Uuid) {
        stateByRoom.remove(roomId)
    }
}
