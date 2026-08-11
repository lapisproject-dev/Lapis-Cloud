package network.lapis.cloud.server.conference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.WhiteboardPointDto
import network.lapis.cloud.shared.domain.WhiteboardStrokeDto
import network.lapis.cloud.shared.domain.WhiteboardTool
import kotlin.uuid.Uuid

private fun testStroke(
    strokeId: String,
    pointCount: Int = 2,
) = WhiteboardStrokeDto(
    strokeId = strokeId,
    authorMemberId = Uuid.random().toString(),
    authorDisplayName = "Test",
    tool = WhiteboardTool.PEN,
    color = "#1a1a1a",
    strokeWidth = 4.0,
    points = List(pointCount) { WhiteboardPointDto(it.toDouble(), it.toDouble()) },
    committedAtEpochMs = 0L,
)

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 7 "Whiteboard" -- proves [ConferenceWhiteboardState] is
 * ACTUALLY capped, not just documented as capped: both dimensions ([maxStrokesPerRoom] and
 * [maxTotalPointsPerRoom]) are exercised INDEPENDENTLY (many small strokes hitting the stroke cap
 * before the point cap, and one large-point stroke hitting the point cap before the stroke cap), per
 * this wave's mandatory test requirement.
 */
class ConferenceWhiteboardStateTest :
    FunSpec({
        test("snapshot: an unknown room returns an empty list, never throws") {
            val state = ConferenceWhiteboardState()
            state.snapshot(Uuid.random()) shouldBe emptyList()
        }

        test("tryCommit: strokes accumulate and are returned by snapshot in commit order") {
            val state = ConferenceWhiteboardState(maxStrokesPerRoom = 10, maxTotalPointsPerRoom = 100)
            val roomId = Uuid.random()
            state.tryCommit(roomId, testStroke("s1")).shouldBeTrue()
            state.tryCommit(roomId, testStroke("s2")).shouldBeTrue()
            state.snapshot(roomId).map { it.strokeId } shouldBe listOf("s1", "s2")
        }

        test("tryCommit: two different rooms have fully independent state") {
            val state = ConferenceWhiteboardState(maxStrokesPerRoom = 1, maxTotalPointsPerRoom = 100)
            val roomA = Uuid.random()
            val roomB = Uuid.random()
            state.tryCommit(roomA, testStroke("a1")).shouldBeTrue()
            // roomA is now at its own stroke cap (1) -- roomB is unaffected.
            state.tryCommit(roomB, testStroke("b1")).shouldBeTrue()
            state.snapshot(roomA).map { it.strokeId } shouldBe listOf("a1")
            state.snapshot(roomB).map { it.strokeId } shouldBe listOf("b1")
        }

        test("tryCommit: the STROKE cap rejects the (N+1)th commit even though the point cap is nowhere near hit -- many small strokes") {
            val state = ConferenceWhiteboardState(maxStrokesPerRoom = 3, maxTotalPointsPerRoom = 1_000)
            val roomId = Uuid.random()
            repeat(3) { i -> state.tryCommit(roomId, testStroke("s$i", pointCount = 2)).shouldBeTrue() }
            // 4th stroke: stroke count would become 4 > cap of 3 -- rejected, even though total points
            // (8) is nowhere near the 1,000 point cap.
            state.tryCommit(roomId, testStroke("s-over", pointCount = 2)).shouldBeFalse()
            state.snapshot(roomId).size shouldBe 3
            state.snapshot(roomId).map { it.strokeId } shouldBe listOf("s0", "s1", "s2")
        }

        test("tryCommit: the POINT cap rejects a single large-point stroke even though the stroke cap is nowhere near hit") {
            val state = ConferenceWhiteboardState(maxStrokesPerRoom = 1_000, maxTotalPointsPerRoom = 50)
            val roomId = Uuid.random()
            state.tryCommit(roomId, testStroke("small", pointCount = 10)).shouldBeTrue()
            // 10 + 45 = 55 > 50 -- rejected, even though stroke count (would become 2) is nowhere
            // near the 1,000 stroke cap.
            state.tryCommit(roomId, testStroke("large", pointCount = 45)).shouldBeFalse()
            state.snapshot(roomId).size shouldBe 1
            state.snapshot(roomId).map { it.strokeId } shouldBe listOf("small")
        }

        test("tryCommit: a rejected commit leaves prior state completely intact (no partial mutation)") {
            val state = ConferenceWhiteboardState(maxStrokesPerRoom = 1, maxTotalPointsPerRoom = 1_000)
            val roomId = Uuid.random()
            state.tryCommit(roomId, testStroke("kept")).shouldBeTrue()
            state.tryCommit(roomId, testStroke("rejected")).shouldBeFalse()
            state.snapshot(roomId).map { it.strokeId } shouldBe listOf("kept")
        }

        test(
            "tryCommit: resubmitting an already-committed strokeId is an idempotent no-op -- accepted, " +
                "not duplicated, does not consume the cap budget again",
        ) {
            val state = ConferenceWhiteboardState(maxStrokesPerRoom = 2, maxTotalPointsPerRoom = 1_000)
            val roomId = Uuid.random()
            state.tryCommit(roomId, testStroke("s1", pointCount = 3)).shouldBeTrue()
            // Same strokeId resubmitted (e.g. a client retry after a flaky response) -- accepted as a
            // no-op, NOT appended a second time and NOT counted against the stroke/point caps again.
            state.tryCommit(roomId, testStroke("s1", pointCount = 3)).shouldBeTrue()
            state.snapshot(roomId).map { it.strokeId } shouldBe listOf("s1")
            state.snapshot(roomId).size shouldBe 1
            // Proof the duplicate did NOT consume the (cap = 2) stroke budget a second time: a genuinely
            // new stroke still fits.
            state.tryCommit(roomId, testStroke("s2")).shouldBeTrue()
            state.snapshot(roomId).map { it.strokeId } shouldBe listOf("s1", "s2")
        }

        test("tryCommit: a duplicate strokeId is a no-op even once the room is already AT its stroke cap") {
            val state = ConferenceWhiteboardState(maxStrokesPerRoom = 1, maxTotalPointsPerRoom = 1_000)
            val roomId = Uuid.random()
            state.tryCommit(roomId, testStroke("s1")).shouldBeTrue()
            // Room is now at its stroke cap (1) -- a resubmission of the SAME strokeId must still
            // succeed as a no-op (it is not "one more stroke"), unlike a genuinely new stroke, which
            // the next test in this file already proves gets rejected at this cap.
            state.tryCommit(roomId, testStroke("s1")).shouldBeTrue()
            state.snapshot(roomId).map { it.strokeId } shouldBe listOf("s1")
        }

        test("clear: removes all state for a room, a no-op for a room with no entry") {
            val state = ConferenceWhiteboardState()
            val roomId = Uuid.random()
            state.tryCommit(roomId, testStroke("s1")).shouldBeTrue()
            state.snapshot(roomId).size shouldBe 1
            state.clear(roomId)
            state.snapshot(roomId) shouldBe emptyList()
            // No-op, does not throw, for a room that was never committed to.
            state.clear(Uuid.random())
        }
    })
