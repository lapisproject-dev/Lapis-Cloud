package network.lapis.cloud.client

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Welle V1.4.28 (W4a), review correction: a screen that writes a field's value programmatically (a platform preset, a
 * locked field) must go THROUGH the `LapisField`, otherwise an error that was already showing stays on a field whose
 * content just changed -- and a screen reader keeps announcing it as invalid.
 */
class ProgrammaticFieldWriteDomTest {
    private fun HTMLElement.all(selector: String): List<HTMLElement> =
        (0 until querySelectorAll(selector).length).map { querySelectorAll(selector).item(it) as HTMLElement }

    /** The input belonging to the `<label>` whose text starts with [labelText] -- by label, never by position in the form. */
    private fun HTMLElement.inputLabelled(labelText: String): HTMLInputElement {
        val label =
            all("label").first {
                it.textContent
                    .orEmpty()
                    .trim()
                    .startsWith(labelText)
            }
        return assertNotNull(
            document.getElementById(label.getAttribute("for").orEmpty()) as? HTMLInputElement,
            "no input for label $labelText",
        )
    }

    @Test
    fun aPlatformPreset_clearsTheAlreadyShownUrlError_becauseTheNewValueIsValid() {
        withMountedRoot("programmatic-stream-preset") { root, element ->
            renderConferenceStreamDestinationsScreen(root)
            // Leeres Formular absenden: alle vier Felder zeigen Fehler.
            element().all("button").first { it.textContent?.trim() == "Stream-Ziel anlegen" }.click()
            val url = element().inputLabelled("RTMP-Basis-URL")
            assertTrue(url.classList.contains("is-invalid"), "precondition: the URL field shows its error")

            val select = element().all("select").first() as HTMLSelectElement
            select.value = "YOUTUBE"
            select.dispatchEvent(Event("change"))

            assertTrue(url.value.startsWith("rtmp"), "the YouTube preset must fill the URL, was '${url.value}'")
            assertFalse(url.classList.contains("is-invalid"), "the stale error frame must be gone")
            assertEquals("false", url.getAttribute("aria-invalid"))
        }
    }

    @Test
    fun lockingTheFeeFieldOnLevelOne_clearsAnAlreadyShownFeeError() {
        withMountedRoot("programmatic-dunning-fee") { root, element ->
            renderDunningLevelForm(root, existing = null) {}
            val level = element().inputLabelled("Stufennummer")
            val fee = element().inputLabelled("Gebühr in EUR")
            level.value = "2"
            level.dispatchEvent(Event("input"))
            fee.value = "abc"
            fee.dispatchEvent(Event("input"))
            fee.dispatchEvent(Event("blur"))
            assertTrue(fee.classList.contains("is-invalid"), "precondition: 'abc' is flagged on leaving the field")

            level.value = "1"
            level.dispatchEvent(Event("input"))

            assertTrue(fee.disabled, "level 1 locks the fee field")
            assertEquals("", fee.value)
            assertFalse(fee.classList.contains("is-invalid"), "a locked, empty field must not keep an error frame")
            assertEquals("false", fee.getAttribute("aria-invalid"))
            assertEquals(0, element().all(".lapis-field-error--shown").size, "no error text may stay behind")
        }
    }
}
