package network.lapis.cloud.client.encounter

import kotlinx.coroutines.delay
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.client.RecordedRequest
import network.lapis.cloud.client.StubResponse
import network.lapis.cloud.client.allOf
import network.lapis.cloud.client.answerWith
import network.lapis.cloud.client.awaitUntil
import network.lapis.cloud.client.formTest
import network.lapis.cloud.client.jsonOf
import network.lapis.cloud.client.livekit.ConferenceConnectFailure
import network.lapis.cloud.client.livekit.DisconnectCause
import network.lapis.cloud.client.routeOf
import network.lapis.cloud.client.rpcService
import network.lapis.cloud.shared.domain.EncounterPresenceRole
import network.lapis.cloud.shared.domain.EncounterPresentDto
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import network.lapis.cloud.shared.domain.EncounterTableDto
import network.lapis.cloud.shared.domain.EncounterTableTokenAnswer
import network.lapis.cloud.shared.domain.EncounterTableTokenDto
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
 * V1.9.80 (stage 2b) -- the tables in the running room: a real mounted room, a stubbed `window.fetch` that plays the server's
 * `joinTable` / `leaveTable` / `tableToken` / `listTables`, a fake table audio session. What the person sees and hears: the seat after the
 * server said yes (no optimistic picture), a fixed sentence when it said no, the microphone off after sitting down, the room rotation
 * (reconnect with a new token, microphone restored), the quiet of a table, speakers only at the own table, the list alternative and the
 * moderators' controls.
 */
class EncounterTableRoomDomTest {
    private companion object {
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
    private var quietedTables: Set<Int> = emptySet()
    private var nextToken: EncounterTableTokenDto? = null
    private var refuseJoin: String? = null
    private var joinDelayMs = 0
    private val joins = mutableListOf<Pair<Int, Int>>()
    private var leaves = 0
    private val quiets = mutableListOf<Pair<Int, Boolean>>()
    private val sentToPlenum = mutableListOf<String>()
    private var tokenAnswer: EncounterTableTokenDto? = null
    private var tokenConflicts = 0
    private var leaveBusy = 0
    private var leaveAttempts = 0

    private class Routes(
        val join: String,
        val leave: String,
        val token: String,
        val list: String,
        val quiet: String,
        val plenum: String,
    )

    private suspend fun routes(): Routes =
        Routes(
            join = routeOf { rpcService<IEncounterSpaceService>().joinTable("space-1", 0, 0) },
            leave = routeOf { rpcService<IEncounterSpaceService>().leaveTable("space-1") },
            token = routeOf { rpcService<IEncounterSpaceService>().tableToken("space-1") },
            list = routeOf { rpcService<IEncounterSpaceService>().listTables("space-1") },
            quiet = routeOf { rpcService<IEncounterSpaceService>().quietTable("space-1", 0, true) },
            plenum = routeOf { rpcService<IEncounterSpaceService>().sendToPlenum("space-1", "x") },
        )

    private fun server(routes: Routes): (RecordedRequest) -> StubResponse =
        { request ->
            when (request.rpcRoute) {
                routes.join -> {
                    val table = (request.rpcParam(1) as Number).toInt()
                    val seat = (request.rpcParam(2) as Number).toInt()
                    joins += table to seat
                    val failure = refuseJoin
                    if (failure != null) {
                        request.refusedWith(failure)
                    } else {
                        people = people.map { if (it.memberId == "me") it.copy(table = table, tableSeat = seat) else it }
                        val token = nextToken ?: testTableToken(table = table, seat = seat)
                        val answer = request.answerWith(jsonOf(EncounterTableTokenDto.serializer(), token))
                        if (joinDelayMs > 0) StubResponse(text = answer.text, delayMs = joinDelayMs) else answer
                    }
                }
                routes.leave -> {
                    leaveAttempts++
                    if (leaveBusy > 0) {
                        leaveBusy--
                        request.refusedWith(busy)
                    } else {
                        leaves++
                        people = people.map { if (it.memberId == "me") it.copy(table = null, tableSeat = null) else it }
                        request.answerWith("null")
                    }
                }
                routes.token ->
                    if (tokenConflicts > 0) {
                        tokenConflicts--
                        request.refusedWith(conflict)
                    } else {
                        request.answerWith(jsonOf(EncounterTableTokenAnswer.serializer(), EncounterTableTokenAnswer(token = tokenAnswer)))
                    }
                routes.list ->
                    request.answerWith(
                        jsonOf(
                            ListSerializer(EncounterTableDto.serializer()),
                            (0 until 3).map {
                                EncounterTableDto(
                                    table = it,
                                    seats = 6,
                                    occupiedSeats = emptyList(),
                                    quieted =
                                        it in quietedTables,
                                )
                            },
                        ),
                    )
                routes.quiet -> {
                    quiets += (request.rpcParam(1) as Number).toInt() to (request.rpcParam(2) as Boolean)
                    request.answerWith("null")
                }
                routes.plenum -> {
                    sentToPlenum += request.rpcParam(1) as String
                    request.answerWith("null")
                }
                else -> StubResponse()
            }
        }

    private suspend fun withTableRoom(
        role: EncounterPresenceRole = EncounterPresenceRole.CONGREGATION,
        opener: FakeTableOpener = FakeTableOpener(),
        privileged: Boolean = false,
        block: suspend (EncounterRoomRig, HTMLElement) -> Unit,
    ) {
        val r = routes()
        withEncounterRoom(
            entry = testEntry(role = role),
            peopleOf = { people },
            clock = { clock },
            privileged = privileged,
            space = tablesSpace(myRole = if (role == EncounterPresenceRole.STEWARD) EncounterSpaceRole.STEWARD else null),
            tableSessionOpener = opener.opener,
            extraRespond = server(r),
            block = block,
        )
    }

    private fun crowd() =
        listOf(
            testPerson("me", name = "Ich Selbst"),
            testPerson("c1", name = "Gast Eins", table = 1, tableSeat = 0),
            testPerson("c2", name = "Gast Zwei", table = 2, tableSeat = 0),
        )

    private fun HTMLElement.live(): String = allOf("[role=status]").joinToString(" | ") { it.textContent.orEmpty() }

    private fun HTMLElement.tableSeat(
        table: Int,
        seat: Int,
    ): HTMLElement =
        assertNotNull(
            querySelector(".lapis-encounter-table-seat[data-table=\"$table\"][data-table-seat=\"$seat\"]") as? HTMLElement,
            "no seat $table/$seat",
        )

    private suspend fun HTMLElement.openPeople() {
        barControl("Chat").click()
        awaitUntil("the side panel is open") { querySelector("[role=tab]") != null }
        allOf("[role=tab]").first { it.textContent.orEmpty().trim() == "Anwesende" }.click()
    }

    private fun HTMLElement.micButtonShown(): HTMLElement? =
        allOf(".lapis-encounter-controls button").firstOrNull {
            it.getAttribute("aria-label") == "Mikrofon am Tisch" && it.getBoundingClientRect().width > 0
        }

    @Test
    fun sittingDown_waitsForTheServer_thenSitsWithTheMicrophoneOffAndAnnouncesOnlyTheOwnResult(): Promise<Unit> =
        formTest {
            people = crowd()
            joinDelayMs = 300
            val opener = FakeTableOpener()
            withTableRoom(opener = opener) { _, element ->
                awaitUntil("the tables are drawn") { element.allOf(".lapis-encounter-table").size == 3 }
                assertEquals(
                    "Tisch 2, Platz 1, besetzt, G E",
                    element.tableSeat(1, 0).getAttribute("aria-label"),
                    "initials of the roster only",
                )
                assertNull(element.micButtonShown(), "no table microphone while one sits in the plenary")
                element.tableSeat(1, 2).click()
                awaitUntil("the seat is pending, not yet own") { element.tableSeat(1, 2).getAttribute("aria-busy") == "true" }
                assertFalse(element.tableSeat(1, 2).classList.contains("lapis-encounter-table-seat--own"), "no optimistic picture")
                awaitUntil("the server said yes and the seat is the viewer's own") {
                    element.tableSeat(1, 2).classList.contains("lapis-encounter-table-seat--own")
                }
                assertEquals(listOf(1 to 2), joins)
                assertEquals(1, opener.opened.size)
                val session = opener.opened.single().second
                assertEquals(1, session.connects)
                assertTrue(session.microphoneCalls.isEmpty(), "the microphone is OFF after sitting down")
                assertTrue(element.live().contains("Sie sitzen an Tisch 2. Ihr Mikrofon ist aus."), element.live())
                assertEquals("Tisch 2, Platz 3, Ihr Platz", element.tableSeat(1, 2).getAttribute("aria-label"))
                val mic = assertNotNull(element.micButtonShown(), "the table microphone appears while seated")
                assertEquals("false", mic.getAttribute("aria-pressed"))
                assertNotNull(
                    element.allOf(".lapis-encounter-controls button").firstOrNull { it.getAttribute("aria-label") == "Podium lauter" },
                    "the assembly's word for the pulpit",
                )
                assertFalse(element.live().contains("Gast"), "no sentence names another person")
            }
        }

    @Test
    fun aSeatThatWasTakenMeanwhile_isAFixedSentence_notASitting(): Promise<Unit> =
        formTest {
            people = crowd()
            refuseJoin = conflict
            val opener = FakeTableOpener()
            withTableRoom(opener = opener) { _, element ->
                awaitUntil("tables") { element.allOf(".lapis-encounter-table").size == 3 }
                element.tableSeat(0, 0).click()
                awaitUntil("the sentence") { element.live().contains("Dieser Platz ist inzwischen besetzt.") }
                assertTrue(opener.opened.isEmpty(), "no audio session was opened")
                assertFalse(element.tableSeat(0, 0).classList.contains("lapis-encounter-table-seat--own"))
            }
        }

    @Test
    fun aBusyServer_isAWaitSentence(): Promise<Unit> =
        formTest {
            people = crowd()
            refuseJoin = busy
            withTableRoom { _, element ->
                awaitUntil("tables") { element.allOf(".lapis-encounter-table").size == 3 }
                element.tableSeat(0, 0).click()
                awaitUntil("the sentence") { element.live().contains("Bitte warten Sie einen Moment") }
            }
        }

    @Test
    fun theMicrophone_isSwitchedByTheBarControl_andShowsItsStateAsTextAndPressed(): Promise<Unit> =
        formTest {
            people = crowd()
            val opener = FakeTableOpener()
            withTableRoom(opener = opener) { _, element ->
                awaitUntil("tables") { element.allOf(".lapis-encounter-table").size == 3 }
                element.tableSeat(0, 1).click()
                awaitUntil("seated") { opener.opened.size == 1 && element.micButtonShown() != null }
                val session = opener.opened.single().second
                assertTrue(element.allOf(".lapis-encounter-table-mic").any { it.textContent == "Mikrofon aus" })
                element.micButtonShown()!!.click()
                awaitUntil("on") {
                    session.microphoneCalls == listOf(true) &&
                        element.micButtonShown()?.getAttribute("aria-pressed") == "true"
                }
                assertTrue(element.allOf(".lapis-encounter-table-mic").any { it.textContent == "Mikrofon an" })
                element.micButtonShown()!!.click()
                awaitUntil("off again") { session.microphoneCalls == listOf(true, false) }
                assertEquals("false", element.micButtonShown()!!.getAttribute("aria-pressed"))
            }
        }

    @Test
    fun aQuietedTablesToken_blocksTheMicrophone_inWordsAndOnTheControl(): Promise<Unit> =
        formTest {
            people = crowd()
            nextToken = testTableToken(table = 0, seat = 0, canPublish = false)
            val opener = FakeTableOpener()
            withTableRoom(opener = opener) { _, element ->
                awaitUntil("tables") { element.allOf(".lapis-encounter-table").size == 3 }
                element.tableSeat(0, 0).click()
                awaitUntil("seated") { opener.opened.size == 1 && element.micButtonShown() != null }
                val mic = element.micButtonShown()!!
                assertTrue(mic.asDynamic().disabled == true, "the control is disabled while the token cannot publish")
                assertTrue(element.allOf(".lapis-encounter-table-mic").any { it.textContent.orEmpty().contains("gesperrt") })
                assertTrue(element.allOf(".lapis-encounter-table-quiet").any { it.getBoundingClientRect().width > 0 })
            }
        }

    @Test
    fun whenTheRoomRotates_theViewerReconnectsWithTheNewToken_andTheMicrophoneComesBack(): Promise<Unit> =
        formTest {
            people = crowd()
            val opener = FakeTableOpener()
            withTableRoom(opener = opener) { _, element ->
                awaitUntil("tables") { element.allOf(".lapis-encounter-table").size == 3 }
                element.tableSeat(0, 3).click()
                awaitUntil("seated") { opener.opened.size == 1 && element.micButtonShown() != null }
                element.micButtonShown()!!.click()
                awaitUntil("mic on") {
                    opener.opened
                        .single()
                        .second.microphoneCalls == listOf(true)
                }
                tokenAnswer = testTableToken(room = "lc-et-2", table = 0, seat = 3)
                opener.lastCallbacks!!.onDisconnected(DisconnectCause.Other)
                awaitUntil("a second session with the new room") { opener.opened.size == 2 }
                assertEquals(
                    "lc-et-2",
                    opener.opened[1]
                        .first.join.livekitRoomName,
                )
                awaitUntil("the microphone is restored") { opener.opened[1].second.microphoneCalls == listOf(true) }
                assertEquals("Tisch 1, Platz 4, Ihr Platz", element.tableSeat(0, 3).getAttribute("aria-label"))
            }
        }

    @Test
    fun whenTheServerSaysNoSeat_theViewerIsBackInThePlenum_withEverythingStopped(): Promise<Unit> =
        formTest {
            people = crowd()
            val opener = FakeTableOpener()
            withTableRoom(opener = opener) { _, element ->
                awaitUntil("tables") { element.allOf(".lapis-encounter-table").size == 3 }
                element.tableSeat(2, 5).click()
                awaitUntil("seated") { opener.opened.size == 1 && element.micButtonShown() != null }
                tokenAnswer = null
                opener.lastCallbacks!!.onDisconnected(DisconnectCause.Other)
                awaitUntil("back in the plenary") { element.live().contains("Sie wurden ins Plenum zurückgesetzt.") }
                assertNull(element.micButtonShown())
                assertTrue(
                    opener.opened
                        .single()
                        .second.disconnects >= 1,
                )
                assertFalse(element.tableSeat(2, 5).classList.contains("lapis-encounter-table-seat--own"))
            }
        }

    @Test
    fun aRotationBetweenSeatingAndTheToken_doesNotLeaveAGhostSeat(): Promise<Unit> =
        formTest {
            people = crowd()
            // the server seated the viewer, then answered "table changed" (Conflict) for the token; the token call afterwards works
            refuseJoin = conflict
            tokenAnswer = testTableToken(room = "lc-et-9", table = 0, seat = 0)
            val opener = FakeTableOpener()
            withTableRoom(opener = opener) { _, element ->
                awaitUntil("tables") { element.allOf(".lapis-encounter-table").size == 3 }
                element.tableSeat(0, 0).click()
                awaitUntil("the viewer is connected to the table the server seated it at") { opener.opened.size == 1 }
                assertEquals(
                    "lc-et-9",
                    opener.opened
                        .single()
                        .first.join.livekitRoomName,
                )
                awaitUntil("the seat is the viewer's own") { element.tableSeat(0, 0).classList.contains("lapis-encounter-table-seat--own") }
                assertFalse(element.live().contains("inzwischen besetzt"), element.live())
            }
        }

    @Test
    fun aSeatedViewer_whoSwitchesToATakenSeat_keepsTheSessionAndTheMicrophone_andHearsTheSentence(): Promise<Unit> =
        formTest {
            people = crowd()
            val opener = FakeTableOpener()
            withTableRoom(opener = opener) { _, element ->
                awaitUntil("tables") { element.allOf(".lapis-encounter-table").size == 3 }
                element.tableSeat(0, 3).click()
                awaitUntil("seated") { opener.opened.size == 1 && element.micButtonShown() != null }
                element.micButtonShown()!!.click()
                val session = opener.opened.single().second
                awaitUntil("mic on") { session.microphoneCalls == listOf(true) }
                // somebody else took the seat first: the join is refused, the server still has the viewer at 0/3
                refuseJoin = conflict
                tokenAnswer = testTableToken(table = 0, seat = 3)
                clock += 60_000.0 // past the choice throttle
                element.tableSeat(1, 2).click()
                awaitUntil("the sentence") { element.live().contains("Dieser Platz ist inzwischen besetzt.") }
                assertEquals(1, opener.opened.size, "no second session, no audible break")
                assertEquals(listOf(true), session.microphoneCalls, "the microphone was not touched")
                assertEquals("true", element.micButtonShown()!!.getAttribute("aria-pressed"))
                assertEquals("Tisch 1, Platz 4, Ihr Platz", element.tableSeat(0, 3).getAttribute("aria-label"))
            }
        }

    @Test
    fun aRotationDuringTheReconnect_asksAgain_insteadOfThrowingTheViewerOutOfTheTable(): Promise<Unit> =
        formTest {
            people = crowd()
            val opener = FakeTableOpener()
            withTableRoom(opener = opener) { _, element ->
                awaitUntil("tables") { element.allOf(".lapis-encounter-table").size == 3 }
                element.tableSeat(0, 3).click()
                awaitUntil("seated") { opener.opened.size == 1 && element.micButtonShown() != null }
                tokenConflicts = 1
                tokenAnswer = testTableToken(room = "lc-et-3", table = 0, seat = 3)
                opener.lastCallbacks!!.onDisconnected(DisconnectCause.Other)
                awaitUntil("a second session after the conflict") { opener.opened.size == 2 }
                assertEquals(
                    "lc-et-3",
                    opener.opened[1]
                        .first.join.livekitRoomName,
                )
                assertEquals(0, leaves, "the viewer did not leave the table")
                assertEquals(0, leaveAttempts)
                assertEquals("Tisch 1, Platz 4, Ihr Platz", element.tableSeat(0, 3).getAttribute("aria-label"))
            }
        }

    @Test
    fun aFailedConnect_stillTellsTheServer_evenWhenTheFirstLeaveIsThrottled(): Promise<Unit> =
        formTest {
            people = crowd()
            leaveBusy = 1
            val opener = FakeTableOpener(sessionFor = { FakeTableSession(connectFailure = ConferenceConnectFailure.OTHER) })
            withTableRoom(opener = opener) { _, element ->
                awaitUntil("tables") { element.allOf(".lapis-encounter-table").size == 3 }
                element.tableSeat(0, 0).click()
                awaitUntil("the connect failed and was announced") {
                    element.live().contains("Die Verbindung zum Tisch konnte nicht hergestellt werden.")
                }
                awaitUntil("the leave got through on the retry") { leaves == 1 }
                assertEquals(2, leaveAttempts)
                assertFalse(element.tableSeat(0, 0).classList.contains("lapis-encounter-table-seat--own"))
            }
        }

    @Test
    fun leaving_stopsTheAudioAtOnce_tellsTheServer_andSaysSo(): Promise<Unit> =
        formTest {
            people = crowd()
            val opener = FakeTableOpener()
            withTableRoom(opener = opener) { _, element ->
                awaitUntil("tables") { element.allOf(".lapis-encounter-table").size == 3 }
                element.tableSeat(0, 0).click()
                awaitUntil("seated") { opener.opened.size == 1 && element.micButtonShown() != null }
                val leave =
                    element.allOf(".lapis-encounter-table button").first {
                        it.textContent?.trim() == "Tisch verlassen" &&
                            it.getBoundingClientRect().width > 0
                    }
                leave.click()
                awaitUntil("the session ended") {
                    opener.opened
                        .single()
                        .second.disconnects >= 1
                }
                awaitUntil("the server was told") { leaves == 1 }
                assertTrue(element.live().contains("Sie sind wieder im Plenum."), element.live())
                assertNull(element.micButtonShown())
            }
        }

    @Test
    fun speakersAreShownAtTheOwnTableOnly(): Promise<Unit> =
        formTest {
            people = crowd()
            val opener = FakeTableOpener()
            withTableRoom(opener = opener) { _, element ->
                awaitUntil("tables") { element.allOf(".lapis-encounter-table").size == 3 }
                element.tableSeat(1, 3).click()
                awaitUntil("seated") { opener.opened.size == 1 }
                opener.lastCallbacks!!.onActiveSpeakers(listOf("c1", "c2"))
                awaitUntil("c1 speaks") { element.tableSeat(1, 0).classList.contains("is-speaking") }
                assertFalse(element.tableSeat(2, 0).classList.contains("is-speaking"), "another table's speaker is never shown")
                assertTrue(
                    element
                        .tableSeat(1, 0)
                        .getAttribute("aria-label")
                        .orEmpty()
                        .endsWith("spricht"),
                )
            }
        }

    @Test
    fun anOfficeHolder_seesTheTables_butCannotSit_andHasNoTableControls(): Promise<Unit> =
        formTest {
            people = crowd() + testPerson("st", role = EncounterPresenceRole.STEWARD, name = "Ordner")
            val opener = FakeTableOpener()
            withTableRoom(role = EncounterPresenceRole.STEWARD, opener = opener) { _, element ->
                awaitUntil("tables") { element.allOf(".lapis-encounter-table").size == 3 }
                element.tableSeat(0, 0).click()
                delay(150)
                assertTrue(joins.isEmpty(), "no request")
                assertEquals("true", element.tableSeat(0, 0).getAttribute("aria-disabled"))
                assertNull(element.micButtonShown())
                assertTrue(
                    element.allOf(".lapis-encounter-controls button").none { it.getAttribute("aria-label") == "Podium lauter" },
                    "no table controls for an office holder",
                )
            }
        }

    @Test
    fun theListAlternative_choosesATableWithoutThePicture_andNamesNoOne(): Promise<Unit> =
        formTest {
            people = crowd()
            val opener = FakeTableOpener()
            withTableRoom(opener = opener) { _, element ->
                awaitUntil("tables") { element.allOf(".lapis-encounter-table").size == 3 }
                element.openPeople()
                awaitUntil("the list alternative") { element.querySelector(".lapis-encounter-table-list") != null }
                val list = element.querySelector(".lapis-encounter-table-list") as HTMLElement
                assertEquals("Tisch über eine Liste wählen", list.querySelector("summary")?.textContent?.trim())
                val buttons = list.allOf("[data-list-table]")
                assertEquals(
                    listOf("Tisch 1 (6 Plätze frei)", "Tisch 2 (5 Plätze frei)", "Tisch 3 (5 Plätze frei)"),
                    buttons.map {
                        it.textContent?.trim()
                    },
                )
                assertTrue(buttons.all { it.getBoundingClientRect().height >= 44 - 0.5 }, "44 px targets")
                assertFalse(list.textContent.orEmpty().contains("Gast"), "no names in the list")
                buttons[1].click()
                awaitUntil("seated at the first free seat of table 2") { joins == listOf(1 to 1) && opener.opened.size == 1 }
                awaitUntil("the list shows the own table") {
                    element
                        .querySelector(".lapis-encounter-table-list")
                        ?.textContent
                        .orEmpty()
                        .contains("Sie sitzen an Tisch 2.")
                }
                assertNotNull(element.querySelector(".lapis-encounter-table-list [data-list-table=leave]"))
            }
        }

    @Test
    fun aModerator_quietsATable_andSendsAPersonBackToThePlenum(): Promise<Unit> =
        formTest {
            people = crowd() + testPerson("st", role = EncounterPresenceRole.STEWARD, name = "Ordner")
            withTableRoom(role = EncounterPresenceRole.STEWARD) { _, element ->
                awaitUntil("tables") { element.allOf(".lapis-encounter-table").size == 3 }
                val quiet =
                    element.allOf(".lapis-encounter-table button").first {
                        it.textContent?.trim() == "Tisch beruhigen" &&
                            it.getBoundingClientRect().width > 0
                    }
                quiet.click()
                awaitUntil("the quiet request") { quiets.isNotEmpty() }
                assertEquals(listOf(0 to true), quiets)
                // the people tab: a person at a table can be sent back to the plenary
                element.openPeople()
                val send =
                    awaitNotNull("the send-to-plenary button") {
                        element.allOf("button").firstOrNull { it.textContent?.trim() == "Ins Plenum setzen" }
                    }
                send.click()
                awaitUntil("the request") { sentToPlenum.isNotEmpty() }
                assertEquals("c1", sentToPlenum.first())
            }
        }

    private suspend fun <T : Any> awaitNotNull(
        description: String,
        probe: () -> T?,
    ): T {
        var found: T? = null
        awaitUntil(description) {
            found = probe()
            found != null
        }
        return found!!
    }
}
