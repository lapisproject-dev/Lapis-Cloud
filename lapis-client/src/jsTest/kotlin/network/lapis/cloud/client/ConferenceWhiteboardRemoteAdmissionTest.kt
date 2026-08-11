package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Security-audit fix (V1.0 Videokonferenzen Wave 7 "Whiteboard") -- covers
 * [canAdmitRemoteWhiteboardStroke], the pure predicate behind
 * [ConferenceWhiteboardController.applyPreview]/[ConferenceWhiteboardController.applyCommit]'s
 * remote-stroke admission cap. Same DOM-free unit-test posture as [ConferenceGridLayoutTest] -- no
 * rendering harness exists in this module, so the function is extracted `internal` specifically to
 * be testable this way (see that class's own KDoc).
 *
 * Tamper cases mirror the audit finding's own described attack: a peer publishing an unbounded
 * NUMBER of individually-small, individually-valid strokes under fresh strokeIds to grow the
 * receiving client's `committed`/`preview` maps without limit.
 */
class ConferenceWhiteboardRemoteAdmissionTest {
    @Test
    fun emptyRoom_admitsANewStroke() {
        assertTrue(
            canAdmitRemoteWhiteboardStroke(
                isAlreadyKnown = false,
                currentStrokeCount = 0,
                currentPointCount = 0,
                incomingPointCount = 10,
            ),
        )
    }

    @Test
    fun alreadyKnownStrokeId_isAlwaysAdmitted_regardlessOfCurrentCounts_theUpdateNeverCountsTwice() {
        assertTrue(
            canAdmitRemoteWhiteboardStroke(
                isAlreadyKnown = true,
                currentStrokeCount = CLIENT_MAX_STROKES_PER_ROOM,
                currentPointCount = CLIENT_MAX_TOTAL_POINTS_PER_ROOM,
                incomingPointCount = 1_000_000,
            ),
        )
    }

    @Test
    fun newStrokeId_atStrokeCountCap_isRejected_theRepeatedFreshStrokeIdAttackTheAuditDescribed() {
        assertFalse(
            canAdmitRemoteWhiteboardStroke(
                isAlreadyKnown = false,
                currentStrokeCount = CLIENT_MAX_STROKES_PER_ROOM,
                currentPointCount = 0,
                incomingPointCount = 1,
            ),
        )
    }

    @Test
    fun newStrokeId_justBelowStrokeCountCap_isAdmitted() {
        assertTrue(
            canAdmitRemoteWhiteboardStroke(
                isAlreadyKnown = false,
                currentStrokeCount = CLIENT_MAX_STROKES_PER_ROOM - 1,
                currentPointCount = 0,
                incomingPointCount = 1,
            ),
        )
    }

    @Test
    fun newStrokeId_wouldExceedTotalPointCap_isRejected() {
        assertFalse(
            canAdmitRemoteWhiteboardStroke(
                isAlreadyKnown = false,
                currentStrokeCount = 0,
                currentPointCount = CLIENT_MAX_TOTAL_POINTS_PER_ROOM - 5,
                incomingPointCount = 10,
            ),
        )
    }

    @Test
    fun newStrokeId_exactlyAtTotalPointCap_isAdmitted() {
        assertTrue(
            canAdmitRemoteWhiteboardStroke(
                isAlreadyKnown = false,
                currentStrokeCount = 0,
                currentPointCount = CLIENT_MAX_TOTAL_POINTS_PER_ROOM - 10,
                incomingPointCount = 10,
            ),
        )
    }
}

/**
 * Second security-audit fix (V1.0 Videokonferenzen Wave 7 "Whiteboard") -- covers
 * [canAcceptWhiteboardStrokeAuthor], the pure predicate behind
 * [ConferenceWhiteboardController.applyPreview]/[ConferenceWhiteboardController.applyCommit]'s
 * per-strokeId author-binding guard. Same DOM-free unit-test posture as
 * [ConferenceWhiteboardRemoteAdmissionTest] above.
 *
 * Tamper case mirrors the audit finding's own described attack: a participant republishing an
 * observed strokeId under a DIFFERENT author identity, attempting to overwrite another
 * participant's rendered stroke content (targeted defacement without ever calling the
 * moderator-gated `clearBoard` RPC).
 */
class ConferenceWhiteboardStrokeAuthorTest {
    @Test
    fun neverSeenBeforeStrokeId_admitsWhicheverAuthorArrivesFirst() {
        assertTrue(canAcceptWhiteboardStrokeAuthor(recordedAuthorMemberId = null, incomingAuthorMemberId = "member-a"))
    }

    @Test
    fun sameAuthorRepublishingTheirOwnStrokeId_isAccepted_theOrdinaryUpdateCase() {
        assertTrue(canAcceptWhiteboardStrokeAuthor(recordedAuthorMemberId = "member-a", incomingAuthorMemberId = "member-a"))
    }

    @Test
    fun differentAuthorReusingAnObservedStrokeId_isRejected_theDefacementAttackTheAuditDescribed() {
        assertFalse(canAcceptWhiteboardStrokeAuthor(recordedAuthorMemberId = "member-a", incomingAuthorMemberId = "member-b"))
    }
}
