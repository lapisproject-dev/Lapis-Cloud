package network.lapis.cloud.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise
import network.lapis.cloud.shared.rpc.IOpenItemService
import network.lapis.cloud.shared.rpc.ProbeCodedConflictException
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

private const val PROBE_FQCN = "network.lapis.cloud.shared.rpc.ProbeCodedConflictException"

/**
 * V1.9.17: client side of the proof whether a NON-message field of an exception (a `code`) crosses the Kilua RPC wire -- the real
 * client decoding path (`CallAgent.jsonRpcCall`: `decodeFromString<AbstractServiceException>(exceptionJson)`, see [serviceExceptionResult])
 * with an exception JSON that carries `code`, exactly the shape the server test `CodedExceptionWireTest` shows the server emits.
 *
 * Findings are pinned as assertions on the actual behaviour: the TYPE arrives (known since V1.2.12), and the `code` field arrives too.
 * The message is pinned as it is today (see [theMessageOfTheProbe_isPinnedAsItIsToday]).
 *
 * What this does NOT prove: a real service method throwing the probe over the generated `/rpc/route...` of the full server (no production
 * service throws it, by design). It proves both halves of the codec -- server-side encoding and client-side decoding -- separately.
 */
class RpcExceptionCodeWireTest {
    private fun test(block: suspend () -> Unit): Promise<Unit> = CoroutineScope(SupervisorJob()).promise { block() }

    private fun probeResponse(
        id: Int,
        code: String,
        message: String,
    ): StubResponse {
        val exceptionJson = js("({})")
        exceptionJson.type = PROBE_FQCN
        exceptionJson.message = message
        exceptionJson.code = code
        val body = js("({})")
        body.id = id
        body.result = null
        body.error = "simulated for a test"
        body.exceptionType = null
        body.exceptionJson = JSON.stringify(exceptionJson)
        return StubResponse(text = JSON.stringify(body))
    }

    private suspend fun callAndCatch(
        code: String,
        message: String,
    ): ProbeCodedConflictException? {
        var caught: ProbeCodedConflictException? = null
        withFetchStub(respond = { request ->
            if (request.isRpc) probeResponse(request.json.id as Int, code, message) else StubResponse()
        }) {
            try {
                rpcService<IOpenItemService>().getOpenItem("x")
            } catch (e: ProbeCodedConflictException) {
                caught = e
            }
        }
        return caught
    }

    @Test
    fun theTypeOfACodedException_arrivesAsItsOwnType(): Promise<Unit> =
        test {
            assertNotNull(callAndCatch("IBAN_TAKEN", "m"), "the probe arrives as ProbeCodedConflictException, not as a generic exception")
        }

    @Test
    fun aNonMessageField_theCode_arrives(): Promise<Unit> =
        test {
            val caught = callAndCatch("IBAN_TAKEN", "m")
            assertNotNull(caught)
            assertEquals("IBAN_TAKEN", caught.code, "a constructor field other than the message crosses the wire")
        }

    @Test
    fun theMessageOfTheProbe_isPinnedAsItIsToday(): Promise<Unit> =
        test {
            val caught = callAndCatch("X", "server-authored text")
            assertNotNull(caught)
            // Pinned, not judged. `AppState.guarded` documents that the message of an exception "never survives"; the likely reason
            // (see `CodedExceptionWireTest`) is that the server throws with the DEFAULT message, which is not encoded
            // (`encodeDefaults = false`). A message that differs from the default DOES arrive once it is in the JSON.
            assertEquals("server-authored text", caught.message, "an explicitly set (non-default) message that IS in the JSON arrives")
        }
}
