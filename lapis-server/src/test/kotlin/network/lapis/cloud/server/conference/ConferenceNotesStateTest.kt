package network.lapis.cloud.server.conference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.NoteBlockDto
import kotlin.uuid.Uuid

private fun testBlock(
    id: String,
    content: String = "content",
    position: Int = 1,
    version: Int = 1,
    editorMemberId: String = Uuid.random().toString(),
) = NoteBlockDto(
    id = id,
    content = content,
    position = position,
    version = version,
    lastEditedByMemberId = editorMemberId,
    lastEditedByDisplayName = "Test",
    lastEditedAtEpochMs = 0L,
)

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 8 "Geteilte Notizen" -- proves [ConferenceNotesState] is
 * ACTUALLY capped and its version-based concurrency control ACTUALLY rejects stale/unauthorized
 * mutations, not just documented as such. Mirrors [ConferenceWhiteboardStateTest]'s own structure.
 */
class ConferenceNotesStateTest :
    FunSpec({
        test("snapshot: an unknown room returns an empty list, never throws") {
            val state = ConferenceNotesState()
            state.snapshot(Uuid.random()) shouldBe emptyList()
        }

        test("tryCreate: blocks accumulate and are returned by snapshot in POSITION order, not insertion order") {
            val state = ConferenceNotesState(maxBlocksPerRoom = 10)
            val roomId = Uuid.random()
            state.tryCreate(roomId, testBlock("b2", position = 2))
            state.tryCreate(roomId, testBlock("b1", position = 1))
            state.snapshot(roomId).map { it.id } shouldBe listOf("b1", "b2")
        }

        test("tryCreate: two different rooms have fully independent state") {
            val state = ConferenceNotesState(maxBlocksPerRoom = 1)
            val roomA = Uuid.random()
            val roomB = Uuid.random()
            state.tryCreate(roomA, testBlock("a1"))
            // roomA is now at its own block cap (1) -- roomB is unaffected.
            state.tryCreate(roomB, testBlock("b1"))
            state.snapshot(roomA).map { it.id } shouldBe listOf("a1")
            state.snapshot(roomB).map { it.id } shouldBe listOf("b1")
        }

        test("tryCreate: the (N+1)th create is rejected once the room is at its block-count cap") {
            val state = ConferenceNotesState(maxBlocksPerRoom = 3)
            val roomId = Uuid.random()
            repeat(3) { i ->
                val block = testBlock("b$i", position = i)
                state.tryCreate(roomId, block) shouldBe ConferenceNotesState.CreateResult.Ok(block)
            }
            state.tryCreate(roomId, testBlock("b-over", position = 99)) shouldBe ConferenceNotesState.CreateResult.RoomFull
            state.snapshot(roomId).size shouldBe 3
        }

        test(
            "tryCreate: resubmitting an already-created blockId is an idempotent no-op -- returns the ORIGINAL " +
                "block unchanged, not the resubmitted content, and does not consume the cap budget again",
        ) {
            val state = ConferenceNotesState(maxBlocksPerRoom = 2)
            val roomId = Uuid.random()
            val original = testBlock("b1", content = "original", position = 1)
            state.tryCreate(roomId, original)
            val resubmit = testBlock("b1", content = "attacker-resubmitted-content", position = 1)
            val result = state.tryCreate(roomId, resubmit)
            (result as ConferenceNotesState.CreateResult.Ok).block.content shouldBe "original"
            state.snapshot(roomId).size shouldBe 1
            // Proof the duplicate did NOT consume the (cap = 2) block budget a second time.
            val second = testBlock("b2", position = 2)
            state.tryCreate(roomId, second) shouldBe ConferenceNotesState.CreateResult.Ok(second)
            state.snapshot(roomId).size shouldBe 2
        }

        test("tryEdit: an accepted edit updates content/version/lastEditedBy*, preserving position") {
            val state = ConferenceNotesState()
            val roomId = Uuid.random()
            state.tryCreate(roomId, testBlock("b1", content = "v1", position = 5, version = 1))
            val result = state.tryEdit(roomId, "b1", baseVersion = 1, "v2", "editor-2", "Editor Two", 1000L)
            val accepted = (result as ConferenceNotesState.EditResult.Accepted).block
            accepted.content shouldBe "v2"
            accepted.version shouldBe 2
            accepted.position shouldBe 5
            accepted.lastEditedByMemberId shouldBe "editor-2"
            accepted.lastEditedByDisplayName shouldBe "Editor Two"
            accepted.lastEditedAtEpochMs shouldBe 1000L
            state.snapshot(roomId).single().content shouldBe "v2"
        }

        test(
            "tamper: tryEdit with a STALE baseVersion is rejected, returning the block's CURRENT (untouched) " +
                "content -- proves no partial mutation happened",
        ) {
            val state = ConferenceNotesState()
            val roomId = Uuid.random()
            state.tryCreate(roomId, testBlock("b1", content = "original", version = 1))
            val result = state.tryEdit(roomId, "b1", baseVersion = 99, "attacker-content", "attacker", "Attacker", 0L)
            val stale = (result as ConferenceNotesState.EditResult.StaleVersion).current
            stale.content shouldBe "original"
            stale.version shouldBe 1
            // State genuinely unchanged, not just the returned value.
            state.snapshot(roomId).single().content shouldBe "original"
            state.snapshot(roomId).single().version shouldBe 1
        }

        test("tryEdit: unknown blockId returns NotFound") {
            val state = ConferenceNotesState()
            val roomId = Uuid.random()
            state.tryEdit(roomId, "does-not-exist", 1, "x", "editor", "Editor", 0L) shouldBe ConferenceNotesState.EditResult.NotFound
        }

        test("tryDelete: the block's own current last-editor can delete it") {
            val state = ConferenceNotesState()
            val roomId = Uuid.random()
            state.tryCreate(roomId, testBlock("b1", editorMemberId = "author-1"))
            state.tryDelete(roomId, "b1", "author-1", callerCanModerate = false) shouldBe ConferenceNotesState.DeleteResult.REMOVED
            state.snapshot(roomId) shouldBe emptyList()
        }

        test("tryDelete: a moderator (not the block's own author) can delete it") {
            val state = ConferenceNotesState()
            val roomId = Uuid.random()
            state.tryCreate(roomId, testBlock("b1", editorMemberId = "author-1"))
            state.tryDelete(roomId, "b1", "moderator-1", callerCanModerate = true) shouldBe ConferenceNotesState.DeleteResult.REMOVED
            state.snapshot(roomId) shouldBe emptyList()
        }

        test(
            "tamper: a non-author, non-moderator caller cannot delete a block -- FORBIDDEN, block still present afterward",
        ) {
            val state = ConferenceNotesState()
            val roomId = Uuid.random()
            state.tryCreate(roomId, testBlock("b1", editorMemberId = "author-1"))
            state.tryDelete(roomId, "b1", "outsider-1", callerCanModerate = false) shouldBe ConferenceNotesState.DeleteResult.FORBIDDEN
            state.snapshot(roomId).map { it.id } shouldBe listOf("b1")
        }

        test("tryDelete: an unknown blockId is idempotently NOT_FOUND, never throws") {
            val state = ConferenceNotesState()
            val roomId = Uuid.random()
            val result = state.tryDelete(roomId, "does-not-exist", "someone", callerCanModerate = true)
            result shouldBe ConferenceNotesState.DeleteResult.NOT_FOUND
        }

        test("clear: removes all state for a room, a no-op for a room with no entry") {
            val state = ConferenceNotesState()
            val roomId = Uuid.random()
            state.tryCreate(roomId, testBlock("b1"))
            state.snapshot(roomId).size shouldBe 1
            state.clear(roomId)
            state.snapshot(roomId) shouldBe emptyList()
            // No-op, does not throw, for a room that was never created into.
            state.clear(Uuid.random())
        }
    })
