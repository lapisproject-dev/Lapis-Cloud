package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V1.9.169 -- the pure decisions of the device picker without browser permission. */
class ConferenceDevicePermissionStateTest {
    private fun state(
        status: DevicePermissionStatus,
        entries: Int,
        usable: Int,
    ) = conferenceDevicePermissionState(status, RawEnumeration(entries, usable))

    @Test
    fun promptAndDenied_areNeedsAccessAndBlocked() {
        assertEquals(DevicePermissionState.NEEDS_ACCESS, state(DevicePermissionStatus.PROMPT, 1, 0))
        assertEquals(DevicePermissionState.BLOCKED, state(DevicePermissionStatus.DENIED, 1, 0))
        assertEquals(DevicePermissionState.BLOCKED, state(DevicePermissionStatus.DENIED, 0, 0))
    }

    @Test
    fun unknownStatus_fallsBackOnTheEnumeration() {
        assertEquals(DevicePermissionState.NEEDS_ACCESS, state(DevicePermissionStatus.UNKNOWN, 1, 0), "blank ids")
        assertEquals(DevicePermissionState.NEEDS_ACCESS, state(DevicePermissionStatus.UNKNOWN, 0, 0), "empty list")
        assertEquals(DevicePermissionState.GRANTED, state(DevicePermissionStatus.UNKNOWN, 2, 2), "a member who allowed it long ago")
    }

    @Test
    fun grantedWithAnEmptyList_staysGranted_soTheRowHidesAsBefore() {
        val granted = state(DevicePermissionStatus.GRANTED, 0, 0)
        assertEquals(DevicePermissionState.GRANTED, granted)
        assertEquals(DeviceRowMode.HIDDEN, conferenceDeviceRowMode(granted, 0))
        assertEquals(DeviceRowMode.NORMAL, conferenceDeviceRowMode(granted, 2))
    }

    @Test
    fun withoutAccess_theRowIsAPlaceholder_evenWithZeroUsableOptions() {
        assertEquals(DeviceRowMode.PLACEHOLDER, conferenceDeviceRowMode(DevicePermissionState.NEEDS_ACCESS, 0))
        assertEquals(DeviceRowMode.PLACEHOLDER, conferenceDeviceRowMode(DevicePermissionState.BLOCKED, 0))
    }

    @Test
    fun theBlock_isVisibleWhileAnyKindLacksAccess() {
        val g = DevicePermissionState.GRANTED
        assertFalse(conferencePermissionBlockVisible(g, g))
        assertTrue(conferencePermissionBlockVisible(DevicePermissionState.NEEDS_ACCESS, g))
        assertTrue(conferencePermissionBlockVisible(g, DevicePermissionState.BLOCKED))
    }

    @Test
    fun thePlaceholderValue_isBlank_soNoDeviceSwitchIsEverApplied() {
        assertEquals(null, conferenceDeviceSelectionToApply(applyingProgrammatic = false, deviceId = ""))
    }

    @Test
    fun promptWithAnEmptyList_isNoDevice_notAMissingAccess() {
        // a desktop PC without a webcam: Chrome says "prompt" and lists no videoinput
        assertEquals(DevicePermissionState.GRANTED, state(DevicePermissionStatus.PROMPT, 0, 0))
        val granted = state(DevicePermissionStatus.PROMPT, 0, 0)
        assertEquals(DeviceRowMode.HIDDEN, conferenceDeviceRowMode(granted, 0))
        assertFalse(
            conferencePermissionBlockVisible(DevicePermissionState.GRANTED, granted),
            "the block does not stay for a missing camera",
        )
        assertEquals(DevicePermissionState.NEEDS_ACCESS, state(DevicePermissionStatus.PROMPT, 1, 0), "blank ids: a device exists")
    }
}
