package network.lapis.cloud.client.livekit

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import network.lapis.cloud.client.NoiseSuppressionUnavailable
import kotlin.js.Promise
import kotlin.js.unsafeCast
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private typealias NoiseRoomListener = (dynamic, dynamic, dynamic, dynamic) -> Unit

/**
 * V1.9.114 -- the filter chain of [LiveKitRoomSession.noiseSuppressionChain] against a fake room with a fake microphone track: the processor is
 * put in front of the SAME track (no unpublish, no mute call, the mute flag is not touched), the audio context is the session's own (never the
 * room's web-audio mix), a context that does not run is a failure BEFORE any processor is set (a suspended context delivers silence), a failing or
 * late-suspended processor is removed again, only `noiseSuppression` is ever set on the raw track (never echo cancellation or gain), and the
 * context is closed on [LiveKitRoomSession.disconnect].
 */
class LiveKitRoomSessionNoiseSuppressionTest {
    private class FakeTrack {
        var muted = true
        var processor: dynamic = null
        var setProcessorCalls = 0
        var stopProcessorCalls = 0
        var audioContext: dynamic = null
        var rejectSetProcessor = false
        var onSetProcessor: () -> Unit = {}
        val constraintCalls = mutableListOf<dynamic>()

        fun asTrack(): dynamic {
            val t: dynamic = js("({})")
            t.kind = "audio"
            t.isMuted = muted
            t.setProcessor = { p: dynamic ->
                setProcessorCalls++
                onSetProcessor()
                if (rejectSetProcessor) {
                    Promise.reject(IllegalStateException("init failed"))
                } else {
                    processor = p
                    Promise.resolve(Unit)
                }
            }
            t.stopProcessor = { _: dynamic ->
                stopProcessorCalls++
                processor = null
                Promise.resolve(Unit)
            }
            t.setAudioContext = { ctx: dynamic -> audioContext = ctx }
            t.getProcessor = { processor }
            t.applyConstraints = { c: dynamic ->
                constraintCalls.add(c)
                Promise.resolve(Unit)
            }
            return t
        }
    }

    private class FakeContext(
        var state: String = "suspended",
        var sampleRate: Int = 48_000,
        private val resumesTo: String = "running",
    ) {
        var closed = false
        var resumeCalls = 0

        fun asContext(): dynamic {
            val c: dynamic = js("({})")
            c.state = state
            c.sampleRate = sampleRate
            c.resume = {
                resumeCalls++
                c.state = resumesTo
                Promise.resolve(Unit)
            }
            c.close = {
                closed = true
                c.state = "closed"
                Promise.resolve(Unit)
            }
            return c
        }
    }

    private class Harness(
        withMicrophone: Boolean = true,
        publishEnabled: Boolean = true,
        context: FakeContext = FakeContext(),
    ) {
        val track = FakeTrack()
        val fakeContext = context
        val jsContext: dynamic = fakeContext.asContext()
        var microphoneEnabledCalls = 0
        var microphonePublished = 0
        var cameraPublished = 0
        val listeners = mutableMapOf<String, NoiseRoomListener>()
        var created = 0

        private val publication: dynamic = js("({})")

        init {
            publication.source = "microphone"
            publication.track = if (withMicrophone) track.asTrack() else null
        }

        val session =
            LiveKitRoomSession(
                onRemoteTrack = { _, _, _, _ -> },
                onRemoteTrackGone = { _, _, _ -> },
                onParticipantJoined = { _, _ -> },
                onParticipantLeft = { _ -> },
                onLocalVideoTrack = { _ -> },
                onLocalTrackMuteChanged = { _, _ -> },
                onRecordingStatusChanged = { _ -> },
                onActiveSpeakersChanged = { _ -> },
                onActiveDeviceChanged = { _, _ -> },
                onMediaDevicesChanged = {},
                onMediaDevicesError = { _, _ -> },
                onChat = { _ -> },
                onWhiteboardPreview = { _, _, _ -> },
                onWhiteboardCommit = { _, _, _ -> },
                onNotesCommit = { _, _, _ -> },
                onReconnecting = {},
                onReconnected = {},
                onDisconnected = { _ -> },
                onLocalCameraTrackPublished = { _ -> cameraPublished++ },
                onLocalMicrophoneTrackPublished = { microphonePublished++ },
                roomFactory = { _ -> asRoom() },
                publishEnabled = publishEnabled,
            ).also { s ->
                s.noiseAudioContextFactory = {
                    created++
                    jsContext
                }
                s.noiseProcessorFactory = {
                    val p: dynamic = js("({})")
                    p.name = RNNOISE_LIVEKIT_PROCESSOR_NAME
                    p
                }
            }

        private fun asRoom(): Room {
            val fake: dynamic = js("({})")
            fake.isRecording = false
            fake.remoteParticipants = js("([])")
            val local: dynamic = js("({})")
            local.identity = "local"
            local.name = "Local"
            local.getTrackPublication = { source: String -> if (source == "microphone") publication else undefined }
            local.setMicrophoneEnabled = { _: Boolean ->
                microphoneEnabledCalls++
                Promise.resolve(null)
            }
            fake.localParticipant = local
            fake.connect = { _: String, _: String -> Promise.resolve(Unit) }
            fake.disconnect = { _: dynamic -> Promise.resolve(Unit) }
            fake.on = { event: String, l: NoiseRoomListener ->
                listeners[event] = l
                fake
            }
            fake.off = { _: String, _: NoiseRoomListener -> fake }
            return fake.unsafeCast<Room>()
        }

        suspend fun connect() {
            session.connect("wss://example.invalid", "token")
        }
    }

    @Test
    fun attach_putsTheProcessorInFrontOfTheSameTrack_withTheSessionsOwnContext_andLeavesTheMuteStateAlone() =
        GlobalScope.promise {
            val h = Harness()
            h.connect()
            val chain = assertNotNull(h.session.noiseSuppressionChain())
            assertNull(chain.attach())
            assertEquals(1, h.track.setProcessorCalls)
            assertSame(h.jsContext, h.track.audioContext, "the track got the session's own context")
            assertEquals(RNNOISE_LIVEKIT_PROCESSOR_NAME, h.track.processor.name as String)
            assertEquals(0, h.microphoneEnabledCalls, "no unpublish, no enable/disable: the same track keeps being sent")
            assertEquals(1, h.created)
            assertTrue(h.track.muted, "a muted track stays muted (the flag is not touched)")
        }

    @Test
    fun attach_isIdempotent_asecondCallDoesNotSetAnotherProcessor() =
        GlobalScope.promise {
            val h = Harness()
            h.connect()
            val chain = assertNotNull(h.session.noiseSuppressionChain())
            assertNull(chain.attach())
            assertNull(chain.attach())
            assertNull(chain.attach())
            assertEquals(1, h.track.setProcessorCalls)
            assertEquals(1, h.created, "one context for the whole session")
        }

    @Test
    fun aContextThatStaysSuspended_isALoadFailure_andNoProcessorIsEverSet() =
        GlobalScope.promise {
            val h = Harness(context = FakeContext(resumesTo = "suspended"))
            h.connect()
            val chain = assertNotNull(h.session.noiseSuppressionChain())
            assertEquals(NoiseSuppressionUnavailable.LOAD_FAILED, chain.attach())
            assertEquals(0, h.track.setProcessorCalls, "a suspended context would deliver silence")
        }

    @Test
    fun aContextWithAnotherSampleRate_isALoadFailure() =
        GlobalScope.promise {
            val h = Harness(context = FakeContext(sampleRate = 44_100))
            h.connect()
            assertEquals(NoiseSuppressionUnavailable.LOAD_FAILED, assertNotNull(h.session.noiseSuppressionChain()).attach())
            assertEquals(0, h.track.setProcessorCalls)
        }

    @Test
    fun aBrowserWithoutAnAudioContext_isALoadFailure() =
        GlobalScope.promise {
            val h = Harness()
            h.session.noiseAudioContextFactory = { null }
            h.connect()
            assertEquals(NoiseSuppressionUnavailable.LOAD_FAILED, assertNotNull(h.session.noiseSuppressionChain()).attach())
            assertEquals(0, h.track.setProcessorCalls)
        }

    @Test
    fun aProcessorThatFailsToInitialise_isRemovedAgain_andReportedAsLoadFailed() =
        GlobalScope.promise {
            val h = Harness()
            h.track.rejectSetProcessor = true
            h.connect()
            assertEquals(NoiseSuppressionUnavailable.LOAD_FAILED, assertNotNull(h.session.noiseSuppressionChain()).attach())
            assertEquals(1, h.track.stopProcessorCalls)
            assertNull(h.track.processor)
        }

    @Test
    fun aContextThatSuspendsDuringTheLoad_isRemovedAgain() =
        GlobalScope.promise {
            val h = Harness()
            h.track.onSetProcessor = { h.jsContext.state = "suspended" }
            h.connect()
            assertEquals(NoiseSuppressionUnavailable.LOAD_FAILED, assertNotNull(h.session.noiseSuppressionChain()).attach())
            assertEquals(1, h.track.stopProcessorCalls)
        }

    @Test
    fun detach_stopsTheProcessor_andIsANoOpWithoutOne() =
        GlobalScope.promise {
            val h = Harness()
            h.connect()
            val chain = assertNotNull(h.session.noiseSuppressionChain())
            chain.detach()
            assertEquals(0, h.track.stopProcessorCalls, "nothing to stop")
            chain.attach()
            chain.detach()
            assertEquals(1, h.track.stopProcessorCalls)
            assertNull(h.track.processor)
        }

    @Test
    fun theBrowsersOwnReduction_isTheOnlyConstraintEverSet_neverEchoCancellationOrGain() =
        GlobalScope.promise {
            val h = Harness()
            h.connect()
            val chain = assertNotNull(h.session.noiseSuppressionChain())
            chain.setBrowserNoiseSuppression(false)
            chain.setBrowserNoiseSuppression(true)
            assertEquals(2, h.track.constraintCalls.size)
            h.track.constraintCalls.forEach { constraints ->
                val keys = js("Object").keys(constraints).unsafeCast<Array<String>>().toList()
                assertEquals(listOf("noiseSuppression"), keys)
            }
            assertEquals(false, h.track.constraintCalls[0].noiseSuppression)
            assertEquals(true, h.track.constraintCalls[1].noiseSuppression)
        }

    @Test
    fun prepare_createsAndResumesTheContextInsideTheGesture() =
        GlobalScope.promise {
            val h = Harness()
            h.connect()
            assertNotNull(h.session.noiseSuppressionChain()).prepare()
            assertEquals(1, h.created)
            assertEquals(1, h.fakeContext.resumeCalls)
        }

    @Test
    fun disconnect_closesTheContext() =
        GlobalScope.promise {
            val h = Harness()
            h.connect()
            assertNotNull(h.session.noiseSuppressionChain()).attach()
            assertFalse(h.fakeContext.closed)
            h.session.disconnect()
            assertTrue(h.fakeContext.closed)
        }

    @Test
    fun noMicrophoneTrack_meansNoChain() =
        GlobalScope.promise {
            val h = Harness(withMicrophone = false)
            h.connect()
            assertNull(h.session.noiseSuppressionChain())
        }

    @Test
    fun aListenOnlySession_neverGetsAChain() =
        GlobalScope.promise {
            val h = Harness(publishEnabled = false)
            h.connect()
            assertNull(h.session.noiseSuppressionChain())
        }

    @Test
    fun aPublishedMicrophone_isRelayedOnce_aPublishedCameraIsNot() =
        GlobalScope.promise {
            val h = Harness()
            h.connect()
            val micPublication: dynamic = js("({})")
            micPublication.source = "microphone"
            micPublication.track = js("({ kind: 'audio' })")
            h.listeners.getValue(RoomEvent.LocalTrackPublished)(micPublication, null, null, null)
            assertEquals(1, h.microphonePublished)
            assertEquals(0, h.cameraPublished)
            val cameraPublication: dynamic = js("({})")
            cameraPublication.source = "camera"
            cameraPublication.track = js("({ kind: 'video' })")
            h.listeners.getValue(RoomEvent.LocalTrackPublished)(cameraPublication, null, null, null)
            assertEquals(1, h.microphonePublished)
            assertEquals(1, h.cameraPublished)
        }
}
