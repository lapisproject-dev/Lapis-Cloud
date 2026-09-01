package network.lapis.cloud.client.livekit

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Review-fix regression guard (GitHub Issue #2 review, round 2, "Race Condition / verwechseltes
 * Erfolgs-Signal") -- [LiveKitRoomSession.setCamera]/[LiveKitRoomSession.setMicrophone] must return
 * [ConferenceDeviceFailure.OTHER] for a `null` `room`, never THROW [IllegalStateException]. A fresh
 * [LiveKitRoomSession] that never had [LiveKitRoomSession.connect] called on it has exactly this
 * `null`-room state -- the same state a real session ends up in mid-call once
 * [LiveKitRoomSession.disconnect] has run (see that class's own KDoc for the concrete race: a
 * `disconnect()` racing a mic/camera button click).
 *
 * These two methods are reachable here WITHOUT touching any `livekit-client` external interface at
 * all -- the `room ?: ...` guard runs before anything Room-shaped is ever dereferenced -- so this
 * test needs no LiveKit mocking, unlike almost everything else this class does.
 *
 * Before the round-2 fix, both methods `throw`ed [IllegalStateException] here instead, which
 * `AppState.kt`'s `guarded {}` (the only caller in `ConferenceScreen.kt`) catches and turns into a
 * `null` return -- indistinguishable, at the call site, from this method's own "null == success"
 * contract (see class KDoc "[setCamera]/[setMicrophone]/[setScreenShare] no longer silently do
 * nothing on a null [room]"). This test asserts the return value directly, bypassing `guarded {}`
 * entirely, so a regression back to the old `throw` shape fails loudly instead of silently
 * round-tripping through `guarded {}` back to `null` and looking identical.
 */
class LiveKitRoomSessionDeviceFailureTest {
    private fun newDisconnectedSession() =
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
            onDisconnected = {},
        )

    // Kotlin/JS's `@Test` functions cannot be `suspend` directly (compiler-enforced, see this
    // module's own `compileTestKotlinJs` error if attempted: "'suspend' functions annotated with
    // '@kotlin.test.Test' are unsupported"). The JS test runner DOES natively await a returned
    // `Promise`, though -- `kotlinx.coroutines.promise` (part of `kotlinx-coroutines-core`, already
    // on this module's classpath transitively via `AppScope`/KVision, no new test dependency needed)
    // is the standard bridge from a `suspend` block to that `Promise`.
    @Test
    fun setCamera_noActiveRoom_returnsOtherFailure_doesNotThrow() =
        GlobalScope.promise {
            val session = newDisconnectedSession()
            assertEquals(ConferenceDeviceFailure.OTHER, session.setCamera(true))
        }

    @Test
    fun setMicrophone_noActiveRoom_returnsOtherFailure_doesNotThrow() =
        GlobalScope.promise {
            val session = newDisconnectedSession()
            assertEquals(ConferenceDeviceFailure.OTHER, session.setMicrophone(true))
        }
}
