package network.lapis.cloud.client.encounter

import kotlinx.browser.document
import kotlinx.coroutines.delay
import kotlinx.serialization.builtins.ListSerializer
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
import network.lapis.cloud.shared.domain.EncounterPresenceRole
import network.lapis.cloud.shared.domain.EncounterPresentDto
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
 * V1.9.79 (stage 2a) -- choosing a seat in the running room: a real mounted room, a stubbed `window.fetch` that plays the server's
 * `selectSeat`/`listPresent`, a fake session. What the person sees and hears: the seat after the server said yes, a fixed sentence when
 * it said no, no optimistic picture, a content-free nudge for the others, and the anonymous announcements.
 */
class EncounterSeatChoiceDomTest {
    private companion object {
        /** The real stylesheets: sizes, the aisle and the 44-pixel minimum are CSS, and the test measures them. */
        val stylesLoaded: Boolean =
            run {
                js("require('bootstrap/dist/css/bootstrap.min.css')")
                js("require('zzz-kvision-assets/css/kv-style.css')")
                js("require('./theme.css')")
                true
            }
    }

    private var clock = 1_000_000.0
    private val conflict = "network.lapis.cloud.shared.rpc.ConflictException"
    private val busy = "network.lapis.cloud.shared.rpc.ServiceBusyException"

    private var people: List<EncounterPresentDto> = emptyList()
    private val selectRequests = mutableListOf<Int?>()

    /** The stub server: records the request and, unless [refuse] names an exception type, seats the viewer where the request says. */
    private fun server(refuse: () -> String? = { null }): (RecordedRequest) -> StubResponse =
        { request ->
            selectRequests += request.requestedSeat()
            val failure = refuse()
            if (failure != null) {
                request.refusedWith(failure)
            } else {
                people = people.map { if (it.memberId == "me") it.copy(seat = request.requestedSeat()) else it }
                request.answerWith(jsonOf(ListSerializer(EncounterPresentDto.serializer()), people))
            }
        }

    private suspend fun withRoom(
        role: EncounterPresenceRole = EncounterPresenceRole.CONGREGATION,
        refuse: () -> String? = { null },
        session: FakeListenerSession = FakeListenerSession(),
        block: suspend (EncounterRoomRig, HTMLElement) -> Unit,
    ) = withEncounterRoom(
        entry = testEntry(role = role),
        peopleOf = { people },
        clock = { clock },
        session = session,
        selectSeatAnswer = server(refuse),
        block = block,
    )

    private fun crowd() =
        listOf(testPerson("me", name = "Ich Selbst")) + (1..3).map { testPerson("c$it", name = "Gast $it", seat = it - 1) }

    private fun HTMLElement.live(): String = allOf("[role=status]").joinToString(" | ") { it.textContent.orEmpty() }

    private fun HTMLElement.cells(): List<HTMLElement> = allOf(".lapis-encounter-seat")

    private fun presentRequests(
        requests: List<RecordedRequest>,
        route: String,
    ) = requests.count { it.isRpc && it.rpcRoute == route }

    @Test
    fun choosingAFreeSeat_waitsForTheServer_thenSitsAnnouncesNudgesAndKeepsTheFocus(): Promise<Unit> =
        formTest {
            people = crowd()
            val session = FakeListenerSession()
            withRoom(session = session) { _, element ->
                awaitUntil("three seats taken") { element.occupied() == 3 }
                val hint = assertNotNull(element.querySelector(".lapis-encounter-seat-hint") as? HTMLElement)
                assertTrue(hint.isShown(), "somebody without a seat is told what to do")
                assertEquals("Tippen Sie auf einen freien Platz, um sich zu setzen.", hint.textContent.orEmpty().trim())
                assertEquals(1, element.allOf(".lapis-encounter-unseated-item").size, "the viewer is in the row without a seat")
                element.cells()[4].click()
                awaitUntil("the server was asked and the seat is the viewer's own") {
                    element.cells()[4].classList.contains("lapis-encounter-seat--own")
                }
                assertEquals(listOf<Int?>(4), selectRequests)
                assertEquals("Reihe 1, Platz 5, Ihr Platz", element.cells()[4].getAttribute("aria-label"))
                assertTrue(element.live().contains("Sie sitzen jetzt in Reihe 1, Platz 5."), element.live())
                awaitUntil("the others are nudged") { session.seatNudges == 1 }
                awaitUntil("the focus is on the own seat") { (document.activeElement as? HTMLElement)?.getAttribute("data-seat") == "4" }
                assertFalse(hint.isShown(), "the hint is gone once the viewer sits")
                assertEquals(0, element.allOf(".lapis-encounter-unseated-item").size)
                assertTrue(element.buttonNamed("Platz freigeben").isShown(), "the release button appears")
            }
        }

    @Test
    fun theSeatIsPendingUntilTheServerAnswers_noOptimisticPicture(): Promise<Unit> =
        formTest {
            people = crowd()
            var answer = false
            withEncounterRoom(
                entry = testEntry(),
                peopleOf = { people },
                clock = { clock },
                selectSeatAnswer = { request ->
                    selectRequests += request.requestedSeat()
                    people = people.map { p -> if (p.memberId == "me") p.copy(seat = request.requestedSeat()) else p }
                    answer = true
                    StubResponse(
                        text = request.answerWith(jsonOf(ListSerializer(EncounterPresentDto.serializer()), people)).text,
                        delayMs = 400,
                    )
                },
            ) { _, element ->
                awaitUntil("three seats taken") { element.occupied() == 3 }
                element.cells()[5].click()
                awaitUntil("the seat shows as pending") { element.cells()[5].getAttribute("aria-busy") == "true" }
                assertFalse(element.cells()[5].classList.contains("lapis-encounter-seat--own"), "not seated before the server says so")
                assertEquals(0, element.cells().count { it.classList.contains("lapis-encounter-seat--own") })
                awaitUntil("then it is the viewer's own seat") { element.cells()[5].classList.contains("lapis-encounter-seat--own") }
                assertNull(element.cells()[5].getAttribute("aria-busy"))
                assertTrue(answer)
            }
        }

    @Test
    fun aLostRace_isAFixedSentence_theSeatDoesNotJump_andTheListIsReloaded(): Promise<Unit> =
        formTest {
            people = crowd()
            val presentRoute = routeOf { rpcService<IEncounterSpaceService>().listPresent("space-1") }
            withRoom(refuse = { conflict }) { rig, element ->
                awaitUntil("three seats taken") { element.occupied() == 3 }
                val before = presentRequests(rig.requests, presentRoute)
                element.cells()[6].click()
                awaitUntil("the fixed sentence") {
                    element.live().contains("Dieser Platz ist inzwischen besetzt. Bitte wählen Sie einen anderen.")
                }
                assertEquals(0, element.cells().count { it.classList.contains("lapis-encounter-seat--own") }, "no seat for the loser")
                assertNull(element.cells()[6].getAttribute("aria-busy"))
                awaitUntil("the list was reloaded") { presentRequests(rig.requests, presentRoute) > before }
                assertEquals(0, rig.session.seatNudges, "a lost race changes nothing for the others")
            }
        }

    @Test
    fun aThrottledOrFullServer_isADifferentSentence_thanASeatTaken(): Promise<Unit> =
        formTest {
            people = crowd()
            withRoom(refuse = { busy }) { _, element ->
                awaitUntil("three seats taken") { element.occupied() == 3 }
                element.cells()[6].click()
                awaitUntil("the busy sentence") { element.live().contains("Bitte warten Sie einen Moment und versuchen Sie es erneut.") }
                assertFalse(element.live().contains("inzwischen besetzt"), "busy is not 'taken'")
                assertEquals(0, element.cells().count { it.classList.contains("lapis-encounter-seat--own") })
            }
        }

    @Test
    fun aSecondChangeWithinASecond_doesNothingAtAll(): Promise<Unit> =
        formTest {
            people = crowd()
            withRoom { _, element ->
                awaitUntil("three seats taken") { element.occupied() == 3 }
                element.cells()[4].click()
                awaitUntil("seated") { element.cells()[4].classList.contains("lapis-encounter-seat--own") }
                element.cells()[7].click()
                delay(150)
                assertEquals(listOf<Int?>(4), selectRequests, "the press inside the gap sent nothing")
                clock += 1_200
                element.cells()[7].click()
                awaitUntil("the seat changed after the gap") { element.cells()[7].classList.contains("lapis-encounter-seat--own") }
                assertEquals(listOf<Int?>(4, 7), selectRequests)
                assertFalse(element.cells()[4].classList.contains("lapis-encounter-seat--own"))
            }
        }

    @Test
    fun releasingTheSeat_asksForNull_announcesIt_andPutsThePersonBackIntoTheRowWithoutASeat(): Promise<Unit> =
        formTest {
            people = crowd().map { if (it.memberId == "me") it.copy(seat = 4) else it }
            withRoom { _, element ->
                awaitUntil("the viewer sits") { element.cells()[4].classList.contains("lapis-encounter-seat--own") }
                element.buttonNamed("Platz freigeben").click()
                awaitUntil("released") { element.live().contains("Sie haben Ihren Platz freigegeben.") }
                assertEquals(listOf<Int?>(null), selectRequests)
                assertEquals(0, element.cells().count { it.classList.contains("lapis-encounter-seat--own") })
                awaitUntil("back in the row without a seat") { element.allOf(".lapis-encounter-unseated-item").size == 1 }
                awaitUntil("the focus stays at the seat that was released") {
                    (document.activeElement as? HTMLElement)?.getAttribute("data-seat") ==
                        "4"
                }
            }
        }

    @Test
    fun anOfficeHolder_cannotChoose_nothingIsSent_andNoHintIsShown(): Promise<Unit> =
        formTest {
            people =
                listOf(testPerson("me", EncounterPresenceRole.STEWARD, "Ich Selbst")) + (1..2).map { testPerson("c$it", seat = it - 1) }
            withRoom(role = EncounterPresenceRole.STEWARD) { _, element ->
                awaitUntil("two seats taken") { element.occupied() == 2 }
                element.cells()[5].click()
                delay(150)
                assertTrue(selectRequests.isEmpty())
                assertEquals("true", element.cells()[5].getAttribute("aria-disabled"))
                assertEquals("Reihe 1, Platz 6, frei", element.cells()[5].getAttribute("aria-label"))
                // a hidden widget is not in the document at all
                assertNull(element.querySelector(".lapis-encounter-seat-hint"), "no hint for somebody who cannot sit")
                assertNull(element.querySelector(".lapis-encounter-seat-release"), "no release button either")
            }
        }

    @Test
    fun aSeatNudge_fromAKnownPerson_reloadsTheList_oneFromAStrangerIsDropped(): Promise<Unit> =
        formTest {
            people = crowd()
            val presentRoute = routeOf { rpcService<IEncounterSpaceService>().listPresent("space-1") }
            withRoom { rig, element ->
                awaitUntil("three seats taken") { element.occupied() == 3 }
                val before = presentRequests(rig.requests, presentRoute)
                rig.room.callbacks.onSeatNudge("stranger")
                delay(200)
                assertEquals(before, presentRequests(rig.requests, presentRoute), "an identity that is not in the list is dropped unread")
                people = people.map { if (it.memberId == "c2") it.copy(seat = 9) else it }
                rig.room.callbacks.onSeatNudge("c2")
                awaitUntil("the list was reloaded") { presentRequests(rig.requests, presentRoute) > before }
                awaitUntil("the new seat is shown") { element.cells()[9].classList.contains("lapis-encounter-seat--taken") }
                assertTrue(element.cells()[1].classList.contains("lapis-encounter-seat--free"))
            }
        }

    @Test
    fun someoneElsesSeatChange_isNeverAnnounced(): Promise<Unit> =
        formTest {
            people = crowd()
            withRoom { rig, element ->
                awaitUntil("three seats taken") { element.occupied() == 3 }
                people = people.map { if (it.memberId == "c2") it.copy(seat = 9) else it }
                rig.room.callbacks.onSeatNudge("c2")
                awaitUntil("the new seat is shown") { element.cells()[9].classList.contains("lapis-encounter-seat--taken") }
                val live = element.live()
                assertFalse(live.contains("Platz") || live.contains("Reihe") || live.contains("Gast"), "silent: $live")
            }
        }

    @Test
    fun aNewcomer_isAnnouncedAnonymouslyAndThrottled_noNameNoNumber(): Promise<Unit> =
        formTest {
            people = crowd()
            withRoom { rig, element ->
                awaitUntil("three seats taken") { element.occupied() == 3 }
                assertFalse(element.live().contains("hinzugekommen"), "the first load announces nobody")
                people = people + testPerson("n1", name = "Neu Eins")
                rig.room.callbacks.onParticipantJoined("n1", "Neu Eins")
                awaitUntil("the anonymous sentence") { element.live().contains("Eine Person ist hinzugekommen.") }
                assertFalse(element.live().contains("Neu"), element.live())
                assertFalse(Regex("\\d").containsMatchIn(element.live()), "no number: ${element.live()}")
            }
        }

    @Test
    fun whenTheCapacityShrinks_seatsBeyondItAreShownButNeverOffered_neitherInThePlanNorInTheList(): Promise<Unit> =
        formTest {
            // 25 people: capacity 36; somebody sits at seat 35
            people = listOf(testPerson("me", name = "Ich Selbst")) + (1..23).map { testPerson("c$it", name = "Gast $it", seat = it - 1) } +
                testPerson("far", name = "Weit Weg", seat = 35)
            withRoom { rig, element ->
                awaitUntil("24 seats taken") { element.occupied() == 24 }
                assertEquals(36, element.cells().size)
                // 10 people leave: capacity 24, but the grid keeps seat 35
                people =
                    listOf(testPerson("me", name = "Ich Selbst")) + (1..13).map { testPerson("c$it", name = "Gast $it", seat = it - 1) } +
                    testPerson("far", name = "Weit Weg", seat = 35)
                rig.room.callbacks.onSeatNudge("c1")
                awaitUntil("the roster shrank") { element.occupied() == 14 }
                assertEquals(36, element.cells().size, "the occupied seat 35 is still drawn")
                assertEquals("true", element.cells()[30].getAttribute("aria-disabled"), "beyond the capacity: not choosable")
                assertNull(element.cells()[20].getAttribute("aria-disabled"), "within the capacity: choosable")
                element.cells()[30].click()
                delay(150)
                assertTrue(selectRequests.isEmpty(), "a seat the server would refuse is never requested")
                element.openPeople()
                val details = assertNotNull(element.querySelector(".lapis-encounter-seat-list") as? HTMLElement)
                val offered = details.allOf("[data-list-seat]").mapNotNull { it.getAttribute("data-list-seat")?.toIntOrNull() }
                assertTrue(offered.isNotEmpty() && offered.all { it < 24 }, "list offers only seats below the capacity: $offered")
            }
        }

    @Test
    fun aRosterThatStillListsAPersonWhoJustLeft_isNotAnnouncedAsANewcomer(): Promise<Unit> =
        formTest {
            people = crowd()
            val presentRoute = routeOf { rpcService<IEncounterSpaceService>().listPresent("space-1") }
            withRoom { rig, element ->
                awaitUntil("three seats taken") { element.occupied() == 3 }
                val before = presentRequests(rig.requests, presentRoute)
                // the presence row is not deleted yet: the server still lists c3
                rig.room.callbacks.onParticipantLeft("c3")
                awaitUntil("the roster was reloaded") { presentRequests(rig.requests, presentRoute) > before }
                delay(250)
                assertFalse(element.live().contains("hinzugekommen"), "silent: ${element.live()}")
            }
        }

    @Test
    fun aListPresentThatStartedBeforeTheSeatAnswer_cannotPutTheOldPictureBack(): Promise<Unit> =
        formTest {
            people = crowd()
            val presentRoute = routeOf { rpcService<IEncounterSpaceService>().listPresent("space-1") }
            var slow = false
            withEncounterRoom(
                entry = testEntry(),
                peopleOf = { people },
                clock = { clock },
                selectSeatAnswer = server(),
                presentDelayMs = { if (slow) 800 else 0 },
            ) { rig, element ->
                awaitUntil("three seats taken") { element.occupied() == 3 }
                val before = presentRequests(rig.requests, presentRoute)
                slow = true
                rig.room.callbacks.onSeatNudge("c1") // a refresh starts; its snapshot has no seat for the viewer
                awaitUntil("the slow listPresent is under way") { presentRequests(rig.requests, presentRoute) > before }
                slow = false
                element.cells()[5].click()
                awaitUntil("seated") { element.cells()[5].classList.contains("lapis-encounter-seat--own") }
                delay(1_200) // the old answer arrives now
                assertTrue(element.cells()[5].classList.contains("lapis-encounter-seat--own"), "still seated")
                assertEquals(0, element.allOf(".lapis-encounter-unseated-item").size, "not back in the row without a seat")
            }
        }

    @Test
    fun theOfficeHoldersTile_showsSpricht_asAClassAndAWord_whileLiveKitSaysSo(): Promise<Unit> =
        formTest {
            people = crowd() + testPerson("p1", EncounterPresenceRole.PULPIT, "Pfarrer Paul")
            withRoom { rig, element ->
                awaitUntil("the pulpit tile exists") { element.querySelector(".lapis-encounter-tile") != null }
                val tile = assertNotNull(element.querySelector(".lapis-encounter-tile") as? HTMLElement)
                assertFalse(tile.classList.contains("lapis-encounter-tile--speaking"))
                rig.room.callbacks.onActiveSpeakers(listOf("p1", "c1"))
                awaitUntil("speaking") { tile.classList.contains("lapis-encounter-tile--speaking") }
                assertTrue(tile.textContent.orEmpty().contains("spricht"))
                assertEquals("Pfarrer Paul, spricht", tile.getAttribute("aria-label"))
                assertEquals(1, element.allOf(".lapis-encounter-tile--speaking").size, "a congregation identity has no tile")
                rig.room.callbacks.onActiveSpeakers(emptyList())
                awaitUntil("not speaking") { !tile.classList.contains("lapis-encounter-tile--speaking") }
                assertNull(tile.getAttribute("aria-label"))
            }
        }

    // ── the list alternative (BR-E2) ──────────────────────────────────────────────

    private suspend fun HTMLElement.openPeople() {
        barControl("Chat").click()
        awaitUntil("the side panel is open") { querySelector("[role=tab]") != null }
        allOf("[role=tab]").first { it.textContent.orEmpty().trim() == "Anwesende" }.click()
        awaitUntil("the list alternative is shown") { querySelector(".lapis-encounter-seat-list") != null }
    }

    @Test
    fun theListAlternative_offersOnlyFreeSeats_withoutNames_inButtonsOf44Pixels_andChoosesLikeThePlan(): Promise<Unit> =
        formTest {
            assertTrue(stylesLoaded)
            people = crowd()
            withRoom { _, element ->
                awaitUntil("three seats taken") { element.occupied() == 3 }
                element.openPeople()
                val details = assertNotNull(element.querySelector(".lapis-encounter-seat-list") as? HTMLElement)
                assertEquals("Platz über eine Liste wählen", details.querySelector("summary")?.textContent?.trim())
                details.setAttribute("open", "")
                assertTrue(details.textContent.orEmpty().contains("Sie haben noch keinen Platz."))
                val buttons = details.allOf("[data-list-seat]")
                assertEquals(24 - 3, buttons.size, "every free seat, and no taken one")
                assertFalse(details.textContent.orEmpty().contains("Gast"), "no names in the list alternative")
                buttons.forEach { b ->
                    val box = b.getBoundingClientRect()
                    assertTrue(
                        box.width >= 44.0 && box.height >= 44.0,
                        "list button ${b.getAttribute("data-list-seat")}: ${box.width} x ${box.height}",
                    )
                }
                assertEquals("Reihe 1, Platz 4 wählen", buttons.first().getAttribute("aria-label"))
                assertTrue(details.allOf("[role=group]").first().getAttribute("aria-label") == "Reihe 1")
                buttons.first().click()
                awaitUntil("the seat is chosen") { element.cells()[3].classList.contains("lapis-encounter-seat--own") }
                assertEquals(listOf<Int?>(3), selectRequests)
                awaitUntil("the list says where the viewer sits") {
                    element
                        .querySelector(".lapis-encounter-seat-list")
                        ?.textContent
                        .orEmpty()
                        .contains("Ihr Platz: Reihe 1, Platz 4")
                }
                assertNotNull(
                    element.querySelector(".lapis-encounter-seat-list [data-list-seat=release]"),
                    "release is offered in the list as well",
                )
            }
        }

    @Test
    fun theListAlternative_isNotOfferedToAnOfficeHolder(): Promise<Unit> =
        formTest {
            people = listOf(testPerson("me", EncounterPresenceRole.STEWARD, "Ich Selbst"), testPerson("c1", seat = 0))
            withRoom(role = EncounterPresenceRole.STEWARD) { _, element ->
                awaitUntil("a seat is taken") { element.occupied() == 1 }
                element.barControl("Chat").click()
                awaitUntil("the side panel is open") { element.querySelector("[role=tab]") != null }
                element.allOf("[role=tab]").first { it.textContent.orEmpty().trim() == "Anwesende" }.click()
                awaitUntil("the people tab is shown") { element.querySelector(".lapis-encounter-person") != null }
                assertNull(element.querySelector(".lapis-encounter-seat-list"))
            }
        }
}
