package network.lapis.cloud.client

import io.kvision.html.div
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.delay
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEventInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.108 -- the roster building blocks of the moderator audio controls in a real mounted root with the real stylesheet cascade: the
 * state indicator (symbol + words), the FIXED action slot, the "Alle stummschalten" control and its confirmation. The call screen cannot be
 * mounted in Karma, so the blocks are built the way the screen builds them (same functions).
 */
class ConferenceModerationRosterDomTest {
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

    private fun HTMLElement.q(selector: String) = assertNotNull(querySelector(selector) as? HTMLElement, "no $selector")

    private fun HTMLElement.all(selector: String): List<HTMLElement> =
        (0 until querySelectorAll(selector).length).map { querySelectorAll(selector).item(it) as HTMLElement }

    @Test
    fun stateIndicator_isSymbolPlusWords_andTheSymbolIsDecoration() {
        assertTrue(stylesLoaded)
        withMountedRoot("moderation-state") { root, element ->
            val box = root.vPanel()
            box.conferenceMicStateIndicator(ConferenceMicState.OPEN)
            box.conferenceMicStateIndicator(ConferenceMicState.MUTED)
            box.conferenceMicStateIndicator(ConferenceMicState.MUTED_BY_MODERATION)
            box.conferenceMicStateIndicator(ConferenceMicState.MUTED, lockedForMe = true)
            val states = element().all(".lapis-conference-mic-state")
            assertEquals(4, states.size)
            assertEquals(
                listOf("Mikrofon an", "Stumm", "Von der Moderation stummgeschaltet", "Stumm, Einschalten gesperrt"),
                states.map { it.textContent.orEmpty().trim() },
            )
            states.forEach { state ->
                val icon = state.q("i")
                assertEquals("true", icon.getAttribute("aria-hidden"), "the symbol is decoration")
                assertTrue(icon.className.contains("fa-"), "a Font Awesome symbol: ${icon.className}")
            }
            assertTrue(states[2].classList.contains("lapis-conference-mic-state--moderation"))
            assertTrue(states[3].classList.contains("lapis-conference-mic-state--locked"))
            // the moderation state carries a badge on its symbol so it is not read as plain "Stumm"
            val badge = window.getComputedStyle(states[2].q("i"), "::after")
            assertEquals("6px", badge.width)
        }
    }

    @Test
    fun actionSlot_hasTheSameWidthWhateverTheAction_andNamedButtons() {
        assertTrue(stylesLoaded)
        withMountedRoot("moderation-slot") { root, element ->
            val box = root.vPanel()
            box.conferenceRosterActionSlot(ConferenceRosterAction.MUTE, "Dana Keller") {}
            box.conferenceRosterActionSlot(ConferenceRosterAction.ASK, "Dana Keller") {}
            box.conferenceRosterActionSlot(ConferenceRosterAction.NONE, "Dana Keller") {}
            val slots = element().all(".lapis-roster-action-slot")
            assertEquals(3, slots.size)
            val widths = slots.map { it.getBoundingClientRect().width }
            assertTrue(widths.all { it == widths[0] }, "one width for every action: $widths")
            assertTrue(widths[0] >= 100.0, "a real fixed width: $widths")
            assertTrue(slots.all { it.getBoundingClientRect().height >= 44.0 }, "a row that does not jump: at least one touch target high")

            val mute = slots[0].q("button")
            assertEquals("Mikrofon von Dana Keller stummschalten", mute.getAttribute("aria-label"))
            assertTrue(mute.textContent.orEmpty().contains("Stummschalten"))
            val ask = slots[1].q("button")
            assertEquals("Dana Keller bitten, das Mikrofon einzuschalten", ask.getAttribute("aria-label"))
            assertTrue(ask.textContent.orEmpty().contains("Bitten"))
            assertNull(slots[2].querySelector("button"), "no action, only the placeholder")
            assertEquals("true", slots[2].getAttribute("aria-hidden"))
            assertTrue(mute.getBoundingClientRect().height >= 44.0 && ask.getBoundingClientRect().height >= 44.0, "touch targets")
            assertTrue(mute.getBoundingClientRect().width >= 44.0)
            assertFalse(element().innerHTML.contains("###KvI18nS###"), "no i18n marker leaked into the DOM")
        }
    }

    @Test
    fun actionSlot_clickRunsTheAction() {
        withMountedRoot("moderation-slot-click") { root, element ->
            var acted = 0
            root.vPanel().conferenceRosterActionSlot(ConferenceRosterAction.MUTE, "Dana") { acted++ }
            (element().q("button") as HTMLButtonElement).click()
            assertEquals(1, acted)
        }
    }

    @Test
    fun aRow_atPhoneWidth_hasNoHorizontalScrollbar_andKeepsTheActionInTheRow() {
        assertTrue(stylesLoaded)
        withMountedRoot("moderation-narrow") { root, element ->
            val host = root.vPanel()
            host.getElement()?.style?.width = "360px"
            val row = host.hPanel(spacing = 6) { addCssClasses("align-items-center flex-wrap") }
            row.div("Ein sehr langer Anzeigename eines Teilnehmers der Besprechung") { addCssClasses("flex-grow-1 small") }
            row.conferenceMicStateIndicator(ConferenceMicState.MUTED_BY_MODERATION)
            row.conferenceRosterActionSlot(ConferenceRosterAction.ASK, "Ein sehr langer Anzeigename") {}
            val box = element()
            assertTrue(box.scrollWidth <= box.clientWidth + 1, "no horizontal scroll: ${box.scrollWidth} > ${box.clientWidth}")
            val rowElement = row.getElement() as HTMLElement
            val button = rowElement.q(".lapis-roster-action-slot button")
            assertTrue(
                button.getBoundingClientRect().right <= rowElement.getBoundingClientRect().right + 1,
                "the action stays inside the row",
            )
        }
    }

    @Test
    fun muteAllControl_isDisabledWithAReason_andEnabledWithCandidates() {
        withMountedRoot("moderation-mute-all") { root, element ->
            var clicks = 0
            val control = ConferenceMuteAllControl(root.vPanel()) { clicks++ }
            val button = element().q("button") as HTMLButtonElement
            assertTrue(button.textContent.orEmpty().contains("Alle stummschalten"))
            assertTrue(button.disabled, "nobody to mute: disabled")
            val hintId = assertNotNull(button.getAttribute("aria-describedby"), "the reason is attached to the button")
            val hint = assertNotNull(document.getElementById(hintId) as? HTMLElement)
            assertEquals("Niemand hat ein offenes Mikrofon.", hint.textContent.orEmpty().trim())
            assertTrue(hint.getBoundingClientRect().width > 0, "the reason is visible while disabled")
            assertTrue(button.getBoundingClientRect().height >= 44.0)

            control.update(2)
            assertFalse(button.disabled)
            assertNull(document.getElementById(hintId), "no reason while there is something to do")
            button.click()
            assertEquals(1, clicks)
            control.update(0)
            assertTrue((element().q("button") as HTMLButtonElement).disabled)
        }
    }

    @Test
    fun confirmation_namesTheCount_focusesAbbrechen_andEscapeCloses(): Promise<Unit> =
        formTest {
            mountedForm("moderation-confirm") { _, _ ->
                var confirmed = 0
                conferenceMuteAllDialog(count = 3) { confirmed++ }
                awaitUntil("the dialog is shown") { document.querySelector(".modal.show") != null }
                val modal = lastOpenModal()
                assertTrue(
                    modal.textContent.orEmpty().contains("3 Personen stummschalten? Moderatoren sind ausgenommen."),
                    modal.textContent.orEmpty(),
                )
                awaitUntil("the focus is on Abbrechen") {
                    (document.activeElement as? HTMLElement)?.textContent.orEmpty().trim() == "Abbrechen"
                }
                val names = modal.all("button").map { it.textContent.orEmpty().trim() }
                assertTrue("Stummschalten" in names && "Abbrechen" in names, "the two buttons: $names")
                modal.dispatchEvent(
                    org.w3c.dom.events
                        .KeyboardEvent("keydown", KeyboardEventInit(key = "Escape", code = "Escape", bubbles = true)),
                )
                awaitUntil("Escape closes the dialog without confirming") { document.querySelector(".modal.show") == null }
                delay(20)
                assertEquals(0, confirmed)
            }
        }

    @Test
    fun lockConfirmation_namesTheOpenMicrophones_orOmitsTheLineWhenThereAreNone(): Promise<Unit> =
        formTest {
            mountedForm("moderation-lock-confirm") { _, _ ->
                conferenceLockDialog(openMicrophones = 4) {}
                awaitUntil("the dialog is shown") { document.querySelector(".modal.show") != null }
                assertTrue(lastOpenModal().textContent.orEmpty().contains("Die offenen Mikrofone von 4 Personen werden stummgeschaltet."))
                closeOpenModals(timeoutMs = 1000)
                conferenceLockDialog(openMicrophones = 0) {}
                awaitUntil("the second dialog is shown") { document.querySelector(".modal.show") != null }
                assertFalse(lastOpenModal().textContent.orEmpty().contains("stummgeschaltet"))
            }
        }
}
