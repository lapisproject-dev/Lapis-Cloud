package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import org.w3c.dom.get
import org.w3c.dom.set

/*
 * V1.9.114 -- optional noise suppression of the OWN microphone (RNNoise, run in the browser only). Nothing leaves the browser: no new
 * request to a third party, no server, no DTO. This file holds the pure parts (names, state, the remembered choice, the capability probe);
 * the state machine is [ConferenceNoiseSuppressionController], the LiveKit processor is `livekit/RnnoiseTrackProcessor.kt`.
 *
 * Why a switch and not a regulator, and why OFF by default: the filter is made for speech; singing and music sound altered with it. The
 * person decides, the browser's own (lighter) noise reduction keeps working whenever the switch is off or the filter is not available.
 */

/**
 * V1.9.119 -- WHY the filter is not used, one code per stage of the loading chain. [retryable] says whether another attempt can help in
 * this page: a missing browser feature (`NO_AUDIO_WORKLET`, `NO_WEB_ASSEMBLY`) or a wrong audio context rate (`SAMPLE_RATE`) cannot change
 * without a different browser or settings, everything else can (a suspended context is resumed by the next click, a failed fetch may be a
 * hiccup). The code (never a raw browser message) is shown in the panel and written once to the console.
 */
internal enum class NoiseSuppressionUnavailable(
    val retryable: Boolean,
) {
    NO_AUDIO_WORKLET(false),
    NO_WEB_ASSEMBLY(false),
    SAMPLE_RATE(false),
    NO_AUDIO_CONTEXT(true),
    CONTEXT_NOT_RUNNING(true),
    WASM_FETCH(true),
    WORKLET_MODULE(true),
    NODE_CREATE(true),
    SET_PROCESSOR(true),
    RESTART(true),
    TIMEOUT(true),
    LOAD_FAILED(true),
}

/** A failure: the stage plus the (shortened, sanitised) name and message of the browser error behind it. Console only, never the panel. */
internal data class NoiseSuppressionFailure(
    val reason: NoiseSuppressionUnavailable,
    val errorName: String? = null,
    val errorMessage: String? = null,
)

/** Thrown inside the loading chain so the stage survives up to the controller. */
internal class NoiseSuppressionStageException(
    val failure: NoiseSuppressionFailure,
    cause: Throwable? = null,
) : IllegalStateException(failure.reason.name, cause)

private const val ERROR_NAME_MAX = 60
private const val ERROR_MESSAGE_MAX = 160

private val queryPart = Regex("""[?#]\S*""")
private val lineBreaks = Regex("""[\r\n\t]+""")

/**
 * Makes a browser error name/message fit for a log line: line breaks become blanks, query strings and fragments (`?token=...`) are cut off
 * (URLs in browser messages), the length is limited (60 / 160). `null` for anything empty or not text.
 */
internal fun sanitizeJsError(
    name: Any?,
    message: Any?,
): Pair<String?, String?> {
    fun clean(
        value: Any?,
        max: Int,
    ): String? {
        val text = (value as? String) ?: return null
        val cleaned = text.replace(lineBreaks, " ").replace(queryPart, "").trim()
        return if (cleaned.isEmpty()) null else cleaned.take(max)
    }
    return clean(name, ERROR_NAME_MAX) to clean(message, ERROR_MESSAGE_MAX)
}

/** Builds a failure of [reason] from an optional cause and an optional own [message] (which wins over the cause's message). */
internal fun failureOf(
    reason: NoiseSuppressionUnavailable,
    t: Throwable? = null,
    message: String? = null,
): NoiseSuppressionFailure {
    val rawName: Any? = if (t == null) null else runCatching { t.asDynamic().name }.getOrNull() ?: t::class.simpleName
    val rawMessage: Any? = message ?: if (t == null) null else runCatching { t.asDynamic().message }.getOrNull()
    val (name, text) = sanitizeJsError(rawName, rawMessage)
    return NoiseSuppressionFailure(reason, name, text)
}

/** What is known about the audio path when something fails. NEVER a device name, id or label: only these numbers. */
internal data class NoiseSuppressionDiagnostics(
    val trigger: String,
    val ctxState: String? = null,
    val ctxRate: Number? = null,
    val trackRate: Number? = null,
    val trackChannels: Number? = null,
)

/** The one console line of a failure: `[noise-suppression] stage=... trigger=... error=... message=... ctxState=... ctxRate=... trackRate=... trackChannels=...`, `-` = unknown. */
internal fun noiseSuppressionLogLine(
    f: NoiseSuppressionFailure,
    d: NoiseSuppressionDiagnostics,
): String {
    fun v(x: Any?): String = x?.toString() ?: "-"
    return "[noise-suppression] stage=${f.reason.name} trigger=${d.trigger} error=${v(f.errorName)} message=${v(f.errorMessage)} " +
        "ctxState=${v(d.ctxState)} ctxRate=${v(d.ctxRate)} trackRate=${v(d.trackRate)} trackChannels=${v(d.trackChannels)}"
}

/** Where the log line goes. A seam: a test collects the lines. */
internal interface NoiseSuppressionLogger {
    fun warn(line: String)
}

/** `console.warn` (never `console.error`, never a dialog). */
internal object ConsoleNoiseSuppressionLogger : NoiseSuppressionLogger {
    override fun warn(line: String) {
        runCatching { console.warn(line) }
    }
}

/**
 * Hands over the person's NEXT gesture (a click or a key press anywhere on the page): the browser lets an audio context start only inside
 * one. [registerOnce] returns the remover (idempotent).
 */
internal interface GestureHook {
    fun registerOnce(onGesture: () -> Unit): () -> Unit
}

/** `pointerdown` / `pointerup` (touch counts as a gesture only on release) / `keydown` on the document, capturing, all removed as soon as one fired. */
private val gestureEvents = listOf("pointerdown", "pointerup", "keydown")

internal object DocumentGestureHook : GestureHook {
    override fun registerOnce(onGesture: () -> Unit): () -> Unit {
        var removed = false
        lateinit var listener: (org.w3c.dom.events.Event) -> Unit
        val remove = {
            if (!removed) {
                removed = true
                runCatching {
                    gestureEvents.forEach { document.removeEventListener(it, listener, true) }
                }
            }
        }
        listener = {
            remove()
            onGesture()
        }
        gestureEvents.forEach { document.addEventListener(it, listener, true) }
        return remove
    }
}

internal sealed interface NoiseSuppressionState {
    /** The filter is not used (the default). */
    data object Off : NoiseSuppressionState

    /** The person wants the filter and it is in the microphone's path (or will be, as soon as a microphone exists). */
    data object On : NoiseSuppressionState

    /** The filter cannot be used here; the browser's own noise reduction is in use instead. Never an error dialog. */
    data class Unavailable(
        val failure: NoiseSuppressionFailure,
    ) : NoiseSuppressionState
}

/**
 * Where the files live. The directory is versioned and served `immutable` (see `ClientAssetRoutes.kt`); if the bytes ever change it becomes
 * `rnnoise-v2` in all THREE places: here, `RNNOISE_ASSET_DIR` in the server route and `lapis-client/build.gradle.kts`
 * (`RnnoiseAssetsTest` keeps them together). Same-origin paths only: never a CDN, never a `blob:` URL.
 */
internal object NoiseSuppressionAssets {
    const val DIR = "rnnoise-v1"
    const val WORKLET = "/assets/$DIR/rnnoise-worklet.js"
    const val WASM = "/assets/$DIR/rnnoise.wasm"
    const val WASM_SIMD = "/assets/$DIR/rnnoise_simd.wasm"

    /** The name the worklet registers its processor under (fixed by the upstream file, which must stay byte for byte unchanged). */
    const val PROCESSOR_NAME = "@sapphi-red/web-noise-suppressor/rnnoise"

    /** RNNoise works at 48 kHz only. */
    const val SAMPLE_RATE = 48_000

    /** An upper limit for a WebAssembly file (the two are about 155 KB); a larger answer is refused. */
    const val WASM_MAX_BYTES = 1_048_576
}

/** The one key of the remembered choice. `"on"` = on, `"off"` = off, anything else (empty, manipulated) = never chosen. */
internal const val NOISE_SUPPRESSION_STORAGE_KEY = "lapis.audio.noiseSuppression.v1"

/**
 * The remembered choice. A seam: a `jsTest` hands over a fake. Never throws.
 *
 * V1.9.124 -- three states: `true` = the person chose on, `false` = the person chose off (stored explicitly, no longer "key removed"),
 * `null` = never chosen (then the instance default of the organisation applies).
 */
internal interface NoiseSuppressionPreference {
    fun read(): Boolean?

    fun write(on: Boolean)
}

/**
 * `localStorage`; a blocked or throwing storage means "never chosen" unless the person chose in this page (then the choice lives in memory
 * for the rest of the page, no message). Only the exact values `"on"` and `"off"` count. The value is not security relevant and says
 * nothing about the person but this one choice.
 */
internal class LocalStorageNoiseSuppressionPreference : NoiseSuppressionPreference {
    private var inMemory: Boolean? = null

    override fun read(): Boolean? {
        inMemory?.let { return it }
        return try {
            when (localStorage[NOISE_SUPPRESSION_STORAGE_KEY]) {
                "on" -> true
                "off" -> false
                else -> null
            }
        } catch (e: Throwable) {
            null
        }
    }

    override fun write(on: Boolean) {
        inMemory = on
        try {
            localStorage[NOISE_SUPPRESSION_STORAGE_KEY] = if (on) "on" else "off"
        } catch (e: Throwable) {
            // blocked storage: the choice is kept for this page only
        }
    }
}

/** The order of the checks: no AudioWorklet first, then no WebAssembly. `null` = the browser can run the filter (loading may still fail). */
internal fun noiseSuppressionSupport(
    hasAudioWorklet: Boolean,
    hasWebAssembly: Boolean,
): NoiseSuppressionUnavailable? =
    when {
        !hasAudioWorklet -> NoiseSuppressionUnavailable.NO_AUDIO_WORKLET
        !hasWebAssembly -> NoiseSuppressionUnavailable.NO_WEB_ASSEMBLY
        else -> null
    }

/** The real probe of this browser. Cheap, no network. */
internal fun noiseSuppressionSupportInThisBrowser(): NoiseSuppressionUnavailable? {
    val scope = window.asDynamic()
    val hasWorklet =
        runCatching {
            val context = scope.AudioContext ?: scope.webkitAudioContext
            val prototype = context?.prototype
            val worklet = scope.AudioWorkletNode
            prototype != null &&
                prototype != undefined &&
                js("Reflect").has(prototype, "audioWorklet") == true &&
                worklet != null &&
                worklet != undefined
        }.getOrDefault(false)
    val hasWasm = runCatching { scope.WebAssembly != null && scope.WebAssembly != undefined }.getOrDefault(false)
    return noiseSuppressionSupport(hasWorklet, hasWasm)
}
