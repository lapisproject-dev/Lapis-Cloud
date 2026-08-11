package network.lapis.cloud.server.conference

import network.lapis.cloud.shared.domain.NoteBlockDto
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val DEFAULT_MAX_BLOCKS_PER_ROOM = 300

// Server-only, room-wide COUNT cap -- NOT shared with lapis-shared, exact same placement decision
// as ConferenceWhiteboardState's own DEFAULT_MAX_STROKES_PER_ROOM/DEFAULT_MAX_TOTAL_POINTS_PER_ROOM
// (room-wide totals stay server-side; only PER-OBJECT structural bounds, which also gate the
// client's data-channel receive path, live in lapis-shared -- see NOTES_MAX_CONTENT_LENGTH KDoc).
//
// Justification for 300: worst-case room footprint is
// DEFAULT_MAX_BLOCKS_PER_ROOM * NOTES_MAX_CONTENT_LENGTH = 300 * 8,000 chars ~= 2.4M chars
// (~4.8MB as JVM UTF-16 Strings) per room -- a tight, acceptable bound at Kleinsitzung scale (<=25
// participants) that does NOT additionally need a whiteboard-style separate "total content length"
// cap: whiteboard needed that THIRD dimension because a single stroke could carry up to 2,000 points
// each, so (maxStrokesPerRoom * pointsPerStroke) would have reached 10M points without an explicit
// room-wide total; here (blocksPerRoom * contentLengthPerBlock) is ALREADY a tight bound on its own,
// so two dimensions (count + per-object size) suffice, one fewer than whiteboard needed.

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 8 "Geteilte Notizen" -- bounded, per-JVM-instance,
 * in-memory shared-notes state. Same `ConcurrentHashMap` + atomic `compute()` idiom as
 * [ConferenceWhiteboardState], generalized from "strokes keyed by strokeId" to "blocks keyed by
 * blockId", with a per-block VERSION COUNTER driving optimistic concurrency control (see
 * [tryEdit]) -- a mechanism whiteboard never needed because strokes are append-only, never mutated
 * in place.
 *
 * Same documented single-instance scope-cut as [ConferenceWhiteboardState] (a multi-server
 * deployment would need a shared store, e.g. Redis, for consistency across instances -- out of
 * scope, matches this codebase's established posture).
 */
class ConferenceNotesState(
    private val maxBlocksPerRoom: Int = DEFAULT_MAX_BLOCKS_PER_ROOM,
) {
    private data class RoomState(
        val blocks: Map<String, NoteBlockDto>,
    )

    private val stateByRoom = ConcurrentHashMap<Uuid, RoomState>()

    /** Position-sorted (server-side, so clients never need to). Empty for an unknown room, never throws. */
    fun snapshot(roomId: Uuid): List<NoteBlockDto> {
        val blocks = stateByRoom[roomId]?.blocks ?: return emptyList()
        return blocks.values.sortedBy { it.position }
    }

    sealed interface CreateResult {
        /** Covers BOTH freshly-created AND idempotent-duplicate-resubmission (same blockId) -- both return success to the caller, matching [ConferenceWhiteboardState.tryCommit]'s dedup contract. */
        data class Ok(
            val block: NoteBlockDto,
        ) : CreateResult

        data object RoomFull : CreateResult
    }

    /**
     * [block] arrives already author-stamped by the SERVICE layer (`version = 1`) -- this class
     * never touches `CurrentMember`/DB. Atomic: two concurrent creates for the same room can never
     * both slip past the cap; a duplicate `blockId` can never be inserted twice, and on a duplicate
     * the map is left UNCHANGED (the ORIGINAL block wins, mirrors
     * [ConferenceWhiteboardState.tryCommit]).
     */
    fun tryCreate(
        roomId: Uuid,
        block: NoteBlockDto,
    ): CreateResult {
        var result: CreateResult = CreateResult.Ok(block)
        stateByRoom.compute(roomId) { _, existing ->
            val current = existing ?: RoomState(emptyMap())
            val already = current.blocks[block.id]
            when {
                already != null -> {
                    result = CreateResult.Ok(already)
                    current
                }
                current.blocks.size + 1 > maxBlocksPerRoom -> {
                    result = CreateResult.RoomFull
                    current
                }
                else -> RoomState(current.blocks + (block.id to block))
            }
        }
        return result
    }

    sealed interface EditResult {
        data class Accepted(
            val block: NoteBlockDto,
        ) : EditResult

        data class StaleVersion(
            val current: NoteBlockDto,
        ) : EditResult

        data object NotFound : EditResult
    }

    /**
     * The core CAS: builds the updated block via `existingBlock.copy(...)` INTERNALLY (preserving
     * `position` automatically) rather than taking a caller-precomputed [NoteBlockDto] -- avoids the
     * caller having to know/guess the block's current `position` at all. Atomic: version-check and
     * install happen inside the SAME `compute()` lambda, so no two concurrent edits can both pass
     * the `baseVersion` check for the same block.
     */
    fun tryEdit(
        roomId: Uuid,
        blockId: String,
        baseVersion: Int,
        newContent: String,
        editorMemberId: String,
        editorDisplayName: String,
        nowEpochMs: Long,
    ): EditResult {
        var result: EditResult = EditResult.NotFound
        stateByRoom.compute(roomId) { _, existing ->
            val current = existing ?: RoomState(emptyMap())
            val existingBlock = current.blocks[blockId]
            when {
                existingBlock == null -> {
                    result = EditResult.NotFound
                    current
                }
                existingBlock.version != baseVersion -> {
                    result = EditResult.StaleVersion(existingBlock)
                    current
                }
                else -> {
                    val updated =
                        existingBlock.copy(
                            content = newContent,
                            version = existingBlock.version + 1,
                            lastEditedByMemberId = editorMemberId,
                            lastEditedByDisplayName = editorDisplayName,
                            lastEditedAtEpochMs = nowEpochMs,
                        )
                    result = EditResult.Accepted(updated)
                    RoomState(current.blocks + (blockId to updated))
                }
            }
        }
        return result
    }

    enum class DeleteResult { REMOVED, NOT_FOUND, FORBIDDEN }

    /**
     * Authorization AND removal happen in the SAME `compute()` lambda -- genuinely atomic, no
     * TOCTOU window at all between "read the block's current author" and "remove it" (an
     * IMPROVEMENT over the equivalent whiteboard check, which has no such combined authorization
     * step at all since `clearBoard` is a room-level, not per-object, gate; here it costs nothing
     * extra since we're already inside `compute()`).
     *
     * Authorization keys off [NoteBlockDto.lastEditedByMemberId], NOT the block's original author --
     * a deliberate simplification (no extra "original author" field needed): someone who typo-fixes
     * another participant's note gains delete rights over it while the original note-taker loses
     * them. Accepted -- the collaborative-editing model this wave is built around already treats
     * "whoever touched it last" as the block's current owner for every other purpose.
     */
    fun tryDelete(
        roomId: Uuid,
        blockId: String,
        callerMemberId: String,
        callerCanModerate: Boolean,
    ): DeleteResult {
        var result = DeleteResult.NOT_FOUND
        stateByRoom.compute(roomId) { _, existing ->
            val current = existing ?: RoomState(emptyMap())
            val block = current.blocks[blockId]
            when {
                block == null -> {
                    result = DeleteResult.NOT_FOUND
                    current
                }
                block.lastEditedByMemberId != callerMemberId && !callerCanModerate -> {
                    result = DeleteResult.FORBIDDEN
                    current
                }
                else -> {
                    result = DeleteResult.REMOVED
                    RoomState(current.blocks - blockId)
                }
            }
        }
        return result
    }

    /**
     * Called ONLY by `ConferenceService`'s `endRoom`/lazy `reconcileRoomIfDue` (room-end teardown --
     * see that class's own KDoc addition), on BOTH paths, mirroring
     * [ConferenceWhiteboardState.clear]'s own "why BOTH paths" reasoning verbatim: a plain,
     * side-effect-free, thread-safe removal with no DB-transaction/deadlock-ordering discipline to
     * respect, so there is no reason to leave the lazy path unclean. Unlike `clearBoard`, this wave
     * has no mid-meeting moderator "clear everything" action of its own -- [clear] exists purely for
     * teardown. A no-op if [roomId] has no entry.
     */
    fun clear(roomId: Uuid) {
        stateByRoom.remove(roomId)
    }
}
