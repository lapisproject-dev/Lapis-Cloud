package network.lapis.cloud.client

/*
 * V1.9.129 "Video-Overlays" -- thin wrappers around the browser globals the overlay drawing step needs (`VideoFrame`, `OffscreenCanvas`,
 * `createImageBitmap`, `Intl.Segmenter`, the `TransformStream` hook). Deliberately NOT in `livekit/TrackProcessorsJs.kt`: that file is a
 * `@file:JsModule("@livekit/track-processors")` file, and a browser global declared there would be resolved against the npm module.
 *
 * Everything here is a few lines of `js(...)` or a `dynamic` access -- no Kotlin external class is declared for a name that Kotlin's own
 * browser stdlib could also declare (a duplicate would only fail at link time). All functions are total: a browser without the API throws,
 * and the caller (the compositor) turns that into a fallback, never into a frozen video.
 */

/** `new VideoFrame(source, {timestamp, alpha: "discard"})`. The caller owns the result and must `close()` it exactly once or enqueue it. */
@Suppress("UNUSED_PARAMETER")
internal fun newVideoFrameFrom(
    source: dynamic,
    timestamp: dynamic,
): dynamic = js("new VideoFrame(source, { timestamp: timestamp, alpha: 'discard' })")

@Suppress("UNUSED_PARAMETER")
internal fun newOffscreenCanvas(
    width: Int,
    height: Int,
): dynamic = js("new OffscreenCanvas(width, height)")

/** `createImageBitmap(blob)` or, with a target size, `createImageBitmap(blob, {resizeWidth, resizeHeight, resizeQuality: 'high'})`. */
@Suppress("UNUSED_PARAMETER")
internal fun createImageBitmapOf(
    source: dynamic,
    resizeWidth: Int,
    resizeHeight: Int,
): dynamic =
    if (resizeWidth > 0 && resizeHeight > 0) {
        js("createImageBitmap(source, { resizeWidth: resizeWidth, resizeHeight: resizeHeight, resizeQuality: 'high' })")
    } else {
        js("createImageBitmap(source)")
    }

/** Grapheme clusters of [text] via `Intl.Segmenter`; code points when the browser has none. A grapheme is never cut in half. */
internal fun splitGraphemes(text: String): List<String> {
    val segmenterAvailable: Boolean = js("typeof Intl !== 'undefined' && typeof Intl.Segmenter === 'function'").unsafeCast<Boolean>()
    if (segmenterAvailable) {
        return runCatching {
            val segments =
                js(
                    "Array.from(new Intl.Segmenter(undefined, { granularity: 'grapheme' }).segment(text), function (s) { return s.segment; })",
                )
            val array = segments.unsafeCast<Array<String>>()
            array.toList()
        }.getOrElse { codePointsOf(text) }
    }
    return codePointsOf(text)
}

private fun codePointsOf(text: String): List<String> {
    val result = mutableListOf<String>()
    var index = 0
    while (index < text.length) {
        val char = text[index]
        val length = if (char.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) 2 else 1
        result += text.substring(index, index + length)
        index += length
    }
    return result
}

/**
 * Replaces `transformer.transform` (called dynamically by the library's own `TransformStream` and, on the canvas fallback path, directly)
 * by a function that runs the original with a controller whose `enqueue` goes through [process]. [process] receives the frame the library
 * wants to enqueue and a function that really enqueues; it takes ownership of the frame. Must run BEFORE the processor is initialised.
 *
 * V1.9.142 -- [pre] is the INPUT side: it sees the camera frame BEFORE the library's transformer (segmentation, background) does, and returns
 * the frame the transformer is to work on -- the same frame, or a new one (then [pre] has closed the original: the library's
 * `BackgroundTransformer.transform` closes whatever frame it receives in its `finally`, `dist/index.mjs` of 0.8.1, so ownership of the frame
 * handed over is the library's, ownership of the original stays with [pre]). [pre] must never throw; if it does, the original frame is used.
 */
@Suppress("UNUSED_PARAMETER")
internal fun installTransformHook(
    transformer: dynamic,
    pre: ((dynamic) -> dynamic)? = null,
    process: (dynamic, dynamic) -> Unit,
) {
    js(
        """
        (function (t, preFrame, processFrame) {
            var original = t.transform;
            var lastController = null;
            var wrapped = null;
            t.transform = function (frame, controller) {
                if (controller !== lastController) {
                    lastController = controller;
                    wrapped = {
                        enqueue: function (f) {
                            processFrame(f, function (out) { controller.enqueue(out); });
                        },
                        error: function (e) { return controller.error(e); },
                        terminate: function () { return controller.terminate(); }
                    };
                }
                var input = frame;
                if (preFrame) {
                    try {
                        var result = preFrame(frame);
                        if (result) { input = result; }
                    } catch (e) {
                        input = frame;
                    }
                }
                return original.call(t, input, wrapped);
            };
        })(transformer, pre, process)
        """,
    )
}

/**
 * A duck-typed transformer for `ProcessorWrapper` that only runs the lightweight steps (camera filter stage, overlay step) -- no segmentation and no background WASM; the filter stage owns its
 * own WebGL context when it is on: the library calls
 * `init({outputCanvas, inputElement})`, `restart`, `update`, `destroy` and reads `.transformer` (a `TransformStream`) and `.transform`.
 */
@Suppress("UNUSED_PARAMETER")
internal fun createLightweightTransformer(process: (dynamic, dynamic) -> Unit): dynamic =
    js(
        """
        (function (processFrame) {
            var t = {
                transformer: null,
                init: function (opts) {
                    t.transformer = new TransformStream({ transform: function (frame, controller) { return t.transform(frame, controller); } });
                    return Promise.resolve();
                },
                restart: function (opts) { return t.init(opts); },
                update: function (opts) { return Promise.resolve(); },
                destroy: function (opts) { return Promise.resolve(); },
                transform: function (frame, controller) {
                    processFrame(frame, function (out) { controller.enqueue(out); });
                }
            };
            return t;
        })(process)
        """,
    )
