package network.lapis.cloud.client

import kotlinx.browser.localStorage
import kotlinx.browser.window
import org.w3c.dom.get
import org.w3c.dom.set

/**
 * V1.9.114 -- optional noise suppression of the OWN microphone (RNNoise, run in the browser only). Nothing leaves the browser: no new
 * request to a third party, no server, no DTO. This file holds the pure parts (names, state, the remembered choice, the capability probe);
 * the state machine is [ConferenceNoiseSuppressionController], the LiveKit processor is `livekit/RnnoiseTrackProcessor.kt`.
 *
 * Why a switch and not a regulator, and why OFF by default: the filter is made for speech; singing and music sound altered with it. The
 * person decides, the browser's own (lighter) noise reduction keeps working whenever the switch is off or the filter is not available.
 */
internal enum class NoiseSuppressionUnavailable { NO_AUDIO_WORKLET, NO_WEB_ASSEMBLY, LOAD_FAILED }

internal sealed interface NoiseSuppressionState {
    /** The filter is not used (the default). */
    data object Off : NoiseSuppressionState

    /** The person wants the filter and it is in the microphone's path (or will be, as soon as a microphone exists). */
    data object On : NoiseSuppressionState

    /** The filter cannot be used here; the browser's own noise reduction is in use instead. Never an error dialog. */
    data class Unavailable(
        val reason: NoiseSuppressionUnavailable,
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

/** The one key of the remembered choice. `"on"` means on, anything else (also a manipulated value) means off. */
internal const val NOISE_SUPPRESSION_STORAGE_KEY = "lapis.audio.noiseSuppression.v1"

/** The remembered choice. A seam: a `jsTest` hands over a fake. Never throws. */
internal interface NoiseSuppressionPreference {
    fun read(): Boolean

    fun write(on: Boolean)
}

/**
 * `localStorage`; a blocked or throwing storage means "off" and the choice lives in memory for the rest of the page (no message). Only the
 * exact value `"on"` counts as on. The value is not security relevant and says nothing about the person but this one choice.
 */
internal class LocalStorageNoiseSuppressionPreference : NoiseSuppressionPreference {
    private var inMemory: Boolean? = null

    override fun read(): Boolean {
        inMemory?.let { return it }
        return try {
            localStorage[NOISE_SUPPRESSION_STORAGE_KEY] == "on"
        } catch (e: Throwable) {
            false
        }
    }

    override fun write(on: Boolean) {
        inMemory = on
        try {
            if (on) localStorage[NOISE_SUPPRESSION_STORAGE_KEY] = "on" else localStorage.removeItem(NOISE_SUPPRESSION_STORAGE_KEY)
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
