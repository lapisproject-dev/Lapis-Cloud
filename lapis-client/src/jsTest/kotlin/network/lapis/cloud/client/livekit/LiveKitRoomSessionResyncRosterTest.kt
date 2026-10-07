package network.lapis.cloud.client.livekit

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ELB-Test-Fix 2026-09-27 (Befund 7, "Wiederbeitritt Mobil -> Browser bleibt unsichtbar bis zum
 * eigenen Reload") -- covers [LiveKitRoomSession.resyncRoster] against a duck-typed fake `Room`
 * injected through the `roomFactory` test seam, same posture as [LiveKitRoomSessionConnectRetryTest].
 * The SDK shapes it walks (`remoteParticipants`/`trackPublications` as JS `Map`s with `forEach`,
 * `TrackPublication.isSubscribed`/`.track`) are modelled with real JS `Map` instances.
 */
class LiveKitRoomSessionResyncRosterTest {
    private fun jsMap(vararg entries: Pair<String, dynamic>): dynamic {
        val map: dynamic = js("new Map()")
        entries.forEach { (k, v) -> map.set(k, v) }
        return map
    }

    private fun publication(
        source: String,
        kind: String,
        subscribed: Boolean,
        withTrack: Boolean,
    ): dynamic {
        val pub: dynamic = js("({})")
        pub.trackSid = "TR_$source"
        pub.source = source
        pub.isSubscribed = subscribed
        if (withTrack) {
            val track: dynamic = js("({})")
            track.kind = kind
            pub.track = track
        } else {
            pub.track = null
        }
        return pub
    }

    private fun participant(
        identity: String,
        name: String?,
        vararg pubs: dynamic,
    ): dynamic {
        val p: dynamic = js("({})")
        p.identity = identity
        p.name = name
        p.trackPublications = jsMap(*pubs.map { (it.trackSid as String) to it }.toTypedArray())
        return p
    }

    private fun fakeRoom(remoteParticipants: dynamic): Room {
        val fake: dynamic = js("({})")
        fake.isRecording = false
        fake.remoteParticipants = remoteParticipants
        val local: dynamic = js("({})")
        local.identity = "local"
        fake.localParticipant = local
        fake.connect = { _: String, _: String -> Promise.resolve(Unit) }
        fake.disconnect = { _: dynamic -> Promise.resolve(Unit) }
        fake.on = { _: String, _: dynamic -> fake }
        fake.off = { _: String, _: dynamic -> fake }
        return fake.unsafeCast<Room>()
    }

    private class Recorder {
        val joined = mutableListOf<Pair<String, String>>()
        val tracks = mutableListOf<Triple<String, String, String>>()
    }

    private fun session(
        recorder: Recorder,
        room: Room,
    ) = LiveKitRoomSession(
        onRemoteTrack = { identity, _, track, publication -> recorder.tracks += Triple(identity, publication.source, track.kind) },
        onRemoteTrackGone = { _, _, _ -> },
        onParticipantJoined = { identity, displayName -> recorder.joined += identity to displayName },
        onParticipantLeft = { },
        onLocalVideoTrack = { },
        onLocalTrackMuteChanged = { _, _ -> },
        onRecordingStatusChanged = { },
        onActiveSpeakersChanged = { },
        onActiveDeviceChanged = { _, _ -> },
        onMediaDevicesChanged = { },
        onMediaDevicesError = { _, _ -> },
        onChat = { },
        onWhiteboardPreview = { _, _, _ -> },
        onWhiteboardCommit = { _, _, _ -> },
        onNotesCommit = { _, _, _ -> },
        onReconnecting = { },
        onReconnected = { },
        onDisconnected = { _ -> },
        roomFactory = { room },
    )

    @Test
    fun resyncRoster_beforeConnect_isEmptyAndFiresNothing() {
        val recorder = Recorder()
        val s = session(recorder, fakeRoom(jsMap()))
        assertTrue(s.resyncRoster().isEmpty())
        assertTrue(recorder.joined.isEmpty())
        assertTrue(recorder.tracks.isEmpty())
    }

    @Test
    fun resyncRoster_replaysJoinAndSubscribedTracks_skipsUnsubscribedAndTrackless() =
        GlobalScope.promise {
            val recorder = Recorder()
            val phoneUser =
                participant(
                    "member-phone",
                    "Phone User",
                    publication(source = "camera", kind = "video", subscribed = true, withTrack = true),
                    publication(source = "microphone", kind = "audio", subscribed = true, withTrack = true),
                    publication(source = "screen_share", kind = "video", subscribed = false, withTrack = false),
                )
            val silent =
                participant(
                    "member-silent",
                    null,
                    publication(source = "camera", kind = "video", subscribed = true, withTrack = false),
                )
            val s = session(recorder, fakeRoom(jsMap("member-phone" to phoneUser, "member-silent" to silent)))
            assertEquals(null, s.connect("wss://example.invalid", "token"))
            // connect's own seedRoster already announced both once -- clear and look at the resync alone.
            recorder.joined.clear()
            recorder.tracks.clear()

            val present = s.resyncRoster()

            assertEquals(setOf("member-phone", "member-silent"), present)
            assertEquals(listOf("member-phone" to "Phone User", "member-silent" to "member-silent"), recorder.joined)
            assertEquals(
                listOf(Triple("member-phone", "camera", "video"), Triple("member-phone", "microphone", "audio")),
                recorder.tracks,
                "only subscribed publications that carry a track are replayed",
            )
        }

    @Test
    fun resyncRoster_isIdempotent_secondCallReplaysTheSameCallbacks() =
        GlobalScope.promise {
            val recorder = Recorder()
            val p = participant("m", "M", publication(source = "microphone", kind = "audio", subscribed = true, withTrack = true))
            val s = session(recorder, fakeRoom(jsMap("m" to p)))
            s.connect("wss://example.invalid", "token")
            recorder.joined.clear()
            recorder.tracks.clear()
            s.resyncRoster()
            s.resyncRoster()
            assertEquals(2, recorder.joined.size)
            assertEquals(2, recorder.tracks.size, "the screen-side handler is what makes this idempotent (detach before attach)")
        }

    @Test
    fun resyncRoster_malformedSdkShape_neverThrows() =
        GlobalScope.promise {
            val recorder = Recorder()
            val broken: dynamic = js("({})")
            broken.identity = "broken"
            broken.trackPublications = "not-a-map"
            val s = session(recorder, fakeRoom(jsMap("broken" to broken)))
            s.connect("wss://example.invalid", "token")
            val present = s.resyncRoster()
            // The participant itself was announced before its publications blew up; the failure is
            // contained, the set reflects what was walked.
            assertEquals(setOf("broken"), present)
        }
}
