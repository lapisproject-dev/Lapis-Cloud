package network.lapis.cloud.client.encounter

import network.lapis.cloud.client.livekit.ConferenceDeviceKind
import network.lapis.cloud.client.livekit.ConferenceDeviceKind.CAMERA
import network.lapis.cloud.client.livekit.ConferenceDeviceKind.MICROPHONE
import network.lapis.cloud.client.livekit.ConferenceDeviceKind.SPEAKER
import network.lapis.cloud.client.livekit.ConferenceDeviceOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.91 -- the pure rules of the device picker: who gets which field, when the button exists, how a remembered device is compared with
 * what is there, and what happens when the active device vanishes. No DOM.
 */
class EncounterDevicePickerTest {
    private fun options(vararg ids: String) = ids.map { ConferenceDeviceOption(it, "Name $it") }

    private val none = emptyList<ConferenceDeviceOption>()

    private fun fields(
        role: EncounterDeviceRole,
        quieted: Boolean = false,
        sinkApi: Boolean = true,
        mics: List<ConferenceDeviceOption> = none,
        cams: List<ConferenceDeviceOption> = none,
        outs: List<ConferenceDeviceOption> = none,
        active: Map<ConferenceDeviceKind, String?> = emptyMap(),
    ) = encounterDeviceFields(role, quieted, sinkApi, mics, cams, outs, active)

    private fun List<EncounterDeviceField>.kinds() = map { it.kind }

    // ── the fields per role ──────────────────────────────────────────────────

    @Test
    fun officeHolder_hasMicrophoneCameraAndSpeaker() {
        val result =
            fields(
                EncounterDeviceRole.OFFICE_HOLDER,
                mics = options("m1", "m2"),
                cams = options("c1"),
                outs = options("s1", "s2"),
                active = mapOf(MICROPHONE to "m2"),
            )
        assertEquals(listOf(MICROPHONE, CAMERA, SPEAKER), result.kinds())
        val mic = result[0] as EncounterDeviceField.Choice
        assertEquals("m2", mic.selected)
        assertFalse(mic.disabled)
        val speaker = result[2] as EncounterDeviceField.Choice
        assertEquals(
            listOf(ENCOUNTER_SYSTEM_DEFAULT_OUTPUT, "s1", "s2"),
            speaker.options.map { it.deviceId },
            "the system default stands first",
        )
        assertEquals(ENCOUNTER_SYSTEM_DEFAULT_OUTPUT, speaker.selected, "no choice yet = the system default")
    }

    @Test
    fun officeHolder_withoutDeviceNames_getsAHintInsteadOfAnEmptyField() {
        val result = fields(EncounterDeviceRole.OFFICE_HOLDER)
        assertEquals(listOf(MICROPHONE, CAMERA), result.kinds())
        assertTrue(result.all { it is EncounterDeviceField.PermissionHint })
        assertFalse(encounterDeviceButtonVisible(result), "a hint alone is no reason for a bar control")
    }

    @Test
    fun congregationAtATable_hasTheTableMicrophoneAndTheSpeaker_neverACamera() {
        val result = fields(EncounterDeviceRole.CONGREGATION_AT_TABLE, mics = options("m1"), outs = options("s1", "s2"))
        assertEquals(listOf(MICROPHONE, SPEAKER), result.kinds())
    }

    @Test
    fun aQuietedTable_disablesTheMicrophoneField_orShowsTheQuietSentence() {
        val withOptions = fields(EncounterDeviceRole.CONGREGATION_AT_TABLE, quieted = true, mics = options("m1"))
        val field = withOptions.single() as EncounterDeviceField.Choice
        assertTrue(field.disabled)
        assertFalse(encounterDeviceButtonVisible(withOptions), "a disabled choice alone gives no button")
        val without = fields(EncounterDeviceRole.CONGREGATION_AT_TABLE, quieted = true)
        assertTrue(without.single() is EncounterDeviceField.TableQuieted)
        // a quieted table does not hide the speaker
        val withSpeaker = fields(EncounterDeviceRole.CONGREGATION_AT_TABLE, quieted = true, outs = options("s1", "s2"))
        assertTrue(encounterDeviceButtonVisible(withSpeaker))
    }

    @Test
    fun congregationWithoutATable_hasTheSpeakerOnly() {
        assertEquals(listOf(SPEAKER), fields(EncounterDeviceRole.CONGREGATION, outs = options("s1", "s2")).kinds())
        // a microphone list given to a role that has none is ignored
        assertEquals(listOf(SPEAKER), fields(EncounterDeviceRole.CONGREGATION, mics = options("m1"), outs = options("s1", "s2")).kinds())
    }

    @Test
    fun theSpeakerField_needsTheApiAndAtLeastTwoUsableOutputs() {
        for (role in EncounterDeviceRole.entries) {
            assertFalse(SPEAKER in fields(role, sinkApi = false, outs = options("s1", "s2", "s3")).kinds(), "no API: $role")
            assertFalse(SPEAKER in fields(role, outs = none).kinds(), "no output: $role")
            assertFalse(SPEAKER in fields(role, outs = options("s1")).kinds(), "one output is no choice: $role")
            // the Chrome alias and blank ids do not count
            assertFalse(SPEAKER in fields(role, outs = options("default", "s1")).kinds(), "alias + one device: $role")
            assertFalse(SPEAKER in fields(role, outs = options("", "s1")).kinds(), "blank id + one device: $role")
            assertTrue(SPEAKER in fields(role, outs = options("default", "s1", "s2")).kinds(), "alias + two devices: $role")
        }
    }

    @Test
    fun theSpeakerField_showsTheActiveDevice() {
        val result = fields(EncounterDeviceRole.CONGREGATION, outs = options("s1", "s2"), active = mapOf(SPEAKER to "s2"))
        assertEquals("s2", (result.single() as EncounterDeviceField.Choice).selected)
    }

    // ── the button and the notes ─────────────────────────────────────────────

    @Test
    fun theButton_existsOnlyForAnEnabledChoice() {
        assertFalse(encounterDeviceButtonVisible(emptyList()))
        assertTrue(encounterDeviceButtonVisible(fields(EncounterDeviceRole.OFFICE_HOLDER, mics = options("m1"))))
        assertTrue(encounterDeviceButtonVisible(fields(EncounterDeviceRole.CONGREGATION, outs = options("s1", "s2"))))
    }

    @Test
    fun theSpeakerNote_isForPeopleWithAnInputFieldAndWithoutTheApi() {
        val officeHolder = fields(EncounterDeviceRole.OFFICE_HOLDER, sinkApi = false, mics = options("m1"))
        assertTrue(encounterSpeakerNoteVisible(officeHolder, sinkApi = false))
        assertFalse(encounterSpeakerNoteVisible(officeHolder, sinkApi = true), "with the API there is a field instead")
        assertFalse(
            encounterSpeakerNoteVisible(fields(EncounterDeviceRole.CONGREGATION, sinkApi = false), sinkApi = false),
            "nothing to note",
        )
    }

    @Test
    fun theButtonName_namesWhatCanBeChosen() {
        val officeHolder = fields(EncounterDeviceRole.OFFICE_HOLDER, mics = options("m1"), outs = options("s1", "s2"))
        assertEquals("Geräte wählen", encounterDeviceButtonLabel(EncounterDeviceRole.OFFICE_HOLDER, officeHolder))
        val table = fields(EncounterDeviceRole.CONGREGATION_AT_TABLE, mics = options("m1"), outs = options("s1", "s2"))
        assertEquals("Mikrofon und Lautsprecher wählen", encounterDeviceButtonLabel(EncounterDeviceRole.CONGREGATION_AT_TABLE, table))
        val speakerOnly = fields(EncounterDeviceRole.CONGREGATION, outs = options("s1", "s2"))
        assertEquals("Lautsprecher wählen", encounterDeviceButtonLabel(EncounterDeviceRole.CONGREGATION, speakerOnly))
    }

    // ── the remembered device ────────────────────────────────────────────────

    @Test
    fun resolve_aRememberedDeviceThatIsThereAndNotActive_isApplied() {
        val resolution = encounterResolveDevice("m2", options("m1", "m2"), active = "m1")
        assertEquals("m2", resolution.id)
        assertFalse(resolution.fellBack)
    }

    @Test
    fun resolve_aRememberedDeviceThatIsAlreadyActive_needsNothing() {
        val resolution = encounterResolveDevice("m1", options("m1", "m2"), active = "m1")
        assertNull(resolution.id)
        assertFalse(resolution.fellBack)
    }

    @Test
    fun resolve_aRememberedDeviceThatIsGone_isAFallbackAndNothingIsSwitched() {
        val resolution = encounterResolveDevice("gone", options("m1", "m2"), active = "m1")
        assertNull(resolution.id)
        assertTrue(resolution.fellBack)
    }

    @Test
    fun resolve_nothingRememberedOrBlank_isNothingToDo() {
        for (stored in listOf(null, "", "   ")) {
            val resolution = encounterResolveDevice(stored, options("m1"), active = null)
            assertNull(resolution.id, "stored=$stored")
            assertFalse(resolution.fellBack, "stored=$stored")
        }
    }

    @Test
    fun resolve_withoutAVisibleList_neverClaimsADeviceIsMissing() {
        val resolution = encounterResolveDevice("m2", none, active = null)
        assertNull(resolution.id)
        assertFalse(resolution.fellBack, "no permission yet is not a missing device")
    }

    // ── a device that vanished ───────────────────────────────────────────────

    @Test
    fun vanished_anActiveDeviceThatIsStillThereOrUnknown_isNoCase() {
        assertNull(encounterVanishedFallback("m1", options("m1", "m2")))
        assertNull(encounterVanishedFallback(null, options("m1")))
        assertNull(encounterVanishedFallback("  ", options("m1")))
        assertNull(encounterVanishedFallback("m1", none), "an empty list is no proof that the device went away")
    }

    @Test
    fun vanished_anActiveDeviceThatIsGone_fallsBackToTheFirstOfTheList() {
        assertEquals("m2", encounterVanishedFallback("m1", options("m2", "m3")))
        // the speaker list starts with the system default
        val speakers = listOf(ConferenceDeviceOption(ENCOUNTER_SYSTEM_DEFAULT_OUTPUT, "")) + options("s2")
        assertEquals(ENCOUNTER_SYSTEM_DEFAULT_OUTPUT, encounterVanishedFallback("s1", speakers))
    }
}
