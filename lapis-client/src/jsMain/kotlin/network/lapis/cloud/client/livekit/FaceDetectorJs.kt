@file:JsModule("@mediapipe/tasks-vision")
@file:JsNonModule
@file:Suppress("unused", "PropertyName")

package network.lapis.cloud.client.livekit

import kotlin.js.Promise

/*
 * V1.9.142 "Kamera-Filter" -- minimal `@mediapipe/tasks-vision` 0.10.14 externals for the face detector of the skin smoothing. The package is
 * not a direct dependency of this module: it comes in transitively with `@livekit/track-processors` (exactly pinned there to 0.10.14, see
 * `build.gradle.kts` and `verifyMediaPipeVersion`) and is hoisted into `build/js/node_modules`. Verified against `vision.d.ts` of that
 * version: `FilesetResolver.forVisionTasks(basePath)`, `FaceDetector.createFromOptions(fileset, options)`, `detectForVideo(frame, ms)`,
 * `close()`; a `VideoFrame` is a valid `TexImageSource` and so a valid input.
 *
 * Deliberately NOT a second place that decides where files come from: the callers pass the same-origin paths of [ConferenceBackgroundAssets]
 * and of the face model -- the library's CDN defaults are never reachable from here (there is no overload without a path).
 */

external interface WasmFileset

external object FilesetResolver {
    fun forVisionTasks(basePath: String): Promise<WasmFileset>
}

external interface FaceDetectorBaseOptions {
    var modelAssetPath: String?

    /** `"GPU"` or `"CPU"`. */
    var delegate: String?
}

external interface FaceDetectorOptions {
    var baseOptions: FaceDetectorBaseOptions?

    /** `"IMAGE"` or `"VIDEO"`. */
    var runningMode: String?
    var minDetectionConfidence: Double?
}

/** The detector instance; `detectForVideo` is synchronous and blocks the thread it runs in. */
external interface FaceDetectorHandle {
    fun detectForVideo(
        frame: dynamic,
        timestampMs: Double,
    ): dynamic

    fun close()
}

external object FaceDetector {
    fun createFromOptions(
        fileset: WasmFileset,
        options: FaceDetectorOptions,
    ): Promise<FaceDetectorHandle>
}
