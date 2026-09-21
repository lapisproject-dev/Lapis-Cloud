package network.lapis.cloud.client

import kotlinx.browser.window
import kotlin.js.Promise

/**
 * A recorded `fetch` call. Two shapes reach `window.fetch` in this client: [AuthHttp] and [BackupHttp] call
 * `fetch(url, init)` with a string body, Kilua RPC calls `fetch(Request)` with a JSON-RPC body
 * (`{"id":1,"method":"/rpc/route<Service>Manager<n>","params":["<json of parameter 0>", ...]}` -- every parameter is
 * itself a JSON STRING inside the `params` array).
 */
internal class RecordedRequest(
    val url: String,
    val method: String,
    /** The body text; `"<blob>"` when the body was a `File`/`Blob` (the restore upload). */
    val body: String,
) {
    /** The whole body parsed as JSON (for a JSON body). */
    val json: dynamic get() = JSON.parse<dynamic>(body)

    val isRpc: Boolean get() = url.contains("/rpc/")

    /**
     * Kilua RPC: the route the call went to (`/rpc/route<Service>Manager<n>`, the `method` of the JSON-RPC body). It IDENTIFIES the
     * called service method -- unlike the parameter count, which several methods share. The index `<n>` is not stable across
     * interface changes, so a test never hard-codes it: it asks [routeOf] for the route of a method by calling that method.
     */
    val rpcRoute: String get() = json.method as String

    /** Kilua RPC: parameter [index] parsed from its own JSON string. */
    fun rpcParam(index: Int): dynamic = JSON.parse<dynamic>(json.params[index] as String)
}

/** What the stub answers. For an RPC call use [rpcResult]; for a plain route a status and a text body. */
internal class StubResponse(
    val status: Int = 200,
    val text: String = "",
    /** The request fails like a dropped connection: `fetch` rejects with a `TypeError` (no HTTP response at all). */
    val networkError: Boolean = false,
    /** Answer only after this many milliseconds (to act while a load is still in flight). */
    val delayMs: Int = 0,
)

/** A successful Kilua RPC answer carrying [resultJson] (the JSON of the return value, `"null"` for `Unit`). */
internal fun rpcResult(
    id: Int,
    resultJson: String,
): StubResponse {
    val body = js("({})")
    body.id = id
    body.result = resultJson
    return StubResponse(text = JSON.stringify(body))
}

/** The promise `fetch` returns for [response]: resolved (after [StubResponse.delayMs]) or rejected for a network error. */
private fun answer(response: StubResponse): Promise<dynamic> =
    Promise { resolve, reject ->
        val deliver = {
            if (response.networkError) {
                reject(
                    js("new TypeError('Failed to fetch')").unsafeCast<Throwable>(),
                )
            } else {
                resolve(newResponse(response))
            }
        }
        if (response.delayMs > 0) window.setTimeout({ deliver() }, response.delayMs) else deliver()
    }

private fun newResponse(response: StubResponse): dynamic {
    val options = js("({})")
    options.status = response.status
    options.headers = js("({ 'Content-Type': 'application/json' })")
    return js("Reflect").construct(window.asDynamic().Response, arrayOf<dynamic>(response.text, options))
}

/**
 * Replaces `window.fetch` for the duration of [block] and hands over the list of recorded requests (filled as the
 * requests arrive -- read it AFTER the awaited action). [respond] decides the answer per request; the default answers
 * every RPC call with a `null` result and every other request with an empty 200. `window.fetch` is restored in
 * `finally`.
 */
internal suspend fun <T> withFetchStub(
    respond: (RecordedRequest) -> StubResponse = { request ->
        if (request.isRpc) rpcResult(request.json.id as Int, "null") else StubResponse()
    },
    block: suspend (List<RecordedRequest>) -> T,
): T {
    val recorded = mutableListOf<RecordedRequest>()
    val realFetch = window.asDynamic().fetch
    window.asDynamic().fetch = { input: dynamic, init: dynamic ->
        if (jsTypeOf(input.text) == "function") {
            // Kilua RPC: fetch(Request). The body is only readable asynchronously.
            val url = input.url as String
            val method = input.method as String
            (input.clone().text() as Promise<String>).then { bodyText ->
                val request = RecordedRequest(url, method, bodyText)
                recorded += request
                answer(respond(request))
            }
        } else {
            val rawBody: dynamic = init?.body
            val bodyText = if (jsTypeOf(rawBody) == "string") rawBody as String else "<blob>"
            val request = RecordedRequest(input.toString(), (init?.method ?: "GET") as String, bodyText)
            recorded += request
            answer(respond(request))
        }
    }
    try {
        return block(recorded)
    } finally {
        window.asDynamic().fetch = realFetch
    }
}

/** Polls [condition] (every 20 ms, up to [timeoutMs]); fails the test with [message] if it never holds. */
internal suspend fun awaitUntil(
    message: String,
    timeoutMs: Int = 3000,
    condition: () -> Boolean,
) {
    var waited = 0
    while (!condition() && waited < timeoutMs) {
        kotlinx.coroutines.delay(20)
        waited += 20
    }
    kotlin.test.assertTrue(condition(), "timeout: $message")
}

/**
 * The route [call] goes to (see [RecordedRequest.rpcRoute]), learned by actually performing it against a private stub. The call is a
 * direct reference to the service method, so the compiler checks the name and a renamed/removed method breaks this test at build time
 * instead of silently matching nothing. Use dummy arguments; the outer stub (if any) is restored afterwards.
 */
internal suspend fun routeOf(call: suspend () -> Unit): String {
    var route = ""
    withFetchStub(
        respond = { request ->
            if (request.isRpc) route = request.rpcRoute
            StubResponse(networkError = true)
        },
    ) {
        try {
            call()
        } catch (ignored: Throwable) {
            // The answer is a dropped connection on purpose: only the outgoing request matters.
        }
    }
    kotlin.test.assertTrue(route.isNotEmpty(), "routeOf: the call sent no RPC request")
    return route
}
