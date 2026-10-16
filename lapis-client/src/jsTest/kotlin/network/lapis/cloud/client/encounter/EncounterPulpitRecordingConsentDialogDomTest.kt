package network.lapis.cloud.client.encounter

import kotlinx.browser.document
import network.lapis.cloud.client.allOf
import network.lapis.cloud.client.awaitUntil
import network.lapis.cloud.client.buttonNamed
import network.lapis.cloud.client.formTest
import network.lapis.cloud.client.lastOpenModal
import network.lapis.cloud.client.mountedForm
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.PulpitRecordingTextsDto
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.9.143 -- the dialog in which the person at the pulpit agrees to being recorded: one short text, the focus on "Nicht aufzeichnen", Escape and the close
 * button are a "no", agreeing hands over the texts exactly as the server delivered them, and an answer is given at most once.
 */
class EncounterPulpitRecordingConsentDialogDomTest {
    private val terms = termsFor(EncounterProfile.CHURCH_SERVICE)
    private val texts =
        PulpitRecordingTextsDto(
            pulpitVersionLabel = "2026-10-16.rec-pulpit.v1",
            pulpitSha256 = "b".repeat(64),
            pulpitText = "Ihre Stimme und Ihr Bild an der Kanzel werden aufgezeichnet.",
            roomText = "Die Kanzel wird aufgezeichnet, die Gemeinde nicht.",
        )

    private fun escape(target: HTMLElement) {
        target.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Escape", bubbles = true)))
    }

    @Test
    fun theDialog_showsTheText_focusesTheSafeAnswer_andEscapeIsANo(): Promise<Unit> =
        formTest {
            mountedForm("pulpit-consent-escape") { _, _ ->
                val accepted = mutableListOf<PulpitRecordingTextsDto>()
                var declined = 0
                showPulpitRecordingConsent(terms, texts, { accepted += it }, { declined++ })
                awaitUntil("the dialog") { document.querySelector(".modal.show") != null }
                val modal = lastOpenModal()
                assertTrue(modal.textContent.orEmpty().contains("Ihre Stimme und Ihr Bild an der Kanzel werden aufgezeichnet."))
                assertTrue(modal.allOf("button").any { it.textContent.orEmpty().trim() == "Aufzeichnung zustimmen" })
                awaitUntil("focus on the safe answer", 1000) { document.activeElement === modal.buttonNamed("Nicht aufzeichnen") }
                escape(modal)
                awaitUntil("Esc closes the dialog") { document.querySelector(".modal.show") == null }
                awaitUntil("Esc is a no") { declined == 1 }
                assertEquals(emptyList(), accepted)
            }
        }

    @Test
    fun agreeing_handsOverTheTextsUnmodified_once(): Promise<Unit> =
        formTest {
            mountedForm("pulpit-consent-accept") { _, _ ->
                val accepted = mutableListOf<PulpitRecordingTextsDto>()
                var declined = 0
                showPulpitRecordingConsent(terms, texts, { accepted += it }, { declined++ })
                awaitUntil("the dialog") { document.querySelector(".modal.show") != null }
                val button = lastOpenModal().buttonNamed("Aufzeichnung zustimmen")
                button.click()
                button.click()
                awaitUntil("the answer") { accepted.isNotEmpty() }
                assertEquals(listOf(texts), accepted)
                kotlinx.coroutines.delay(50)
                assertEquals(1, accepted.size, "answered at most once")
                assertEquals(0, declined, "closing after agreeing is not a no")
            }
        }

    @Test
    fun decliningWithTheButton_isANo_once(): Promise<Unit> =
        formTest {
            mountedForm("pulpit-consent-decline") { _, _ ->
                var accepted = 0
                var declined = 0
                showPulpitRecordingConsent(terms, texts, { accepted++ }, { declined++ })
                awaitUntil("the dialog") { document.querySelector(".modal.show") != null }
                lastOpenModal().buttonNamed("Nicht aufzeichnen").click()
                awaitUntil("the no") { declined >= 1 }
                kotlinx.coroutines.delay(50)
                assertEquals(1, declined)
                assertFalse(accepted > 0)
            }
        }
}
