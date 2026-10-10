package network.lapis.cloud.client.encounter

import kotlinx.browser.localStorage
import kotlinx.coroutines.delay
import network.lapis.cloud.client.allOf
import network.lapis.cloud.client.awaitUntil
import network.lapis.cloud.client.formTest
import network.lapis.cloud.shared.domain.EncounterPresenceRole
import network.lapis.cloud.shared.domain.EncounterProfile
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.96 -- the bell sound switch in the device panel: only in a church-service room, for every role; default off; opening and redrawing
 * only READ the storage; the person's own change writes the key (and removes it again); turning it on plays exactly one probe strike,
 * turning it off none; a storage that throws means "off"; and the gear is there for the congregation even without a device to choose.
 */
class EncounterBellSwitchDomTest {
    private companion object {
        val stylesLoaded: Boolean =
            run {
                js("require('bootstrap/dist/css/bootstrap.min.css')")
                js("require('zzz-kvision-assets/css/kv-style.css')")
                js("require('@fortawesome/fontawesome-free/css/all.css')")
                js("require('./theme.css')")
                true
            }
        const val SOUND_KEY = "lapis.encounter.bellSoundOn"
    }

    private var clock = 1_000_000.0
    private val crowd = seatedCrowd()

    private suspend fun withRoom(
        role: EncounterPresenceRole,
        profile: EncounterProfile = EncounterProfile.CHURCH_SERVICE,
        sound: FakeBellSound = FakeBellSound(),
        block: suspend (EncounterRoomRig, HTMLElement) -> Unit,
    ) {
        val entry = testEntry(role = role)
        localStorage.removeItem(SOUND_KEY)
        try {
            withEncounterRoom(
                entry = entry,
                peopleOf = { listOf(testPerson("me", entry.presenceRole, "Ich Selbst")) + crowd },
                clock = { clock },
                session = if (role == EncounterPresenceRole.CONGREGATION) FakeListenerSession() else FakeSpeakerSession(),
                space = testSpace(profile = profile),
                bellSound = sound,
                block = block,
            )
        } finally {
            localStorage.removeItem(SOUND_KEY)
        }
    }

    private fun HTMLElement.gear(): HTMLElement? =
        (querySelector("button[aria-controls=lapis-encounter-device-panel]") as? HTMLElement)?.takeIf { it.offsetWidth > 0 }

    private fun HTMLElement.switch(): HTMLInputElement? = querySelector("#lapis-encounter-bell-switch") as? HTMLInputElement

    private suspend fun HTMLElement.openPanel(): HTMLInputElement {
        val gear = assertNotNull(gear(), "the gear is in the bar")
        gear.click()
        awaitUntil("the panel is open") {
            (querySelector("#lapis-encounter-device-panel") as? HTMLElement)?.offsetWidth?.let { it > 0 } ==
                true
        }
        return assertNotNull(switch())
    }

    @Test
    fun theSwitch_existsInAChurchRoom_forEveryRole_andNeverInAnAssembly(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            for (role in listOf(EncounterPresenceRole.CONGREGATION, EncounterPresenceRole.STEWARD, EncounterPresenceRole.PULPIT)) {
                withRoom(role) { _, element ->
                    val input = element.openPanel()
                    assertEquals("checkbox", input.type)
                    assertEquals("switch", input.getAttribute("role"))
                    val label = assertNotNull(element.querySelector("label[for=lapis-encounter-bell-switch]"))
                    assertEquals("Glockenton", label.textContent.orEmpty().trim())
                    val hint = assertNotNull(element.querySelector("#${input.getAttribute("aria-describedby")}"))
                    assertEquals("Gilt nur für diesen Browser.", hint.textContent.orEmpty().trim())
                    assertEquals("false", input.getAttribute("aria-checked"), "$role: default off")
                    assertFalse(input.checked)
                }
            }
            withRoom(EncounterPresenceRole.CONGREGATION, profile = EncounterProfile.ASSEMBLY) { _, element ->
                assertNull(element.switch(), "an assembly has no switch in the DOM")
                assertNull(element.gear(), "and, without devices, no gear")
            }
        }

    @Test
    fun theGear_isThereForTheCongregationWithoutADevice_andNamesTheBellSound(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            withRoom(EncounterPresenceRole.CONGREGATION) { _, element ->
                val gear = assertNotNull(element.gear())
                assertEquals("Glockenton einstellen", gear.getAttribute("aria-label"))
                assertEquals("", gear.textContent.orEmpty().trim(), "icon only")
            }
        }

    @Test
    fun openingAndRedrawing_onlyRead_theStorageStaysUntouched(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            withRoom(EncounterPresenceRole.CONGREGATION) { _, element ->
                assertNull(localStorage.getItem(SOUND_KEY))
                element.openPanel()
                delay(100)
                element.gear()!!.click() // closes
                element.gear()!!.click() // opens again (a redraw)
                delay(100)
                assertNull(localStorage.getItem(SOUND_KEY), "no write by opening or redrawing")
            }
        }

    @Test
    fun turningItOn_storesTheKeyAndPlaysExactlyOneProbe_turningItOff_removesTheKeyWithoutASound(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            val sound = FakeBellSound()
            withRoom(EncounterPresenceRole.CONGREGATION, sound = sound) { _, element ->
                val input = element.openPanel()
                input.click()
                assertTrue(input.checked)
                assertEquals("1", localStorage.getItem(SOUND_KEY))
                assertEquals("true", input.getAttribute("aria-checked"))
                assertEquals(1, sound.probes, "one probe strike, synchronously in the gesture")
                input.click()
                assertFalse(input.checked)
                assertNull(localStorage.getItem(SOUND_KEY), "the key is gone again")
                assertEquals("false", input.getAttribute("aria-checked"))
                assertEquals(1, sound.probes, "turning it off plays nothing")
                assertEquals(0, sound.rings)
            }
        }

    @Test
    fun aRememberedOn_isShownAsOn_whenThePanelOpens(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            withRoom(EncounterPresenceRole.CONGREGATION) { _, element ->
                localStorage.setItem(SOUND_KEY, "1")
                val input = element.openPanel()
                awaitUntil("on") { input.checked && input.getAttribute("aria-checked") == "true" }
            }
        }

    @Test
    fun aStorageThatThrows_meansOff_andNeverAnException(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            val patch: dynamic =
                js(
                    """(function () {
                        var original = Storage.prototype.getItem;
                        Storage.prototype.getItem = function () { throw new Error('blocked'); };
                        return function () { Storage.prototype.getItem = original; };
                    })""",
                )
            val restore: dynamic = patch()
            try {
                withRoom(EncounterPresenceRole.CONGREGATION) { _, element ->
                    val input = element.openPanel()
                    assertEquals("false", input.getAttribute("aria-checked"))
                    assertFalse(input.checked)
                }
            } finally {
                restore()
            }
        }

    @Test
    fun theSwitch_isOperableByKeyboard_andPartOfThePanelsTabOrder(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            withRoom(EncounterPresenceRole.CONGREGATION) { _, element ->
                val input = element.openPanel()
                awaitUntil("the switch has the focus") { kotlinx.browser.document.activeElement === input }
                assertTrue(input.tabIndex >= 0)
                assertEquals(1, element.allOf("#lapis-encounter-device-panel input[type=checkbox]").size)
            }
        }
}
