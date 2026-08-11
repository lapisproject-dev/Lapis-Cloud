package network.lapis.cloud.shared.domain

import kotlinx.serialization.Serializable

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 8 "Geteilte Notizen" -- a shared, block-structured
 * notes document participants in the main conference room build together during a meeting. See
 * [network.lapis.cloud.shared.rpc.IConferenceNotesService] KDoc for the full authorization matrix
 * and design decisions. This file holds the wire shapes only.
 *
 * Concurrency model (deliberately NOT character-level OT/CRDT -- see that interface KDoc "Why not
 * real-time collaborative text"): the shared document is a set of independent BLOCKS (paragraphs/
 * agenda items), each with its own `version` counter. A stale commit is REJECTED, never silently
 * overwritten -- see [NoteBlockCommitResultDto].
 *
 * Server-persisted shape, also the client's local rendering model (no separate Renderable* type
 * needed -- content is plain text, nothing to unify away).
 */
@Serializable
data class NoteBlockDto(
    val id: String,
    val content: String,
    val position: Int,
    val version: Int,
    val lastEditedByMemberId: String,
    val lastEditedByDisplayName: String,
    val lastEditedAtEpochMs: Long,
)

/**
 * Client-generated [blockId] (mirrors [WhiteboardStrokeWireDto.strokeId]) -- lets a flaky-response
 * retry be idempotent, see [network.lapis.cloud.shared.rpc.IConferenceNotesService.createBlock].
 * No author field -- structural trust-boundary guarantee, mirrors [WhiteboardStrokeWireDto].
 */
@Serializable
data class NoteBlockCreateWireDto(
    val blockId: String,
    val content: String,
    val position: Int,
)

/** No author field either -- see [NoteBlockCreateWireDto] KDoc. [baseVersion] drives the atomic compare-and-swap in `ConferenceNotesState.tryEdit`. */
@Serializable
data class NoteBlockEditWireDto(
    val blockId: String,
    val content: String,
    val baseVersion: Int,
)

/**
 * Deliberately NOT a thrown exception on a stale [baseVersion] -- see
 * [network.lapis.cloud.shared.rpc.IConferenceNotesService.commitBlockEdit] KDoc "Conflict
 * semantics" for why. [block] is non-null in every case EXCEPT `accepted == false && block ==
 * null`, which means the block was deleted (by someone else, or never existed) -- the client must
 * drop it locally, not show a reconciliation UI for it.
 */
@Serializable
data class NoteBlockCommitResultDto(
    val accepted: Boolean,
    val block: NoteBlockDto?,
)

@Serializable
data class ConferenceNotesStateDto(
    val blocks: List<NoteBlockDto>,
)

@Serializable
data class ConferenceNotesSaveResultDto(
    val documentId: String,
)

/**
 * LiveKit data-channel broadcast payload, topic `lapis-notes-commit` -- see
 * [network.lapis.cloud.shared.rpc.IConferenceNotesService] KDoc "Real-time propagation". NO author
 * field (mirrors [NoteBlockCreateWireDto]/[NoteBlockEditWireDto], same structural guarantee) and NO
 * `lastEditedAt` field (cosmetic only on the live-preview path; the receiving client stamps its own
 * receipt time, corrected on the next full [ConferenceNotesStateDto] resync).
 */
@Serializable
data class NoteBlockBroadcastDto(
    val blockId: String,
    val content: String,
    val position: Int,
    val version: Int,
)

/**
 * Per-block structural bound -- shared because [isStructurallyValid] gates BOTH
 * `ConferenceNotesService` (server RPC) and `LiveKitRoomSession`'s data-channel receive path,
 * exact same reasoning as [WHITEBOARD_MAX_POINTS_PER_STROKE]'s own KDoc. Chosen larger than a
 * whiteboard stroke's metadata footprint (this is prose, not point coordinates) but still bounded:
 * 8,000 chars is comfortably enough for a meeting-notes paragraph/agenda item (roughly 1,200-1,500
 * words) while keeping a single block's footprint small.
 */
const val NOTES_MAX_CONTENT_LENGTH = 8_000
const val NOTES_MAX_BLOCK_ID_LENGTH = 100

/**
 * True iff [this] is well-formed enough to store/render safely -- mirrors
 * [WhiteboardStrokeWireDto.isStructurallyValid]'s role exactly. Deliberately does NOT bound
 * [NoteBlockBroadcastDto.position] -- unlike a whiteboard point's canvas coordinates (which feed
 * directly into Canvas2D drawing math), position is purely a sort key with a fixed 4-byte cost
 * regardless of value; a garbage position is cosmetic (wrong sort order), self-correcting, never a
 * safety/DoS concern.
 */
fun NoteBlockBroadcastDto.isStructurallyValid(): Boolean {
    if (blockId.isBlank() || blockId.length > NOTES_MAX_BLOCK_ID_LENGTH) return false
    if (content.isBlank() || content.length > NOTES_MAX_CONTENT_LENGTH) return false
    if (version < 1) return false
    return true
}
