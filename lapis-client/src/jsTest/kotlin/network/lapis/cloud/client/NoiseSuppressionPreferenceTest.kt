package network.lapis.cloud.client

import kotlinx.browser.localStorage
import kotlinx.browser.window
import org.w3c.dom.get
import org.w3c.dom.set
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.114 -- the remembered choice of the noise suppression: one key, only the exact value `"on"` counts as on, a manipulated or empty
 * value means off, and a storage that throws (private window, blocked site data) means "off" without any message, the choice living on in
 * memory for the page.
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
    fun defaultIsOff_andOnRoundTrips_andOffRemovesTheKey() {
        val preference = LocalStorageNoiseSuppressionPreference()
        assertFalse(preference.read())
        preference.write(true)
        assertEquals("on", localStorage[NOISE_SUPPRESSION_STORAGE_KEY])
        assertTrue(LocalStorageNoiseSuppressionPreference().read(), "a fresh reader (a new page) sees it")
        preference.write(false)
        assertNull(localStorage[NOISE_SUPPRESSION_STORAGE_KEY], "off leaves nothing behind")
        assertFalse(LocalStorageNoiseSuppressionPreference().read())
    }

    @Test
    fun onlyTheExactValueOnCounts() {
        listOf("", "ON", "On", "true", "1", " on", "on ", "yes", "{\"on\":true}", "on\u0000").forEach { value ->
            localStorage[NOISE_SUPPRESSION_STORAGE_KEY] = value
            assertFalse(LocalStorageNoiseSuppressionPreference().read(), "'$value' is not on")
        }
        localStorage[NOISE_SUPPRESSION_STORAGE_KEY] = "on"
        assertTrue(LocalStorageNoiseSuppressionPreference().read())
    }

    @Test
    fun aThrowingStorage_means_off_andTheChoiceLivesInMemory_withoutAMessage() {
        val scope = window.asDynamic()
        val original = js("Object").getOwnPropertyDescriptor(scope, "localStorage")
        try {
            val blocked: dynamic = js("({})")
            blocked.configurable = true
            blocked.get = { throw IllegalStateException("blocked") }
            js("Object").defineProperty(scope, "localStorage", blocked)
            val preference = LocalStorageNoiseSuppressionPreference()
            assertFalse(preference.read(), "reading a blocked storage is off")
            preference.write(true) // must not throw
            assertTrue(preference.read(), "the choice is kept for this page")
            preference.write(false)
            assertFalse(preference.read())
        } finally {
            js("Object").defineProperty(scope, "localStorage", original)
        }
    }
}
