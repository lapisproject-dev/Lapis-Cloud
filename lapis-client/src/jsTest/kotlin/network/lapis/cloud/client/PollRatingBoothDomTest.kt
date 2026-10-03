package network.lapis.cloud.client

import kotlinx.browser.document
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
 * V1.9.41: the resistance booth of the consensus polls, mounted for real. Like the choice booth it is an irreversible single-shot action:
 * these tests pin the exact number of requests, what the booth says after every kind of failure, what exactly is sent, and that no rating
 * leaks into attributes, ids, names, the address or a toast.
 */
class PollRatingBoothDomTest {
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
                renderPollRatingBooth(root, world.poll, onReview = { reviews += it }, onExit = { refresh -> exits += refresh })
                block(element(), calls, routes)
            }
        }
    }

    private fun skWorld() = PollWorld(poll = pollSkDto())

    /** Rates item [group] (0 = the passive option, then the real ones) with [value] the way a person does: click the scale label. */
    private fun HTMLElement.rate(
        group: Int,
        value: Int,
    ) {
        val label = assertNotNull(querySelector("label[for='sk-r-${group * 11 + value}']") as? HTMLElement, "no scale field $group/$value")
        label.click()
    }

    private suspend fun HTMLElement.rateAll(values: List<Int> = listOf(4, 1, 7)) {
        values.forEachIndexed { group, value -> rate(group, value) }
        awaitUntil("review enabled", 1500) { !isButtonDisabled("Prüfen") }
    }

    private suspend fun HTMLElement.reviewAll(values: List<Int> = listOf(4, 1, 7)) {
        rateAll(values)
        buttonNamed("Prüfen").click()
        awaitUntil("review step", 1500) { hasButton("Endgültig abgeben") }
    }

    private suspend fun HTMLElement.submit() {
        reviewAll()
        buttonNamed("Endgültig abgeben").click()
    }

    @Test
    fun theBooth_listsThePassiveOptionFirst_withTheAnchorsAndTheNonBindingNote_andNothingIsPreselected(): Promise<Unit> =
        formTest {
            withBooth(skWorld(), "poll-rating-select") { el, calls, routes ->
                assertEquals(33, el.allOf("input[type=radio]").size)
                assertTrue(el.allOf("input[type=radio]").none { (it as HTMLInputElement).checked }, "nothing is preselected")
                assertEquals(listOf("P", "1", "2"), el.allOf(".lapis-sk-num").map { it.textContent.orEmpty().trim() })
                val first = el.allOf("fieldset").first().flatText()
                assertTrue(first.contains("Keine Änderung") && first.contains("Passivlösung"))
                assertEquals(3, el.allOf(".lapis-sk-anchors").size)
                el.allOf(".lapis-sk-anchors").forEach {
                    assertEquals(listOf("kein Widerstand", "Bedenken", "starker Widerstand"), it.allOf("span").map { s -> s.flatText() })
                }
                val text = el.flatText()
                assertTrue(text.contains("Unverbindliches Stimmungsbild, keine Abstimmung."))
                assertTrue(text.contains("Wie groß ist Ihr Widerstand gegen jede Option?"))
                assertTrue(text.contains("Ihre Antwort ist anonym: Es wird gespeichert, dass Sie geantwortet haben, aber nicht, wie."))
                assertTrue(text.contains("Wann soll das Sommerfest stattfinden?"))
                assertTrue(el.isButtonDisabled("Prüfen"))
                assertTrue(el.allOf(".lapis-booth.lapis-booth-poll").isNotEmpty(), "the narrow scale rule is scoped to the poll booth")
                assertEquals(0, calls.toRoute(routes.castRatings).size, "nothing is sent while rating")
            }
        }

    @Test
    fun theOptionExplanation_isShownUnderItsOption_neverForThePassiveOne(): Promise<Unit> =
        formTest {
            withBooth(skWorld(), "poll-rating-explanation") { el, _, _ ->
                val fieldsets = el.allOf("fieldset")
                assertTrue(fieldsets[1].flatText().contains("Wetter ist meist besser."))
                assertEquals(0, fieldsets[0].allOf(".lapis-sk-why").size, "the passive option has no explanation")
                assertEquals(0, fieldsets[2].allOf(".lapis-sk-why").size)
            }
        }

    @Test
    fun theCheckButton_waitsForEveryOption_andTheCounterCounts(): Promise<Unit> =
        formTest {
            withBooth(skWorld(), "poll-rating-counter") { el, _, _ ->
                el.rate(0, 3)
                el.rate(1, 5)
                awaitUntil("two of three", 1500) { el.flatText().contains("2 von 3 Optionen bewertet") }
                assertTrue(el.isButtonDisabled("Prüfen"))
                el.rate(2, 0)
                awaitUntil("enabled", 1500) { !el.isButtonDisabled("Prüfen") }
                assertTrue(el.flatText().contains("3 von 3 Optionen bewertet"))
            }
        }

    @Test
    fun theReview_listsEveryRating_andBackKeepsTheChoiceAsAPropertyNeverAsAnAttribute(): Promise<Unit> =
        formTest {
            val reviews = mutableListOf<Boolean>()
            withBooth(skWorld(), "poll-rating-review", reviews = reviews) { el, calls, routes ->
                el.reviewAll(listOf(4, 1, 7))
                val text = el.flatText()
                assertTrue(text.contains("Keine Änderung: Widerstand 4 von 10"))
                assertTrue(text.contains("Im Juli: Widerstand 1 von 10"))
                assertTrue(text.contains("Im August: Widerstand 7 von 10"))
                assertTrue(text.contains("Nach der Abgabe kann Ihre Bewertung nicht mehr geändert werden."))
                assertEquals(true, reviews.last())
                assertEquals(0, calls.toRoute(routes.castRatings).size)
                el.buttonNamed("Zurück").click()
                awaitUntil("back on the scales", 1500) { el.hasButton("Prüfen") }
                awaitUntil("choice kept", 1500) { !el.isButtonDisabled("Prüfen") }
                val checked = el.allOf("input[type=radio]").mapIndexedNotNull { i, r -> if ((r as HTMLInputElement).checked) i else null }
                assertEquals(listOf(4, 11 + 1, 22 + 7), checked, "every rating is restored")
                assertTrue(el.allOf("input[type=radio]").none { it.hasAttribute("checked") }, "restored as a property")
                assertFalse(el.outerHTML.contains("checked"), "no checked attribute anywhere in the markup")
                assertEquals(false, reviews.last())
            }
        }

    @Test
    fun theRequest_carriesThePollAndOneRatingPerOptionIncludingThePassiveOne_andNothingElse(): Promise<Unit> =
        formTest {
            withBooth(skWorld(), "poll-rating-send") { el, calls, routes ->
                el.submit()
                awaitUntil("cast sent", 1500) { calls.toRoute(routes.castRatings).size == 1 }
                val input = calls.singleCall(routes.castRatings).rpcParam(0)
                assertEquals("p1", input.pollId as String)
                assertEquals(2, js("Object.keys")(input).unsafeCast<Array<String>>().size)
                val ratings = input.ratings
                val keys = js("Object.keys")(ratings).unsafeCast<Array<String>>().sorted()
                assertEquals(listOf("o-a", "o-b", "o-p"), keys)
                assertEquals(1, ratings["o-a"] as Int)
                assertEquals(7, ratings["o-b"] as Int)
                assertEquals(4, ratings["o-p"] as Int)
            }
        }

    @Test
    fun theDom_hasNoDataAttribute_noValue_andNoOptionIdInIdsOrNames(): Promise<Unit> =
        formTest {
            withBooth(skWorld(), "poll-rating-dom") { el, _, _ ->
                el.rate(1, 6)
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
                listOf("o-a", "o-b", "o-p").forEach { id ->
                    assertTrue(
                        all.none { it.id.contains(id) || it.getAttribute("name").orEmpty().contains(id) },
                        "option id $id in an id or name",
                    )
                }
                assertTrue(el.allOf("input[type=radio]").none { it.hasAttribute("value") || it.hasAttribute("checked") })
            }
        }

    @Test
    fun aDoubleClickOnTheFinalButton_sendsExactlyOneRequest(): Promise<Unit> =
        formTest {
            withBooth(PollWorld(poll = pollSkDto(), castDelayMs = 250), "poll-rating-double") { el, calls, routes ->
                el.reviewAll()
                val submit = el.buttonNamed("Endgültig abgeben")
                submit.click()
                submit.click()
                submit.click()
                awaitUntil("saved", 3000) { el.flatText().contains("Ihre Antwort ist gespeichert.") }
                assertEquals(1, calls.toRoute(routes.castRatings).size, "three clicks, one answer")
            }
        }

    @Test
    fun afterTheSubmit_noRatingIsLeftInTheDocument_noToast_noStorage_noAddressChange(): Promise<Unit> =
        formTest {
            val exits = mutableListOf<Boolean>()
            val before = kotlinx.browser.window.location.href
            withBooth(skWorld(), "poll-rating-trace", exits) { el, _, _ ->
                el.submit()
                awaitUntil("saved", 1500) { el.flatText().contains("Ihre Antwort ist gespeichert.") }
                assertEquals(0, el.allOf("input[type=radio]").size)
                assertFalse(el.flatText().contains("Widerstand 7"), "the review text is gone")
                assertFalse(document.body!!.innerHTML.contains("o-b"), "no option id anywhere")
                assertEquals(0, document.querySelectorAll(".toast").length)
                assertEquals(before, kotlinx.browser.window.location.href)
                assertNoStoredCode()
                el.buttonNamed("Fertig").click()
                assertEquals(listOf(true), exits)
            }
        }

    @Test
    fun aConflict_afterWhichTheMemberHasAnswered_saysItIsSaved(): Promise<Unit> =
        formTest {
            val routes = pollRoutes()
            val world = PollWorld(poll = pollSkDto(), participation = pollParticipation(hasResponded = true, canRespond = false))
            world.failures[routes.castRatings] = CONFLICT_EXCEPTION
            withBooth(world, "poll-rating-conflict-answered") { el, calls, _ ->
                el.submit()
                awaitUntil("explained", 2000) { el.flatText().contains("Bereits geantwortet") }
                assertTrue(el.flatText().contains("Ihre Antwort ist gespeichert."))
                assertFalse(el.flatText().contains("simulated"), "no server message is ever shown")
                assertEquals(1, calls.toRoute(routes.castRatings).size)
            }
        }

    @Test
    fun aConflict_afterWhichThePollIsClosed_saysItIsOver(): Promise<Unit> =
        formTest {
            val routes = pollRoutes()
            val world = PollWorld(poll = pollSkDto(), participation = pollParticipation(canRespond = false))
            world.failures[routes.castRatings] = CONFLICT_EXCEPTION
            withBooth(world, "poll-rating-conflict-closed") { el, _, _ ->
                el.reviewAll()
                world.onRoute = { route ->
                    if (route == routes.get) world.poll = pollSkDto(status = PollStatus.CLOSED, responseCount = 6)
                }
                el.buttonNamed("Endgültig abgeben").click()
                awaitUntil("explained", 2000) { el.flatText().contains("Die Umfrage ist beendet.") }
                assertTrue(el.hasButton("Zurück zur Umfrage"))
                assertEquals(0, el.allOf("input[type=radio]").size)
            }
        }

    @Test
    fun aConflictWhileStillOpen_keepsTheRatings_asksToWait_andNeverResendsByItself(): Promise<Unit> =
        formTest {
            val routes = pollRoutes()
            val world = PollWorld(poll = pollSkDto(), participation = pollParticipation(canRespond = true))
            world.failures[routes.castRatings] = CONFLICT_EXCEPTION
            withBooth(world, "poll-rating-conflict-wait") { el, calls, _ ->
                el.submit()
                awaitUntil("asked to wait", 2000) { el.flatText().contains("Bitte kurz warten und erneut versuchen.") }
                assertTrue(el.hasButton("Endgültig abgeben"), "the review is back, the member decides")
                assertTrue(el.flatText().contains("Im August: Widerstand 7 von 10"), "the ratings are kept")
                kotlinx.coroutines.delay(400)
                assertEquals(1, calls.toRoute(routes.castRatings).size, "nothing is resent by itself")
            }
        }

    @Test
    fun aRefusal_saysOnlyActiveMembersMayAnswer_withoutAToast(): Promise<Unit> =
        formTest {
            val routes = pollRoutes()
            val world = skWorld()
            world.failures[routes.castRatings] = FORBIDDEN_EXCEPTION
            withBooth(world, "poll-rating-forbidden") { el, _, _ ->
                el.submit()
                awaitUntil("explained", 2000) { el.flatText().contains("Nur aktive Mitglieder können antworten.") }
                assertTrue(el.hasButton("Zurück zur Umfrage"))
                assertEquals(0, document.querySelectorAll(".toast").length)
            }
        }

    @Test
    fun aLostConnection_neverClaimsMoreThanIsKnown(): Promise<Unit> =
        formTest {
            val saved =
                PollWorld(
                    poll = pollSkDto(),
                    participation = pollParticipation(hasResponded = true, canRespond = false),
                    castNetworkError = true,
                )
            withBooth(saved, "poll-rating-network-saved") { el, _, _ ->
                el.submit()
                awaitUntil("explained", 2000) {
                    el.flatText().contains("Ihre Antwort ist gespeichert.") &&
                        el.flatText().contains("Die Bestätigung ist wegen eines Verbindungsabbruchs nicht angekommen.")
                }
            }
            val routes = pollRoutes()
            val unknown = PollWorld(poll = pollSkDto(), castNetworkError = true)
            unknown.failures[routes.get] = "network.lapis.cloud.shared.rpc.NotFoundException"
            unknown.failures[routes.participation] = "network.lapis.cloud.shared.rpc.NotFoundException"
            withBooth(unknown, "poll-rating-network-unknown") { el, _, _ ->
                el.submit()
                awaitUntil("explained", 2000) { el.flatText().contains("Bitte laden Sie die Seite neu.") }
                assertFalse(el.flatText().contains("Ihre Antwort ist gespeichert."), "no claim without knowledge")
            }
        }

    @Test
    fun cancelInTheScales_leavesTheBoothWithoutRefresh(): Promise<Unit> =
        formTest {
            val exits = mutableListOf<Boolean>()
            withBooth(skWorld(), "poll-rating-cancel", exits) { el, calls, routes ->
                el.rate(1, 2)
                el.buttonNamed("Abbrechen").click()
                assertEquals(listOf(false), exits)
                assertEquals(0, calls.toRoute(routes.castRatings).size)
            }
        }

    @Test
    fun aRankingPollHasNoPassiveOption(): Promise<Unit> =
        formTest {
            val world = PollWorld(poll = pollSkDto(kind = network.lapis.cloud.shared.domain.PollKind.SK_PRIORITY))
            withBooth(world, "poll-rating-ranking") { el, _, _ ->
                assertEquals(listOf("1", "2"), el.allOf(".lapis-sk-num").map { it.textContent.orEmpty().trim() })
                assertEquals(22, el.allOf("input[type=radio]").size)
                assertFalse(el.flatText().contains("Passivlösung"))
            }
        }
}
