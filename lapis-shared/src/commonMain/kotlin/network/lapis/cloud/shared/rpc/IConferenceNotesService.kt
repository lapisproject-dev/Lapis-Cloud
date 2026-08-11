package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.ConferenceNotesSaveResultDto
import network.lapis.cloud.shared.domain.ConferenceNotesStateDto
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.NoteBlockCommitResultDto
import network.lapis.cloud.shared.domain.NoteBlockCreateWireDto
import network.lapis.cloud.shared.domain.NoteBlockDto
import network.lapis.cloud.shared.domain.NoteBlockEditWireDto

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 8 "Geteilte Notizen" -- the THIRD and FINAL of the
 * three previously-deferred Videokonferenzen domains (Breakout-Räume and Whiteboard already merged
 * in Waves 6/7). Participants in the main conference room can collaboratively build a shared,
 * block-structured notes document during the meeting. A SIXTH, separate conference RPC service (not
 * folded into [IConferenceService]), justified on the same three axes [IConferenceWhiteboardService]
 * KDoc uses for its own separateness:
 *
 * 1. Distinct collaborators -- `network.lapis.cloud.server.conference.ConferenceNotesState` (new,
 *    bounded, in-memory) and the document-storage archiving helpers -- [IConferenceService] has no
 *    use for either.
 * 2. **Deliberately no independent availability gate** -- same deviation [IConferenceWhiteboardService]
 *    KDoc already documents and justifies: notes need nothing beyond [network.lapis.cloud.server
 *    .conference.ConferenceConfig.enabled] (the LiveKit data channel and the document store both
 *    already exist).
 * 3. Surface-growth control -- same reasoning as every prior wave's own separate service.
 *
 * ## Why not real-time collaborative text (OT/CRDT)
 *
 * No CRDT/OT library exists in this codebase's dependency graph, and no good JVM-resolvable one
 * exists for real-time collaborative text (Automerge/Yjs have no serious JVM port). Writing a
 * custom OT/CRDT algorithm from scratch would be a multi-week research-grade undertaking, wildly
 * disproportionate to every other wave's engineering scope in this codebase. Every wave in this
 * module is explicitly scoped to "Kleinsitzung" (<=25 participants) -- at this scale true
 * character-level OT/CRDT (built for hundreds of anonymous, network-partitioned editors) is not
 * justified. Instead: the document is a set of independent BLOCKS (paragraphs/agenda items), each
 * with its own `version` counter, and a stale commit is REJECTED (never silently overwritten) --
 * this collapses collision probability versus whole-document last-write-wins, because two
 * participants rarely edit the exact same block at the exact same moment.
 *
 * ## No new schema this wave -- deliberately, not an oversight
 *
 * Notes state is: (a) LIVE/ephemeral -- a bounded per-JVM-instance in-memory map
 * (`ConferenceNotesState`, mirrors `ConferenceWhiteboardState`'s own `ConcurrentHashMap` idiom and
 * documented single-instance scope-cut), explicitly never persisted; and (b) DURABLE -- an ordinary
 * row in the ALREADY-EXISTING `document`/`document_version` tables, created via [saveAsDocument]. No
 * new table, no new access-control model -- it inherits [DocumentAccessLevel] wholesale, exactly
 * like Whiteboard's own document bridge.
 *
 * ## Why block deletion is own-last-editor-or-moderator gated, but create/edit are not
 *
 * Adding a block and editing any existing block are open to every current participant -- this
 * module's collaborative spirit, shared notes belong to the meeting, not to one author. Deletion is
 * different: a note block can hold up to 8,000 characters of composed prose that is materially
 * harder to reconstruct from memory than a whiteboard stroke, and this module's established
 * "moderator gates disruptive/irreversible actions" pattern (`endRoom`, `clearBoard`,
 * `removeParticipant`) would make blanket-open deletion an outlier. [deleteBlock] is therefore
 * gated to the block's own current `lastEditedByMemberId` OR a moderator/privileged caller -- a
 * genuinely novel combination of two precedents already in this codebase (`MemberService`'s own-
 * resource-or-privileged pattern; Whiteboard's moderator-or-privileged pattern for `clearBoard`),
 * not invented from nothing.
 *
 * ## Real-time propagation
 *
 * Reuses LiveKit's data channel, RELIABLE, topic `lapis-notes-commit` -- a successfully committed
 * block edit is broadcast so other open panels update live without polling. Unlike Whiteboard there
 * is no separate unreliable "preview" topic -- text editing at Kleinsitzung scale does not need
 * live keystroke-by-keystroke streaming; a block is edited locally (a plain textarea) and committed
 * as one explicit action (a per-row "Speichern" button, never auto-commit on blur), a fundamentally
 * lower-frequency event than continuous pointer movement. [deleteBlock] is likewise NOT broadcast --
 * mirrors [IConferenceWhiteboardService.clearBoard]'s own ACTUAL (not aspirationally-documented)
 * behavior: no data-channel push at all, every other already-open panel catches up the next time it
 * re-fetches [getNotesState] (panel reopen/reconnect) -- a single-block removal's blast radius is
 * lower than a whole-board clear, so the same disclosed V1 scope cut applies with even less risk.
 */
@RpcService
interface IConferenceNotesService {
    /**
     * Role: any caller with a currently OPEN `conference_participation` row for [roomId] (same
     * "actual current participant" gate as [createBlock]/[commitBlockEdit]/[deleteBlock]/
     * [saveAsDocument] -- stricter than [IConferenceBreakoutService.getMyBreakoutAssignment]'s own
     * "ever participated" gate, which would be wrong here: shared notes are live collaboration
     * state, not a historical fact a former participant should still be able to query). Returns
     * every currently-committed block for the room, position-sorted, bounded by
     * `network.lapis.cloud.server.conference.ConferenceNotesState`'s own caps. The ONE mechanism a
     * client uses to seed its local model: (a) right after connect resolves (late-joiner seed), and
     * (b) after any reconnect. Deltas after the seed travel over the data channel -- see
     * [commitBlockEdit] KDoc.
     */
    suspend fun getNotesState(roomId: String): ConferenceNotesStateDto

    /**
     * Role: same "actual current participant" gate as [getNotesState]. Validates [block] (content
     * length, blockId shape), then durably records it into the room's bounded in-memory state,
     * stamping [NoteBlockDto.lastEditedByMemberId]/[NoteBlockDto.lastEditedByDisplayName] from the
     * CALLER'S OWN verified identity -- never from anything client-supplied (there is no author
     * field on [NoteBlockCreateWireDto] at all). Throws
     * [network.lapis.cloud.shared.rpc.ConflictException] if the room's notes are already at their
     * block-count cap. Idempotent on [NoteBlockCreateWireDto.blockId] resubmission (mirrors
     * `ConferenceWhiteboardState.tryCommit`'s own `strokeId` dedup) -- returns the ORIGINAL block
     * unchanged on a resubmitted id, not the resubmitted content.
     */
    suspend fun createBlock(
        roomId: String,
        block: NoteBlockCreateWireDto,
    ): NoteBlockDto

    /**
     * Role: same "actual current participant" gate as [getNotesState] -- open to EVERY current
     * participant, not just the block's own author (see class KDoc "Why block deletion is
     * own-last-editor-or-moderator gated, but create/edit are not").
     *
     * ## Conflict semantics
     *
     * Does NOT throw on a stale [NoteBlockEditWireDto.baseVersion] -- deliberate protocol departure
     * from `IConferenceWhiteboardService.commitStroke`. A version conflict here is a ROUTINE,
     * EXPECTED outcome of the concurrency model this wave is built around (two participants editing
     * nearby text), not a rare terminal error -- and the client needs an entire second DTO's worth
     * of information back (the block's current content+version) to let the participant reconcile,
     * which a message-only `ConflictException` (`AbstractServiceException` has no structured-
     * payload field) cannot carry safely (embedding arbitrary user content in a delimited exception
     * message is fragile/unsafe). See [NoteBlockCommitResultDto] KDoc.
     */
    suspend fun commitBlockEdit(
        roomId: String,
        edit: NoteBlockEditWireDto,
    ): NoteBlockCommitResultDto

    /**
     * Role: caller is the block's own current `lastEditedByMemberId`, OR moderator/privileged -- see
     * class KDoc "Why block deletion is own-last-editor-or-moderator gated, but create/edit are
     * not". Idempotent: deleting an already-gone `blockId` is a silent success (mirrors
     * `ConferenceWhiteboardState.clear`'s "no-op on unknown room" posture), preventing a race
     * between two participants deleting the same block from both erroring.
     */
    suspend fun deleteBlock(
        roomId: String,
        blockId: String,
    )

    /**
     * Role: same "actual current participant" gate as [getNotesState] -- deliberately NOT
     * moderator-gated, same reasoning [IConferenceWhiteboardService.saveAsDocument] KDoc already
     * gives for its own save action: additive and repeatable, so it needs no destructive-action
     * gate. Renders the room's current committed blocks (position-sorted, each with its own last-
     * author attribution) into a formatted Markdown document and archives it into the SAME
     * Document/DocumentVersion store Whiteboard's own [IConferenceWhiteboardService.saveAsDocument]
     * uses (folder `"Notizen"`), with caller-chosen [accessLevel]. Throws
     * [network.lapis.cloud.shared.rpc.ConflictException] if there are currently zero blocks
     * (nothing to save). Never clears the live notes as a side effect -- saving and deleting are two
     * independent actions with two independent (and different) authorization gates.
     */
    suspend fun saveAsDocument(
        roomId: String,
        accessLevel: DocumentAccessLevel,
    ): ConferenceNotesSaveResultDto
}
