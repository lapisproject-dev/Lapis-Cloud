package network.lapis.cloud.client.livekit

import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import network.lapis.cloud.client.AppScope
import network.lapis.cloud.client.NoiseSuppressionAssets
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
            if (wasm == null) wasm = fetchWasm()
            wasm
        }

    /** Registers the worklet module in [context] once. Throws if loading fails; nothing is remembered then. */
    suspend fun ensureModule(context: dynamic) {
        lock.withLock {
            if (modules.any { it === context }) return@withLock
            addModule(context)
            modules.add(context)
        }
    }
}

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
            window
                .asDynamic()
                .fetch(url, init)
                .unsafeCast<Promise<dynamic>>()
                .await()
        if (response.ok != true) throw IllegalStateException("wasm refused")
        val type: dynamic = response.headers.get("Content-Type")
        if (type !is String || !type.lowercase().startsWith("application/wasm")) throw IllegalStateException("wasm refused")
        val body: dynamic = response.arrayBuffer().unsafeCast<Promise<dynamic>>().await()
        val size: Int = (body.byteLength as? Number)?.toInt() ?: 0
        if (size <= 0 || size > NoiseSuppressionAssets.WASM_MAX_BYTES) throw IllegalStateException("wasm refused")
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
        if (ctx == null || ctx == undefined) throw IllegalStateException("no audio context")
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
internal fun RnnoiseTrackProcessor.asLiveKitProcessor(): dynamic {
    val processor = this
    val adapter: dynamic = js("({})")
    adapter.name = RNNOISE_LIVEKIT_PROCESSOR_NAME
    adapter.init = { options: dynamic ->
        AppScope.promise {
            processor.init(options)
            adapter.processedTrack = processor.processedTrack
        }
    }
    adapter.restart = { options: dynamic ->
        AppScope.promise {
            processor.restart(options)
            adapter.processedTrack = processor.processedTrack
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
