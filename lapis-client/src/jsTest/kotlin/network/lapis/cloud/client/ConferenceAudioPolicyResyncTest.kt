package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertEquals

/** V1.9.108 -- after a failed policy call the panel follows the stored room, never its own guess. */
class ConferenceAudioPolicyResyncTest {
    @Test
    fun failedUnlock_thatWasStored_showsUnlocked() {
        // local still believes "locked" (mayUnmute=false) but the server committed the unlock before LiveKit failed
        val resynced =
            conferenceAudioPolicyResync(
                local = ConferenceAudioPolicyState(muteOnJoin = false, participantsMayUnmute = false),
                stored = ConferenceAudioPolicyState(muteOnJoin = false, participantsMayUnmute = true),
            )
        assertEquals(true, resynced.participantsMayUnmute)
    }

    @Test
    fun failedLock_thatWasStored_showsLocked() {
        val resynced =
            conferenceAudioPolicyResync(
                local = ConferenceAudioPolicyState(muteOnJoin = true, participantsMayUnmute = true),
                stored = ConferenceAudioPolicyState(muteOnJoin = true, participantsMayUnmute = false),
            )
        assertEquals(ConferenceAudioPolicyState(true, false), resynced)
    }

    @Test
    fun unreadableRoom_keepsTheLocalState() {
        val local = ConferenceAudioPolicyState(muteOnJoin = true, participantsMayUnmute = false)
        assertEquals(local, conferenceAudioPolicyResync(local = local, stored = null))
    }
}
