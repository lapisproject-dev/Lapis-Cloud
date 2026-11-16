package network.lapis.cloud.client

import io.kvision.panel.Root
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import network.lapis.cloud.shared.domain.RoomPollCountingMode
import network.lapis.cloud.shared.domain.RoomPollDto
import network.lapis.cloud.shared.domain.RoomPollGroupResultDto
import network.lapis.cloud.shared.domain.RoomPollOptionDto
import network.lapis.cloud.shared.domain.RoomPollResultDto
import network.lapis.cloud.shared.domain.RoomPollStateDto
import network.lapis.cloud.shared.domain.RoomPollStatus
import network.lapis.cloud.shared.domain.RoomPollVersionDto
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val ROOM = "0b9a3c1e-4d2f-4a6b-9c8d-1e2f3a4b5c6d"

/** Room polls, wave B -- the viewer page mounted for real, on a fake clock and with a scripted server. */
class RoomPollViewerScreenDomTest {
    private class FakeEnv : RoomPollViewerEnv {
        val clock = OperatorFakeScheduler()
        override val scheduler: ConferenceVoteScheduler = clock
        override val scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined)
        override val random: () -> Double = { 0.0 }
        var hidden = false
        var listening = 0

        override fun isHidden(): Boolean = hidden

        override fun listen(
            onVisibility: () -> Unit,
            onOnline: () -> Unit,
            onOffline: () -> Unit,
        ): () -> Unit {
            listening++
            return { listening-- }
        }
    }

    private class Server {
        var token = "none"
        var state = RoomPollStateDto(roomId = ROOM, version = "none")
        var versionCalls = 0
        var stateCalls = 0
        val answered = mutableListOf<Triple<String, String, String>>()
        var versionFailure: (() -> Throwable)? = null
        var answerFailure: (() -> Throwable)? = null
        var answerState: RoomPollStateDto? = null

        val rpc =
            RoomPollViewerRpc(
                version = { _, known ->
                    versionCalls++
                    versionFailure?.let { throw it() }
                    if (known == token) RoomPollVersionDto(changed = false) else RoomPollVersionDto(changed = true, version = token)
                },
                state = {
                    stateCalls++
                    state
                },
                answer = { room, poll, option ->
                    answered += Triple(room, poll, option)
                    answerFailure?.let { throw it() }
                    answerState ?: state
                },
            )
    }

    private fun poll(status: RoomPollStatus = RoomPollStatus.OPEN) =
        RoomPollDto(
            id = "p1",
            question = "Sollen wir verlängern?",
            options =
                listOf(
                    RoomPollOptionDto("o1", 0, "Ja"),
                    RoomPollOptionDto("o2", 1, "Nein"),
                    RoomPollOptionDto("o3", 2, "Enthaltung"),
                ),
            countingMode = RoomPollCountingMode.BOTH,
            status = status,
        )

    private fun open(voted: Boolean = false) =
        RoomPollStateDto(roomId = ROOM, poll = poll(), canAnswer = !voted, voted = voted, version = "p1.O")

    private val result =
        RoomPollResultDto(
            totalAnswerCount = 10,
            resultAvailable = true,
            splitAvailable = false,
            all = RoomPollGroupResultDto(null, 10, listOf(50, 30, 20)),
        )

    private fun closed() = RoomPollStateDto(roomId = ROOM, poll = poll(RoomPollStatus.CLOSED), result = result, version = "p1.C")

    private fun HTMLElement.text(): String = textContent.orEmpty().replace(Regex("\\s+"), " ").trim()

    private fun HTMLElement.live(): String = assertNotNull(querySelector("[role=status]")).textContent.orEmpty()

    private suspend fun <T> withViewer(
        server: Server,
        env: FakeEnv = FakeEnv(),
        roomId: String? = ROOM,
        authenticated: Boolean = true,
        block: suspend (HTMLElement, FakeEnv, RoomPollViewerHandle) -> T,
    ): T =
        mountedForm("room-poll-viewer") { root: Root, element ->
            val handle = renderRoomPollViewerScreen(root, roomId, authenticated, server.rpc, env)
            try {
                // the first call is scheduled out of the insert patch
                env.clock.advance(0)
                block(element(), env, handle)
            } finally {
                handle.dispose()
            }
        }

    @Test
    fun noPollThenOpen_theFirstLoadIsSilent_theChangeIsAnnounced_theTabSaysSo(): Promise<Unit> =
        formTest {
            val server = Server()
            withViewer(server) { el, env, handle ->
                awaitUntil("no poll") { el.text().contains("Gerade läuft keine Umfrage. Diese Seite aktualisiert sich selbst.") }
                assertEquals("", el.live(), "the first load is silent")
                server.token = "p1.O"
                server.state = open()
                env.clock.advance(5_000)
                awaitUntil("the poll") { el.text().contains("Sollen wir verlängern?") }
                assertEquals("Eine Umfrage hat begonnen.", el.live())
                assertEquals(3, el.allOf(".lapis-room-poll-viewer-option").size)
                assertTrue(document.title.contains("Umfrage läuft"))
                // the room's name is never shown and nothing but the question and the answers
                assertFalse(el.text().contains("Videoraum"))
                handle.dispose()
                assertFalse(document.title.contains("Umfrage läuft"), "the subtitle goes away with the page")
            }
        }

    @Test
    fun oneTapLocksThePage_theChosenButtonIsPressed_theAnswerIsConfirmed_theChoiceIsNeverShown(): Promise<Unit> =
        formTest {
            val server = Server()
            server.token = "p1.O"
            server.state = open()
            server.answerState = open(voted = true)
            withViewer(server) { el, _, _ ->
                awaitUntil("buttons") { el.allOf(".lapis-room-poll-viewer-option").size == 3 }
                val buttons = el.allOf(".lapis-room-poll-viewer-option")
                buttons[1].click()
                assertEquals(1, server.answered.size)
                assertEquals(Triple(ROOM, "p1", "o2"), server.answered.single())
                awaitUntil("confirmation") { el.text().contains("Ihre Stimme wurde gezählt.") }
                assertTrue(el.text().contains("Das Ergebnis erscheint hier, sobald die Umfrage beendet ist."))
                assertEquals("Ihre Stimme wurde gezählt.", el.live())
                assertEquals(0, el.allOf(".lapis-room-poll-viewer-option").size)
                // never which option was chosen
                assertFalse(el.text().contains("Nein"))
                assertFalse(el.text().contains("Enthaltung"))
                assertEquals(1, server.answered.size)
            }
        }

    @Test
    fun whileTheAnswerIsOnItsWay_everyButtonIsOff_andTheChosenOneIsPressed(): Promise<Unit> =
        formTest {
            val server = Server()
            server.token = "p1.O"
            server.state = open()
            // the answer never comes back in this test: the page stays locked
            val slow =
                RoomPollViewerRpc(
                    version = server.rpc.version,
                    state = server.rpc.state,
                    answer = { _, _, _ -> kotlinx.coroutines.awaitCancellation() },
                )
            val env = FakeEnv()
            mountedForm("room-poll-viewer-lock") { root: Root, element ->
                val handle = renderRoomPollViewerScreen(root, ROOM, true, slow, env)
                try {
                    env.clock.advance(0)
                    val el = element()
                    awaitUntil("buttons") { el.allOf(".lapis-room-poll-viewer-option").size == 3 }
                    el.allOf(".lapis-room-poll-viewer-option")[0].click()
                    awaitUntil("locked") { el.allOf(".lapis-room-poll-viewer-option").all { it.hasAttribute("disabled") } }
                    val after = el.allOf(".lapis-room-poll-viewer-option")
                    assertEquals("true", after[0].getAttribute("aria-pressed"))
                    assertEquals("false", after[1].getAttribute("aria-pressed"))
                } finally {
                    handle.dispose()
                    cancelAppScopeWork()
                }
            }
        }

    @Test
    fun aConflictOnTheAnswer_isResolvedThroughTheState(): Promise<Unit> =
        formTest {
            // already counted
            val server = Server()
            server.token = "p1.O"
            server.state = open()
            server.answerFailure = { ConflictException("x") }
            withViewer(server) { el, _, _ ->
                awaitUntil("buttons") { el.allOf(".lapis-room-poll-viewer-option").size == 3 }
                server.state = open(voted = true)
                el.allOf(".lapis-room-poll-viewer-option")[0].click()
                awaitUntil("already counted") { el.text().contains("Ihre Stimme wurde bereits gezählt.") }
            }
            // the poll just ended
            val ended = Server()
            ended.token = "p1.O"
            ended.state = open()
            ended.answerFailure = { ConflictException("x") }
            withViewer(ended) { el, _, _ ->
                awaitUntil("buttons") { el.allOf(".lapis-room-poll-viewer-option").size == 3 }
                ended.state = closed()
                el.allOf(".lapis-room-poll-viewer-option")[0].click()
                awaitUntil("ended hint") { el.text().contains("Die Umfrage wurde gerade beendet.") }
                assertEquals(1, el.allOf(".lapis-room-poll-result").size)
            }
            // anything else (a rate limit): the generic error, the buttons are back
            val limited = Server()
            limited.token = "p1.O"
            limited.state = open()
            limited.answerFailure = { ConflictException("x") }
            withViewer(limited) { el, _, _ ->
                awaitUntil("buttons") { el.allOf(".lapis-room-poll-viewer-option").size == 3 }
                el.allOf(".lapis-room-poll-viewer-option")[0].click()
                awaitUntil("generic error") {
                    el.text().contains("Die Antwort konnte nicht gesendet werden. Bitte versuchen Sie es erneut.")
                }
                awaitUntil("buttons back") { el.allOf(".lapis-room-poll-viewer-option").none { it.hasAttribute("disabled") } }
            }
        }

    @Test
    fun aClosedPollShowsTheResultComponent_andANewResultIsAnnounced(): Promise<Unit> =
        formTest {
            val server = Server()
            server.token = "p1.O"
            server.state = open(voted = true)
            withViewer(server) { el, env, _ ->
                awaitUntil("answered") { el.text().contains("Ihre Stimme wurde gezählt.") }
                server.token = "p1.C"
                server.state = closed()
                env.clock.advance(5_000)
                awaitUntil("result") { el.allOf(".lapis-room-poll-result").size == 1 }
                assertEquals("Die Umfrage ist beendet. Das Ergebnis wird angezeigt.", el.live())
                assertTrue(el.text().contains("Sollen wir verlängern?"))
            }
        }

    @Test
    fun aRefusalShowsTheOneSentence_aResultOnScreenStays_andRecheckAsksOnce(): Promise<Unit> =
        formTest {
            val server = Server()
            server.token = "p1.C"
            server.state = closed()
            withViewer(server) { el, env, _ ->
                awaitUntil("result") { el.allOf(".lapis-room-poll-result").size == 1 }
                server.versionFailure = { ForbiddenException() }
                env.clock.advance(5_000)
                awaitUntil("sentence") { el.text().contains("Zu diesem Raum läuft kein Stream.") }
                assertEquals(1, el.allOf(".lapis-room-poll-result").size, "the result stays below the sentence")
                val calls = server.versionCalls
                env.clock.advance(300_000)
                assertEquals(calls, server.versionCalls, "no polling after a refusal")
                server.versionFailure = null
                server.token = "p1.C"
                el.buttonNamed("Erneut prüfen").click()
                env.clock.advance(5_100)
                awaitUntil("one more call") { server.versionCalls > calls }
                awaitUntil("back to the result") { !el.text().contains("Zu diesem Raum läuft kein Stream.") }
            }
        }

    @Test
    fun withoutASession_thePageOnlyAsksToLogIn_noCallIsMade(): Promise<Unit> =
        formTest {
            val server = Server()
            withViewer(server, authenticated = false) { el, env, _ ->
                awaitUntil("prompt") { el.text().contains("Bitte melden Sie sich an, um an der Umfrage teilzunehmen.") }
                el.buttonNamed("Anmelden")
                env.clock.advance(600_000)
                assertEquals(0, server.versionCalls)
                assertEquals(0, server.stateCalls)
                assertEquals(0, env.listening)
            }
        }

    @Test
    fun anInvalidRoomId_showsTheUnavailableSentence_withoutAnyCall(): Promise<Unit> =
        formTest {
            listOf(null, "", "not-an-id", ROOM.uppercase()).forEach { bad ->
                val server = Server()
                withViewer(server, roomId = bad) { el, env, _ ->
                    awaitUntil("sentence") { el.text().contains("Zu diesem Raum läuft kein Stream.") }
                    env.clock.advance(600_000)
                    assertEquals(0, server.versionCalls)
                    assertEquals(0, server.stateCalls)
                }
            }
        }

    @Test
    fun disposeRemovesTheListeners_andStopsEveryTimer(): Promise<Unit> =
        formTest {
            val server = Server()
            withViewer(server) { el, env, handle ->
                awaitUntil("loaded") { el.text().contains("Gerade läuft keine Umfrage") }
                assertEquals(1, env.listening)
                handle.dispose()
                assertEquals(0, env.listening)
                val calls = server.versionCalls
                env.clock.advance(120_000)
                assertEquals(calls, server.versionCalls)
            }
        }
}
