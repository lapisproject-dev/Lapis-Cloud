package network.lapis.cloud.client

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.129 -- the browser-side glue against REAL browser objects (headless Chrome): the hook that sits between the library's transformer and
 * `controller.enqueue`, and the duck-typed overlay-only transformer the library's `ProcessorWrapper` drives. A real `VideoFrame`
 * (made from a canvas) travels through a fake controller; the frame the hook enqueues is the one the processor passed on.
 */
class VideoOverlayTransformHookTest {
    private fun realFrame(): dynamic {
        // A canvas without a context is an invalid VideoFrame source -- the production surface always has one.
        val canvas = newOffscreenCanvas(64, 36)
        canvas.getContext("2d").fillRect(0, 0, 64, 36)
        return newVideoFrameFrom(canvas, 123456)
    }

    private fun apisPresent(): Boolean =
        js(
            "typeof VideoFrame !== 'undefined' && typeof OffscreenCanvas !== 'undefined' && typeof TransformStream !== 'undefined'",
        ).unsafeCast<Boolean>()

    /** A transformer with an "original" `transform` that enqueues the frame it gets (what the library's own transformer does). */
    private fun fakeTransformer(): dynamic =
        js("({ calls: 0, transform: function (frame, controller) { this.calls++; controller.enqueue(frame); } })")

    private fun fakeController(sink: dynamic): dynamic = js("({ enqueue: function (f) { sink.push(f); } })")

    @Test
    fun theHook_routesEveryEnqueueThroughTheProcessor_andKeepsTheOriginalTransformRunning() {
        if (!apisPresent()) return
        val sink: dynamic = js("[]")
        val processed: dynamic = js("[]")
        val transformer = fakeTransformer()
        val controller = fakeController(sink)
        installTransformHook(transformer) { frame: dynamic, enqueue: dynamic ->
            processed.push(frame)
            enqueue.call(null, frame)
        }
        val frame = realFrame()
        transformer.transform(frame, controller)
        transformer.transform(frame, controller)
        assertEquals(2, transformer.calls as Int, "the library's own transform still runs")
        assertEquals(2, processed.length as Int)
        assertEquals(2, sink.length as Int, "what the processor passes on reaches the real controller")
        frame.close()
    }

    @Test
    fun theHook_aProcessorThatSwallowsTheFrame_enqueuesNothing() {
        if (!apisPresent()) return
        val sink: dynamic = js("[]")
        val transformer = fakeTransformer()
        installTransformHook(transformer) { _: dynamic, _: dynamic -> }
        val frame = realFrame()
        transformer.transform(frame, fakeController(sink))
        assertEquals(0, sink.length as Int)
        frame.close()
    }

    @Test
    fun theOverlayOnlyTransformer_isAValidTransformStreamSource_andRunsTheProcessorPerFrame() =
        GlobalScope.promise {
            if (!apisPresent()) return@promise
            val seen: dynamic = js("[]")
            val transformer =
                createOverlayOnlyTransformer { frame: dynamic, enqueue: dynamic ->
                    seen.push(frame)
                    enqueue.call(null, frame)
                }
            transformer.init(js("({})")).unsafeCast<Promise<dynamic>>().await()
            val stream = transformer.transformer
            assertNotNull(stream.readable)
            assertNotNull(stream.writable)
            val writer = stream.writable.getWriter()
            val reader = stream.readable.getReader()
            val frame = realFrame()
            writer.write(frame)
            val result = reader.read().unsafeCast<Promise<dynamic>>().await()
            assertEquals(false, result.done as Boolean)
            assertEquals(1, seen.length as Int)
            assertTrue(result.value != null)
            result.value.close()
        }
}
