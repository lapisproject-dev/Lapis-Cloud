package network.lapis.cloud.client.livekit

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlin.js.Promise
import kotlin.js.jsTypeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * V1.9.114 -- the loading cache and the processor object of the noise suppression, with fake nodes (a real `AudioWorkletNode` needs a real
 * audio context): the WebAssembly and the worklet module load at most once per page / per context, a failed load is NOT remembered, the chain is
 * source -> worklet -> destination, a device change ([RnnoiseTrackProcessor.restart]) rebuilds it around the new raw track WITHOUT loading
 * again, [RnnoiseTrackProcessor.destroy] takes every node apart, and the plain JS object LiveKit calls has the five members it reads.
 */
private fun fakeNode(label: String): dynamic {
    val n: dynamic = js("({})")
    n.label = label
    n.connectedTo = js("([])")
    n.disconnects = 0
    n.connect = { target: dynamic -> n.connectedTo.push(target) }
    n.disconnect = { n.disconnects = (n.disconnects as Int) + 1 }
    return n
}

class RnnoiseTrackProcessorTest {
    private class Fakes {
        var wasmLoads = 0
        var wasmFailures = 0
        var moduleLoads = 0
        var moduleFailures = 0
        val modules = mutableListOf<dynamic>()
        val nodes = mutableListOf<dynamic>()

        val wasm: dynamic = js("({ byteLength: 152656 })")

        val cache =
            RnnoiseAssetCache(
                fetchWasm = {
                    wasmLoads++
                    if (wasmFailures > 0) {
                        wasmFailures--
                        error("wasm refused")
                    }
                    wasm
                },
                addModule = { ctx ->
                    moduleLoads++
                    if (moduleFailures > 0) {
                        moduleFailures--
                        error("module refused")
                    }
                    modules.add(ctx)
                },
            )
    }

    private fun context(
        fakes: Fakes,
        nodeFactory: (String) -> dynamic,
    ): dynamic {
        val ctx: dynamic = js("({})")
        ctx.createMediaStreamSource = { _: dynamic ->
            val source = nodeFactory("source")
            fakes.nodes.add(source)
            source
        }
        ctx.createMediaStreamDestination = {
            val destination = nodeFactory("destination")
            val track: dynamic = js("({ kind: 'audio' })")
            destination.stream = js("({})")
            destination.stream.getAudioTracks = {
                val list: dynamic = js("([])")
                list.push(track)
                list
            }
            destination.track = track
            fakes.nodes.add(destination)
            destination
        }
        return ctx
    }

    private fun processor(fakes: Fakes): RnnoiseTrackProcessor =
        RnnoiseTrackProcessor(
            assets = fakes.cache,
            newStream = { track -> track },
            newWorkletNode = { _, options ->
                val n = fakeNode("worklet")
                n.options = options
                n.port = js("({ messages: [] })")
                n.port.postMessage = { message: dynamic -> n.port.messages.push(message) }
                fakes.nodes.add(n)
                n
            },
        )

    private fun options(
        ctx: dynamic,
        track: dynamic = js("({ id: 'raw-1' })"),
    ): dynamic {
        val o: dynamic = js("({})")
        o.audioContext = ctx
        o.track = track
        return o
    }

    @Test
    fun theWasmLoadsOncePerPage_andAFailedLoadIsNotRemembered() =
        GlobalScope.promise {
            val fakes = Fakes()
            fakes.wasmFailures = 1
            assertFailsWith<IllegalStateException> { fakes.cache.wasm() }
            assertEquals(1, fakes.wasmLoads)
            val first = fakes.cache.wasm()
            val second = fakes.cache.wasm()
            val third = fakes.cache.wasm()
            assertSame(first, second)
            assertSame(second, third)
            assertEquals(2, fakes.wasmLoads, "one failed attempt, one successful load, then cached")
        }

    @Test
    fun theWorkletModuleLoadsOncePerContext_andAFailedLoadIsNotRemembered() =
        GlobalScope.promise {
            val fakes = Fakes()
            val a: dynamic = js("({})")
            val b: dynamic = js("({})")
            fakes.moduleFailures = 1
            assertFailsWith<IllegalStateException> { fakes.cache.ensureModule(a) }
            fakes.cache.ensureModule(a)
            fakes.cache.ensureModule(a)
            assertEquals(2, fakes.moduleLoads, "a failed attempt is retried, then remembered for this context")
            fakes.cache.ensureModule(b)
            assertEquals(3, fakes.moduleLoads, "another context needs its own registration")
            assertSame(a, fakes.modules[0])
            assertSame(b, fakes.modules[1])
        }

    @Test
    fun init_buildsSourceToWorkletToDestination_andHandsOverTheDestinationTrack() =
        GlobalScope.promise {
            val fakes = Fakes()
            val ctx = context(fakes, ::fakeNode)
            val p = processor(fakes)
            assertNull(p.processedTrack)
            p.init(options(ctx))
            val source = fakes.nodes.first { it.label == "source" }
            val worklet = fakes.nodes.first { it.label == "worklet" }
            val destination = fakes.nodes.first { it.label == "destination" }
            assertSame(worklet, source.connectedTo[0], "source feeds the worklet")
            assertSame(destination, worklet.connectedTo[0], "the worklet feeds the destination")
            assertSame(destination.track, p.processedTrack)
            assertEquals(1, worklet.options.processorOptions.maxChannels)
            assertSame(fakes.wasm, worklet.options.processorOptions.wasmBinary)
            assertEquals(1, worklet.options.channelCount)
            assertEquals("explicit", worklet.options.channelCountMode)
            assertEquals(1, fakes.wasmLoads)
            assertEquals(1, fakes.moduleLoads)
        }

    @Test
    fun init_withoutAnAudioContext_fails() =
        GlobalScope.promise {
            val fakes = Fakes()
            val p = processor(fakes)
            val o: dynamic = js("({})")
            o.track = js("({})")
            assertFailsWith<IllegalStateException> { p.init(o) }
        }

    @Test
    fun restart_rebuildsAroundTheNewRawTrack_withoutLoadingAgain_andTakesTheOldNodesApart() =
        GlobalScope.promise {
            val fakes = Fakes()
            val ctx = context(fakes, ::fakeNode)
            val p = processor(fakes)
            p.init(options(ctx))
            val firstTrack = p.processedTrack
            val oldNodes = fakes.nodes.toList()
            // LiveKit's `restart` does NOT repeat the audio context: the processor keeps the one from `init`
            val restartOptions: dynamic = js("({})")
            restartOptions.track = js("({ id: 'raw-2' })")
            p.restart(restartOptions)
            assertTrue(p.processedTrack !== firstTrack, "a new processed track")
            oldNodes.forEach { assertTrue((it.disconnects as Int) >= 1, "old ${it.label} is disconnected") }
            assertEquals(1, fakes.wasmLoads)
            assertEquals(1, fakes.moduleLoads)
        }

    @Test
    fun destroy_disconnectsEveryNode_tellsTheWorklet_dropsTheTrack_andForbidsARestart() =
        GlobalScope.promise {
            val fakes = Fakes()
            val ctx = context(fakes, ::fakeNode)
            val p = processor(fakes)
            p.init(options(ctx))
            val worklet = fakes.nodes.first { it.label == "worklet" }
            p.destroy()
            fakes.nodes.forEach { assertTrue((it.disconnects as Int) >= 1, "${it.label} is disconnected") }
            assertEquals("destroy", worklet.port.messages[0])
            assertNull(p.processedTrack)
            val o: dynamic = js("({})")
            o.track = js("({})")
            assertFailsWith<IllegalStateException> { p.restart(o) }
        }

    @Test
    fun aFailingLoad_leavesNoNodesAndNoTrack() =
        GlobalScope.promise {
            val fakes = Fakes()
            fakes.wasmFailures = 1
            val ctx = context(fakes, ::fakeNode)
            val p = processor(fakes)
            assertFailsWith<IllegalStateException> { p.init(options(ctx)) }
            assertNull(p.processedTrack)
            assertTrue(fakes.nodes.isEmpty())
        }

    @Test
    fun theLiveKitObject_hasTheFiveMembersLiveKitReads_andSetsTheTrackBeforeInitResolves() =
        GlobalScope.promise {
            val fakes = Fakes()
            val ctx = context(fakes, ::fakeNode)
            val adapter = processor(fakes).asLiveKitProcessor()
            assertEquals(RNNOISE_LIVEKIT_PROCESSOR_NAME, adapter.name)
            assertEquals("function", jsTypeOf(adapter.init))
            assertEquals("function", jsTypeOf(adapter.restart))
            assertEquals("function", jsTypeOf(adapter.destroy))
            adapter.init(options(ctx)).unsafeCast<Promise<dynamic>>().await()
            assertNotNull(adapter.processedTrack)
            assertEquals("audio", adapter.processedTrack.kind)
            val restartOptions: dynamic = js("({})")
            restartOptions.track = js("({})")
            adapter.restart(restartOptions).unsafeCast<Promise<dynamic>>().await()
            assertNotNull(adapter.processedTrack)
            adapter.destroy().unsafeCast<Promise<dynamic>>().await()
            assertNull(adapter.processedTrack)
        }
}
