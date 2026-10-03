package network.lapis.cloud.client

import kotlinx.browser.window
import kotlin.js.Date
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

/**
 * Review fix (Welle V1.9.14, MAJOR test-coverage finding): simulates the server rejecting an RPC
 * call with a thrown [network.lapis.cloud.shared.rpc.AbstractServiceException] subclass, the way
 * `RegistrationScreen.kt`'s own catch chain (and every other `regionalChapterGuarded`/`guarded`
 * caller) actually receives it over the wire -- needed wherever the exception is caught OUTSIDE a
 * guard function (a plain `try`/`catch` block, so a block-that-throws-directly unit test like
 * `MemberAdminGuardTest` cannot exercise it).
 *
 * Verified against the pinned `dev.kilua:kilua-rpc-core` 0.0.45 sources (`CallAgent.jsonRpcCall`,
 * `dev.kilua.rpc.RpcServiceManager` (jvm) `createJsonRpcRequestHandler`): on `error != null` with
 * `exceptionType != "dev.kilua.rpc.ServiceException"` and `exceptionJson != null`, the client does
 * `RpcSerialization.getJson().decodeFromString<AbstractServiceException>(exceptionJson)`. That
 * `Json` has no custom `classDiscriminator`, so [network.lapis.cloud.shared.rpc.AbstractServiceException]'s
 * open-polymorphism default applies: a flat `{"type":"<fully qualified subclass name>",...}`
 * object, [fqcn] being the value `subclass(...::class)` registers each exception class under in the
 * KSP-generated `dev.kilua.rpc.registerRpcServiceExceptions()` (`GeneratedRpcServiceExceptions.kt`,
 * `lapis-shared/build/generated/ksp/...`) -- i.e. the class's fully qualified Kotlin name, unchanged
 * by any `@SerialName` (none of this project's `@RpcServiceException` classes declare one).
 * `message` is included so `ignoreUnknownKeys = true` covers either way whether the compiler plugin
 * actually keeps it serializable (see `AppState.guarded` KDoc: empirically it never survives to the
 * client's own `e.message`, so its exact wire presence is irrelevant to any test asserting on the
 * reconstructed exception's TYPE, never its message).
 */
internal fun serviceExceptionResult(
    id: Int,
    fqcn: String,
): StubResponse {
    val exceptionJson = js("({})")
    exceptionJson.type = fqcn
    exceptionJson.message = "simulated for a test"
    val body = js("({})")
    body.id = id
    body.result = null
    body.error = "simulated for a test"
    body.exceptionType = null
    body.exceptionJson = JSON.stringify(exceptionJson)
    return StubResponse(text = JSON.stringify(body))
}

/**
 * The promise `fetch` returns for [response]: resolved (after [StubResponse.delayMs]) or rejected for a network error. An answer
 * that would only arrive after its stub was removed ([stubOpen] is `false` by then) is rejected like a dropped connection instead
 * (see [withFetchStub]).
 */
private fun answer(
    response: StubResponse,
    stubOpen: () -> Boolean,
): Promise<dynamic> =
    Promise { resolve, reject ->
        val deliver = {
            if (response.networkError || !stubOpen()) {
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
 *
 * Once the block has ended, this stub no longer answers successfully: a request whose answer is still pending then (a
 * [StubResponse.delayMs], or a Kilua body that is only read afterwards) is rejected like a dropped connection. Otherwise the
 * code waiting for it would carry on in the NEXT test -- e.g. a write's "reload the screen" whose requests then reach the next
 * test's stub and are answered (and counted) there (see [settleAppScope]).
 */
internal suspend fun <T> withFetchStub(
    respond: (RecordedRequest) -> StubResponse = { request ->
        if (request.isRpc) rpcResult(request.json.id as Int, "null") else StubResponse()
    },
    block: suspend (List<RecordedRequest>) -> T,
): T {
    val recorded = mutableListOf<RecordedRequest>()
    var open = true
    val isOpen = { open }
    val realFetch = window.asDynamic().fetch
    window.asDynamic().fetch = { input: dynamic, init: dynamic ->
        if (jsTypeOf(input.text) == "function") {
            // Kilua RPC: fetch(Request). The body is only readable asynchronously.
            val url = input.url as String
            val method = input.method as String
            (input.clone().text() as Promise<String>).then { bodyText ->
                val request = RecordedRequest(url, method, bodyText)
                recorded += request
                answer(respond(request), isOpen)
            }
        } else {
            val rawBody: dynamic = init?.body
            val bodyText = if (jsTypeOf(rawBody) == "string") rawBody as String else "<blob>"
            val request = RecordedRequest(input.toString(), (init?.method ?: "GET") as String, bodyText)
            recorded += request
            answer(respond(request), isOpen)
        }
    }
    try {
        return block(recorded)
    } finally {
        open = false
        window.asDynamic().fetch = realFetch
    }
}

/**
 * Polls [condition] every 20 ms; fails the test with [message] (plus [detail], evaluated only on failure -- e.g. the counts the
 * condition compared) if it never holds. The failure text is also written to the console.
 *
 * The budget is at least [AWAIT_UNTIL_MIN_TIMEOUT_MS], whatever [timeoutMs] says, counted in 20 ms polls (a `delay(20)` never takes
 * LESS than 20 ms, so the real wait is at least as long and grows with a slow machine -- a browser that stalls for a while does not
 * use the budget up; that is why it is not a wall-clock deadline). Why the floor (CI flakes of 2026-10-03, e.g.
 * `CarpoolCollapsibleFormDomTest` "awaitUntil timeout after 3000 ms"): on the slow shared GitHub runners 3000 ms or less was too
 * short for a screen to load, save and reload. Every caller waits for something that MUST happen eventually (none probes for a
 * failure -- checked 2026-10-03), so a longer budget never weakens an assertion; a condition that holds returns at once, only a real
 * failure reports later. The failure text names the wall-clock time actually waited.
 */
internal suspend fun awaitUntil(
    message: String,
    timeoutMs: Int = AWAIT_UNTIL_MIN_TIMEOUT_MS,
    detail: (() -> String)? = null,
    condition: () -> Boolean,
) {
    val budget = maxOf(timeoutMs, AWAIT_UNTIL_MIN_TIMEOUT_MS)
    val started = Date.now()
    var polled = 0
    // Not reset in a `finally`: when the deadline of [formTest] cancels this wait, its failure has to name it.
    currentAwait = message
    while (!condition() && polled < budget) {
        kotlinx.coroutines.delay(AWAIT_UNTIL_POLL_MS.toLong())
        polled += AWAIT_UNTIL_POLL_MS
    }
    currentAwait = null
    if (condition()) return
    val elapsed = (Date.now() - started).toLong()
    val extra = detail?.let { " -- ${it()}" }.orEmpty()
    val text = "timeout: $message (not reached after $polled ms of polls, $elapsed ms wall clock)$extra"
    console.error("awaitUntil $text")
    kotlin.test.fail(text)
}

/** The smallest budget [awaitUntil] grants (see there). */
internal const val AWAIT_UNTIL_MIN_TIMEOUT_MS = 15_000

private const val AWAIT_UNTIL_POLL_MS = 20

/** The [awaitUntil] currently running, named in the deadline failure of [formTest] (`null` between waits). */
internal var currentAwait: String? = null

/**
 * The route [call] goes to (see [RecordedRequest.rpcRoute]), learned by actually performing it against a private stub. The call is a
 * direct reference to the service method, so the compiler checks the name and a renamed/removed method breaks this test at build time
 * instead of silently matching nothing. Use dummy arguments; the outer stub (if any) is restored afterwards.
 *
 * The private stub answers EVERY fetch made while it is installed, also one of a coroutine still running from earlier work. Taking
 * "the last RPC seen" would then return that foreign route, and the test would answer its own method with nothing (or count the
 * wrong calls). So the route is only accepted when exactly one RPC request went out during the call; otherwise the call is repeated.
 */
internal suspend fun routeOf(call: suspend () -> Unit): String {
    var seen: List<String> = emptyList()
    repeat(ROUTE_OF_ATTEMPTS) {
        val routes = mutableListOf<String>()
        withFetchStub(
            respond = { request ->
                if (request.isRpc) routes += request.rpcRoute
                StubResponse(networkError = true)
            },
        ) {
            try {
                call()
            } catch (ignored: Throwable) {
                // The answer is a dropped connection on purpose: only the outgoing request matters.
            }
        }
        if (routes.size == 1) return routes.single()
        seen = routes
    }
    val problem = if (seen.isEmpty()) "the call sent no RPC request" else "more than one RPC request during the call: $seen"
    console.error("routeOf: $problem")
    kotlin.test.fail("routeOf: $problem")
}

private const val ROUTE_OF_ATTEMPTS = 5
