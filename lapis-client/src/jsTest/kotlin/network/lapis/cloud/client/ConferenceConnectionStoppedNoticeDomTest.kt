package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.69 -- the card that replaces the call when the connection stopped on its own, in a REAL mounted root with the real stylesheets.
 * Covers only this extracted component; the wiring `onDisconnected -> stopAndShowNotice` in the (unmountable) conference page is covered
 * by the source scan `ClientConferenceRejoinTripwireTest`.
 */
class ConferenceConnectionStoppedNoticeDomTest {
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

    private fun HTMLElement.card() = assertNotNull(querySelector(".lapis-connection-stopped") as? HTMLElement, "no card")

    @Test
    fun displaced_showsTheTexts_theAlertRole_theFocus_andTheButtonsInOrder() =
        formTest {
            assertTrue(stylesLoaded)
            withMountedRoot("conn-stopped-displaced") { root, element ->
                root.conferenceConnectionStoppedNotice(ConnectionStoppedKind.Displaced, { true }, {})
                awaitUntil("the card is focused") { document.activeElement === element().card() }
                val card = element().card()
                assertEquals("alert", card.getAttribute("role"))
                assertEquals("-1", card.getAttribute("tabindex"))
                val text = card.textContent.orEmpty()
                assertTrue(text.contains("Auf einem anderen Gerät verbunden"))
                assertTrue(text.contains("Pro Konto ist zurzeit nur ein Gerät gleichzeitig möglich."))
                assertTrue(text.contains("Wenn Sie hier fortsetzen, wird das andere Gerät getrennt."))
                val buttons = card.querySelectorAll("button")
                assertEquals(2, buttons.length)
                val resume = buttons.item(0) as HTMLElement
                val overview = buttons.item(1) as HTMLElement
                assertActionIcon(resume, "Hier fortsetzen", "fa-right-to-bracket")
                assertActionIcon(overview, "Zur Übersicht", "fa-arrow-left")
                assertTrue(resume.classList.contains("btn-primary"), "primary comes first")
                assertTrue(overview.classList.contains("btn-outline-secondary"))
            }
        }

    @Test
    fun loopStopped_hasItsOwnTexts_andNoSecondParagraph() =
        formTest {
            withMountedRoot("conn-stopped-loop") { root, element ->
                root.conferenceConnectionStoppedNotice(ConnectionStoppedKind.LoopStopped, { true }, {})
                awaitUntil("the card is there") { element().querySelector(".lapis-connection-stopped") != null }
                val card = element().card()
                assertTrue(card.textContent.orEmpty().contains("Verbindung mehrmals getrennt"))
                assertTrue(card.textContent.orEmpty().contains("Bitte treten Sie erneut bei."))
                assertEquals(1, card.querySelectorAll("p").length)
                assertActionIcon(card.buttonNamed("Erneut beitreten"), "Erneut beitreten", "fa-right-to-bracket")
            }
        }

    @Test
    fun resume_disablesAtOnce_aSecondClickStartsNoSecondAttempt_andAFailureReEnablesIt(): Promise<Unit> =
        formTest {
            withMountedRoot("conn-stopped-resume") { root, element ->
                var attempts = 0
                val gate = kotlinx.coroutines.CompletableDeferred<Boolean>()
                root.conferenceConnectionStoppedNotice(
                    ConnectionStoppedKind.Displaced,
                    {
                        attempts++
                        gate.await()
                    },
                    {},
                )
                awaitUntil("the card is there") { element().querySelector(".lapis-connection-stopped") != null }
                val resume = element().card().buttonNamed("Hier fortsetzen")
                resume.click()
                awaitUntil("the attempt started") { attempts == 1 }
                assertTrue(resume.hasAttribute("disabled"), "disabled at once")
                awaitUntil("aria-busy is set") { element().card().buttonNamed("Hier fortsetzen").getAttribute("aria-busy") == "true" }
                resume.click()
                kotlinx.coroutines.delay(50)
                assertEquals(1, attempts, "a double click must not start a second attempt")
                gate.complete(false)
                awaitUntil("the button is active again") { !element().card().buttonNamed("Hier fortsetzen").hasAttribute("disabled") }
                awaitUntil("aria-busy is gone") { element().card().buttonNamed("Hier fortsetzen").getAttribute("aria-busy") == null }
            }
        }

    @Test
    fun overview_callsBackExactlyOnce_andResumeIsNotTriggered(): Promise<Unit> =
        formTest {
            withMountedRoot("conn-stopped-overview") { root, element ->
                var overview = 0
                var resumed = 0
                root.conferenceConnectionStoppedNotice(ConnectionStoppedKind.Displaced, {
                    resumed++
                    true
                }, { overview++ })
                awaitUntil("the card is there") { element().querySelector(".lapis-connection-stopped") != null }
                element().card().buttonNamed("Zur Übersicht").click()
                assertEquals(1, overview)
                assertEquals(0, resumed)
            }
        }

    @Test
    fun theCard_hasNoScrollSurfaceOfItsOwn() =
        formTest {
            assertTrue(stylesLoaded)
            withMountedRoot("conn-stopped-scroll") { root, element ->
                root.conferenceConnectionStoppedNotice(ConnectionStoppedKind.Displaced, { true }, {})
                awaitUntil("the card is there") { element().querySelector(".lapis-connection-stopped") != null }
                val style = window.getComputedStyle(element().card())
                assertEquals("none", style.maxHeight)
                assertTrue(style.overflowY == "visible", "overflow-y was ${style.overflowY}")
            }
        }
}
