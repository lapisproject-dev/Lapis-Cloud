@file:JsModule("@livekit/track-processors")
@file:JsNonModule
@file:Suppress("unused", "PropertyName")

package network.lapis.cloud.client.livekit

import kotlin.js.Promise

/*
 * V1.4.23 Videokonferenz-Hintergrundeffekte -- minimal `@livekit/track-processors` 0.8.1 externals
 * (Apache-2.0, exakt gepinnt, siehe `build.gradle.kts`). Same `@JsModule` + `@JsNonModule` pair and the
 * same "deliberately minimal" discipline as [LiveKitJs.kt][RoomOptions]: a field or method missing here is
 * a DELIBERATE omission -- only what `ConferenceBackgroundController` actually needs.
 *
 * Every shape below was verified against the package's own `dist/src/index.d.ts` and the package's own TypeScript sources
 * (not guessed):
 * - `BackgroundProcessor(options, name?)` is a `const` arrow function, NOT a constructor.
 * - `switchTo(options)` takes the union `{mode:'disabled'} | {mode:'background-blur', blurRadius?} |
 *   {mode:'virtual-background', imagePath}`.
 * - `assetPaths.tasksVisionFileSet` flows straight into `FilesetResolver.forVisionTasks(basePath)` and
 *   `assetPaths.modelAssetPath` straight into `baseOptions.modelAssetPath` -- the ONLY hooks that keep the
 *   library from fetching its WASM from `cdn.jsdelivr.net` and its model from `storage.googleapis.com`
 *   (its defaults). Both are overridden on every construction, see `ConferenceBackgroundAssets`.
 *
 * **Naming of the factory** (ktlint `function-naming` vs. the package's capitalised export). Variant 1 of
 * the fallback chain is used: a lower-case Kotlin function carrying `@JsName("BackgroundProcessor")`, so no
 * lint suppression is needed. (Variant 2 -- keep the capitalised name and suppress -- and variant 3 --
 * construct `BackgroundTransformer` + `BackgroundProcessorWrapper` directly -- were not necessary.)
 */

/**
 * Passed as `segmenterOptions`. Only `delegate` is exposed on purpose: `segmenterOptions` is spread into
 * `baseOptions` AFTER `modelAssetPath` and `delegate` (`BackgroundTransformer.init`), so setting a
 * `modelAssetPath` here would OVERRIDE the same-origin path from [BackgroundAssetPaths]. It is NOT a
 * resolution or FPS knob.
 */
external interface SegmenterOptions {
    /** `"GPU"` (library default) or `"CPU"`. */
    var delegate: String?
}

external interface BackgroundAssetPaths {
    var tasksVisionFileSet: String?
    var modelAssetPath: String?
}

external interface BackgroundProcessorOptions {
    /** `"background-blur"` | `"virtual-background"` | `"disabled"`. */
    var mode: String
    var blurRadius: Int?
    var imagePath: String?
    var segmenterOptions: SegmenterOptions?
    var assetPaths: BackgroundAssetPaths?

    /** `ProcessorWrapperOptions.maxFps` -- takes effect ONLY in the canvas fallback path (browsers
     * without `MediaStreamTrackProcessor`, i.e. Firefox/Safari); the modern path has no FPS throttle. */
    var maxFps: Int?
}

external interface SwitchBackgroundProcessorOptions {
    var mode: String
    var blurRadius: Int?
    var imagePath: String?
}

/**
 * `BackgroundTransformer.backgroundImageAndPath` -- `{ imageData: ImageBitmap, path: string } | null`
 * (`transformers/BackgroundTransformer.ts:41`, a plain public field). Only `path` is exposed: it is the ONLY
 * observable evidence that the transformer really holds the requested background image.
 */
external interface BackgroundImageAndPath {
    var path: String
}

/**
 * The transformer behind a [BackgroundProcessorWrapper] (`ProcessorWrapper.transformer`, public field,
 * `ProcessorWrapper.ts:69`). Declared as an `external interface` (not a class) on purpose -- it is only ever
 * READ, never constructed here.
 *
 * Why this is needed at all (audit finding M1): `BackgroundTransformer.init` SWALLOWS a failing background
 * image (`await this.loadAndSetBackground(...).catch(err => this.log.error(...))`,
 * `BackgroundTransformer.ts:83-86`), so `LocalTrack.setProcessor` RESOLVES even though the WebGL background
 * texture stayed empty -- the person would be composited over black with no error anywhere. Reading this field
 * after a successful apply turns that silent failure back into a real, classifiable one.
 */
external interface BackgroundTransformerHandle {
    var backgroundImageAndPath: BackgroundImageAndPath?
}

external class BackgroundProcessorWrapper {
    /** Inherited public field of `ProcessorWrapper` -- see [BackgroundTransformerHandle]. */
    val transformer: BackgroundTransformerHandle

    fun switchTo(options: SwitchBackgroundProcessorOptions): Promise<Unit>

    fun destroy(): Promise<Unit>
}

/** `BackgroundTransformer.isSupported && ProcessorWrapper.isSupported` (source: `src/index.ts`). */
external fun supportsBackgroundProcessors(): Boolean

/** `BackgroundTransformer.isSupported && ProcessorWrapper.hasModernApiSupport` -- informational only (the
 * fallback path works), never used as a gate. */
external fun supportsModernBackgroundProcessors(): Boolean

@JsName("BackgroundProcessor")
external fun createBackgroundProcessor(
    options: BackgroundProcessorOptions,
    name: String = definedExternally,
): BackgroundProcessorWrapper
