package network.lapis.cloud.client

import kotlinx.coroutines.CancellationException
import network.lapis.cloud.client.livekit.stage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * V1.9.119 -- the pure parts of the noise suppression diagnosis: which stages are final, how a browser error is made fit for a log line (short,
 * one line, no query string), the format of the one console line, and that no device data can get into it.
 */
class NoiseSuppressionDiagnosticsTest {
    @Test
    fun onlyTheStagesThatCannotChange_areNotRetryable() {
        val final = NoiseSuppressionUnavailable.entries.filter { !it.retryable }.toSet()
        assertEquals(
            setOf(
                NoiseSuppressionUnavailable.NO_AUDIO_WORKLET,
                NoiseSuppressionUnavailable.NO_WEB_ASSEMBLY,
                NoiseSuppressionUnavailable.SAMPLE_RATE,
            ),
            final,
        )
        assertEquals(12, NoiseSuppressionUnavailable.entries.size)
    }

    @Test
    fun sanitize_cutsLineBreaksQueryStringsAndLength_andKeepsNullForNothing() {
        val (name, message) =
            sanitizeJsError("Type\nError", "Failed to fetch https://lapis.example/assets/rnnoise.wasm?token=abc123&x=1\nsecond line")
        assertEquals("Type Error", name)
        assertEquals("Failed to fetch https://lapis.example/assets/rnnoise.wasm second line", message)
        assertFalse(message!!.contains("token"))
        assertFalse(message.contains("abc123"))
        assertFalse(message.contains("\n"))
        val (longName, longMessage) = sanitizeJsError("n".repeat(200), "m".repeat(500))
        assertEquals(60, longName!!.length)
        assertEquals(160, longMessage!!.length)
        assertEquals(null to null, sanitizeJsError(null, ""))
        assertEquals(null to null, sanitizeJsError(42, undefined))
        assertEquals(null to null, sanitizeJsError("   ", "\n\n"))
    }

    @Test
    fun failureOf_readsNameAndMessageOfTheCause_andAnOwnMessageWins() {
        val cause = IllegalStateException("boom")
        val plain = failureOf(NoiseSuppressionUnavailable.NODE_CREATE, cause)
        assertEquals(NoiseSuppressionUnavailable.NODE_CREATE, plain.reason)
        assertEquals("boom", plain.errorMessage)
        assertTrue(plain.errorName.orEmpty().isNotEmpty())
        assertEquals("rate=44100", failureOf(NoiseSuppressionUnavailable.SAMPLE_RATE, null, "rate=44100").errorMessage)
        assertEquals("own", failureOf(NoiseSuppressionUnavailable.NODE_CREATE, cause, "own").errorMessage)
        val none = failureOf(NoiseSuppressionUnavailable.TIMEOUT)
        assertNull(none.errorName)
        assertNull(none.errorMessage)
    }

    @Test
    fun theLogLine_hasAllFieldsInOrder_withDashForUnknown() {
        val line =
            noiseSuppressionLogLine(
                NoiseSuppressionFailure(NoiseSuppressionUnavailable.CONTEXT_NOT_RUNNING, "InvalidStateError", "state=suspended"),
                NoiseSuppressionDiagnostics("auto", "suspended", 48000, 44100, 2),
            )
        assertEquals(
            "[noise-suppression] stage=CONTEXT_NOT_RUNNING trigger=auto error=InvalidStateError message=state=suspended " +
                "ctxState=suspended ctxRate=48000 trackRate=44100 trackChannels=2",
            line,
        )
        val unknown =
            noiseSuppressionLogLine(NoiseSuppressionFailure(NoiseSuppressionUnavailable.TIMEOUT), NoiseSuppressionDiagnostics("click"))
        assertEquals(
            "[noise-suppression] stage=TIMEOUT trigger=click error=- message=- ctxState=- ctxRate=- trackRate=- trackChannels=-",
            unknown,
        )
    }

    @Test
    fun aQueryInABrowserMessage_neverReachesTheLogLine() {
        val failure = failureOf(NoiseSuppressionUnavailable.WASM_FETCH, null, "Failed to fetch /assets/rnnoise.wasm?token=abc")
        val line = noiseSuppressionLogLine(failure, NoiseSuppressionDiagnostics("auto"))
        assertFalse(line.contains("token"))
        assertFalse(line.contains("abc"))
    }

    @Test
    fun stage_wrapsOtherErrors_keepsAStageException_andPassesACancellationOn() {
        val wrapped =
            assertFailsWith<NoiseSuppressionStageException> {
                stage(NoiseSuppressionUnavailable.NODE_CREATE) { error("x") }
            }
        assertEquals(NoiseSuppressionUnavailable.NODE_CREATE, wrapped.failure.reason)
        assertEquals("x", wrapped.failure.errorMessage)
        val own = NoiseSuppressionStageException(NoiseSuppressionFailure(NoiseSuppressionUnavailable.WASM_FETCH, null, "status=404"))
        val same = assertFailsWith<NoiseSuppressionStageException> { stage(NoiseSuppressionUnavailable.NODE_CREATE) { throw own } }
        assertSame(own, same)
        assertFailsWith<CancellationException> { stage(NoiseSuppressionUnavailable.NODE_CREATE) { throw CancellationException("c") } }
    }
}
