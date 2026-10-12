package network.lapis.cloud.client

import io.kvision.panel.hPanel
import kotlinx.browser.document
import network.lapis.cloud.shared.conference.ConferenceView
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.115 -- the view switcher: roles, the roving focus, Escape, the disabled share radio with its reason, the check mark. */
class ConferenceViewSwitcherDomTest {
    private fun HTMLElement.radios() =
        (0 until querySelectorAll("[role=radio]").length).map {
            querySelectorAll("[role=radio]").item(it) as HTMLElement
        }

    private fun key(
        element: HTMLElement,
        key: String,
    ) {
        element.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = key, bubbles = true, cancelable = true)))
    }

    @Test
    fun theGroupIsARadiogroupWithFourNamedRadios_andOnlyTheCurrentOneIsInTheTabOrder() {
        withMountedRoot("view-switcher-roles") { root, element ->
            val group = root.conferenceViewRadioGroup("t1", onChoose = {}, onEscape = {})
            group.update(ConferenceView.SPEAKER, shareActive = false)
            val host = element()
            val box = assertNotNull(host.querySelector("[role=radiogroup]") as? HTMLElement)
            assertEquals("Ansicht", box.getAttribute("aria-label"))
            val radios = host.radios()
            assertEquals(4, radios.size)
            assertEquals(
                listOf("Galerie", "Sprecher groß", "Person anheften", "Bildschirmfreigabe groß"),
                radios.map {
                    it.textContent
                        .orEmpty()
                        .replace("✓", "")
                        .trim()
                },
            )
            assertEquals(listOf("false", "true", "false", "false"), radios.map { it.getAttribute("aria-checked") })
            assertEquals(listOf("-1", "0", "-1", "-1"), radios.map { it.getAttribute("tabindex") })
        }
    }

    @Test
    fun theCurrentViewCarriesACheckMarkAndNoOtherDoes() {
        withMountedRoot("view-switcher-check") { root, element ->
            val group = root.conferenceViewRadioGroup("t2", onChoose = {}, onEscape = {})
            group.update(ConferenceView.PINNED, shareActive = true)
            val shown =
                element().radios().map { (it.querySelector(".lapis-conference-view-check") as HTMLElement).style.display != "none" }
            assertEquals(listOf(false, false, true, false), shown)
        }
    }

    @Test
    fun theShareRadioIsDisabledWithAReasonUntilSomebodyShares_andRefusesTheClickMeanwhile() {
        withMountedRoot("view-switcher-share") { root, element ->
            val chosen = mutableListOf<ConferenceView>()
            val group = root.conferenceViewRadioGroup("t3", onChoose = { chosen += it }, onEscape = {})
            group.update(ConferenceView.GALLERY, shareActive = false)
            val share = element().radios().last()
            assertEquals("true", share.getAttribute("aria-disabled"))
            val hintId = assertNotNull(share.getAttribute("aria-describedby"))
            assertEquals("Niemand teilt gerade den Bildschirm", element().querySelector("#$hintId")?.textContent)
            share.click()
            assertTrue(chosen.isEmpty(), "a disabled radio is not a choice")
            group.update(ConferenceView.GALLERY, shareActive = true)
            assertNull(share.getAttribute("aria-disabled"))
            share.click()
            assertEquals(listOf(ConferenceView.SCREEN_SHARE), chosen)
        }
    }

    @Test
    fun arrowKeysMoveTheFocusAndEscapeCloses() {
        withMountedRoot("view-switcher-keys") { root, element ->
            var escaped = 0
            val group = root.conferenceViewRadioGroup("t4", onChoose = {}, onEscape = { escaped++ })
            group.update(ConferenceView.GALLERY, shareActive = false)
            val radios = element().radios()
            radios[0].focus()
            key(radios[0], "ArrowDown")
            assertTrue(document.activeElement === radios[1], "ArrowDown moves to the next radio")
            key(radios[1], "ArrowUp")
            assertTrue(document.activeElement === radios[0])
            key(radios[0], "ArrowLeft")
            assertTrue(document.activeElement === radios[3], "wraps around")
            key(radios[3], "Escape")
            assertEquals(1, escaped)
        }
    }

    @Test
    fun enterOnARadioChoosesItAndThePopoverClosesAndGivesTheFocusBack() {
        withMountedRoot("view-switcher-popover") { root, element ->
            val bar = root.hPanel()
            val trigger = bar.conferenceControlButton(ActionIcon.VIEW_GALLERY, "Ansicht")
            val chosen = mutableListOf<ConferenceView>()
            val popover = ConferenceViewPopover(root, trigger) { chosen += it }
            trigger.onClick { popover.toggle() }
            val triggerEl = trigger.getElement() as HTMLElement
            assertEquals("false", triggerEl.getAttribute("aria-expanded"))
            triggerEl.click()
            assertTrue(popover.isOpen)
            assertEquals("true", triggerEl.getAttribute("aria-expanded"))
            element().radios()[1].click()
            assertEquals(listOf(ConferenceView.SPEAKER), chosen)
            assertFalse(popover.isOpen)
            assertTrue(document.activeElement === triggerEl, "the focus goes back to the bar button")
            popover.dispose()
        }
    }

    @Test
    fun aPopoverOpenedThroughATwinClickStaysOpen() {
        withMountedRoot("view-switcher-twin") { root, _ ->
            val bar = root.hPanel()
            val trigger = bar.conferenceControlButton(ActionIcon.VIEW_GALLERY, "Ansicht")
            val popover = ConferenceViewPopover(root, trigger) { }
            trigger.onClick { popover.toggle() }
            val triggerEl = trigger.getElement() as HTMLElement
            // like the "Mehr" sheet twin: its own click handler clicks the primary button synchronously
            val twin = bar.conferenceControlButton(ActionIcon.VIEW_GALLERY, "Ansicht (Mehr)")
            twin.onClick { triggerEl.click() }
            (twin.getElement() as HTMLElement).click()
            assertTrue(popover.isOpen, "the click that opened the popover must not close it again")
            popover.dispose()
        }
    }

    @Test
    fun theRadioTargetIsPureArrowArithmetic() {
        assertEquals(ConferenceView.SPEAKER, conferenceViewRadioTarget(ConferenceView.GALLERY, "ArrowRight"))
        assertEquals(ConferenceView.SCREEN_SHARE, conferenceViewRadioTarget(ConferenceView.GALLERY, "ArrowLeft"))
        assertEquals(ConferenceView.GALLERY, conferenceViewRadioTarget(ConferenceView.SCREEN_SHARE, "Home"))
        assertEquals(ConferenceView.SCREEN_SHARE, conferenceViewRadioTarget(ConferenceView.GALLERY, "End"))
        assertNull(conferenceViewRadioTarget(ConferenceView.GALLERY, "a"))
    }
}
