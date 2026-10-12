package network.lapis.cloud.client.livekit

import kotlinx.browser.window
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import network.lapis.cloud.client.NoiseSuppressionFailure
import network.lapis.cloud.client.NoiseSuppressionStageException
import network.lapis.cloud.client.NoiseSuppressionUnavailable
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

    // ── V1.9.119: every failing step names its stage ──────────────────────────────────────────────────────────────

    @Test
    fun aWasmThatFailsToLoad_isTheWasmFetchStage_withTheErrorBehindIt() =
        GlobalScope.promise {
            val fakes = Fakes()
            fakes.wasmFailures = 1
            val e = assertFailsWith<NoiseSuppressionStageException> { fakes.cache.wasm() }
            assertEquals(NoiseSuppressionUnavailable.WASM_FETCH, e.failure.reason)
            assertEquals("wasm refused", e.failure.errorMessage)
        }

    @Test
    fun aStageExceptionOfTheFetch_isNotWrappedAgain() =
        GlobalScope.promise {
            val own = NoiseSuppressionStageException(NoiseSuppressionFailure(NoiseSuppressionUnavailable.WASM_FETCH, null, "status=404"))
            val cache = RnnoiseAssetCache(fetchWasm = { throw own }, addModule = {})
            assertSame(own, assertFailsWith<NoiseSuppressionStageException> { cache.wasm() })
        }

    @Test
    fun aModuleThatFailsToLoad_isTheWorkletModuleStage() =
        GlobalScope.promise {
            val fakes = Fakes()
            fakes.moduleFailures = 1
            val e = assertFailsWith<NoiseSuppressionStageException> { fakes.cache.ensureModule(js("({})")) }
            assertEquals(NoiseSuppressionUnavailable.WORKLET_MODULE, e.failure.reason)
        }

    @Test
    fun init_withoutAnAudioContext_isTheNoAudioContextStage() =
        GlobalScope.promise {
            val fakes = Fakes()
            val o: dynamic = js("({})")
            o.track = js("({})")
            val e = assertFailsWith<NoiseSuppressionStageException> { processor(fakes).init(o) }
            assertEquals(NoiseSuppressionUnavailable.NO_AUDIO_CONTEXT, e.failure.reason)
        }

    @Test
    fun aNodeThatCannotBeCreated_isTheNodeCreateStage() =
        GlobalScope.promise {
            val fakes = Fakes()
            val ctx = context(fakes, ::fakeNode)
            val p =
                RnnoiseTrackProcessor(
                    assets = fakes.cache,
                    newStream = { track -> track },
                    newWorkletNode = { _, _ -> error("AudioWorkletNode refused") },
                )
            val e = assertFailsWith<NoiseSuppressionStageException> { p.init(options(ctx)) }
            assertEquals(NoiseSuppressionUnavailable.NODE_CREATE, e.failure.reason)
            assertEquals("AudioWorkletNode refused", e.failure.errorMessage)
        }

    @Test
    fun aRestartThatFails_resolves_handsOverTheRawTrack_andReportsTheRestartStage() =
        GlobalScope.promise {
            val fakes = Fakes()
            val ctx = context(fakes, ::fakeNode)
            var failNext = false
            val p =
                RnnoiseTrackProcessor(
                    assets = fakes.cache,
                    newStream = { track -> track },
                    newWorkletNode = { _, options ->
                        if (failNext) error("device gone")
                        val n = fakeNode("worklet")
                        n.options = options
                        n.port = js("({ messages: [] })")
                        n.port.postMessage = { message: dynamic -> n.port.messages.push(message) }
                        fakes.nodes.add(n)
                        n
                    },
                )
            val reported = mutableListOf<NoiseSuppressionFailure>()
            val adapter = p.asLiveKitProcessor { reported += it }
            adapter.init(options(ctx)).unsafeCast<Promise<dynamic>>().await()
            val processedBefore = adapter.processedTrack
            assertNotNull(processedBefore)
            failNext = true
            val restartOptions: dynamic = js("({})")
            val raw: dynamic = js("({ id: 'raw-2' })")
            restartOptions.track = raw
            // MUST resolve: a rejection would leave LiveKit without a track
            adapter.restart(restartOptions).unsafeCast<Promise<dynamic>>().await()
            assertSame(raw, adapter.processedTrack, "the raw microphone track is passed on: the person stays audible")
            assertEquals(1, reported.size)
            assertEquals(NoiseSuppressionUnavailable.RESTART, reported.single().reason)
            assertEquals("device gone", reported.single().errorMessage)
        }

    // ── the real fetch, with a stubbed window.fetch ───────────────────────────────────────────────────────────────

    private fun fakeResponse(
        ok: Boolean,
        status: Int,
        type: String?,
        bytes: Int,
    ): dynamic {
        val r: dynamic = js("({})")
        r.ok = ok
        r.status = status
        val headers: dynamic = js("({})")
        headers.get = { _: String -> type }
        r.headers = headers
        r.arrayBuffer = { js("Promise").resolve(js("Reflect").construct(js("ArrayBuffer"), arrayOf(bytes))) }
        return r
    }

    private suspend fun withFetch(
        answer: () -> dynamic,
        block: suspend () -> Unit,
    ) {
        val scope = window.asDynamic()
        val original = scope.fetch
        scope.fetch = { _: String, _: dynamic -> answer() }
        try {
            block()
        } finally {
            scope.fetch = original
        }
    }

    private suspend fun fetchFailure(answer: () -> dynamic): NoiseSuppressionFailure {
        var failure: NoiseSuppressionFailure? = null
        withFetch(answer) { failure = assertFailsWith<NoiseSuppressionStageException> { browserFetchRnnoiseWasm() }.failure }
        return assertNotNull(failure)
    }

    @Test
    fun theRealFetch_namesStatusTypeAndSize() =
        GlobalScope.promise {
            val status = fetchFailure { js("Promise").resolve(fakeResponse(false, 404, "text/html", 10)) }
            assertEquals(NoiseSuppressionUnavailable.WASM_FETCH, status.reason)
            assertEquals("status=404", status.errorMessage)
            val type = fetchFailure { js("Promise").resolve(fakeResponse(true, 200, "text/html; charset=utf-8", 10)) }
            assertEquals("type=text/html", type.errorMessage)
            val size = fetchFailure { js("Promise").resolve(fakeResponse(true, 200, "application/wasm", 2_000_000)) }
            assertEquals("size=2000000", size.errorMessage)
            val empty = fetchFailure { js("Promise").resolve(fakeResponse(true, 200, "application/wasm", 0)) }
            assertEquals("size=0", empty.errorMessage)
        }

    @Test
    fun theRealFetch_aTimerAbort_isAborted_andAFetchError_keepsItsNameAndMessage() =
        GlobalScope.promise {
            val abort: dynamic = js("new Error('The user aborted a request.')")
            abort.name = "AbortError"
            val aborted = fetchFailure { js("Promise").reject(abort) }
            assertEquals("aborted", aborted.errorMessage)
            assertEquals("AbortError", aborted.errorName)
            val typeError: dynamic = js("new TypeError('Failed to fetch')")
            val failed = fetchFailure { js("Promise").reject(typeError) }
            assertEquals("TypeError", failed.errorName)
            assertEquals("Failed to fetch", failed.errorMessage)
        }

    @Test
    fun theRealFetch_aGoodAnswer_returnsTheBytes() =
        GlobalScope.promise {
            var size = 0
            withFetch({ js("Promise").resolve(fakeResponse(true, 200, "application/wasm", 1_000)) }) {
                size = (browserFetchRnnoiseWasm().byteLength as Number).toInt()
            }
            assertEquals(1_000, size)
        }
}
