package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PollStatus
import network.lapis.cloud.shared.domain.PollWeightedWithheldReason
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V1.9.31: the detail view of a poll, mounted for real with a stubbed server. */
class PollDetailDomTest {
    private val ctx = PollUiContext(currentMemberId = "m-1")

    private suspend fun <T> withDetail(
        world: PollWorld,
        id: String,
        block: suspend (HTMLElement, List<RecordedRequest>, PollRoutes) -> T,
    ): T {
        AppState.setSession(pollSession(MemberStatus.ACTIVE))
        val routes = pollRoutes()
        return withFetchStub(respond = world.respond(routes)) { calls ->
            mountedForm(id) { root, element ->
                renderPollDetail(root, "p1", ctx)
                awaitUntil("detail rendered", 3000) { element().flatText().contains(world.poll.question) }
                block(element(), calls, routes)
            }
        }
    }

    private suspend fun awaitModalButton(text: String): HTMLElement {
        awaitUntil("the modal shows '$text'", 2000) { lastOpenModal().allOf("button").any { it.textContent?.trim() == text } }
        return lastOpenModal().buttonNamed(text)
    }

    @Test
    fun anOpenPoll_forAnEligibleMember_showsTheBooth_notTheResult_andNoCount(): Promise<Unit> =
        formTest {
            val world = PollWorld(pollDto(responseCount = 9), pollParticipation(canRespond = true))
            withDetail(world, "poll-detail-open") { el, calls, routes ->
                assertTrue(el.allOf("input[type=radio]").size == 3, "the booth with one radio per option")
                assertTrue(el.hasButton("Weiter"))
                assertTrue(el.flatText().contains("Unverbindlich – ein Stimmungsbild, kein Beschluss."))
                assertTrue(el.allOf(".alert-secondary").isNotEmpty())
                assertFalse(el.flatText().contains("9 Antworten"))
                assertEquals(0, calls.toRoute(routes.result).size, "an open poll has no result to ask for")
                assertTrue(el.flatText().contains("Gestartet von Clara Chair"))
                assertTrue(el.flatText().contains("endet am"))
            }
        }

    @Test
    fun anOpenPoll_aMemberHasAnswered_saysSo_andOffersNoBooth(): Promise<Unit> =
        formTest {
            val world = PollWorld(pollDto(), pollParticipation(hasResponded = true))
            withDetail(world, "poll-detail-answered") { el, _, _ ->
                assertTrue(el.flatText().contains("Sie haben an dieser Umfrage teilgenommen."))
                assertEquals(0, el.allOf("input[type=radio]").size)
                assertTrue(el.flatText().contains("Ja, im Juli"), "the options are listed")
            }
        }

    @Test
    fun anOpenPoll_forSomeoneNotEligible_saysOnlyActiveMembersMayAnswer(): Promise<Unit> =
        formTest {
            val world = PollWorld(pollDto(), pollParticipation(eligible = false))
            withDetail(world, "poll-detail-ineligible") { el, _, _ ->
                assertTrue(el.flatText().contains("Nur aktive Mitglieder können antworten."))
                assertEquals(0, el.allOf("input[type=radio]").size)
            }
        }

    @Test
    fun aClosedPoll_showsTheResult_andTheCount_butNoBooth(): Promise<Unit> =
        formTest {
            val world =
                PollWorld(
                    pollDto(status = PollStatus.CLOSED, responseCount = 7),
                    pollParticipation(hasResponded = true, canRespond = false),
                    pollResult(),
                )
            withDetail(world, "poll-detail-closed") { el, calls, routes ->
                assertTrue(el.flatText().contains("Ergebnis"))
                assertTrue(el.flatText().contains("Nach Köpfen") && el.flatText().contains("Nach LTR-Gewicht"))
                assertTrue(el.flatText().contains("7 Antworten"))
                assertEquals(0, el.allOf("input[type=radio]").size)
                assertEquals(1, calls.toRoute(routes.result).size)
            }
        }

    @Test
    fun anAbortedPoll_saysItsAnswersAreNeverEvaluated(): Promise<Unit> =
        formTest {
            val world = PollWorld(pollDto(status = PollStatus.ABORTED), pollParticipation(canRespond = false))
            withDetail(world, "poll-detail-aborted") { el, calls, routes ->
                assertTrue(el.flatText().contains("Diese Umfrage wurde abgebrochen. Die Antworten werden nie ausgewertet."))
                assertEquals(0, calls.toRoute(routes.result).size)
                assertTrue(el.allOf(".alert-secondary").isNotEmpty(), "the non-binding note is always there")
            }
        }

    @Test
    fun closeAndAbort_areOfferedOnlyToThoseWhoManage(): Promise<Unit> =
        formTest {
            withDetail(PollWorld(pollDto(canManage = false), pollParticipation()), "poll-detail-nomanage") { el, _, _ ->
                assertFalse(el.hasButton("Umfrage schließen") || el.hasButton("Umfrage abbrechen"))
            }
            withDetail(PollWorld(pollDto(canManage = true), pollParticipation()), "poll-detail-manage") { el, _, _ ->
                assertTrue(el.hasButton("Umfrage schließen") && el.hasButton("Umfrage abbrechen"))
            }
            withDetail(
                PollWorld(pollDto(status = PollStatus.CLOSED, canManage = true, responseCount = 6), pollParticipation(), pollResult()),
                "poll-detail-manage-closed",
            ) { el, _, _ ->
                assertFalse(
                    el.hasButton("Umfrage schließen") || el.hasButton("Umfrage abbrechen"),
                    "a finished poll cannot be managed any more",
                )
            }
        }

    @Test
    fun closing_asksFirst_thenCloses_andReloadsTheDetail(): Promise<Unit> =
        formTest {
            val world = PollWorld(pollDto(canManage = true), pollParticipation(canRespond = false))
            withDetail(world, "poll-detail-close") { el, calls, routes ->
                el.buttonNamed("Umfrage schließen").click()
                assertEquals(0, calls.toRoute(routes.close).size, "nothing before the confirmation")
                world.poll = pollDto(status = PollStatus.CLOSED, canManage = true, responseCount = 6)
                world.result = pollResult(responseCount = 6)
                awaitModalButton("Umfrage schließen").click()
                awaitUntil("closed", 3000) { calls.toRoute(routes.close).size == 1 }
                awaitUntil("reloaded with the result", 3000) { el.flatText().contains("Nach Köpfen") }
                assertEquals(2, calls.toRoute(routes.get).size, "the detail was read again")
            }
        }

    @Test
    fun aborting_needsTheTypedWord_aWrongWordKeepsTheButtonDisabled(): Promise<Unit> =
        formTest {
            val world = PollWorld(pollDto(canManage = true), pollParticipation(canRespond = false))
            withDetail(world, "poll-detail-abort") { el, calls, routes ->
                el.buttonNamed("Umfrage abbrechen").click()
                val confirm = awaitModalButton("Umfrage endgültig abbrechen")
                val modal = lastOpenModal()
                assertTrue(modal.flatText().contains("Zum Bestätigen bitte \"ABBRECHEN\" eingeben:"))
                assertTrue(modal.hasButton("Zurück") && !modal.hasButton("Abbrechen"), "the close button must not say 'Abbrechen' as well")
                assertTrue(confirm.hasAttribute("disabled"))
                val input = modal.querySelector("input") as HTMLInputElement
                input.value = "abbrechen"
                input.dispatchEvent(
                    org.w3c.dom.events
                        .Event("input"),
                )
                assertTrue(confirm.hasAttribute("disabled"), "the wrong word keeps it disabled")
                input.value = "ABBRECHEN"
                input.dispatchEvent(
                    org.w3c.dom.events
                        .Event("input"),
                )
                awaitUntil("enabled", 1500) { !confirm.hasAttribute("disabled") }
                world.poll = pollDto(status = PollStatus.ABORTED, canManage = true)
                confirm.click()
                awaitUntil("aborted", 3000) { calls.toRoute(routes.abort).size == 1 }
                awaitUntil("reloaded", 3000) { el.flatText().contains("Diese Umfrage wurde abgebrochen.") }
            }
        }

    @Test
    fun aConflictWhileClosing_reloads_andNeverShowsTheServersText(): Promise<Unit> =
        formTest {
            val routes = pollRoutes()
            val world = PollWorld(pollDto(canManage = true), pollParticipation(canRespond = false))
            world.failures[routes.close] = CONFLICT_EXCEPTION
            val shown = mutableListOf<String>()
            val original = pollErrorSink
            pollErrorSink = { shown += it }
            try {
                withDetail(world, "poll-detail-close-conflict") { el, calls, _ ->
                    el.buttonNamed("Umfrage schließen").click()
                    world.poll = pollDto(status = PollStatus.CLOSED, canManage = true, responseCount = 6)
                    world.result = pollResult(responseCount = 6)
                    awaitModalButton("Umfrage schließen").click()
                    awaitUntil("fixed text", 3000) { shown.isNotEmpty() }
                    assertEquals(listOf("Der Stand hat sich geändert."), shown)
                    awaitUntil("reloaded", 3000) { calls.toRoute(routes.get).size >= 2 && el.flatText().contains("Nach Köpfen") }
                    assertFalse(el.flatText().contains("simulated"))
                }
            } finally {
                pollErrorSink = original
            }
        }

    @Test
    fun theWithheldReasons_areNotNeededForTheDetailItself(): Promise<Unit> =
        formTest {
            val world =
                PollWorld(
                    pollDto(status = PollStatus.CLOSED, responseCount = 3),
                    pollParticipation(canRespond = false),
                    pollResult(
                        responseCount = 3,
                        headAvailable = false,
                        weightedAvailable = false,
                        reason = PollWeightedWithheldReason.TOO_FEW_RESPONSES,
                    ),
                )
            withDetail(world, "poll-detail-withheld") { el, _, _ ->
                assertEquals(
                    2,
                    el.flatText().split("Zu wenige Antworten für eine anonyme Auswertung (mindestens 5).").size - 1,
                    "both blocks say it",
                )
            }
        }

    // ── V1.9.41: consensus polls ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun anOpenConsensusPoll_showsTheRatingBooth_notTheChoiceBooth(): Promise<Unit> =
        formTest {
            val world = PollWorld(pollSkDto(), pollParticipation(canRespond = true))
            withDetail(world, "poll-detail-sk-open") { el, calls, routes ->
                assertEquals(33, el.allOf("input[type=radio]").size, "eleven scale fields for each of the three options")
                assertTrue(el.hasButton("Prüfen"))
                assertFalse(el.hasButton("Weiter"))
                assertEquals(0, calls.toRoute(routes.result).size)
            }
        }

    @Test
    fun aConsensusPollTheMemberAnswered_listsTheOptionsNumbered_passiveFirst_withTheirExplanation(): Promise<Unit> =
        formTest {
            val world = PollWorld(pollSkDto(), pollParticipation(hasResponded = true))
            withDetail(world, "poll-detail-sk-answered") { el, _, _ ->
                assertTrue(el.flatText().contains("Sie haben an dieser Umfrage teilgenommen."))
                assertEquals(0, el.allOf("input[type=radio]").size)
                assertEquals(listOf("P", "1", "2"), el.allOf(".lapis-sk-num").map { it.textContent.orEmpty().trim() })
                assertTrue(el.flatText().contains("Keine Änderung") && el.flatText().contains("Passivlösung"))
                assertTrue(el.flatText().contains("Wetter ist meist besser."))
            }
        }

    @Test
    fun aClosedConsensusPoll_asksForTheResult_andShowsTheRatingResult(): Promise<Unit> =
        formTest {
            val poll = pollSkDto(status = PollStatus.CLOSED, responseCount = 7)
            val result =
                pollRatingResult(
                    options =
                        listOf(
                            pollRatingOption("o-a", rank = 1, cumulative = 7, mean = 1.0),
                            pollRatingOption("o-b", rank = 2, cumulative = 40, mean = 5.7),
                            pollRatingOption("o-p", rank = 3, cumulative = 49, mean = 7.0),
                        ),
                )
            val world = PollWorld(poll, pollParticipation(hasResponded = true, canRespond = false), result = result)
            withDetail(world, "poll-detail-sk-closed") { el, calls, routes ->
                awaitUntil("rating result", 3000) { el.flatText().contains("Geringster Widerstand: Im Juli") }
                assertEquals(1, calls.toRoute(routes.result).size)
                assertEquals(0, el.allOf(".lapis-poll-block").size, "no head/weighted blocks for a consensus poll")
            }
        }
}
