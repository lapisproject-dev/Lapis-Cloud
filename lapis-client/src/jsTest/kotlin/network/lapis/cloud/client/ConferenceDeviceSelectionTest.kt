package network.lapis.cloud.client

import network.lapis.cloud.client.livekit.ConferenceDeviceFailure
import network.lapis.cloud.client.livekit.ConferenceDeviceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.3.x Geräteauswahl (GitHub Issue #2) -- covers the pure, DOM-independent helpers factored out
 * of `ConferenceScreen.kt`'s in-call device-selection UI ([conferencePreferredDeviceId], the
 * localStorage-vs-LiveKit-active precedence used by `refreshDeviceOptions()`;
 * [conferenceDeviceOptionLabel], the empty-label numbered-placeholder fallback;
 * [conferenceDeviceStorageKey], the single place a device kind's `localStorage` key is composed;
 * [conferenceDeviceErrorMessage]/[conferenceDeviceEnableErrorMessage]/[conferenceDeviceLostMessage]/
 * [conferenceDeviceSubject], the German copy tables for a failed/vanished device;
 * [conferenceShouldNotifyDeviceLost], the review-fix decision of whether the "vanished" toast may
 * fire at all). Same DOM-free unit-test posture as [ConferenceScreenTest] -- no rendering harness
 * exists in this module, so the actual `<select>` wiring, hotplug-focus deferral, and fullscreen
 * `moreToggleButton` visibility are out of scope here and covered by manual live-browser
 * verification instead (see this wave's plan, "Abschluss-Gate").
 */
class ConferenceDeviceSelectionTest {
    // --- conferencePreferredDeviceId ---------------------------------------------------------

    @Test
    fun conferencePreferredDeviceId_storedAvailable_winsOverActive() {
        assertEquals(
            "mic-stored",
            conferencePreferredDeviceId(stored = "mic-stored", active = "mic-active", available = listOf("mic-stored", "mic-active")),
        )
    }

    @Test
    fun conferencePreferredDeviceId_storedNoLongerAvailable_fallsBackToActive() {
        assertEquals(
            "mic-active",
            conferencePreferredDeviceId(stored = "mic-gone", active = "mic-active", available = listOf("mic-active", "mic-other")),
        )
    }

    @Test
    fun conferencePreferredDeviceId_neitherAvailable_returnsNull() {
        assertNull(conferencePreferredDeviceId(stored = "mic-gone", active = "mic-also-gone", available = listOf("mic-other")))
    }

    @Test
    fun conferencePreferredDeviceId_noStoredNoActive_returnsNull() {
        assertNull(conferencePreferredDeviceId(stored = null, active = null, available = listOf("mic-a", "mic-b")))
    }

    @Test
    fun conferencePreferredDeviceId_emptyAvailableList_returnsNull() {
        assertNull(conferencePreferredDeviceId(stored = "mic-stored", active = "mic-active", available = emptyList()))
    }

    // --- conferenceDeviceOptionLabel ----------------------------------------------------------

    @Test
    fun conferenceDeviceOptionLabel_nonBlankRawLabel_returnedUnchanged() {
        assertEquals(
            "Iraklis iPhone-Mikrofon",
            conferenceDeviceOptionLabel(ConferenceDeviceKind.MICROPHONE, "Iraklis iPhone-Mikrofon", index = 1),
        )
    }

    @Test
    fun conferenceDeviceOptionLabel_blankRawLabel_fallsBackToNumberedPlaceholderPerKind() {
        assertEquals("Mikrofon 1", conferenceDeviceOptionLabel(ConferenceDeviceKind.MICROPHONE, "", index = 1))
        assertEquals("Kamera 1", conferenceDeviceOptionLabel(ConferenceDeviceKind.CAMERA, "", index = 1))
        assertEquals("Lautsprecher 1", conferenceDeviceOptionLabel(ConferenceDeviceKind.SPEAKER, "", index = 1))
    }

    @Test
    fun conferenceDeviceOptionLabel_whitespaceOnlyRawLabel_alsoFallsBack() {
        assertEquals("Mikrofon 2", conferenceDeviceOptionLabel(ConferenceDeviceKind.MICROPHONE, "   ", index = 2))
    }

    @Test
    fun conferenceDeviceOptionLabel_indexBoundary_distinguishesFirstAndSecondDevice() {
        assertEquals("Mikrofon 1", conferenceDeviceOptionLabel(ConferenceDeviceKind.MICROPHONE, "", index = 1))
        assertEquals("Mikrofon 2", conferenceDeviceOptionLabel(ConferenceDeviceKind.MICROPHONE, "", index = 2))
    }

    // --- conferenceDeviceStorageKey ------------------------------------------------------------

    @Test
    fun conferenceDeviceStorageKey_oneKeyPerKind_matchesJsKindLiteral() {
        assertEquals("lapis-cloud-device-audioinput", conferenceDeviceStorageKey(ConferenceDeviceKind.MICROPHONE))
        assertEquals("lapis-cloud-device-videoinput", conferenceDeviceStorageKey(ConferenceDeviceKind.CAMERA))
        assertEquals("lapis-cloud-device-audiooutput", conferenceDeviceStorageKey(ConferenceDeviceKind.SPEAKER))
    }

    // --- conferenceDeviceSubject ----------------------------------------------------------------

    @Test
    fun conferenceDeviceSubject_oneArticledNounPerKind() {
        assertEquals("Das Mikrofon", conferenceDeviceSubject(ConferenceDeviceKind.MICROPHONE))
        assertEquals("Die Kamera", conferenceDeviceSubject(ConferenceDeviceKind.CAMERA))
        assertEquals("Der Lautsprecher", conferenceDeviceSubject(ConferenceDeviceKind.SPEAKER))
    }

    // --- conferenceDeviceErrorMessage -- all 3 kinds x 4 failures = 12 combinations -------------

    @Test
    fun conferenceDeviceErrorMessage_permissionDenied_allKinds() {
        assertEquals(
            "Das Mikrofon konnte nicht gewechselt werden -- der Browser hat den Zugriff verweigert.",
            conferenceDeviceErrorMessage(ConferenceDeviceKind.MICROPHONE, ConferenceDeviceFailure.PERMISSION_DENIED),
        )
        assertEquals(
            "Die Kamera konnte nicht gewechselt werden -- der Browser hat den Zugriff verweigert.",
            conferenceDeviceErrorMessage(ConferenceDeviceKind.CAMERA, ConferenceDeviceFailure.PERMISSION_DENIED),
        )
        assertEquals(
            "Der Lautsprecher konnte nicht gewechselt werden -- der Browser hat den Zugriff verweigert.",
            conferenceDeviceErrorMessage(ConferenceDeviceKind.SPEAKER, ConferenceDeviceFailure.PERMISSION_DENIED),
        )
    }

    @Test
    fun conferenceDeviceErrorMessage_notFound_allKinds() {
        assertEquals(
            "Das Mikrofon konnte nicht gewechselt werden -- das Gerät wurde nicht gefunden.",
            conferenceDeviceErrorMessage(ConferenceDeviceKind.MICROPHONE, ConferenceDeviceFailure.NOT_FOUND),
        )
        assertEquals(
            "Die Kamera konnte nicht gewechselt werden -- das Gerät wurde nicht gefunden.",
            conferenceDeviceErrorMessage(ConferenceDeviceKind.CAMERA, ConferenceDeviceFailure.NOT_FOUND),
        )
        assertEquals(
            "Der Lautsprecher konnte nicht gewechselt werden -- das Gerät wurde nicht gefunden.",
            conferenceDeviceErrorMessage(ConferenceDeviceKind.SPEAKER, ConferenceDeviceFailure.NOT_FOUND),
        )
    }

    @Test
    fun conferenceDeviceErrorMessage_deviceInUse_allKinds() {
        assertEquals(
            "Das Mikrofon konnte nicht gewechselt werden -- das Gerät wird bereits von einer anderen Anwendung verwendet.",
            conferenceDeviceErrorMessage(ConferenceDeviceKind.MICROPHONE, ConferenceDeviceFailure.DEVICE_IN_USE),
        )
        assertEquals(
            "Die Kamera konnte nicht gewechselt werden -- das Gerät wird bereits von einer anderen Anwendung verwendet.",
            conferenceDeviceErrorMessage(ConferenceDeviceKind.CAMERA, ConferenceDeviceFailure.DEVICE_IN_USE),
        )
        assertEquals(
            "Der Lautsprecher konnte nicht gewechselt werden -- das Gerät wird bereits von einer anderen Anwendung verwendet.",
            conferenceDeviceErrorMessage(ConferenceDeviceKind.SPEAKER, ConferenceDeviceFailure.DEVICE_IN_USE),
        )
    }

    @Test
    fun conferenceDeviceErrorMessage_other_allKinds() {
        assertEquals(
            "Das Mikrofon konnte nicht gewechselt werden.",
            conferenceDeviceErrorMessage(ConferenceDeviceKind.MICROPHONE, ConferenceDeviceFailure.OTHER),
        )
        assertEquals(
            "Die Kamera konnte nicht gewechselt werden.",
            conferenceDeviceErrorMessage(ConferenceDeviceKind.CAMERA, ConferenceDeviceFailure.OTHER),
        )
        assertEquals(
            "Der Lautsprecher konnte nicht gewechselt werden.",
            conferenceDeviceErrorMessage(ConferenceDeviceKind.SPEAKER, ConferenceDeviceFailure.OTHER),
        )
    }

    // --- conferenceDeviceEnableErrorMessage -- all 3 kinds x 4 failures = 12 combinations,
    // review fix (GitHub Issue #2 review, "dupliziertes + falsch formuliertes Fehler-Toast") -------

    @Test
    fun conferenceDeviceEnableErrorMessage_permissionDenied_allKinds() {
        assertEquals(
            "Das Mikrofon konnte nicht aktiviert werden -- der Browser hat den Zugriff verweigert.",
            conferenceDeviceEnableErrorMessage(ConferenceDeviceKind.MICROPHONE, ConferenceDeviceFailure.PERMISSION_DENIED),
        )
        assertEquals(
            "Die Kamera konnte nicht aktiviert werden -- der Browser hat den Zugriff verweigert.",
            conferenceDeviceEnableErrorMessage(ConferenceDeviceKind.CAMERA, ConferenceDeviceFailure.PERMISSION_DENIED),
        )
        assertEquals(
            "Der Lautsprecher konnte nicht aktiviert werden -- der Browser hat den Zugriff verweigert.",
            conferenceDeviceEnableErrorMessage(ConferenceDeviceKind.SPEAKER, ConferenceDeviceFailure.PERMISSION_DENIED),
        )
    }

    @Test
    fun conferenceDeviceEnableErrorMessage_notFound_allKinds() {
        assertEquals(
            "Das Mikrofon konnte nicht aktiviert werden -- das Gerät wurde nicht gefunden.",
            conferenceDeviceEnableErrorMessage(ConferenceDeviceKind.MICROPHONE, ConferenceDeviceFailure.NOT_FOUND),
        )
        assertEquals(
            "Die Kamera konnte nicht aktiviert werden -- das Gerät wurde nicht gefunden.",
            conferenceDeviceEnableErrorMessage(ConferenceDeviceKind.CAMERA, ConferenceDeviceFailure.NOT_FOUND),
        )
        assertEquals(
            "Der Lautsprecher konnte nicht aktiviert werden -- das Gerät wurde nicht gefunden.",
            conferenceDeviceEnableErrorMessage(ConferenceDeviceKind.SPEAKER, ConferenceDeviceFailure.NOT_FOUND),
        )
    }

    @Test
    fun conferenceDeviceEnableErrorMessage_deviceInUse_allKinds() {
        assertEquals(
            "Das Mikrofon konnte nicht aktiviert werden -- das Gerät wird bereits von einer anderen Anwendung verwendet.",
            conferenceDeviceEnableErrorMessage(ConferenceDeviceKind.MICROPHONE, ConferenceDeviceFailure.DEVICE_IN_USE),
        )
        assertEquals(
            "Die Kamera konnte nicht aktiviert werden -- das Gerät wird bereits von einer anderen Anwendung verwendet.",
            conferenceDeviceEnableErrorMessage(ConferenceDeviceKind.CAMERA, ConferenceDeviceFailure.DEVICE_IN_USE),
        )
        assertEquals(
            "Der Lautsprecher konnte nicht aktiviert werden -- das Gerät wird bereits von einer anderen Anwendung verwendet.",
            conferenceDeviceEnableErrorMessage(ConferenceDeviceKind.SPEAKER, ConferenceDeviceFailure.DEVICE_IN_USE),
        )
    }

    @Test
    fun conferenceDeviceEnableErrorMessage_other_allKinds() {
        assertEquals(
            "Das Mikrofon konnte nicht aktiviert werden.",
            conferenceDeviceEnableErrorMessage(ConferenceDeviceKind.MICROPHONE, ConferenceDeviceFailure.OTHER),
        )
        assertEquals(
            "Die Kamera konnte nicht aktiviert werden.",
            conferenceDeviceEnableErrorMessage(ConferenceDeviceKind.CAMERA, ConferenceDeviceFailure.OTHER),
        )
        assertEquals(
            "Der Lautsprecher konnte nicht aktiviert werden.",
            conferenceDeviceEnableErrorMessage(ConferenceDeviceKind.SPEAKER, ConferenceDeviceFailure.OTHER),
        )
    }

    @Test
    fun conferenceDeviceEnableErrorMessage_neverSaysGewechselt() {
        // Review fix regression guard: the whole point of this separate message table is that it
        // must never reuse conferenceDeviceErrorMessage's "switch"-specific wording -- the initial
        // join-time enable and every mic/camera button click never involve a device switch at all.
        for (kind in ConferenceDeviceKind.entries) {
            for (failure in ConferenceDeviceFailure.entries) {
                assertFalse(
                    conferenceDeviceEnableErrorMessage(kind, failure).contains("gewechselt"),
                    "unexpected 'gewechselt' wording for $kind/$failure",
                )
            }
        }
    }

    // --- conferenceDeviceLostMessage ------------------------------------------------------------

    @Test
    fun conferenceDeviceLostMessage_oneSentencePerKind() {
        assertEquals(
            "Das Mikrofon ist nicht mehr verfügbar -- es wurde ein anderes Gerät ausgewählt.",
            conferenceDeviceLostMessage(ConferenceDeviceKind.MICROPHONE),
        )
        assertEquals(
            "Die Kamera ist nicht mehr verfügbar -- es wurde ein anderes Gerät ausgewählt.",
            conferenceDeviceLostMessage(ConferenceDeviceKind.CAMERA),
        )
        assertEquals(
            "Der Lautsprecher ist nicht mehr verfügbar -- es wurde ein anderes Gerät ausgewählt.",
            conferenceDeviceLostMessage(ConferenceDeviceKind.SPEAKER),
        )
    }

    // --- conferenceShouldNotifyDeviceLost -- review fix (GitHub Issue #2 review, "irreführender
    // Info-Toast bei Geräte-Verlust ohne Ersatzgerät") ------------------------------------------

    @Test
    fun conferenceShouldNotifyDeviceLost_replacementFound_true() {
        assertTrue(
            conferenceShouldNotifyDeviceLost(
                preserveFocusedSelect = true,
                activeBeforeRefresh = "mic-gone",
                availableDeviceIds = listOf("mic-other"),
                preferredDeviceId = "mic-other",
            ),
        )
    }

    @Test
    fun conferenceShouldNotifyDeviceLost_noReplacementFound_false() {
        // The exact bug: no stored preference AND no other device of this kind still available --
        // conferencePreferredDeviceId(...) returns null, so no replacement was actually selected,
        // and the "es wurde ein anderes Gerät ausgewählt" toast would be a false claim.
        assertFalse(
            conferenceShouldNotifyDeviceLost(
                preserveFocusedSelect = true,
                activeBeforeRefresh = "mic-gone",
                availableDeviceIds = emptyList(),
                preferredDeviceId = null,
            ),
        )
    }

    @Test
    fun conferenceShouldNotifyDeviceLost_notPreservingFocus_false() {
        // The very first post-connect enumeration always passes preserveFocusedSelect = false --
        // never a "the device vanished" event, so the toast must never fire there regardless of the
        // other three arguments.
        assertFalse(
            conferenceShouldNotifyDeviceLost(
                preserveFocusedSelect = false,
                activeBeforeRefresh = "mic-gone",
                availableDeviceIds = listOf("mic-other"),
                preferredDeviceId = "mic-other",
            ),
        )
    }

    @Test
    fun conferenceShouldNotifyDeviceLost_noPriorActiveDevice_false() {
        assertFalse(
            conferenceShouldNotifyDeviceLost(
                preserveFocusedSelect = true,
                activeBeforeRefresh = null,
                availableDeviceIds = listOf("mic-other"),
                preferredDeviceId = "mic-other",
            ),
        )
    }

    @Test
    fun conferenceShouldNotifyDeviceLost_activeDeviceStillAvailable_false() {
        // Not actually a hotplug loss -- the active device is still in the list, so nothing vanished.
        assertFalse(
            conferenceShouldNotifyDeviceLost(
                preserveFocusedSelect = true,
                activeBeforeRefresh = "mic-still-here",
                availableDeviceIds = listOf("mic-still-here", "mic-other"),
                preferredDeviceId = "mic-still-here",
            ),
        )
    }
}
