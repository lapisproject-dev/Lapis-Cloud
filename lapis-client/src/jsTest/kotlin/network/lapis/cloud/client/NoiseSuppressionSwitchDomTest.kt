package network.lapis.cloud.client

import io.kvision.html.span
import kotlinx.coroutines.CompletableDeferred
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.114 -- the one noise suppression row in a REAL mounted root: a `role="switch"` button with `aria-checked`, a visible label and a hint that
 * is always there, a polite status element that is EMPTY on construction, `aria-disabled` while a change runs and when the filter is not
 * available (with the reason as visible text and in the status), and the keyboard (Space and Enter are the native button's own click).
 */
class NoiseSuppressionSwitchDomTest {
    private companion object {
        val stylesLoaded: Boolean =
            run {
                js("require('bootstrap/dist/css/bootstrap.min.css')")
                js("require('zzz-kvision-assets/css/kv-style.css')")
                js("require('@fortawesome/fontawesome-free/css/all.css')")
                js("require('./theme.css')")
                true
            }
    }

    private class Rig(
        val chain: FakeNoiseSuppressionChain? = FakeNoiseSuppressionChain(),
        val preference: FakeNoiseSuppressionPreference = FakeNoiseSuppressionPreference(),
        capability: NoiseSuppressionUnavailable? = null,
    ) {
        val controller =
            ConferenceNoiseSuppressionController(
                preference = preference,
                chain = { chain },
                capability = { capability },
                loadTimeoutMs = 2_000L,
                stopTimeoutMs = 500L,
            )
    }

    private fun HTMLElement.switchButton() = assertNotNull(querySelector("button[role=switch]") as? HTMLElement)

    private fun HTMLElement.status() = assertNotNull(querySelector("[role=status]") as? HTMLElement)

    private fun mount(
        rig: Rig,
        block: (HTMLElement, NoiseSuppressionSwitchRow) -> Unit,
    ) {
        withMountedRoot("noise-suppression-switch-test") { root, element ->
            assertTrue(stylesLoaded)
            val row = NoiseSuppressionSwitchRow(root, rig.controller)
            block(element(), row)
        }
    }

    @Test
    fun theRow_isASwitchWithALabelAndAnAlwaysVisibleHint_andAnEmptyStatusOnConstruction() {
        mount(Rig()) { element, _ ->
            val button = element.switchButton()
            assertEquals("button", button.tagName.lowercase())
            assertEquals("button", button.getAttribute("type"))
            assertEquals("switch", button.getAttribute("role"))
            assertEquals("false", button.getAttribute("aria-checked"))
            assertEquals("false", button.getAttribute("aria-disabled"))
            val labelledBy = assertNotNull(button.getAttribute("aria-labelledby"))
            val label = assertNotNull(element.querySelector("#$labelledBy"))
            assertEquals("Rauschunterdrückung", label.textContent.orEmpty().trim())
            val describedBy = assertNotNull(button.getAttribute("aria-describedby"))
            val hint = assertNotNull(element.querySelector("#$describedBy") as? HTMLElement)
            assertEquals(
                "Wird nur in diesem Browser verarbeitet. Gesang und Musik klingen damit verändert.",
                hint.textContent.orEmpty().trim(),
            )
            assertTrue(hint.getBoundingClientRect().height > 0, "the hint is always visible")
            val status = element.status()
            assertEquals("polite", status.getAttribute("aria-live"))
            assertEquals("", status.textContent.orEmpty().trim(), "nothing is announced while building")
            assertNull(element.querySelector("[aria-live=assertive]"), "never assertive")
        }
    }

    @Test
    fun aRememberedOn_showsCheckedAtOnce_withoutAnAnnouncement() {
        mount(Rig(preference = FakeNoiseSuppressionPreference(stored = true))) { element, _ ->
            assertEquals("true", element.switchButton().getAttribute("aria-checked"))
            assertEquals(
                "",
                element
                    .status()
                    .textContent
                    .orEmpty()
                    .trim(),
            )
        }
    }

    @Test
    fun aClick_switchesOn_announcesOnce_andASecondClickSwitchesOffAndClearsTheStatus(): Promise<Unit> =
        formTest {
            val rig = Rig()
            mountedForm("noise-suppression-switch-click") { root, element ->
                NoiseSuppressionSwitchRow(root, rig.controller)
                val button = element().switchButton()
                button.click()
                awaitUntil("the switch is on") {
                    button.getAttribute("aria-checked") == "true" &&
                        button.getAttribute("aria-disabled") == "false"
                }
                assertEquals(
                    "Rauschunterdrückung aktiv.",
                    element()
                        .status()
                        .textContent
                        .orEmpty()
                        .trim(),
                )
                assertEquals(1, rig.chain!!.prepareCalls, "the context is prepared inside the click")
                assertEquals(1, rig.chain.attachCalls)
                assertEquals(listOf(true), rig.preference.writes)
                button.click()
                awaitUntil("the switch is off") {
                    button.getAttribute("aria-checked") == "false" &&
                        button.getAttribute("aria-disabled") == "false"
                }
                assertEquals(
                    "",
                    element()
                        .status()
                        .textContent
                        .orEmpty()
                        .trim(),
                    "switching off leaves nothing to read",
                )
                assertEquals(1, rig.chain.detachCalls)
            }
        }

    @Test
    fun spaceAndEnter_workLikeAClick_becauseTheSwitchIsARealButton(): Promise<Unit> =
        formTest {
            val rig = Rig()
            mountedForm("noise-suppression-switch-keys") { root, element ->
                NoiseSuppressionSwitchRow(root, rig.controller)
                val button = element().switchButton()
                // a button's own keyboard activation is a click event the browser fires itself; a synthetic keydown does not trigger it,
                // so the test pins what makes it work: the element is a focusable, enabled button and focus reaches it
                button.focus()
                assertEquals(button, element().ownerDocument!!.activeElement, "the switch takes focus (Tab reaches it)")
                assertFalse(button.hasAttribute("disabled"), "never the native disabled attribute (it would drop out of the tab order)")
                assertTrue(button.tabIndex >= 0)
                button.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = " ")))
                button.click() // what Space/Enter do natively
                awaitUntil("on") { button.getAttribute("aria-checked") == "true" }
            }
        }

    @Test
    fun whileAChangeRuns_theSwitchSaysAriaDisabled_andAClickIsDropped(): Promise<Unit> =
        formTest {
            val gate = CompletableDeferred<Unit>()
            val chain =
                object : NoiseSuppressionChain {
                    var attaches = 0

                    override fun prepare() {}

                    override suspend fun attach(): NoiseSuppressionFailure? {
                        attaches++
                        gate.await()
                        return null
                    }

                    override suspend fun detach() {}

                    override suspend fun setBrowserNoiseSuppression(enabled: Boolean) {}
                }
            val controller =
                ConferenceNoiseSuppressionController(
                    preference = FakeNoiseSuppressionPreference(),
                    chain = { chain },
                    capability = { null },
                    loadTimeoutMs = 5_000L,
                    stopTimeoutMs = 500L,
                )
            mountedForm("noise-suppression-switch-busy") { root, element ->
                NoiseSuppressionSwitchRow(root, controller)
                val button = element().switchButton()
                button.click()
                awaitUntil("busy") { button.getAttribute("aria-disabled") == "true" }
                button.click()
                button.click()
                gate.complete(Unit)
                awaitUntil("done") { button.getAttribute("aria-checked") == "true" && button.getAttribute("aria-disabled") == "false" }
                assertEquals(1, chain.attaches, "a quick double click does not stack two filters")
            }
        }

    @Test
    fun noAudioWorklet_showsNotAvailable_withTheReason_disabled_andNothingIsAnnouncedWhileBuilding() {
        mount(Rig(capability = NoiseSuppressionUnavailable.NO_AUDIO_WORKLET)) { element, _ ->
            val button = element.switchButton()
            assertEquals("true", button.getAttribute("aria-disabled"))
            assertEquals("false", button.getAttribute("aria-checked"))
            val text = element.textContent.orEmpty()
            assertTrue(text.contains("Nicht verfügbar"), "the short word is visible")
            assertTrue(text.contains("dieser Browser unterstützt AudioWorklet nicht"), "the reason is visible")
            assertTrue(text.contains("Es wird die Rauschminderung des Browsers verwendet."))
            assertEquals(
                "",
                element
                    .status()
                    .textContent
                    .orEmpty()
                    .trim(),
                "the static state is not announced",
            )
        }
    }

    @Test
    fun noWebAssembly_namesItsOwnReason() {
        mount(Rig(capability = NoiseSuppressionUnavailable.NO_WEB_ASSEMBLY)) { element, _ ->
            assertTrue(element.textContent.orEmpty().contains("dieser Browser unterstützt WebAssembly nicht"))
        }
    }

    @Test
    fun aFailedLoad_fallsBack_announcesTheReasonPolitely_andNeverOpensADialog(): Promise<Unit> =
        formTest {
            val rig = Rig(chain = FakeNoiseSuppressionChain().also { it.attachResult = NoiseSuppressionUnavailable.LOAD_FAILED })
            mountedForm("noise-suppression-switch-fail") { root, element ->
                NoiseSuppressionSwitchRow(root, rig.controller)
                root.span("sibling")
                val button = element().switchButton()
                button.click()
                awaitUntil("the fallback") {
                    element().textContent.orEmpty().contains("das Laden ist aus unbekanntem Grund fehlgeschlagen")
                }
                assertEquals("false", button.getAttribute("aria-disabled"), "a temporary failure does not lock the switch")
                assertEquals("false", button.getAttribute("aria-checked"))
                assertTrue(
                    element()
                        .status()
                        .textContent
                        .orEmpty()
                        .contains("das Laden ist aus unbekanntem Grund fehlgeschlagen"),
                )
                assertEquals("polite", element().status().getAttribute("aria-live"))
                assertNull(element().ownerDocument!!.querySelector(".modal.show, [role=dialog], [role=alertdialog]"), "no dialog")
                assertEquals(listOf(true), rig.chain!!.browserNoiseSuppression, "the browser's own reduction is back on")
            }
        }

    // ── V1.9.119: the reason code in the panel, retry, final failures ─────────────────────────────────────────────

    @Test
    fun aTemporaryFailure_showsReasonAndCode_offersARetry_keepsTheSwitchUsable_andANewClickSucceeds(): Promise<Unit> =
        formTest {
            val chain = FakeNoiseSuppressionChain()
            chain.attachFailures =
                listOf(NoiseSuppressionFailure(NoiseSuppressionUnavailable.WASM_FETCH, "TypeError", "Failed to fetch"), null)
            val rig = Rig(chain = chain)
            mountedForm("noise-suppression-switch-retry") { root, element ->
                NoiseSuppressionSwitchRow(root, rig.controller)
                val button = element().switchButton()
                button.click()
                awaitUntil("the failure is shown") { element().textContent.orEmpty().contains("(WASM_FETCH)") }
                val text = element().textContent.orEmpty()
                assertTrue(text.contains("Grund: die Rauschfilter-Datei konnte nicht geladen werden."), text)
                assertTrue(text.contains("Es wird die Rauschminderung des Browsers verwendet."))
                assertTrue(text.contains("Zum erneuten Versuch einschalten."), "a temporary failure offers the retry")
                assertFalse(text.contains("Failed to fetch"), "the raw browser message stays in the console")
                assertEquals("false", button.getAttribute("aria-checked"), "the switch shows OFF while the filter is not in use")
                assertEquals("false", button.getAttribute("aria-disabled"), "a click may try again")
                assertEquals("polite", element().status().getAttribute("aria-live"))
                assertTrue(
                    element()
                        .status()
                        .textContent
                        .orEmpty()
                        .contains("(WASM_FETCH)"),
                    "announced once",
                )
                button.click()
                awaitUntil("the retry works") { button.getAttribute("aria-checked") == "true" }
                assertEquals(2, chain.attachCalls)
                assertFalse(element().textContent.orEmpty().contains("(WASM_FETCH)"), "the reason line is gone")
            }
        }

    @Test
    fun aFinalFailure_namesItsCode_hasNoRetryHint_andStaysDisabled() {
        mount(Rig(capability = NoiseSuppressionUnavailable.NO_WEB_ASSEMBLY)) { element, _ ->
            val text = element.textContent.orEmpty()
            assertTrue(text.contains("(NO_WEB_ASSEMBLY)"))
            assertFalse(text.contains("Zum erneuten Versuch einschalten."), "nothing to retry")
            assertEquals("true", element.switchButton().getAttribute("aria-disabled"))
            assertEquals("false", element.switchButton().getAttribute("aria-checked"))
        }
    }

    @Test
    fun theUnavailableSentence_hasTheCode_andTheRetryHintOnlyForTemporaryStages() {
        for (reason in NoiseSuppressionUnavailable.entries) {
            val sentence = noiseSuppressionUnavailableSentence(NoiseSuppressionFailure(reason, "SomeError", "raw detail"))
            assertTrue(sentence.endsWith("(${reason.name})"), sentence)
            assertTrue(sentence.startsWith("Nicht verfügbar – Grund: "), sentence)
            assertEquals(reason.retryable, sentence.contains("Zum erneuten Versuch einschalten."), sentence)
            assertFalse(sentence.contains("raw detail"))
            assertFalse(sentence.contains("SomeError"))
        }
    }
}
