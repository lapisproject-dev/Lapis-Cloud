package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.PollStatus
import network.lapis.cloud.shared.domain.PollWeightedWithheldReason
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V1.9.31: the poll list, mounted for real with a stubbed server. */
class PollListDomTest {
    private suspend fun <T> withList(
        world: PollWorld,
        id: String,
        block: suspend (HTMLElement, List<RecordedRequest>, PollRoutes) -> T,
    ): T {
        AppState.setSession(pollSession())
        val routes = pollRoutes()
        return withFetchStub(respond = world.respond(routes)) { calls ->
            mountedForm(id) { root, element ->
                renderPollScreen(root)
                awaitUntil("list rendered", 3000) {
                    calls.toRoute(routes.list).isNotEmpty() &&
                        !element().flatText().contains("Wird geladen")
                }
                block(element(), calls, routes)
            }
        }
    }

    private fun HTMLElement.chip(text: String): HTMLElement = buttonNamed(text)

    @Test
    fun theOpenFilter_isTheDefault_andSwitchingToClosedAsksTheServerForClosedPolls(): Promise<Unit> =
        formTest {
            val world =
                PollWorld(
                    all =
                        listOf(
                            pollDto(id = "p1", question = "Offene Frage"),
                            pollDto(id = "p2", status = PollStatus.CLOSED, question = "Alte Frage", responseCount = 9),
                        ),
                )
            withList(world, "poll-list-filter") { el, calls, routes ->
                assertEquals("OPEN", calls.toRoute(routes.list).first().rpcParam(0) as String)
                assertEquals("true", el.chip("Offen").getAttribute("aria-pressed"))
                assertEquals("false", el.chip("Geschlossen").getAttribute("aria-pressed"))
                assertTrue(el.flatText().contains("Offene Frage") && !el.flatText().contains("Alte Frage"))
                el.chip("Geschlossen").click()
                awaitUntil("closed list", 3000) { el.flatText().contains("Alte Frage") }
                assertEquals("CLOSED", calls.toRoute(routes.list).last().rpcParam(0) as String)
                assertEquals("true", el.chip("Geschlossen").getAttribute("aria-pressed"))
                assertEquals("false", el.chip("Offen").getAttribute("aria-pressed"))
                assertFalse(el.flatText().contains("Offene Frage"))
            }
        }

    @Test
    fun theOwnState_isABadgeForOpenForYou_andQuietTextForAnswered(): Promise<Unit> =
        formTest {
            val world =
                PollWorld(
                    all = listOf(pollDto(id = "p1", question = "Frage eins"), pollDto(id = "p2", question = "Frage zwei")),
                    participations =
                        listOf(
                            pollParticipation(id = "p1", canRespond = true),
                            pollParticipation(id = "p2", hasResponded = true),
                        ),
                )
            withList(world, "poll-list-own") { el, calls, routes ->
                awaitUntil("own state", 3000) { el.flatText().contains("Offen für Sie") }
                val badge = el.allOf(".badge").first { it.textContent?.trim() == "Offen für Sie" }
                assertTrue(badge.className.contains("badge"))
                val answered = el.allOf("span").first { it.textContent?.trim() == "Beantwortet" }
                assertTrue(answered.className.contains("text-muted"), "an answered poll is quiet text, not a badge")
                assertFalse(answered.className.contains("badge"))
                assertEquals(1, calls.toRoute(routes.participations).size, "one batch call, not one per row")
            }
        }

    @Test
    fun theNumberOfAnswers_isAColumnOfTheClosedPollsOnly(): Promise<Unit> =
        formTest {
            val world =
                PollWorld(
                    all =
                        listOf(
                            pollDto(id = "p1", status = PollStatus.OPEN, responseCount = 12),
                            pollDto(id = "p2", status = PollStatus.CLOSED, responseCount = 12, question = "Beendet"),
                            pollDto(id = "p3", status = PollStatus.CLOSED, responseCount = 1, question = "Beendet eins"),
                        ),
                )
            withList(world, "poll-list-count") { el, _, _ ->
                assertFalse(el.flatText().contains("12 Antworten"), "an open poll shows no count")
                el.chip("Geschlossen").click()
                awaitUntil("closed rows", 3000) { el.flatText().contains("Beendet") }
                assertTrue(el.flatText().contains("12 Antworten"))
                assertTrue(el.flatText().contains("1 Antwort") && !el.flatText().contains("1 Antworten"))
            }
        }

    @Test
    fun anEmptyList_saysWhatIsMissing_perFilter(): Promise<Unit> =
        formTest {
            withList(PollWorld(all = emptyList()), "poll-list-empty") { el, _, _ ->
                assertTrue(el.flatText().contains("Zurzeit keine offenen Umfragen."))
                el.chip("Geschlossen").click()
                awaitUntil("closed empty", 3000) { el.flatText().contains("Noch keine geschlossenen Umfragen.") }
                el.chip("Abgebrochen").click()
                awaitUntil("aborted empty", 3000) { el.flatText().contains("Keine abgebrochenen Umfragen.") }
            }
        }

    @Test
    fun moreLoad_asksForTheNextPageAtOffset50_andAppendsTheRows(): Promise<Unit> =
        formTest {
            val world = PollWorld(all = pollListOf(60))
            withList(world, "poll-list-more") { el, calls, routes ->
                val first = calls.toRoute(routes.list).first()
                assertEquals(51, first.rpcParam(1) as Int, "one more than a page, only to know that there is a next page")
                assertEquals(0, first.rpcParam(2) as Int)
                assertTrue(
                    el.flatText().contains("Frage Nummer 50") && !el.flatText().contains("Frage Nummer 51"),
                    "the extra row is cut off",
                )
                el.buttonNamed("Mehr laden").click()
                awaitUntil("second page", 3000) { calls.toRoute(routes.list).size == 2 }
                assertEquals(50, calls.toRoute(routes.list).last().rpcParam(2) as Int)
                awaitUntil("appended", 3000) { el.flatText().contains("Frage Nummer 60") }
                assertTrue(Regex("Frage Nummer 1(?!\\d)").containsMatchIn(el.flatText()), "the first page is still there")
                assertFalse(el.hasButton("Mehr laden"), "no further page")
            }
        }

    @Test
    fun theOwnStates_ofMoreThan100Polls_comeInBlocksOf100(): Promise<Unit> =
        formTest {
            val routes = pollRoutes()
            val world = PollWorld()
            withFetchStub(respond = world.respond(routes)) { calls ->
                loadOwnParticipations((1..150).map { "p$it" })
                assertEquals(2, calls.toRoute(routes.participations).size)
                assertEquals(100, (calls.toRoute(routes.participations)[0].rpcParam(0).length as Int))
                assertEquals(50, (calls.toRoute(routes.participations)[1].rpcParam(0).length as Int))
            }
        }

    @Test
    fun theCreateButton_existsOnlyWhenTheServerSaysTheViewerMayCreate(): Promise<Unit> =
        formTest {
            withList(PollWorld(all = pollListOf(1), canCreate = true), "poll-list-can") { el, calls, routes ->
                awaitUntil("button visible", 3000) { el.hasButton("Umfrage erstellen") }
                assertEquals(1, calls.toRoute(routes.canCreate).size)
            }
            withList(PollWorld(all = pollListOf(1), canCreate = false), "poll-list-cannot") { el, _, routes ->
                kotlinx.coroutines.delay(200)
                assertFalse(el.allOf("button").any { it.textContent?.trim() == "Umfrage erstellen" && it.offsetParent != null })
                assertTrue(routes.canCreate.isNotEmpty())
            }
        }

    @Test
    fun aRefusedReader_isSentAwayWithoutAToastOrAServerText(): Promise<Unit> =
        formTest {
            AppState.setSession(pollSession(network.lapis.cloud.shared.domain.MemberStatus.GUEST))
            val routes = pollRoutes()
            val world = PollWorld(all = pollListOf(2))
            world.failures[routes.list] = FORBIDDEN_EXCEPTION
            val refused = mutableListOf<String>()
            val original = pollRefusedNavigator
            pollRefusedNavigator = { refused += it }
            try {
                withFetchStub(respond = world.respond(routes)) { calls ->
                    mountedForm("poll-list-guest") { root, element ->
                        renderPollScreen(root)
                        awaitUntil("redirected", 3000) { refused.isNotEmpty() }
                        assertEquals(listOf(Routes.DASHBOARD), refused)
                        assertEquals(0, document().querySelectorAll(".toast").length, "no toast")
                        assertFalse(element().flatText().contains("simulated"), "no server text")
                        assertFalse(element().flatText().contains("Frage Nummer"))
                        assertEquals(0, calls.toRoute(routes.participations).size)
                    }
                }
            } finally {
                pollRefusedNavigator = original
            }
        }

    private fun document() = kotlinx.browser.document

    @Test
    fun aWithheldWeightedReason_isNotNeededInTheList(): Promise<Unit> =
        formTest {
            // The list never asks for a result: only the closed detail does.
            val world =
                PollWorld(
                    all = listOf(pollDto(status = PollStatus.CLOSED, responseCount = 3)),
                    result = pollResult(weightedAvailable = false, reason = PollWeightedWithheldReason.ZERO_TOTAL_WEIGHT),
                )
            withList(world, "poll-list-noresult") { el, calls, routes ->
                el.chip("Geschlossen").click()
                awaitUntil("closed rows", 3000) { calls.toRoute(routes.list).size == 2 }
                assertEquals(0, calls.toRoute(routes.result).size)
            }
        }
}
