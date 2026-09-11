package network.lapis.cloud.client.livekit

import network.lapis.cloud.client.conferenceConnectErrorMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Relay-fallback wave (bug report from an ELB board member, 2026-09-10, "in some browsers audio
 * and video do not start at all") -- pure, DOM-free unit coverage for the two top-level helpers
 * [conferenceShouldRetryOverRelay] and [network.lapis.cloud.client.conferenceConnectErrorMessage]
 * this wave extracted specifically so they ARE testable without a real `Room`/WebRTC stack. See
 * [LiveKitRoomSession.connect] KDoc "Relay fallback" for what the actual retry does with these two
 * values -- that wiring itself needs a real browser/WebRTC stack and is verified manually instead
 * (this wave's plan, `deploy/local/README.adoc` "coturn's role is mostly symbolic here").
 *
 * Mirrors [LiveKitRoomSessionDeviceFailureTest]'s own "test the extracted pure function directly,
 * not through `guarded {}`/a real `Room`" discipline.
 */
class ConferenceConnectFallbackTest {
    // ── conferenceShouldRetryOverRelay -- every ConnectionErrorReason (0..7) plus null ──────────

    @Test
    fun shouldRetryOverRelay_internalError_isRetryable() {
        // ConnectionErrorReason.InternalError -- what ensurePCTransportConnection throws on its
        // 15s peerConnectionTimeout, the exact case this whole wave exists for.
        assertTrue(conferenceShouldRetryOverRelay(2))
    }

    @Test
    fun shouldRetryOverRelay_timeout_isRetryable() {
        // ConnectionErrorReason.Timeout
        assertTrue(conferenceShouldRetryOverRelay(5))
    }

    @Test
    fun shouldRetryOverRelay_unreadableReason_isRetryable() {
        // A missed retry costs a still-broken call; a superfluous one costs 15s -- see
        // conferenceShouldRetryOverRelay's own KDoc for why null is treated as retryable.
        assertTrue(conferenceShouldRetryOverRelay(null))
    }

    @Test
    fun shouldRetryOverRelay_notAllowed_isNotRetryable() {
        // ConnectionErrorReason.NotAllowed -- a bad/expired token, relay cannot help.
        assertFalse(conferenceShouldRetryOverRelay(0))
    }

    @Test
    fun shouldRetryOverRelay_serverUnreachable_isNotRetryable() {
        // ConnectionErrorReason.ServerUnreachable -- a signalling-layer failure, ICE is irrelevant.
        assertFalse(conferenceShouldRetryOverRelay(1))
    }

    @Test
    fun shouldRetryOverRelay_cancelled_isNotRetryable() {
        // ConnectionErrorReason.Cancelled -- the caller already ended this attempt.
        assertFalse(conferenceShouldRetryOverRelay(3))
    }

    @Test
    fun shouldRetryOverRelay_leaveRequest_isNotRetryable() {
        // ConnectionErrorReason.LeaveRequest -- the server already ended this attempt.
        assertFalse(conferenceShouldRetryOverRelay(4))
    }

    @Test
    fun shouldRetryOverRelay_webSocket_isNotRetryable() {
        // ConnectionErrorReason.WebSocket -- a signalling-layer failure, ICE is irrelevant.
        assertFalse(conferenceShouldRetryOverRelay(6))
    }

    @Test
    fun shouldRetryOverRelay_serviceNotFound_isNotRetryable() {
        // ConnectionErrorReason.ServiceNotFound -- a signalling-layer failure, ICE is irrelevant.
        assertFalse(conferenceShouldRetryOverRelay(7))
    }

    @Test
    fun shouldRetryOverRelay_unknownPositiveReason_isNotRetryable() {
        // Any numeric reason outside the documented 0..7 range (a future livekit-client version
        // adding a new reason) must NOT silently become retryable -- only the two explicitly listed
        // reasons (2, 5) are, everything else (including unrecognized ones) defaults to false.
        assertFalse(conferenceShouldRetryOverRelay(99))
    }

    // ── ConferenceConnectFailure.entries guard ───────────────────────────────────────────────

    @Test
    fun connectFailure_hasExactlyTwoVariants() {
        // Guards against a later-added ConferenceConnectFailure variant silently missing a branch
        // in conferenceConnectErrorMessage's `when` -- the `when` itself is exhaustive at compile
        // time, but this test documents the intent explicitly, mirroring this wave's own plan (C5).
        assertEquals(2, ConferenceConnectFailure.entries.size)
    }

    // ── network.lapis.cloud.client.conferenceConnectErrorMessage ────────────────────────────

    @Test
    fun connectErrorMessage_relayExhausted_isNonBlank() {
        val message = conferenceConnectErrorMessage(ConferenceConnectFailure.RELAY_EXHAUSTED)
        assertTrue(message.isNotBlank())
    }

    @Test
    fun connectErrorMessage_other_isNonBlank() {
        val message = conferenceConnectErrorMessage(ConferenceConnectFailure.OTHER)
        assertTrue(message.isNotBlank())
    }

    @Test
    fun connectErrorMessage_relayExhausted_differsFromOther() {
        val relayExhausted = conferenceConnectErrorMessage(ConferenceConnectFailure.RELAY_EXHAUSTED)
        val other = conferenceConnectErrorMessage(ConferenceConnectFailure.OTHER)
        assertNotEquals(relayExhausted, other)
    }

    @Test
    fun connectErrorMessage_neverLeaksTechnicalTerms() {
        // SECURITY/UX: neither branch may name a raw WebRTC/LiveKit internal term -- the whole point
        // of this typed failure + translated-message pair is to keep those OUT of what a participant
        // sees (see LiveKitRoomSession KDoc "Relay fallback" -- the log line carries the technical
        // detail instead, never this user-facing string).
        val forbiddenTerms = listOf("ICE", "SDP", "RTCPeerConnection", "ConnectionError", "WebRTC")
        for (failure in ConferenceConnectFailure.entries) {
            val message = conferenceConnectErrorMessage(failure)
            for (term in forbiddenTerms) {
                assertFalse(message.contains(term), "message for $failure unexpectedly contains \"$term\": $message")
            }
        }
    }
}
