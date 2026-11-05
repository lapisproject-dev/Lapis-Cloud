package network.lapis.cloud.client.livekit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V1.9.282 -- the guest's screen share button is fail-closed: only a source list that NAMES the screen share allows it. */
class ScreenShareAllowedByPermissionTest {
    @Test
    fun anEmptySourceList_isNotAllowed_unlikeTheMicrophone() {
        assertFalse(screenShareAllowedByPermission(canPublish = true, publishSources = emptyList()))
        assertTrue(microphoneAllowedByPermission(canPublish = true, publishSources = emptyList()))
    }

    @Test
    fun theScreenShareSource_inTheList_allows() {
        assertEquals(3, LIVEKIT_TRACK_SOURCE_SCREEN_SHARE)
        assertTrue(screenShareAllowedByPermission(canPublish = true, publishSources = listOf(1, 2, 3, 4)))
        assertTrue(screenShareAllowedByPermission(canPublish = true, publishSources = listOf(3)))
    }

    @Test
    fun withoutTheSource_orWithoutCanPublish_itIsNotAllowed() {
        assertFalse(screenShareAllowedByPermission(canPublish = true, publishSources = listOf(1, 2)))
        assertFalse(screenShareAllowedByPermission(canPublish = false, publishSources = listOf(1, 2, 3, 4)))
    }
}
