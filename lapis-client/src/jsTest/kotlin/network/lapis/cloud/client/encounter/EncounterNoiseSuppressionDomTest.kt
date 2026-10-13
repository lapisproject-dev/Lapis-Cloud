package network.lapis.cloud.client.encounter

import kotlinx.browser.localStorage
import network.lapis.cloud.client.FakeNoiseSuppressionChain
import network.lapis.cloud.client.NOISE_SUPPRESSION_STORAGE_KEY
import network.lapis.cloud.client.NoiseSuppressionWarmup
import network.lapis.cloud.client.awaitUntil
import network.lapis.cloud.client.formTest
import network.lapis.cloud.shared.domain.EncounterPresenceRole
import network.lapis.cloud.shared.domain.EncounterProfile
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.114 -- the noise suppression switch in the pulpit's device panel: the row is in the panel of an office holder (pulpit and steward),
 * NEVER in the congregation's; it carries the music hint; one click puts the filter in front of the microphone and writes the one shared key
 * (the same as the video conference: "lapis.audio.noiseSuppression.v1"); the microphone button keeps its `aria-pressed` pattern and gets no
 * new glyph.
 */
class EncounterNoiseSuppressionDomTest {
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

    private var clock = 1_000_000.0
    private val crowd = seatedCrowd()

    private suspend fun withRoom(
        role: EncounterPresenceRole,
        session: FakeListenerSession,
        block: suspend (EncounterRoomRig, HTMLElement) -> Unit,
    ) {
        localStorage.removeItem(NOISE_SUPPRESSION_STORAGE_KEY)
        try {
            val entry = testEntry(role = role)
            withEncounterRoom(
                entry = entry,
                peopleOf = { listOf(testPerson("me", entry.presenceRole, "Ich Selbst")) + crowd },
                clock = { clock },
                session = session,
                space = testSpace(profile = EncounterProfile.CHURCH_SERVICE),
                block = block,
            )
        } finally {
            localStorage.removeItem(NOISE_SUPPRESSION_STORAGE_KEY)
        }
    }

    private fun HTMLElement.openPanel() {
        val gear = assertNotNull(querySelector("button[aria-controls=lapis-encounter-device-panel]") as? HTMLElement)
        gear.click()
    }

    private fun HTMLElement.noiseSwitch(): HTMLElement? = querySelector("#lapis-encounter-device-panel button[role=switch]") as? HTMLElement

    @Test
    fun anOfficeHolder_hasTheRowInTheDevicePanel_withTheHint_andTheCongregationNever(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            for (role in listOf(EncounterPresenceRole.PULPIT, EncounterPresenceRole.STEWARD)) {
                withRoom(role, FakeSpeakerSession()) { _, element ->
                    element.openPanel()
                    awaitUntil("the panel is open") {
                        (element.querySelector("#lapis-encounter-device-panel") as? HTMLElement)?.offsetWidth?.let {
                            it >
                                0
                        } ==
                            true
                    }
                    val switch = assertNotNull(element.noiseSwitch(), "$role has the switch")
                    assertEquals("false", switch.getAttribute("aria-checked"))
                    val panelText = element.querySelector("#lapis-encounter-device-panel")!!.textContent.orEmpty()
                    assertTrue(panelText.contains("Rauschunterdrückung"))
                    assertTrue(panelText.contains("Gesang und Musik klingen damit verändert."), "the hint names the price")
                }
            }
            withRoom(EncounterPresenceRole.CONGREGATION, FakeListenerSession()) { _, element ->
                element.openPanel()
                awaitUntil("the panel is open") {
                    (element.querySelector("#lapis-encounter-device-panel") as? HTMLElement)?.offsetWidth?.let {
                        it >
                            0
                    } ==
                        true
                }
                assertNull(element.noiseSwitch(), "no switch for the congregation")
                assertTrue(!element.textContent.orEmpty().contains("Rauschunterdrückung"))
            }
        }

    @Test
    fun aClick_putsTheFilterInFrontOfTheMicrophone_andWritesTheSharedKey(): Promise<Unit> =
        formTest {
            val session = FakeSpeakerSession()
            val chain = FakeNoiseSuppressionChain()
            session.noiseChain = chain
            withRoom(EncounterPresenceRole.PULPIT, session) { _, element ->
                element.openPanel()
                awaitUntil("the switch") { element.noiseSwitch() != null }
                val switch = assertNotNull(element.noiseSwitch())
                switch.click()
                awaitUntil("on") { switch.getAttribute("aria-checked") == "true" && switch.getAttribute("aria-disabled") == "false" }
                assertEquals(1, chain.attachCalls)
                assertEquals("on", localStorage.getItem(NOISE_SUPPRESSION_STORAGE_KEY))
                assertEquals("lapis.audio.noiseSuppression.v1", NOISE_SUPPRESSION_STORAGE_KEY, "the key of the video conference")
                val status = assertNotNull(element.querySelector("#lapis-encounter-device-panel [role=status]"))
                assertEquals("Rauschunterdrückung aktiv.", status.textContent.orEmpty().trim())
                assertEquals(listOf<Boolean>(), session.microphoneCalls, "the switch does not touch the microphone's on/off")
            }
        }

    @Test
    fun theMicrophoneButton_keepsItsAriaPressedPattern_andGetsNoNewGlyph(): Promise<Unit> =
        formTest {
            withRoom(EncounterPresenceRole.PULPIT, FakeSpeakerSession()) { _, element ->
                val micButton = assertNotNull(element.querySelector("button i.fa-microphone-slash")?.parentElement as? HTMLElement)
                assertEquals("false", micButton.getAttribute("aria-pressed"))
                assertEquals(1, micButton.querySelectorAll("i").length, "one glyph, the microphone")
                assertNull(micButton.querySelector(".lapis-noise-switch"), "no noise glyph on the microphone button")
            }
        }

    /**
     * V1.9.124 -- the instance default belongs to the video conference ONLY. Even when the page already knows an organisation default of
     * "on" (the lobby of a conference was opened before), the pulpit starts without the filter (singing is its normal use), shows no
     * "Voreinstellung der Instanz" line and writes nothing.
     */
    @Test
    fun theInstanceDefault_neverReachesThePulpit(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            NoiseSuppressionWarmup.remember(true)
            try {
                withRoom(EncounterPresenceRole.PULPIT, FakeSpeakerSession()) { _, element ->
                    element.openPanel()
                    awaitUntil("the panel is open") {
                        (element.querySelector("#lapis-encounter-device-panel") as? HTMLElement)?.offsetWidth?.let { it > 0 } == true
                    }
                    val switch = assertNotNull(element.noiseSwitch())
                    assertEquals("false", switch.getAttribute("aria-checked"), "the pulpit starts without the filter")
                    val panel = element.querySelector("#lapis-encounter-device-panel")!!
                    assertTrue(!panel.textContent.orEmpty().contains("Voreinstellung der Instanz"), "no default line")
                    val line = panel.querySelector("[id^=lapis-noise-default-]") as? HTMLElement
                    assertTrue(line == null || line.getBoundingClientRect().height == 0.0, "the default line is not shown")
                    assertNull(localStorage.getItem(NOISE_SUPPRESSION_STORAGE_KEY), "nothing was written")
                }
            } finally {
                NoiseSuppressionWarmup.remember(false)
            }
        }
}
