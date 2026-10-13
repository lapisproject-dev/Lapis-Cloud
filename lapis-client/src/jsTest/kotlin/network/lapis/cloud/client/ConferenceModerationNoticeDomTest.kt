package network.lapis.cloud.client

import io.kvision.panel.vPanel
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.108 -- what the AFFECTED person sees and hears, in a real mounted root with the real stylesheet: the calm band, the two live
 * regions (an assertive notice once, a polite request), no focus change, no error colour, light and dark, and the moderation disclosure's
 * switches. The call screen cannot be mounted in Karma; the blocks are the ones the screen uses.
 */
class ConferenceModerationNoticeDomTest {
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

    @AfterTest
    fun resetTheme() {
        document.documentElement?.removeAttribute("data-theme")
    }

    private fun HTMLElement.q(selector: String) = assertNotNull(querySelector(selector) as? HTMLElement, "no $selector")

    private fun HTMLElement.all(selector: String): List<HTMLElement> =
        (0 until querySelectorAll(selector).length).map { querySelectorAll(selector).item(it) as HTMLElement }

    private fun liveRegion(
        host: HTMLElement,
        mode: String,
    ) = host.q("[aria-live=$mode]")

    @Test
    fun aMute_showsACalmBand_andIsAnnouncedOnceAssertively_withoutMovingTheFocus() {
        assertTrue(stylesLoaded)
        withMountedRoot("moderation-muted-notice") { root, element ->
            val field = document.createElement("input") as HTMLInputElement
            element().appendChild(field)
            field.focus()
            val notices = ConferenceModerationNotices(root.vPanel())
            notices.showMuted()

            val text = "Ihr Mikrofon wurde von der Moderation stummgeschaltet."
            assertEquals(text, notices.bandText)
            assertEquals(text, liveRegion(element(), "assertive").textContent.orEmpty(), "announced assertively")
            assertEquals("", liveRegion(element(), "polite").textContent.orEmpty())
            assertEquals(field, document.activeElement, "the focus did not move")
            // the same event again within the dedupe window is not announced a second time
            liveRegion(element(), "assertive").textContent = ""
            notices.showMuted()
            assertEquals("", liveRegion(element(), "assertive").textContent.orEmpty(), "once")
            assertFalse(element().innerHTML.contains("###KvI18nS###"), "no i18n marker leaked into the DOM")
        }
    }

    @Test
    fun theBand_isInfoColoured_neverErrorRed_andHasA44pxCloseButton() {
        assertTrue(stylesLoaded)
        withMountedRoot("moderation-band-style") { root, element ->
            val notices = ConferenceModerationNotices(root.vPanel())
            notices.showMuted()
            val band = element().q(".lapis-conference-moderation-band")
            val style = window.getComputedStyle(band)
            assertFalse(band.className.contains("danger") || band.className.contains("alert-danger"), band.className)
            assertEquals(probeBackground(element(), "var(--lapis-accent-soft)"), style.backgroundColor)
            val close = band.q("button")
            assertTrue(close.getBoundingClientRect().height >= 44.0, "close target height")
            assertTrue(close.getBoundingClientRect().width >= 44.0, "close target width")
            assertEquals("Hinweis schließen", close.getAttribute("aria-label"))
            (close as HTMLButtonElement).click()
            assertNull(notices.bandText, "closed")
        }
    }

    @Test
    fun theBand_followsTheThemeTokens_inLightAndDark() {
        assertTrue(stylesLoaded)
        withMountedRoot("moderation-band-theme") { root, element ->
            val notices = ConferenceModerationNotices(root.vPanel())
            notices.showMuted()
            val band = element().q(".lapis-conference-moderation-band")
            document.documentElement!!.setAttribute("data-theme", "light")
            val light = window.getComputedStyle(band).backgroundColor
            assertEquals(probeBackground(element(), "var(--lapis-accent-soft)"), light)
            document.documentElement!!.setAttribute("data-theme", "dark")
            val dark = window.getComputedStyle(band).backgroundColor
            assertEquals(probeBackground(element(), "var(--lapis-accent-soft)"), dark)
            assertNotEquals(light, dark, "the band has a light and a dark colour")
        }
    }

    private fun probeBackground(
        host: HTMLElement,
        value: String,
    ): String {
        val probe = document.createElement("div") as HTMLElement
        probe.style.background = value
        host.appendChild(probe)
        val computed = window.getComputedStyle(probe).backgroundColor
        host.removeChild(probe)
        return computed
    }

    @Test
    fun anUnmuteRequest_isAPoliteBand_withTheOwnToggleAndNotNow_andNeverMovesTheFocus() {
        assertTrue(stylesLoaded)
        withMountedRoot("moderation-request-notice") { root, element ->
            val field = document.createElement("input") as HTMLInputElement
            element().appendChild(field)
            field.focus()
            val notices = ConferenceModerationNotices(root.vPanel())
            var turnedOn = 0
            notices.showUnmuteRequest { turnedOn++ }

            val text = "Die Moderation bittet Sie, Ihr Mikrofon einzuschalten."
            assertEquals(text, notices.bandText)
            assertEquals(text, liveRegion(element(), "polite").textContent.orEmpty(), "announced politely")
            assertEquals("", liveRegion(element(), "assertive").textContent.orEmpty())
            assertEquals(field, document.activeElement, "the focus did not move")
            val names = element().all(".lapis-conference-moderation-band button").map { it.textContent.orEmpty().trim() }
            assertEquals(listOf("Mikrofon einschalten", "Nicht jetzt"), names)
            assertTrue(element().all(".lapis-conference-moderation-band button").all { it.getBoundingClientRect().height >= 44.0 })

            // the band offers the person's own toggle; nothing happens before the person clicks
            assertEquals(0, turnedOn)
            (element().all(".lapis-conference-moderation-band button")[0] as HTMLButtonElement).click()
            assertEquals(1, turnedOn)
            assertNull(notices.bandText, "the band is gone after the person acted")
        }
    }

    @Test
    fun repeatedRequests_replaceTheBand_theyNeverStack() {
        withMountedRoot("moderation-request-replace") { root, element ->
            val notices = ConferenceModerationNotices(root.vPanel())
            notices.showUnmuteRequest {}
            notices.showUnmuteRequest {}
            notices.showMuted()
            assertEquals(1, element().all(".lapis-conference-moderation-band").size)
            assertEquals(1, element().all(".lapis-conference-moderation-band-text").size)
            (element().all(".lapis-conference-moderation-band button")[0] as HTMLButtonElement).click()
            assertNull(notices.bandText)
        }
    }

    @Test
    fun lockAnnouncements_areTheTwoFixedTexts() {
        assertEquals(
            "Die Moderation hat das Einschalten der Mikrofone gesperrt.",
            conferenceModerationAnnouncement(
                network.lapis.cloud.client.livekit.ConferenceModerationSignal.Lock,
            )?.removePrefix("###KvI18nS###"),
        )
        assertEquals(
            "Sie können Ihr Mikrofon wieder einschalten.",
            conferenceModerationAnnouncement(
                network.lapis.cloud.client.livekit.ConferenceModerationSignal.Unlock,
            )?.removePrefix("###KvI18nS###"),
        )
        assertNull(conferenceModerationAnnouncement(network.lapis.cloud.client.livekit.ConferenceModerationSignal.Muted))
    }

    // -- the disclosure -----------------------------------------------------------------------------------------------------------------

    @Test
    fun theDisclosure_hasFourRealSwitches_aRealToggle_andOpensWhileLocked() {
        assertTrue(stylesLoaded)
        withMountedRoot("moderation-panel") { root, element ->
            val panel =
                ConferenceModerationPanel(
                    parent = root.vPanel(),
                    muteOnJoin = false,
                    participantsMayUnmute = true,
                    onMuteOnJoin = { _, _ -> },
                    onLock = { _, _ -> },
                )
            val toggle = element().q("button.lapis-conference-moderation-toggle")
            assertEquals("Moderation", toggle.textContent.orEmpty().trim())
            assertEquals("false", toggle.getAttribute("aria-expanded"), "starts closed while the room is not locked")
            // a hidden KVision widget is not rendered at all: closed = no body in the DOM, no switch to tab into
            assertNull(element().querySelector(".lapis-conference-moderation-body"), "the body is not shown")
            assertNull(element().querySelector("input[role=switch]"))
            toggle.click()
            assertEquals("true", toggle.getAttribute("aria-expanded"))
            val switches = element().all("input[type=checkbox][role=switch]")
            assertEquals(4, switches.size, "mute on join, lock, (V1.9.120) the join/leave tones and (V1.9.127) the reactions")
            switches.forEach { input ->
                val label = assertNotNull(document.querySelector("label[for='${input.id}']"), "a label for ${input.id}")
                assertTrue(label.textContent.orEmpty().isNotBlank())
                assertTrue(input.getAttribute("aria-describedby").orEmpty().isNotBlank(), "a hint")
            }
            assertEquals(
                listOf(
                    "Teilnehmende stumm beitreten lassen",
                    "Teilnehmende dürfen sich nicht selbst einschalten",
                    "Töne bei Beitritt und Verlassen in diesem Raum",
                    "Reaktionen in diesem Raum",
                ),
                switches.map {
                    document
                        .querySelector("label[for='${it.id}']")!!
                        .textContent
                        .orEmpty()
                        .trim()
                },
            )
            // a locked room opens the panel by itself
            panel.setOpen(false)
            panel.setState(muteOnJoin = true, participantsMayUnmute = false)
            assertEquals("true", toggle.getAttribute("aria-expanded"))
            // the closed body was removed from the DOM, so the switches are new elements: read them again
            val reopened = element().all("input[type=checkbox][role=switch]")
            assertEquals(4, reopened.size)
            assertTrue((reopened[0] as HTMLInputElement).checked, "mute on join is on")
            assertTrue((reopened[1] as HTMLInputElement).checked, "locked = the second switch is on")
        }
    }

    @Test
    fun theSwitches_reportTheWish_andCanBePutBack() {
        withMountedRoot("moderation-panel-events") { root, element ->
            val seen = mutableListOf<String>()
            val panel =
                ConferenceModerationPanel(
                    parent = root.vPanel(),
                    muteOnJoin = false,
                    participantsMayUnmute = true,
                    onMuteOnJoin = { value, revert ->
                        seen += "join=$value"
                        revert()
                    },
                    onLock = { locked, revert ->
                        seen += "lock=$locked"
                        revert()
                    },
                )
            panel.setOpen(true)
            val switches = element().all("input[type=checkbox][role=switch]")
            (switches[0] as HTMLInputElement).click()
            assertFalse((switches[0] as HTMLInputElement).checked, "put back by revert()")
            (switches[1] as HTMLInputElement).click()
            assertFalse((switches[1] as HTMLInputElement).checked, "put back by revert()")
            assertEquals(listOf("join=true", "lock=true"), seen)
            panel.setBusy(true)
            assertTrue((switches[0] as HTMLInputElement).disabled && (switches[1] as HTMLInputElement).disabled)
            panel.setBusy(false)
            assertFalse((switches[0] as HTMLInputElement).disabled)
        }
    }
}
