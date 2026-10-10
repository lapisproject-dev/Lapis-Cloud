package network.lapis.cloud.client.encounter

import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.coroutines.delay
import network.lapis.cloud.client.StubResponse
import network.lapis.cloud.client.allOf
import network.lapis.cloud.client.awaitUntil
import network.lapis.cloud.client.formTest
import network.lapis.cloud.client.routeOf
import network.lapis.cloud.client.rpcService
import network.lapis.cloud.shared.domain.EncounterEntryDto
import network.lapis.cloud.shared.domain.EncounterPresenceRole
import network.lapis.cloud.shared.domain.EncounterPresentDto
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.rpc.IEncounterSpaceService
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.96 -- the bell on the real DOM: the button exists for the pulpit of a church-service room and for nobody else, it is a 44 px
 * icon-only control that fires one call per press and locks itself for ten seconds (`aria-disabled`, never `disabled`), and the sign is the
 * same quiet chip for everybody -- announced once in a region of its own, extended (not doubled) by a second packet, sounded only when
 * this browser's sound is on AND the page is visible, and never able to be kept away by a failing sound.
 */
class EncounterBellDomTest {
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

    private fun peopleFor(entry: EncounterEntryDto): List<EncounterPresentDto> =
        listOf(testPerson("me", entry.presenceRole, "Ich Selbst")) + crowd

    private fun soundStored(on: Boolean) {
        if (on) localStorage.setItem(SOUND_KEY, "1") else localStorage.removeItem(SOUND_KEY)
    }

    private suspend fun withRoom(
        role: EncounterPresenceRole,
        profile: EncounterProfile = EncounterProfile.CHURCH_SERVICE,
        privileged: Boolean = false,
        bellScheduler: EncounterBlessingScheduler = browserBlessingScheduler,
        blessingScheduler: EncounterBlessingScheduler = browserBlessingScheduler,
        sound: FakeBellSound = FakeBellSound(),
        pageVisible: () -> Boolean = { true },
        reducedMotion: () -> Boolean = { false },
        extraRespond: (network.lapis.cloud.client.RecordedRequest) -> StubResponse? = { null },
        block: suspend (EncounterRoomRig, HTMLElement) -> Unit,
    ) {
        val entry = testEntry(role = role)
        try {
            withEncounterRoom(
                entry = entry,
                peopleOf = { peopleFor(entry) },
                clock = { clock },
                privileged = privileged,
                session = if (role == EncounterPresenceRole.CONGREGATION) FakeListenerSession() else FakeSpeakerSession(),
                space = testSpace(profile = profile),
                bellScheduler = bellScheduler,
                blessingScheduler = blessingScheduler,
                bellSound = sound,
                bellPageVisible = pageVisible,
                bellReducedMotion = reducedMotion,
                extraRespond = extraRespond,
                block = block,
            )
        } finally {
            soundStored(false)
        }
    }

    private fun HTMLElement.sign(): HTMLElement? = querySelector(".lapis-encounter-bell") as? HTMLElement

    private fun HTMLElement.liveTexts(): List<String> =
        allOf("[role=status]")
            .map {
                it.textContent.orEmpty().trim()
            }.filter { it.isNotEmpty() }

    @Test
    fun thePulpitOfAChurchRoom_hasTheBell_asA44pxIconOnlyButton_beforeTheBlessing(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            withRoom(EncounterPresenceRole.PULPIT) { _, element ->
                val button = element.barControl("Glocke läuten")
                assertEquals("BUTTON", button.tagName)
                assertEquals("Glocke läuten", button.getAttribute("title"))
                assertEquals("", button.textContent.orEmpty().trim(), "no visible word")
                assertNotNull(button.querySelector("i.fa-bell"), "the bell symbol")
                val box = button.getBoundingClientRect()
                assertTrue(box.width >= 44.0 && box.height >= 44.0, "at least 44 x 44 px: ${box.width} x ${box.height}")
                assertFalse(button.hasAttribute("disabled"))
                assertNotNull(button.closest(".lapis-encounter-control-group--liturgy"), "in the liturgy group")
                val names = element.barControlNames()
                assertTrue(names.indexOf("Glocke läuten") < names.indexOf("Segen"), "the bell stands before the blessing: $names")
                assertTrue(names.indexOf("Glocke läuten") > names.indexOf("Amen"), "after the reactions: $names")
            }
        }

    @Test
    fun everybodyElse_hasNoBell_neitherInTheBar_norInTheSheet_noSign_noSwitch(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            val cases =
                listOf(
                    "steward" to Triple(EncounterPresenceRole.STEWARD, EncounterProfile.CHURCH_SERVICE, false),
                    "congregation" to Triple(EncounterPresenceRole.CONGREGATION, EncounterProfile.CHURCH_SERVICE, false),
                    "admin without an office" to Triple(EncounterPresenceRole.CONGREGATION, EncounterProfile.CHURCH_SERVICE, true),
                    "pulpit of an assembly" to Triple(EncounterPresenceRole.PULPIT, EncounterProfile.ASSEMBLY, false),
                    "congregation of an assembly" to Triple(EncounterPresenceRole.CONGREGATION, EncounterProfile.ASSEMBLY, false),
                )
            for ((name, case) in cases) {
                withRoom(role = case.first, profile = case.second, privileged = case.third) { _, element ->
                    assertFalse("Glocke läuten" in element.barControlNames(), "$name: no bell control: ${element.barControlNames()}")
                    assertTrue(element.allOf(".lapis-encounter-more-sheet button").none { it.textContent.orEmpty().contains("Glocke") })
                    if (case.second == EncounterProfile.ASSEMBLY) {
                        assertNull(element.sign(), "$name: an assembly has no bell sign at all")
                        assertNull(element.querySelector("#lapis-encounter-bell-switch"), "$name: and no sound switch")
                    }
                }
            }
        }

    @Test
    fun atPhoneWidths_thePulpitReachesTheBell_andTheOtherControlsKeepTheirPlaces(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            for (width in listOf(360, 320)) {
                withRoom(EncounterPresenceRole.PULPIT) { _, element ->
                    (element.querySelector(".lapis-encounter-room")!!.parentElement as HTMLElement).style.width = "${width}px"
                    delay(150)
                    val shown = element.barButtons().filter { it.offsetWidth > 0 }
                    val inBar = shown.any { it.barName() == "Glocke läuten" }
                    if (!inBar) {
                        element.barControl("Mehr").click()
                        awaitUntil("$width px: the twin is in the open sheet") {
                            element.allOf(".lapis-encounter-more-sheet button").any {
                                it.textContent.orEmpty().trim() == "Glocke läuten" && it.offsetWidth > 0
                            }
                        }
                    }
                    assertEquals("Verlassen", shown.last().barName(), "Verlassen stays last")
                    assertTrue(
                        shown.any { it.getAttribute("aria-controls") == "lapis-encounter-device-panel" },
                        "$width px: the gear stays in the bar",
                    )
                    val bar = element.querySelector(".lapis-encounter-controls") as HTMLElement
                    assertTrue(bar.scrollWidth <= bar.clientWidth + 1, "$width px: the bar never scrolls sideways")
                }
            }
        }

    @Test
    fun theCongregation_atPhoneWidth_getsTheGearWithoutAnOverflowOrAScrollbar(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            withRoom(EncounterPresenceRole.CONGREGATION) { _, element ->
                (element.querySelector(".lapis-encounter-room")!!.parentElement as HTMLElement).style.width = "360px"
                delay(150)
                val bar = element.querySelector(".lapis-encounter-controls") as HTMLElement
                val shown = element.barButtons().filter { it.offsetWidth > 0 }
                assertTrue(shown.any { it.getAttribute("aria-controls") == "lapis-encounter-device-panel" }, "the gear is in the bar")
                assertTrue(bar.scrollWidth <= bar.clientWidth + 1, "no sideways scrolling")
                val tops = shown.map { it.getBoundingClientRect().top }
                assertTrue(
                    tops.max() - tops.min() < 20.0,
                    "the bar does not wrap: ${shown.map { it.barName() to it.getBoundingClientRect().top }}",
                )
            }
        }

    @Test
    fun aPress_callsRingBellOnce_locksTheButtonAndItsTwinForTenSeconds_andThenFreesThemAgain(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            val route = routeOf { rpcService<IEncounterSpaceService>().ringBell("space-1") }
            val scheduler = ManualBlessingScheduler()
            withRoom(
                EncounterPresenceRole.PULPIT,
                bellScheduler = scheduler,
                extraRespond = { request -> if (request.isRpc && request.rpcRoute == route) StubResponse(text = "null") else null },
            ) { rig, element ->
                val button = element.barControl("Glocke läuten")
                assertNull(button.getAttribute("aria-disabled"))
                button.click()
                awaitUntil("the call went out") { rig.requests.any { it.isRpc && it.rpcRoute == route } }
                assertEquals("true", button.getAttribute("aria-disabled"), "locked")
                assertFalse(button.hasAttribute("disabled"), "never disabled: the focus stays")
                assertEquals("", button.textContent.orEmpty().trim(), "no number, no countdown")
                button.click()
                button.click()
                delay(150)
                assertEquals(1, rig.requests.count { it.isRpc && it.rpcRoute == route }, "locked presses make no call")
                assertEquals(1, scheduler.pending(ENCOUNTER_BELL_CLIENT_LOCK_MS))
                scheduler.fire(ENCOUNTER_BELL_CLIENT_LOCK_MS)
                assertNull(button.getAttribute("aria-disabled"), "free again after ten seconds")
                button.click()
                awaitUntil("a second call after the lock") { rig.requests.count { it.isRpc && it.rpcRoute == route } == 2 }
            }
        }

    @Test
    fun aRefusedBell_isNeitherShownNorAnnounced(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            val route = routeOf { rpcService<IEncounterSpaceService>().ringBell("space-1") }
            withRoom(
                EncounterPresenceRole.PULPIT,
                extraRespond = { request ->
                    if (request.isRpc &&
                        request.rpcRoute == route
                    ) {
                        request.refusedWith("network.lapis.cloud.shared.rpc.ForbiddenException")
                    } else {
                        null
                    }
                },
            ) { rig, element ->
                element.barControl("Glocke läuten").click()
                awaitUntil("the call went out") { rig.requests.any { it.isRpc && it.rpcRoute == route } }
                delay(200)
                assertTrue(element.sign()?.hasAttribute("hidden") ?: true, "nothing is shown by a refusal")
                assertEquals("", element.liveTexts().joinToString(""), "and nothing is announced")
            }
        }

    @Test
    fun aPacket_showsTheSameQuietSign_toEveryRole_withTheNoteWhileTheSoundIsOff(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            for (role in listOf(EncounterPresenceRole.CONGREGATION, EncounterPresenceRole.STEWARD, EncounterPresenceRole.PULPIT)) {
                val sound = FakeBellSound()
                withRoom(role, bellScheduler = ManualBlessingScheduler(), sound = sound) { rig, element ->
                    assertTrue(element.sign()?.hasAttribute("hidden") == true, "$role: hidden until a bell arrives")
                    rig.room.callbacks.onBell()
                    awaitUntil("$role: the sign shows") { element.sign()?.hasAttribute("hidden") == false }
                    val sign = assertNotNull(element.sign())
                    assertEquals("true", sign.getAttribute("aria-hidden"), "decoration; the live region speaks")
                    assertNotNull(sign.querySelector(".fa-bell"), "the bell symbol")
                    assertTrue(sign.textContent.orEmpty().contains("Glocke"), "the word")
                    val note = assertNotNull(sign.querySelector(".lapis-encounter-bell-mute") as? HTMLElement)
                    assertFalse(note.hasAttribute("hidden"), "$role: the sound is off, so the sign says so")
                    assertEquals("Ton aus", note.textContent.orEmpty().trim())
                    assertTrue(sign.querySelectorAll("button, a, input, [tabindex]").length == 0, "nothing to operate in the sign")
                    assertNotNull(sign.closest(".lapis-encounter-signs"), "in the common holder")
                    assertNotNull(sign.closest(".lapis-encounter-pulpit"), "inside the pulpit area")
                    assertEquals(
                        "none",
                        kotlinx.browser.window
                            .getComputedStyle(sign)
                            .getPropertyValue("pointer-events"),
                    )
                    assertEquals(0, sound.rings, "$role: the sound is off, nothing rings")
                }
            }
        }

    @Test
    fun theAnnouncement_isOneFixedSentenceInARegionOfItsOwn_andASecondPacketExtendsInsteadOfDoubling(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            val scheduler = ManualBlessingScheduler()
            withRoom(EncounterPresenceRole.CONGREGATION, bellScheduler = scheduler) { rig, element ->
                rig.room.callbacks.onBell()
                awaitUntil("announced once") { element.liveTexts() == listOf("Die Glocke läutet") }
                scheduler.fire(ENCOUNTER_BELL_ANNOUNCE_CLEAR_MS)
                awaitUntil("the region is empty again") { element.liveTexts().isEmpty() }
                clock += 2_000.0
                rig.room.callbacks.onBell()
                delay(100)
                assertTrue(element.liveTexts().isEmpty(), "no second announcement while the sign is on: ${element.liveTexts()}")
                assertEquals(1, scheduler.pending(ENCOUNTER_BELL_DISPLAY_MS), "only the restarted hide timer is pending")
                assertEquals(1, element.allOf(".lapis-encounter-bell").size, "never a second sign")
                scheduler.fire(ENCOUNTER_BELL_DISPLAY_MS)
                scheduler.fire(ENCOUNTER_BELL_FADE_MS)
                awaitUntil("hidden again") { element.sign()?.hasAttribute("hidden") == true }
                clock += 20_000.0
                rig.room.callbacks.onBell()
                awaitUntil("a later bell is announced again") { element.liveTexts() == listOf("Die Glocke läutet") }
                val spoken = element.liveTexts().joinToString(" ")
                crowd.forEach { assertFalse(it.displayName in spoken, "no name in the announcement") }
            }
        }

    @Test
    fun reducedMotion_hidesTheSignAtOnce_withoutAFade(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            val scheduler = ManualBlessingScheduler()
            withRoom(EncounterPresenceRole.CONGREGATION, bellScheduler = scheduler, reducedMotion = { true }) { rig, element ->
                rig.room.callbacks.onBell()
                awaitUntil("shown") { element.sign()?.hasAttribute("hidden") == false }
                scheduler.fire(ENCOUNTER_BELL_DISPLAY_MS)
                assertTrue(element.sign()?.hasAttribute("hidden") == true, "hidden at once")
                assertEquals(0, scheduler.pending(ENCOUNTER_BELL_FADE_MS), "no fade timer")
            }
        }

    @Test
    fun theSound_rings_onlyWhenTheSwitchIsOnAndThePageIsVisible_andOnlyOncePerWindow(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            // switch on, page visible: one ring; the extending packet does not ring again; the sign has no "Ton aus" note
            soundStored(true)
            val on = FakeBellSound()
            val scheduler = ManualBlessingScheduler()
            withRoom(EncounterPresenceRole.CONGREGATION, bellScheduler = scheduler, sound = on) { rig, element ->
                rig.room.callbacks.onBell()
                awaitUntil("shown") { element.sign()?.hasAttribute("hidden") == false }
                assertEquals(1, on.rings)
                assertTrue(
                    element.sign()!!.querySelector(".lapis-encounter-bell-mute")!!.hasAttribute("hidden"),
                    "no note while the sound is on",
                )
                clock += 1_000.0
                rig.room.callbacks.onBell()
                delay(50)
                assertEquals(1, on.rings, "an extending packet does not ring")
            }
            // switch on, tab hidden: no sound, but the sign appears
            soundStored(true)
            val hidden = FakeBellSound()
            withRoom(EncounterPresenceRole.CONGREGATION, bellScheduler = ManualBlessingScheduler(), sound = hidden, pageVisible = {
                false
            }) { rig, element ->
                rig.room.callbacks.onBell()
                awaitUntil("the sign appears in a hidden tab too") { element.sign()?.hasAttribute("hidden") == false }
                assertEquals(0, hidden.rings, "never a sound in a background tab")
            }
        }

    @Test
    fun aSoundThatFails_neverKeepsTheSignAway(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            soundStored(true)
            val failing =
                object : EncounterBellSound {
                    override fun prime() = Unit

                    override fun ring() = throw IllegalStateException("no audio")

                    override fun probe() = Unit

                    override fun dispose() = Unit
                }
            val entry = testEntry(role = EncounterPresenceRole.CONGREGATION)
            try {
                withEncounterRoom(
                    entry = entry,
                    peopleOf = { peopleFor(entry) },
                    clock = { clock },
                    bellSound = failing,
                    bellScheduler = ManualBlessingScheduler(),
                ) { rig, element ->
                    rig.room.callbacks.onBell()
                    awaitUntil("the sign is there although the sound threw") { element.sign()?.hasAttribute("hidden") == false }
                    assertEquals(listOf("Die Glocke läutet"), element.liveTexts())
                }
            } finally {
                soundStored(false)
            }
        }

    @Test
    fun afterAReloadWithTheSoundOn_theContextIsPrimedByTheFirstGestureOnly(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            soundStored(true)
            val sound = FakeBellSound()
            withRoom(EncounterPresenceRole.CONGREGATION, sound = sound) { _, _ ->
                assertEquals(0, sound.primes, "no prime before a gesture")
                document.dispatchEvent(Event("keydown"))
                assertEquals(1, sound.primes, "the first gesture primes")
                document.dispatchEvent(Event("pointerdown"))
                document.dispatchEvent(Event("keydown"))
                assertEquals(1, sound.primes, "and only once")
            }
        }

    @Test
    fun withTheSoundOff_noGestureListenerIsArmed(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            soundStored(false)
            val sound = FakeBellSound()
            withRoom(EncounterPresenceRole.CONGREGATION, sound = sound) { _, _ ->
                document.dispatchEvent(Event("keydown"))
                assertEquals(0, sound.primes)
            }
        }

    @Test
    fun theBellAndTheBlessing_areBothInTheHolder_eachWithItsOwnSentence(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            withRoom(
                EncounterPresenceRole.CONGREGATION,
                bellScheduler = ManualBlessingScheduler(),
                blessingScheduler = ManualBlessingScheduler(),
            ) { rig, element ->
                rig.room.callbacks.onBell()
                rig.room.callbacks.onBlessing()
                awaitUntil("both shown") {
                    element.sign()?.hasAttribute("hidden") == false &&
                        element.querySelector(".lapis-encounter-blessing")?.hasAttribute("hidden") == false
                }
                val holder = assertNotNull(element.querySelector(".lapis-encounter-signs") as? HTMLElement)
                assertNotNull(element.sign()!!.closest(".lapis-encounter-signs"))
                assertNotNull(element.querySelector(".lapis-encounter-blessing")!!.closest(".lapis-encounter-signs"))
                assertEquals(2, holder.children.length, "exactly the two signs")
                assertTrue(
                    element.sign()!!.getBoundingClientRect().left <
                        element.querySelector(".lapis-encounter-blessing")!!.getBoundingClientRect().left,
                    "the bell on the left, the blessing on the right",
                )
                assertEquals(setOf("Die Glocke läutet", "Der Segen wird gesprochen"), element.liveTexts().toSet())
            }
        }

    @Test
    fun disposingTheRoom_cancelsTheBellTimers_andClosesTheSound(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            val scheduler = ManualBlessingScheduler()
            val sound = FakeBellSound()
            withRoom(EncounterPresenceRole.PULPIT, bellScheduler = scheduler, sound = sound) { rig, element ->
                rig.room.callbacks.onBell()
                element.barControl("Glocke läuten").click()
                assertTrue(scheduler.tasks.any { !it.cancelled })
                rig.room.dispose()
                assertTrue(scheduler.tasks.all { it.cancelled }, "every timer is cancelled by dispose")
                assertTrue(sound.disposed, "the audio context is closed")
            }
        }
}
