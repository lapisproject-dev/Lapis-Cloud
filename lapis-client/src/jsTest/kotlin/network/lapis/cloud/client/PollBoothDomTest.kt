package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.browser.window
import network.lapis.cloud.shared.domain.PollStatus
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.31: the answer booth, mounted for real. It is an irreversible single-shot action, so these tests pin the exact number of requests
 * (never two), what the booth says after every kind of failure, and that nothing about the chosen option leaks into attributes, storage,
 * the console, the address or a toast.
 */
class PollBoothDomTest {
    private suspend fun <T> withBooth(
        world: PollWorld,
        id: String,
        exits: MutableList<Boolean> = mutableListOf(),
        reviews: MutableList<Boolean> = mutableListOf(),
        block: suspend (HTMLElement, List<RecordedRequest>, PollRoutes) -> T,
    ): T {
        val routes = pollRoutes()
        return withFetchStub(respond = world.respond(routes)) { calls ->
            mountedForm(id) { root, element ->
                renderPollBooth(root, world.poll, onReview = { reviews += it }, onExit = { refresh -> exits += refresh })
                block(element(), calls, routes)
            }
        }
    }

    /** Chooses the option whose label contains [text] the way a person does: click its label. */
    private fun HTMLElement.choose(text: String) {
        val label = assertNotNull(allOf("label").firstOrNull { it.textContent.orEmpty().contains(text) }, "no option '$text'")
        label.click()
    }

    private suspend fun HTMLElement.chooseAndReview(text: String = "Nein, im August") {
        choose(text)
        awaitUntil("next enabled", 1500) { !isButtonDisabled("Weiter") }
        buttonNamed("Weiter").click()
        awaitUntil("review step", 1500) { hasButton("Endgültig abgeben") }
    }

    private suspend fun HTMLElement.submit() {
        chooseAndReview()
        buttonNamed("Endgültig abgeben").click()
    }

    @Test
    fun nothingIsPreselected_andNextWaitsForAChoice(): Promise<Unit> =
        formTest {
            withBooth(PollWorld(), "poll-booth-select") { el, calls, routes ->
                assertEquals(3, el.allOf("input[type=radio]").size)
                assertTrue(el.allOf("input[type=radio]").none { (it as HTMLInputElement).checked }, "no option is preselected")
                assertTrue(el.isButtonDisabled("Weiter"))
                assertTrue(
                    el
                        .allOf("legend")
                        .single()
                        .textContent
                        .orEmpty()
                        .contains("Sommerfest"),
                    "the question is the legend",
                )
                el.choose("Egal")
                awaitUntil("enabled", 1500) { !el.isButtonDisabled("Weiter") }
                assertEquals(0, calls.toRoute(routes.cast).size, "nothing is sent while choosing")
            }
        }

    @Test
    fun theCheckStep_namesTheChoice_explainsFinality_andBackKeepsItAsAProperty(): Promise<Unit> =
        formTest {
            val reviews = mutableListOf<Boolean>()
            withBooth(PollWorld(), "poll-booth-review", reviews = reviews) { el, calls, routes ->
                el.chooseAndReview("Nein, im August")
                val text = el.flatText()
                assertTrue(text.contains("Sie haben „Nein, im August“ gewählt."))
                assertTrue(text.contains("Ihre Antwort ist endgültig: einmalig, nicht änderbar und anonym."))
                assertTrue(text.contains("Ihr LTR-Stand wird für die gewichtete Auswertung festgehalten, aber nirgends angezeigt."))
                assertEquals(true, reviews.last(), "the detail is told that the check step is open")
                assertEquals(0, calls.toRoute(routes.cast).size)
                el.buttonNamed("Zurück").click()
                awaitUntil("back on the choice", 1500) { el.hasButton("Weiter") }
                awaitUntil("choice kept", 1500) { !el.isButtonDisabled("Weiter") }
                val checked = el.allOf("input[type=radio]").map { (it as HTMLInputElement).checked }
                assertEquals(listOf(false, true, false), checked, "the choice is restored")
                assertTrue(
                    el.allOf("input[type=radio]").none { it.hasAttribute("checked") },
                    "restored as a property, never as an attribute",
                )
                assertEquals(false, reviews.last())
            }
        }

    @Test
    fun theAnswer_carriesThePollAndTheOptionId_andNothingElse(): Promise<Unit> =
        formTest {
            withBooth(PollWorld(), "poll-booth-send") { el, calls, routes ->
                el.submit()
                awaitUntil("cast sent", 1500) { calls.toRoute(routes.cast).size == 1 }
                val input = calls.singleCall(routes.cast).rpcParam(0)
                assertEquals("p1", input.pollId as String)
                assertEquals("o-b", input.optionId as String)
                assertEquals(2, js("Object.keys")(input).unsafeCast<Array<String>>().size)
            }
        }

    @Test
    fun theDom_hasNoDataAttribute_noValue_andNoOptionIdInIdsOrNames(): Promise<Unit> =
        formTest {
            withBooth(PollWorld(), "poll-booth-dom") { el, _, _ ->
                el.choose("Ja, im Juli")
                val all = el.allOf("*")
                assertTrue(
                    all.none { node ->
                        (0 until node.attributes.length).any {
                            node.attributes
                                .item(it)!!
                                .name
                                .startsWith("data-")
                        }
                    },
                    "no data-* attribute",
                )
                listOf("o-a", "o-b", "o-c").forEach { id ->
                    assertTrue(
                        all.none { it.id.contains(id) || it.getAttribute("name").orEmpty().contains(id) },
                        "option id $id in an id or name",
                    )
                }
                assertTrue(el.allOf("input[type=radio]").none { it.hasAttribute("value") }, "a radio button names no value")
                assertTrue(
                    el
                        .allOf("input[type=radio]")
                        .map { it.getAttribute("name") }
                        .toSet()
                        .size == 1,
                    "one constant group name",
                )
            }
        }

    @Test
    fun aDoubleClickOnTheFinalButton_sendsExactlyOneAnswer(): Promise<Unit> =
        formTest {
            withBooth(PollWorld(castDelayMs = 250), "poll-booth-double") { el, calls, routes ->
                el.chooseAndReview()
                val submit = el.buttonNamed("Endgültig abgeben")
                submit.click()
                submit.click()
                submit.click()
                awaitUntil("saved", 3000) { el.flatText().contains("Ihre Antwort ist gespeichert.") }
                assertEquals(1, calls.toRoute(routes.cast).size, "three clicks, one answer")
            }
        }

    @Test
    fun afterTheSubmit_theChosenOptionIsNowhereInTheDocument_noStorage_noConsole_noToast_noAddressChange(): Promise<Unit> =
        formTest {
            val exits = mutableListOf<Boolean>()
            val before = window.location.href
            withConsoleSpy { consoleCalls ->
                withBooth(PollWorld(), "poll-booth-trace", exits) { el, _, _ ->
                    el.submit()
                    awaitUntil("saved", 1500) { el.flatText().contains("Ihre Antwort ist gespeichert.") }
                    assertFalse(el.innerHTML.contains("Nein, im August"), "the option text is gone from the booth")
                    assertFalse(document.body!!.innerHTML.contains("Nein, im August"), "and from the whole document")
                    assertFalse(document.body!!.innerHTML.contains("o-b"), "no option id anywhere")
                    assertEquals(0, el.allOf("input[type=radio]").size)
                    assertEquals(0, document.querySelectorAll(".toast").length)
                    assertEquals(before, window.location.href)
                    assertNoStoredCode()
                    assertTrue(
                        window.localStorage.length == 0 ||
                            (0 until window.localStorage.length).none {
                                window.localStorage
                                    .key(it)
                                    .orEmpty()
                                    .contains("poll")
                            },
                    )
                    el.buttonNamed("Fertig").click()
                    assertEquals(listOf(true), exits)
                }
                assertEquals(0, consoleCalls(), "nothing about an answer is logged")
            }
        }

    @Test
    fun aConflict_afterWhichTheMemberHasAnswered_saysItIsSaved(): Promise<Unit> =
        formTest {
            val routes = pollRoutes()
            val world = PollWorld(participation = pollParticipation(hasResponded = true, canRespond = false))
            world.failures[routes.cast] = CONFLICT_EXCEPTION
            withBooth(world, "poll-booth-conflict-answered") { el, calls, _ ->
                el.submit()
                awaitUntil("explained", 2000) { el.flatText().contains("Ihre Antwort ist gespeichert.") }
                assertFalse(el.flatText().contains("Die Bestätigung ist wegen eines Verbindungsabbruchs nicht angekommen."))
                assertFalse(el.flatText().contains("simulated"), "no server message is ever shown")
                assertEquals(1, calls.toRoute(routes.cast).size)
            }
        }

    @Test
    fun aConflict_afterWhichThePollIsClosed_saysItIsOver(): Promise<Unit> =
        formTest {
            val routes = pollRoutes()
            val world = PollWorld(participation = pollParticipation(canRespond = false))
            world.failures[routes.cast] = CONFLICT_EXCEPTION
            withBooth(world, "poll-booth-conflict-closed") { el, _, _ ->
                el.chooseAndReview()
                world.onRoute = { route -> if (route == routes.get) world.poll = pollDto(status = PollStatus.CLOSED, responseCount = 6) }
                el.buttonNamed("Endgültig abgeben").click()
                awaitUntil("explained", 2000) { el.flatText().contains("Die Umfrage ist beendet.") }
                assertTrue(el.hasButton("Zurück zur Umfrage"))
                assertEquals(0, el.allOf("input[type=radio]").size)
            }
        }

    @Test
    fun aConflictWhileStillOpen_keepsTheChoice_asksToWait_andNeverResendsByItself(): Promise<Unit> =
        formTest {
            val routes = pollRoutes()
            val world = PollWorld(participation = pollParticipation(canRespond = true))
            world.failures[routes.cast] = CONFLICT_EXCEPTION
            withBooth(world, "poll-booth-conflict-wait") { el, calls, _ ->
                el.submit()
                awaitUntil("asked to wait", 2000) { el.flatText().contains("Bitte kurz warten und erneut versuchen.") }
                assertTrue(el.hasButton("Endgültig abgeben"), "the check step is back, the member decides")
                assertTrue(el.flatText().contains("Sie haben „Nein, im August“ gewählt."), "the choice is kept")
                kotlinx.coroutines.delay(400)
                assertEquals(1, calls.toRoute(routes.cast).size, "nothing is resent by itself")
            }
        }

    @Test
    fun aRefusal_saysOnlyActiveMembersMayAnswer_withoutAToast(): Promise<Unit> =
        formTest {
            val routes = pollRoutes()
            val world = PollWorld()
            world.failures[routes.cast] = FORBIDDEN_EXCEPTION
            withBooth(world, "poll-booth-forbidden") { el, _, _ ->
                el.submit()
                awaitUntil("explained", 2000) { el.flatText().contains("Nur aktive Mitglieder können antworten.") }
                assertTrue(el.hasButton("Zurück zur Umfrage"))
                assertEquals(0, document.querySelectorAll(".toast").length)
            }
        }

    @Test
    fun aLostConnection_neverClaimsMoreThanIsKnown(): Promise<Unit> =
        formTest {
            val saved = PollWorld(participation = pollParticipation(hasResponded = true, canRespond = false), castNetworkError = true)
            withBooth(saved, "poll-booth-network-saved") { el, _, _ ->
                el.submit()
                awaitUntil("explained", 2000) {
                    el.flatText().contains("Ihre Antwort ist gespeichert.") &&
                        el.flatText().contains("Die Bestätigung ist wegen eines Verbindungsabbruchs nicht angekommen.")
                }
            }
            val routes = pollRoutes()
            val unknown = PollWorld(castNetworkError = true)
            unknown.failures[routes.get] = "network.lapis.cloud.shared.rpc.NotFoundException"
            unknown.failures[routes.participation] = "network.lapis.cloud.shared.rpc.NotFoundException"
            withBooth(unknown, "poll-booth-network-unknown") { el, _, _ ->
                el.submit()
                awaitUntil("explained", 2000) { el.flatText().contains("Bitte laden Sie die Seite neu.") }
                assertFalse(el.flatText().contains("Ihre Antwort ist gespeichert."), "no claim without knowledge")
            }
        }

    @Test
    fun cancelInTheChoice_leavesTheBoothWithoutRefresh(): Promise<Unit> =
        formTest {
            val exits = mutableListOf<Boolean>()
            withBooth(PollWorld(), "poll-booth-cancel", exits) { el, calls, routes ->
                el.choose("Egal")
                el.buttonNamed("Abbrechen").click()
                assertEquals(listOf(false), exits)
                assertEquals(0, calls.toRoute(routes.cast).size)
            }
        }
}
