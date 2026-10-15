package network.lapis.cloud.client.encounter

import kotlinx.browser.window
import kotlinx.coroutines.delay
import network.lapis.cloud.client.RecordedRequest
import network.lapis.cloud.client.StubResponse
import network.lapis.cloud.client.allOf
import network.lapis.cloud.client.answerWith
import network.lapis.cloud.client.awaitUntil
import network.lapis.cloud.client.buttonNamed
import network.lapis.cloud.client.formTest
import network.lapis.cloud.client.jsonOf
import network.lapis.cloud.client.routeOf
import network.lapis.cloud.client.rpcService
import network.lapis.cloud.shared.domain.EncounterEntryDto
import network.lapis.cloud.shared.domain.EncounterFloorDto
import network.lapis.cloud.shared.domain.EncounterFloorSignal
import network.lapis.cloud.shared.domain.EncounterPresenceRole
import network.lapis.cloud.shared.domain.EncounterPresentDto
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterReaction
import network.lapis.cloud.shared.rpc.IEncounterSpaceService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.138 -- the temporary floor on the real DOM: the band everybody sees (no name), the viewer's own band with the microphone switch
 * and "Wort zurückgeben", the last-ten-seconds emphasis (no animation), the list of hands with "Wort erteilen" for office holders only,
 * and the end of the microphone with the floor.
 */
class EncounterFloorDomTest {
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
    private var mono = 50_000.0

    private var grantRoute = ""
    private var extendRoute = ""
    private var endRoute = ""
    private var stateRoute = ""

    private suspend fun resolveRoutes() {
        grantRoute = routeOf { rpcService<IEncounterSpaceService>().grantFloor("s", "m") }
        extendRoute = routeOf { rpcService<IEncounterSpaceService>().extendFloor("s", 60) }
        endRoute = routeOf { rpcService<IEncounterSpaceService>().endFloor("s") }
        stateRoute = routeOf { rpcService<IEncounterSpaceService>().floorState("s") }
    }

    /** What the stub server answers: `floorState` (mutable), grant and extend with [grantAnswer]. */
    private var stateAnswer = EncounterFloorDto(active = false, remainingSeconds = 0)
    private var grantAnswer: EncounterFloorDto? = null
    private var refuseGrant = false

    private fun respond(request: RecordedRequest): StubResponse? =
        when {
            !request.isRpc -> null
            request.rpcRoute == stateRoute -> request.answerWith(jsonOf(EncounterFloorDto.serializer(), stateAnswer))
            request.rpcRoute == grantRoute || request.rpcRoute == extendRoute ->
                if (refuseGrant) {
                    request.refusedWith("network.lapis.cloud.shared.rpc.ConflictException")
                } else {
                    request.answerWith(jsonOf(EncounterFloorDto.serializer(), grantAnswer ?: stateAnswer))
                }
            // a Unit result is the empty object on the wire
            request.rpcRoute == endRoute -> request.answerWith("{}")
            else -> null
        }

    private fun peopleFor(entry: EncounterEntryDto): List<EncounterPresentDto> =
        listOf(
            testPerson("me", entry.presenceRole, "Ich Selbst"),
            testPerson("c1", name = "Anna Beispiel"),
            testPerson("c2", name = "Ben Beispiel"),
            testPerson("c3", name = "Clara Beispiel", table = 0, tableSeat = 0),
        )

    private suspend fun withRoom(
        role: EncounterPresenceRole,
        privileged: Boolean = false,
        profile: EncounterProfile = EncounterProfile.CHURCH_SERVICE,
        session: FakeListenerSession = if (role == EncounterPresenceRole.CONGREGATION) FakeListenerSession() else FakeSpeakerSession(),
        block: suspend (EncounterRoomRig, HTMLElement) -> Unit,
    ) {
        resolveRoutes()
        val entry = testEntry(role = role)
        withEncounterRoom(
            entry = entry,
            peopleOf = { peopleFor(entry) },
            clock = { clock },
            privileged = privileged,
            session = session,
            space = testSpace(profile = profile),
            extraRespond = ::respond,
            monotonicNow = { mono },
            block = block,
        )
    }

    private fun HTMLElement.band(): HTMLElement = querySelector(".lapis-encounter-floor") as HTMLElement

    private fun HTMLElement.bandVisible(): Boolean = !band().hasAttribute("hidden")

    private fun HTMLElement.bandText(): String =
        band()
            .textContent
            .orEmpty()
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun HTMLElement.liveTexts(): List<String> =
        allOf("[role=status]").map { it.textContent.orEmpty().trim() }.filter { it.isNotEmpty() }

    private suspend fun HTMLElement.openPeople() {
        barControl("Chat").click()
        awaitUntil("the side panel is open") { querySelector("[role=tab]") != null }
        allOf("[role=tab]").first { it.textContent.orEmpty().trim() == "Anwesende" }.click()
        awaitUntil("the people tab is shown") { querySelector(".lapis-encounter-person") != null }
    }

    // ── the band everybody sees ──────────────────────────────────────────────────

    @Test
    fun theCongregation_seesNoBandWithoutAFloor_andAnAnonymousBandWithOne(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            withRoom(EncounterPresenceRole.CONGREGATION) { rig, element ->
                assertFalse(element.bandVisible())
                rig.room.callbacks.onFloor(EncounterFloorSignal.On(120))
                awaitUntil("the band is shown") { element.bandVisible() }
                assertEquals("Gerade spricht jemand aus der Gemeinde · noch 02:00", element.bandText())
                assertFalse(element.bandText().contains("Anna") || element.bandText().contains("Beispiel"), "no name")
                assertTrue(element.band().allOf("button").none { it.getBoundingClientRect().width > 0 }, "no button for a bystander")
                assertEquals("timer", element.querySelector(".lapis-encounter-floor-text")!!.getAttribute("role"))
                assertEquals("off", element.querySelector(".lapis-encounter-floor-text")!!.getAttribute("aria-live"))
                rig.room.callbacks.onFloor(EncounterFloorSignal.Off)
                awaitUntil("the band is gone") { !element.bandVisible() }
            }
        }

    @Test
    fun anAssemblyRoom_saysAssemblyInsteadOfCongregation(): Promise<Unit> =
        formTest {
            withRoom(EncounterPresenceRole.CONGREGATION, profile = EncounterProfile.ASSEMBLY) { rig, element ->
                rig.room.callbacks.onFloor(EncounterFloorSignal.On(90))
                awaitUntil("the band is shown") { element.bandVisible() }
                assertEquals("Gerade spricht jemand aus der Versammlung · noch 01:30", element.bandText())
            }
        }

    @Test
    fun theCountdownFollowsTheMonotonicClock_andTheLastTenSecondsAreEmphasisedWithoutAnimation(): Promise<Unit> =
        formTest {
            withRoom(EncounterPresenceRole.CONGREGATION) { rig, element ->
                rig.room.callbacks.onFloor(EncounterFloorSignal.On(30))
                awaitUntil("the band is shown") { element.bandVisible() }
                mono += 21_000.0
                rig.room.floorTick()
                assertEquals("Gerade spricht jemand aus der Gemeinde · noch 00:09", element.bandText().substringBefore("Gleich").trim())
                assertTrue(element.band().classList.contains("is-final"))
                assertTrue(element.bandText().contains("Gleich zu Ende"))
                val style = window.getComputedStyle(element.band())
                assertEquals("none", style.getPropertyValue("animation-name"), "no animation")
                assertEquals("0s", style.getPropertyValue("transition-duration"), "no transition either, with or without reduced motion")
                assertEquals("3px", style.getPropertyValue("border-top-width"), "a thicker border instead")
                assertEquals(
                    listOf("Noch 10 Sekunden"),
                    element.liveTexts().filter { it in setOf("Wort erteilt", "Noch 10 Sekunden", "Wort beendet") }.takeLast(1),
                    "the last sentence stands in the one polite region",
                )
            }
        }

    @Test
    fun theThreeSentences_areAnnouncedInTheRoomsPoliteRegion_andNeverTheSeconds(): Promise<Unit> =
        formTest {
            withRoom(EncounterPresenceRole.CONGREGATION) { rig, element ->
                val announced = mutableListOf<String>()
                rig.room.callbacks.onFloor(EncounterFloorSignal.On(12))
                announced += element.liveTexts().filter { it.startsWith("Wort") }
                mono += 3_000.0
                rig.room.floorTick()
                rig.room.callbacks.onFloor(EncounterFloorSignal.Off)
                assertFalse(element.bandVisible())
                val regions = element.allOf("[role=status][aria-live=polite]").map { it.textContent.orEmpty().trim() }
                assertTrue("Wort beendet" in regions, "the end is announced: $regions")
                assertEquals(listOf("Wort erteilt"), announced)
            }
        }

    // ── the viewer who has the floor ─────────────────────────────────────────────

    @Test
    fun aYouPacket_showsTheViewersBand_withTheMicrophoneSwitch_andLowersTheirHand(): Promise<Unit> =
        formTest {
            val session = FakeListenerSession()
            withRoom(EncounterPresenceRole.CONGREGATION, session = session) { rig, element ->
                element.barControl("Hand heben").click()
                awaitUntil("the hand went up") { session.reactions.contains(EncounterReaction.HAND) }
                rig.room.callbacks.onFloor(EncounterFloorSignal.You(120))
                awaitUntil("the viewer's band is shown") { element.bandVisible() }
                assertEquals("Sie haben das Wort · noch 02:00", element.bandText().substringBefore(" Mikrofon einschalten"))
                assertTrue(
                    element
                        .band()
                        .textContent
                        .orEmpty()
                        .contains("Mikrofon einschalten"),
                )
                assertTrue(
                    element
                        .band()
                        .textContent
                        .orEmpty()
                        .contains("Wort zurückgeben"),
                )
                awaitUntil("the hand went down") { session.reactions.contains(EncounterReaction.HAND_LOWERED) }
                assertEquals(0, session.floorMicrophoneCalls.size, "the microphone is never switched on by itself")
            }
        }

    @Test
    fun theMicrophoneButton_switchesTheFloorMicrophoneOn_andTheBandSaysSo(): Promise<Unit> =
        formTest {
            val session = FakeListenerSession()
            withRoom(EncounterPresenceRole.CONGREGATION, session = session) { rig, element ->
                rig.room.callbacks.onFloor(EncounterFloorSignal.You(120))
                awaitUntil("the band is shown") { element.bandVisible() }
                element.band().buttonNamed("Mikrofon einschalten").click()
                awaitUntil("the microphone call was made") { session.floorMicrophoneCalls == listOf(true) }
                awaitUntil("the band shows the state") { element.bandText().contains("Mikrofon an") }
                assertTrue(rig.room.floorView.microphoneOn)
            }
        }

    @Test
    fun whenTheTimeIsUp_theMicrophoneEnds_andTheBandGoes(): Promise<Unit> =
        formTest {
            val session = FakeListenerSession()
            withRoom(EncounterPresenceRole.CONGREGATION, session = session) { rig, element ->
                rig.room.callbacks.onFloor(EncounterFloorSignal.You(5))
                awaitUntil("the band is shown") { element.bandVisible() }
                mono += 6_000.0
                rig.room.floorTick()
                awaitUntil("the microphone was ended") { session.floorMicrophoneEnds >= 1 }
                assertFalse(element.bandVisible())
            }
        }

    @Test
    fun handingTheFloorBack_callsTheServer_andEndsTheMicrophone(): Promise<Unit> =
        formTest {
            val session = FakeListenerSession()
            withRoom(EncounterPresenceRole.CONGREGATION, session = session) { rig, element ->
                rig.room.callbacks.onFloor(EncounterFloorSignal.You(100))
                awaitUntil("the band is shown") { element.bandVisible() }
                element.band().buttonNamed("Wort zurückgeben").click()
                awaitUntil("the server was told") { rig.requests.count { it.isRpc && it.rpcRoute == endRoute } == 1 }
                awaitUntil("the band is gone") { !element.bandVisible() }
                awaitUntil("the microphone was ended") { session.floorMicrophoneEnds >= 1 }
            }
        }

    @Test
    fun losingThePublishPermission_endsTheViewersFloor(): Promise<Unit> =
        formTest {
            val session = FakeListenerSession()
            withRoom(EncounterPresenceRole.CONGREGATION, session = session) { rig, element ->
                rig.room.callbacks.onFloor(EncounterFloorSignal.You(100))
                awaitUntil("the band is shown") { element.bandVisible() }
                rig.room.callbacks.onMicrophonePermission(false)
                awaitUntil("the band is gone") { !element.bandVisible() }
                awaitUntil("the microphone was ended") { session.floorMicrophoneEnds >= 1 }
            }
        }

    // ── the list of hands (office holders) ───────────────────────────────────────

    private suspend fun raiseHands(
        rig: EncounterRoomRig,
        element: HTMLElement,
        vararg ids: String,
    ) {
        ids.forEach { id ->
            rig.room.callbacks.onReaction(id, EncounterReaction.HAND)
            clock += 3_000
        }
        awaitUntil("the hands are listed") { element.allOf(".lapis-encounter-hand-row").size == ids.size }
    }

    @Test
    fun aSteward_seesTheNumberedHands_withAGrantButtonPerHand_disabledForSomebodyAtATable(): Promise<Unit> =
        formTest {
            withRoom(EncounterPresenceRole.STEWARD) { rig, element ->
                element.openPeople()
                raiseHands(rig, element, "c2", "c1", "c3")
                assertEquals(
                    "Wortmeldungen",
                    element
                        .querySelector(".lapis-encounter-present h2")!!
                        .textContent
                        .orEmpty()
                        .trim(),
                )
                val list = element.querySelector("ol.lapis-encounter-hands-list")
                assertNotNull(list, "the hands are an ordered list")
                assertEquals(
                    listOf("Ben Beispiel", "Anna Beispiel", "Clara Beispiel"),
                    element.allOf(".lapis-encounter-hand-row").map {
                        it.textContent.orEmpty().trim()
                    },
                )
                val grants = element.allOf("[data-floor-grant]")
                assertEquals(3, grants.size)
                assertEquals(listOf("c2", "c1", "c3"), grants.map { it.getAttribute("data-floor-grant") })
                assertFalse(grants[0].hasAttribute("disabled"))
                assertFalse(grants[1].hasAttribute("disabled"))
                assertTrue(grants[2].hasAttribute("disabled"), "Clara sits at a table")
            }
        }

    @Test
    fun grantingTheFloor_callsTheServer_showsTheRunLineWithTheName_andDisablesEveryOtherGrant(): Promise<Unit> =
        formTest {
            withRoom(EncounterPresenceRole.STEWARD) { rig, element ->
                grantAnswer =
                    EncounterFloorDto(
                        active = true,
                        remainingSeconds = 120,
                        totalSeconds = 120,
                        holderMemberId = "c2",
                        holderDisplayName = "Ben Beispiel",
                    )
                element.openPeople()
                raiseHands(rig, element, "c2", "c1")
                element.querySelector("[data-floor-grant=c2]")!!.let { (it as HTMLElement).click() }
                awaitUntil("the grant reached the server") { rig.requests.count { it.isRpc && it.rpcRoute == grantRoute } == 1 }
                val call = rig.requests.first { it.isRpc && it.rpcRoute == grantRoute }
                assertEquals("space-1", call.rpcParam(0).toString())
                assertEquals("c2", call.rpcParam(1).toString())
                awaitUntil("the run line is shown") { element.querySelector(".lapis-encounter-floor-run-line") != null }
                val line = element.querySelector(".lapis-encounter-floor-run-line")!!.textContent.orEmpty()
                assertEquals("Ben Beispiel spricht · noch 02:00 · Mikrofon aus", line)
                assertTrue(element.textContent.orEmpty().contains("Es spricht gerade jemand."))
                assertTrue(element.allOf("[data-floor-grant]").all { it.hasAttribute("disabled") })
                assertNotNull(element.querySelector("[data-floor-extend=\"60\"]"))
                assertNotNull(element.querySelector("[data-floor-extend=\"120\"]"))
                assertNotNull(element.querySelector("[data-floor-take-back]"))
                // the band of the stewards names nobody: the name lives in the list only
                assertFalse(
                    element
                        .band()
                        .textContent
                        .orEmpty()
                        .contains("Ben"),
                )
                // the next hand is the one that is highlighted
                assertNotNull(element.querySelector(".lapis-encounter-hand-next [data-floor-grant=c1]"))
            }
        }

    @Test
    fun aRefusedGrant_leavesNoFloor_andLooksAtTheServersPictureAgain(): Promise<Unit> =
        formTest {
            withRoom(EncounterPresenceRole.STEWARD) { rig, element ->
                refuseGrant = true
                element.openPeople()
                raiseHands(rig, element, "c1")
                val statesBefore = rig.requests.count { it.isRpc && it.rpcRoute == stateRoute }
                (element.querySelector("[data-floor-grant=c1]") as HTMLElement).click()
                awaitUntil("the grant reached the server") { rig.requests.count { it.isRpc && it.rpcRoute == grantRoute } == 1 }
                awaitUntil("the picture was refreshed") { rig.requests.count { it.isRpc && it.rpcRoute == stateRoute } > statesBefore }
                assertFalse(rig.room.floorView.active)
                assertNull(element.querySelector(".lapis-encounter-floor-run-line"))
                awaitUntil(
                    "the grant button is usable again",
                ) { !(element.querySelector("[data-floor-grant=c1]") as HTMLElement).hasAttribute("disabled") }
            }
        }

    @Test
    fun theExtensionButtons_callTheServer_andAreDisabledAtTheMaximum(): Promise<Unit> =
        formTest {
            withRoom(EncounterPresenceRole.PULPIT) { rig, element ->
                stateAnswer =
                    EncounterFloorDto(
                        active = true,
                        remainingSeconds = 300,
                        totalSeconds = 600,
                        maxReached = true,
                        holderMemberId = "c1",
                        holderDisplayName = "Anna Beispiel",
                    )
                grantAnswer = stateAnswer
                element.openPeople()
                rig.room.callbacks.onFloor(EncounterFloorSignal.On(300))
                awaitUntil("the run line is shown") { element.querySelector(".lapis-encounter-floor-run-line") != null }
                assertTrue((element.querySelector("[data-floor-extend=\"60\"]") as HTMLElement).hasAttribute("disabled"))
                assertTrue((element.querySelector("[data-floor-extend=\"120\"]") as HTMLElement).hasAttribute("disabled"))
                assertTrue(element.textContent.orEmpty().contains("Höchstdauer 10 Min erreicht"))
                assertFalse((element.querySelector("[data-floor-take-back]") as HTMLElement).hasAttribute("disabled"))
            }
        }

    @Test
    fun theExtensionButton_sendsTheSteps_andTakeBackCallsEndFloor(): Promise<Unit> =
        formTest {
            withRoom(EncounterPresenceRole.STEWARD) { rig, element ->
                stateAnswer =
                    EncounterFloorDto(
                        active = true,
                        remainingSeconds = 100,
                        totalSeconds = 120,
                        holderMemberId = "c1",
                        holderDisplayName = "Anna Beispiel",
                    )
                grantAnswer = stateAnswer
                element.openPeople()
                rig.room.callbacks.onFloor(EncounterFloorSignal.On(100))
                awaitUntil("the run line is shown") { element.querySelector("[data-floor-extend=\"120\"]") != null }
                (element.querySelector("[data-floor-extend=\"120\"]") as HTMLElement).click()
                awaitUntil("the extension reached the server") { rig.requests.count { it.isRpc && it.rpcRoute == extendRoute } == 1 }
                assertEquals(
                    "120",
                    rig.requests
                        .first { it.isRpc && it.rpcRoute == extendRoute }
                        .rpcParam(1)
                        .toString(),
                )
                awaitUntil(
                    "the buttons are back",
                ) { !(element.querySelector("[data-floor-take-back]") as HTMLElement).hasAttribute("disabled") }
                (element.querySelector("[data-floor-take-back]") as HTMLElement).click()
                awaitUntil("the server was told to end") { rig.requests.count { it.isRpc && it.rpcRoute == endRoute } == 1 }
                delay(1000)
                assertFalse(
                    rig.room.floorView.active,
                    "view=${rig.room.floorView} routes=${rig.requests.filter { it.isRpc }.map { it.rpcRoute }}",
                )
            }
        }

    @Test
    fun boardWithoutAnOffice_seesTheHands_butNoButtons(): Promise<Unit> =
        formTest {
            withRoom(EncounterPresenceRole.CONGREGATION, privileged = true) { rig, element ->
                element.openPeople()
                raiseHands(rig, element, "c1")
                assertEquals(0, element.allOf("[data-floor-grant]").size, "no grant button without an office")
                stateAnswer =
                    EncounterFloorDto(
                        active = true,
                        remainingSeconds = 90,
                        totalSeconds = 120,
                        holderMemberId = "c2",
                        holderDisplayName = "Ben Beispiel",
                    )
                rig.room.callbacks.onFloor(EncounterFloorSignal.On(90))
                awaitUntil("the run line is shown") { element.querySelector(".lapis-encounter-floor-run-line") != null }
                assertEquals(0, element.allOf("[data-floor-extend]").size)
                assertNull(element.querySelector("[data-floor-take-back]"))
            }
        }

    @Test
    fun theCongregation_getsNoListAndNoButton(): Promise<Unit> =
        formTest {
            withRoom(EncounterPresenceRole.CONGREGATION) { rig, element ->
                rig.room.callbacks.onReaction("c1", EncounterReaction.HAND)
                delay(100)
                assertEquals(0, element.allOf("[data-floor-grant]").size)
                assertFalse(element.textContent.orEmpty().contains("Wortmeldungen"))
            }
        }
}
