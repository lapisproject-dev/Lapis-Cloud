package network.lapis.cloud.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Code review, Welle V1.2.9 round 2 (test-coverage finding): [pspProbe] is the central behavioral
 * change of the "PspProbeResult separates a real transport failure from an honest business-level
 * DTO" fix (see that sealed interface's own KDoc), and had no test at all -- everything downstream
 * ([PspCheckoutSection], [DonationCheckoutScreen]) merely branches on its result. Same
 * "`@Test` cannot be `suspend`, bridge via `GlobalScope.promise`" posture as
 * `livekit/LiveKitRoomSessionDeviceFailureTest`.
 */
class PspProbeTest {
    @Test
    fun pspProbe_successfulBlock_returnsOkWithTheValue() =
        GlobalScope.promise {
            val result = pspProbe { "availability-dto" }
            assertEquals(PspProbeResult.Ok("availability-dto"), result)
        }

    @Test
    fun pspProbe_blockThrows_returnsTransportError() =
        GlobalScope.promise {
            val result =
                pspProbe {
                    throw IllegalStateException("dropped connection")
                }
            assertTrue(result is PspProbeResult.TransportError)
        }

    @Test
    fun pspProbe_blockThrowsCancellationException_propagatesInsteadOfBeingSwallowed() =
        GlobalScope.promise {
            assertFailsWith<CancellationException> {
                pspProbe {
                    throw CancellationException("coroutine scope cancelled")
                }
            }
        }
}
