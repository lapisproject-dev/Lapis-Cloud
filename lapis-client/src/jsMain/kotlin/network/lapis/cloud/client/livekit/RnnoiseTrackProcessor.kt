package network.lapis.cloud.client.livekit

import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import network.lapis.cloud.client.AppScope
import network.lapis.cloud.client.NoiseSuppressionAssets
import network.lapis.cloud.client.NoiseSuppressionFailure
import network.lapis.cloud.client.NoiseSuppressionStageException
import network.lapis.cloud.client.NoiseSuppressionUnavailable
import network.lapis.cloud.client.failureOf
import network.lapis.cloud.client.sanitizeJsError
import kotlin.js.Promise

/** The name LiveKit shows for this processor (`getProcessor().name`); the session uses it to see that the filter is already in place. */
internal const val RNNOISE_LIVEKIT_PROCESSOR_NAME = "lapis-rnnoise"

/**
 * V1.9.114 -- loading of the two things the filter needs, each at most ONCE per page: the WebAssembly bytes (a SIMD or a plain build) and,
 * per audio context, the worklet module. A failed load is NOT remembered (the next attempt loads again). The browser calls are seams
 * ([fetchWasm], [addModule]), so the caching is testable without a network.
 */
internal class RnnoiseAssetCache(
    private val fetchWasm: suspend () -> dynamic,
    private val addModule: suspend (context: dynamic) -> Unit,
) {
    private val lock = Mutex()
    private var wasm: dynamic = null
    private val modules = mutableListOf<dynamic>()

    /** The WebAssembly bytes (`ArrayBuffer`). Throws if loading fails; nothing is cached then. */
    suspend fun wasm(): dynamic =
        lock.withLock {
            if (wasm == null) wasm = stage(NoiseSuppressionUnavailable.WASM_FETCH) { fetchWasm() }
            wasm
        }

    /** Registers the worklet module in [context] once. Throws if loading fails; nothing is remembered then. */
    suspend fun ensureModule(context: dynamic) {
        lock.withLock {
            if (modules.any { it === context }) return@withLock
            stage(NoiseSuppressionUnavailable.WORKLET_MODULE) { addModule(context) }
            modules.add(context)
        }
    }
}

/** Runs [block]; anything it throws (a cancellation excepted) becomes a [NoiseSuppressionStageException] of [reason], unless it already is one. */
internal inline fun <T> stage(
    reason: NoiseSuppressionUnavailable,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: NoiseSuppressionStageException) {
        throw e
    } catch (e: Throwable) {
        throw NoiseSuppressionStageException(failureOf(reason, e), e)
    }

private fun wasmRefused(
    message: String,
    name: String? = null,
): Nothing = throw NoiseSuppressionStageException(NoiseSuppressionFailure(NoiseSuppressionUnavailable.WASM_FETCH, name, message))

private val simdProbe =
    intArrayOf(0, 97, 115, 109, 1, 0, 0, 0, 1, 5, 1, 96, 0, 1, 123, 3, 2, 1, 0, 10, 10, 1, 8, 0, 65, 0, 253, 15, 253, 98, 11)

private fun browserSupportsSimd(): Boolean =
    runCatching {
        val scope = window.asDynamic()
        val bytes = js("Reflect").construct(scope.Uint8Array, arrayOf(simdProbe.toTypedArray()))
        scope.WebAssembly.validate(bytes) == true
    }.getOrDefault(false)

/**
 * The real WebAssembly fetch: ONE same-origin request (no cookies, no referrer, no redirect), the answer must be `ok`, of type
 * `application/wasm` and not larger than [NoiseSuppressionAssets.WASM_MAX_BYTES]. Aborts itself after the load timeout so no request hangs on.
 */
internal suspend fun browserFetchRnnoiseWasm(): dynamic {
    val url = if (browserSupportsSimd()) NoiseSuppressionAssets.WASM_SIMD else NoiseSuppressionAssets.WASM
    val init: dynamic = js("{}")
    init.credentials = "omit"
    init.referrerPolicy = "no-referrer"
    init.mode = "same-origin"
    init.redirect = "error"
    init.cache = "default"
    val abort: dynamic = js("new AbortController()")
    init.signal = abort.signal
    val timer = window.setTimeout({ abort.abort() }, 8_000)
    try {
        val response: dynamic =
            try {
                window
                    .asDynamic()
                    .fetch(url, init)
                    .unsafeCast<Promise<dynamic>>()
                    .await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val (name, message) =
                    sanitizeJsError(
                        runCatching { e.asDynamic().name }.getOrNull(),
                        runCatching { e.asDynamic().message }.getOrNull(),
                    )
                if (name == "AbortError") wasmRefused("aborted", name)
                throw NoiseSuppressionStageException(NoiseSuppressionFailure(NoiseSuppressionUnavailable.WASM_FETCH, name, message), e)
            }
        if (response.ok != true) wasmRefused("status=${response.status}")
        // NOT `type: dynamic` with a smart cast: on a dynamic value `type.lowercase()` is a JS call that does not exist (every real response
        // threw "type.lowercase is not a function" and ended as a load failure); the explicit String is a real Kotlin string.
        val type: String? = response.headers.get("Content-Type") as? String
        if (type == null || !type.lowercase().startsWith("application/wasm")) {
            wasmRefused("type=${type?.substringBefore(';')?.trim()?.take(60) ?: "-"}")
        }
        val body: dynamic = response.arrayBuffer().unsafeCast<Promise<dynamic>>().await()
        val size: Int = (body.byteLength as? Number)?.toInt() ?: 0
        if (size <= 0 || size > NoiseSuppressionAssets.WASM_MAX_BYTES) wasmRefused("size=$size")
        return body
    } finally {
        window.clearTimeout(timer)
    }
}

/** The real worklet registration: the module comes from the same origin (never a `blob:` URL, never a CDN). */
internal suspend fun browserAddRnnoiseModule(context: dynamic) {
    context.audioWorklet
        .addModule(NoiseSuppressionAssets.WORKLET)
        .unsafeCast<Promise<dynamic>>()
        .await()
}

/** The one cache of this page. */
internal val browserRnnoiseAssets = RnnoiseAssetCache(::browserFetchRnnoiseWasm, ::browserAddRnnoiseModule)

private fun browserMediaStreamOf(track: dynamic): dynamic = js("Reflect").construct(window.asDynamic().MediaStream, arrayOf(arrayOf(track)))

private fun browserWorkletNode(
    context: dynamic,
    options: dynamic,
): dynamic = js("Reflect").construct(window.asDynamic().AudioWorkletNode, arrayOf(context, NoiseSuppressionAssets.PROCESSOR_NAME, options))

/**
 * V1.9.114 -- the LiveKit `TrackProcessor` of the optional noise suppression: raw microphone track -> `MediaStreamAudioSourceNode` ->
 * `AudioWorkletNode` (RNNoise) -> `MediaStreamAudioDestinationNode` -> [processedTrack].
 *
 * The audio context is the session's (handed over by LiveKit in `init`, which does NOT repeat it in `restart` -- so it is kept); it is never
 * closed here. [destroy] disconnects every node. A device change makes LiveKit call [restart] with the new raw track: the chain is rebuilt
 * around it (the worklet module and the WebAssembly are not loaded again, [assets] caches them).
 *
 * Everything runs in the browser; the only requests are the two same-origin asset fetches of [assets].
 */
internal class RnnoiseTrackProcessor(
    private val assets: RnnoiseAssetCache = browserRnnoiseAssets,
    /** Test seam: wraps the raw track in a `MediaStream` (a fake track cannot be put into a real one). */
    private val newStream: (track: dynamic) -> dynamic = ::browserMediaStreamOf,
    /** Test seam: builds the worklet node (a real `AudioWorkletNode` needs a real audio context). */
    private val newWorkletNode: (context: dynamic, options: dynamic) -> dynamic = ::browserWorkletNode,
) {
    private var context: dynamic = null
    private var source: dynamic = null
    private var node: dynamic = null
    private var destination: dynamic = null
    private var destroyed = false

    /** The track LiveKit sends instead of the raw one; `null` before [init]. */
    var processedTrack: dynamic = null
        private set

    suspend fun init(options: dynamic) {
        val ctx = options.audioContext
        if (ctx == null || ctx == undefined) {
            throw NoiseSuppressionStageException(
                NoiseSuppressionFailure(NoiseSuppressionUnavailable.NO_AUDIO_CONTEXT, null, "no audio context"),
            )
        }
        context = ctx
        destroyed = false
        build(ctx, options.track)
    }

    suspend fun restart(options: dynamic) {
        val ctx = context ?: throw IllegalStateException("not initialised")
        if (destroyed) throw IllegalStateException("destroyed")
        teardown()
        build(ctx, options.track)
    }

    suspend fun destroy() {
        destroyed = true
        teardown()
    }

    private suspend fun build(
        ctx: dynamic,
        track: dynamic,
    ) {
        val wasm = assets.wasm()
        assets.ensureModule(ctx)
        if (destroyed) throw IllegalStateException("destroyed")
        stage(NoiseSuppressionUnavailable.NODE_CREATE) { buildNodes(ctx, track, wasm) }
    }

    private fun buildNodes(
        ctx: dynamic,
        track: dynamic,
        wasm: dynamic,
    ) {
        val stream = newStream(track)
        val nodeOptions: dynamic = js("{}")
        nodeOptions.channelCount = 1
        nodeOptions.channelCountMode = "explicit"
        nodeOptions.numberOfInputs = 1
        nodeOptions.numberOfOutputs = 1
        nodeOptions.outputChannelCount = arrayOf(1)
        val processorOptions: dynamic = js("{}")
        processorOptions.maxChannels = 1
        processorOptions.wasmBinary = wasm
        nodeOptions.processorOptions = processorOptions
        val newSource = ctx.createMediaStreamSource(stream)
        val newNode = newWorkletNode(ctx, nodeOptions)
        val newDestination = ctx.createMediaStreamDestination()
        source = newSource
        node = newNode
        destination = newDestination
        newSource.connect(newNode)
        newNode.connect(newDestination)
        processedTrack = newDestination.stream.getAudioTracks()[0]
    }

    private fun teardown() {
        runCatching { source?.disconnect() }
        runCatching { node?.port?.postMessage("destroy") }
        runCatching { node?.disconnect() }
        runCatching { destination?.disconnect() }
        source = null
        node = null
        destination = null
        processedTrack = null
    }
}

/**
 * The plain JS object LiveKit expects (`name`, `init`, `restart`, `destroy`, `processedTrack`): Kotlin member names are not stable JS names,
 * so the object is built by hand. `processedTrack` is set BEFORE the `init`/`restart` promise resolves, which is when LiveKit reads it.
 */
internal fun RnnoiseTrackProcessor.asLiveKitProcessor(onRuntimeFailure: (NoiseSuppressionFailure) -> Unit = {}): dynamic {
    val processor = this
    val adapter: dynamic = js("({})")
    adapter.name = RNNOISE_LIVEKIT_PROCESSOR_NAME
    adapter.init = { options: dynamic ->
        AppScope.promise {
            processor.init(options)
            adapter.processedTrack = processor.processedTrack
        }
    }
    // A failing restart (a device change whose new track the filter cannot be rebuilt around) must NEVER leave LiveKit without a track:
    // the promise resolves, the RAW track is passed on (the person stays audible), and the controller is told so it falls back.
    adapter.restart = { options: dynamic ->
        AppScope.promise {
            try {
                processor.restart(options)
                adapter.processedTrack = processor.processedTrack
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                adapter.processedTrack = options.track
                val failure =
                    (e as? NoiseSuppressionStageException)?.failure?.copy(reason = NoiseSuppressionUnavailable.RESTART)
                        ?: failureOf(NoiseSuppressionUnavailable.RESTART, e)
                runCatching { onRuntimeFailure(failure) }
            }
        }
    }
    adapter.destroy = {
        AppScope.promise {
            processor.destroy()
            adapter.processedTrack = null
        }
    }
    return adapter
}
