package network.lapis.cloud.client

import kotlinx.browser.localStorage
import kotlinx.browser.window
import org.w3c.dom.get
import org.w3c.dom.set
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * V1.9.114 / V1.9.124 -- the remembered choice of the noise suppression: one key with THREE states. `"on"` is on, `"off"` is off (stored
 * explicitly, since V1.9.124 -- before, off removed the key), anything else (missing, empty, manipulated) is "never chosen" (`null`), where
 * the instance default applies. A storage that throws (private window, blocked site data) is "never chosen" without any message, the
 * person's choice living on in memory for the page.
 */
class NoiseSuppressionPreferenceTest {
    @BeforeTest
    fun clear() {
        runCatching { localStorage.removeItem(NOISE_SUPPRESSION_STORAGE_KEY) }
    }

    @AfterTest
    fun clearAgain() = clear()

    @Test
    fun theKey_isTheDocumentedOne() {
        assertEquals("lapis.audio.noiseSuppression.v1", NOISE_SUPPRESSION_STORAGE_KEY)
    }

    @Test
    fun neverChosenIsNull_andOnAndOffRoundTrip_andOffIsStoredExplicitly() {
        val preference = LocalStorageNoiseSuppressionPreference()
        assertNull(preference.read(), "nothing stored: never chosen")
        preference.write(true)
        assertEquals("on", localStorage[NOISE_SUPPRESSION_STORAGE_KEY])
        assertEquals(true, LocalStorageNoiseSuppressionPreference().read(), "a fresh reader (a new page) sees it")
        preference.write(false)
        assertEquals("off", localStorage[NOISE_SUPPRESSION_STORAGE_KEY], "off is a choice and is written, never a removed key")
        assertEquals(false, LocalStorageNoiseSuppressionPreference().read())
    }

    @Test
    fun onlyTheExactValuesOnAndOffCount_anythingElseIsNeverChosen() {
        listOf(
            "",
            "ON",
            "On",
            "OFF",
            "Off",
            "true",
            "false",
            "1",
            "0",
            " on",
            "on ",
            " off",
            "yes",
            "{\"on\":true}",
            "on\u0000",
            "off\u0000",
        ).forEach { value ->
            localStorage[NOISE_SUPPRESSION_STORAGE_KEY] = value
            assertNull(LocalStorageNoiseSuppressionPreference().read(), "'$value' is not a choice")
        }
        localStorage[NOISE_SUPPRESSION_STORAGE_KEY] = "on"
        assertEquals(true, LocalStorageNoiseSuppressionPreference().read())
        localStorage[NOISE_SUPPRESSION_STORAGE_KEY] = "off"
        assertEquals(false, LocalStorageNoiseSuppressionPreference().read())
    }

    @Test
    fun aThrowingStorage_isNeverChosen_andTheChoiceLivesInMemory_withoutAMessage() {
        val scope = window.asDynamic()
        val original = js("Object").getOwnPropertyDescriptor(scope, "localStorage")
        try {
            val blocked: dynamic = js("({})")
            blocked.configurable = true
            blocked.get = { throw IllegalStateException("blocked") }
            js("Object").defineProperty(scope, "localStorage", blocked)
            val preference = LocalStorageNoiseSuppressionPreference()
            assertNull(preference.read(), "reading a blocked storage: never chosen")
            preference.write(true) // must not throw
            assertEquals(true, preference.read(), "the choice is kept for this page")
            preference.write(false)
            assertEquals(false, preference.read(), "an explicit off survives in memory too")
        } finally {
            js("Object").defineProperty(scope, "localStorage", original)
        }
    }
}
