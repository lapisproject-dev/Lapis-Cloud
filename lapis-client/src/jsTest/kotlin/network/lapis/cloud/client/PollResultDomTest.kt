package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.PollResultDto
import network.lapis.cloud.shared.domain.PollStatus
import network.lapis.cloud.shared.domain.PollWeightedWithheldReason
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V1.9.31: the result of a closed poll -- two blocks, the order of the poll, no winner, and a sentence where a block is withheld. */
class PollResultDomTest {
    private suspend fun <T> withResult(
        id: String,
        result: PollResultDto?,
        block: suspend (HTMLElement) -> T,
    ): T =
        mountedForm(id) { root, element ->
            renderPollResult(root, pollDto(status = PollStatus.CLOSED, responseCount = result?.responseCount), result)
            block(element())
        }

    private fun HTMLElement.blocks(): List<HTMLElement> = allOf("section.lapis-poll-block")

    @Test
    fun bothBlocksAreThere_inTheOrderOfThePoll_evenIfTheCountsAreDescendingElsewhere(): Promise<Unit> =
        formTest {
            // counts 1, 2, 4 for options c, b, a: the order stays the poll's (a, b, c), never sorted by rank
            withResult("poll-result-order", pollResult()) { el ->
                assertEquals(2, el.blocks().size)
                val head = el.blocks()[0]
                val labels = head.allOf(".lapis-poll-row > div").map { it.textContent.orEmpty().trim() }.filter { it.isNotEmpty() }
                assertEquals(listOf("Ja, im Juli", "Nein, im August", "Egal"), labels)
                assertTrue(head.flatText().contains("57 % · 4 Antworten"))
                assertTrue(head.flatText().contains("29 % · 2 Antworten"))
                assertTrue(head.flatText().contains("14 % · 1 Antwort") && !head.flatText().contains("1 Antworten"))
                val weighted = el.blocks()[1]
                assertEquals(
                    listOf("Ja, im Juli", "Nein, im August", "Egal"),
                    weighted
                        .allOf(".lapis-poll-row > div")
                        .map {
                            it.textContent.orEmpty().trim()
                        }.filter { it.isNotEmpty() },
                )
                assertTrue(
                    weighted.flatText().contains("50 %") && weighted.flatText().contains("30 %") && weighted.flatText().contains("20 %"),
                )
            }
        }

    @Test
    fun noWinnerIsMarked_noBoldNoBadge(): Promise<Unit> =
        formTest {
            withResult("poll-result-nowinner", pollResult()) { el ->
                assertEquals(0, el.allOf(".fw-bold, strong, b").size, "no emphasis inside the result")
                assertEquals(0, el.allOf(".badge").size, "no winner badge")
                assertFalse(el.flatText().contains("Gewinner") || el.flatText().contains("Sieger"))
                assertEquals(6, el.allOf(".lapis-poll-bar__fill").size)
                assertEquals(3, el.allOf(".lapis-poll-bar__fill--weighted").size, "the weighted bars are paler, nothing else differs")
            }
        }

    @Test
    fun theHeader_countsTheAnswers_inSingularAndPlural(): Promise<Unit> =
        formTest {
            withResult("poll-result-header", pollResult(responseCount = 7)) { el ->
                assertTrue(el.flatText().contains("Unverbindliches Stimmungsbild · 7 Antworten"))
            }
            withResult(
                "poll-result-header-one",
                pollResult(
                    responseCount = 1,
                    headAvailable = false,
                    weightedAvailable = false,
                    reason = PollWeightedWithheldReason.TOO_FEW_RESPONSES,
                ),
            ) { el ->
                val text = el.flatText()
                assertTrue(text.contains("Unverbindliches Stimmungsbild · 1 Antwort") && !text.contains("1 Antworten"))
            }
        }

    @Test
    fun theWeightedBlock_explainsItself_always_andShowsNoAbsoluteNumbers(): Promise<Unit> =
        formTest {
            withResult("poll-result-explain", pollResult()) { el ->
                val weighted = el.blocks()[1]
                assertTrue(
                    weighted.flatText().contains(
                        "Jede Antwort zählt mit dem LTR-Stand, den die Person beim Antworten hatte – " +
                            "wer mehr beigetragen hat, zählt mehr. Deshalb kann dieses Bild vom Ergebnis nach Köpfen abweichen.",
                    ),
                )
                assertFalse(Regex("""\d+ Antwort""").containsMatchIn(weighted.flatText()), "no count in the weighted block")
            }
            withResult(
                "poll-result-explain-withheld",
                pollResult(weightedAvailable = false, reason = PollWeightedWithheldReason.ZERO_TOTAL_WEIGHT),
            ) { el ->
                assertTrue(el.blocks()[1].flatText().contains("Jede Antwort zählt mit dem LTR-Stand"), "also when withheld")
            }
        }

    @Test
    fun eachWithheldReason_hasItsOwnSentence_inTheWeightedBlockOnly(): Promise<Unit> =
        formTest {
            val expected =
                mapOf(
                    PollWeightedWithheldReason.TOO_FEW_RESPONSES to "Zu wenige Antworten für eine anonyme Auswertung (mindestens 5).",
                    PollWeightedWithheldReason.ZERO_TOTAL_WEIGHT to
                        "Keine der Antworten hatte LTR-Gewicht – eine gewichtete Auswertung ist nicht möglich.",
                    PollWeightedWithheldReason.TOO_FEW_WEIGHTED_RESPONSES to
                        "Zu wenige Antworten mit LTR-Gewicht für eine anonyme gewichtete Auswertung (mindestens 5).",
                    PollWeightedWithheldReason.SMALL_WEIGHTED_GROUP to
                        "Die gewichtete Auswertung wird zurückgehalten, damit keine einzelne Antwort erkennbar wird.",
                )
            expected.forEach { (reason, sentence) ->
                withResult("poll-result-reason-$reason", pollResult(weightedAvailable = false, reason = reason)) { el ->
                    assertTrue(el.blocks()[1].flatText().contains(sentence), "$reason: ${el.blocks()[1].flatText()}")
                    assertTrue(el.blocks()[0].flatText().contains("57 %"), "the head block still shows its numbers")
                    assertFalse(el.blocks()[0].flatText().contains(sentence))
                }
            }
        }

    @Test
    fun aWithheldHeadResult_putsTheSentenceInBothBlocks_andNoNumbers(): Promise<Unit> =
        formTest {
            withResult(
                "poll-result-headwithheld",
                pollResult(
                    responseCount = 3,
                    headAvailable = false,
                    weightedAvailable = false,
                    reason = PollWeightedWithheldReason.TOO_FEW_RESPONSES,
                ),
            ) { el ->
                val sentence = "Zu wenige Antworten für eine anonyme Auswertung (mindestens 5)."
                assertEquals(2, el.blocks().size, "the structure stays calm: both blocks exist")
                el.blocks().forEach { assertTrue(it.flatText().contains(sentence)) }
                assertEquals(0, el.allOf(".lapis-poll-bar__fill").size, "no bars, no partial numbers")
                assertFalse(el.flatText().contains("%  ") || Regex("""\d+ %""").containsMatchIn(el.flatText()))
            }
        }

    @Test
    fun aWeightedResultWithoutAReason_neverRendersABlank(): Promise<Unit> =
        formTest {
            withResult("poll-result-noreason", pollResult(weightedAvailable = false, reason = null)) { el ->
                assertTrue(el.blocks()[1].flatText().contains("Die gewichtete Auswertung ist nicht verfügbar."))
            }
        }

    @Test
    fun aMissingResult_saysItCouldNotBeLoaded(): Promise<Unit> =
        formTest {
            withResult("poll-result-missing", null) { el ->
                assertTrue(el.flatText().contains("Das Ergebnis konnte nicht geladen werden."))
                assertEquals(0, el.blocks().size)
            }
        }

    @Test
    fun anUnknownOptionIdInTheResult_isSkippedSilently(): Promise<Unit> =
        formTest {
            val result = pollResult(head = listOf("o-a" to 3, "o-zzz" to 4), weighted = listOf("o-a" to 100, "o-zzz" to 0))
            withResult("poll-result-unknown", result) { el ->
                assertFalse(el.flatText().contains("o-zzz"))
                assertEquals(3, el.blocks()[0].allOf(".lapis-poll-row").size, "exactly the options of the poll")
            }
        }

    // ── V1.9.41: the results of the consensus kinds ──────────────────────────────────────────────────────────────

    private suspend fun <T> withRatingResult(
        id: String,
        poll: network.lapis.cloud.shared.domain.PollDto,
        result: PollResultDto?,
        block: suspend (HTMLElement) -> T,
    ): T =
        mountedForm(id) { root, element ->
            renderPollResult(root, poll, result)
            block(element())
        }

    private fun decisionPoll() = pollSkDto(status = PollStatus.CLOSED, responseCount = 7)

    private fun HTMLElement.badges(): List<String> = allOf(".badge").map { it.textContent.orEmpty().trim() }

    @Test
    fun aDecision_marksTheLeadingOption_withTheNeutralInfoBadge_andShowsTheFiguresPerOption(): Promise<Unit> =
        formTest {
            val result =
                pollRatingResult(
                    options =
                        listOf(
                            pollRatingOption("o-a", rank = 1, cumulative = 7, mean = 1.0, max = 3, index = 0.1),
                            pollRatingOption("o-p", rank = 2, cumulative = 24, mean = 3.4, max = 6, top = 1, index = 0.34),
                            pollRatingOption("o-b", rank = 3, cumulative = 49, mean = 7.0, max = 10, top = 2, index = 0.7),
                        ),
                )
            withRatingResult("poll-rating-decision", decisionPoll(), result) { el ->
                val text = el.flatText()
                assertTrue(text.contains("Unverbindliches Stimmungsbild · 7 Antworten"))
                assertTrue(text.contains("Geringster Widerstand: Im Juli"))
                val leading = el.allOf(".badge").first { it.textContent?.trim() == "Geringster Widerstand" }
                assertTrue(leading.className.contains("text-bg-info"), "neutral info tone, not the success colour")
                assertFalse(el.allOf(".text-bg-success").isNotEmpty(), "no success colour anywhere")
                assertTrue(text.contains("Ø 3,4") && text.contains("Ø 1,0") && text.contains("Ø 7,0"))
                assertTrue(text.contains("Summe 24 · 10er: 1"))
                assertTrue(text.contains("Gruppenkonflikt: Tragfähiger Konsens (0,10)"))
                assertTrue(text.contains("Gruppenkonflikt: Mit Bedenken (0,34)"))
                assertTrue(text.contains("Gruppenkonflikt: Warnsignal (0,70)"))
                assertEquals(listOf("Starker Einwand"), el.badges().filter { it == "Starker Einwand" })
                assertTrue(text.contains("Höchster Einzelwert: 10. Das ist ein starker Einwand."))
                assertEquals(
                    listOf("1", "P", "2"),
                    el.allOf(".lapis-sk-num").map {
                        it.textContent.orEmpty().trim()
                    },
                    "the server order, numbered like the booth",
                )
                assertEquals(0, el.allOf(".lapis-poll-bar, .lapis-election-bar").size, "no bars")
            }
        }

    @Test
    fun aDecision_wherePassiveWins_saysNothingChanges_andNamesTheTie(): Promise<Unit> =
        formTest {
            val result =
                pollRatingResult(
                    outcome = network.lapis.cloud.shared.domain.PollDecisionOutcome.NO_CHANGE_WINS,
                    winner = "o-p",
                    tieAtLowest = true,
                    options =
                        listOf(
                            pollRatingOption("o-p", rank = 1, cumulative = 12, mean = 1.7, tied = true),
                            pollRatingOption("o-a", rank = 1, cumulative = 12, mean = 1.7, tied = true),
                            pollRatingOption("o-b", rank = 3, cumulative = 40, mean = 5.7),
                        ),
                )
            withRatingResult("poll-rating-passive", decisionPoll(), result) { el ->
                val text = el.flatText()
                assertTrue(text.contains("Keine Änderung hat den geringsten Widerstand."))
                assertTrue(text.contains("Gleichstand beim kumulierten Widerstand: Bei Gleichstand bleibt es bei keiner Änderung."))
                assertEquals(1, el.badges().count { it == "Geringster Widerstand" }, "the passive row carries the mark")
            }
        }

    @Test
    fun aDecision_withoutAClearResult_marksNoOne_andTheLowestMaximumTiebreakIsNamed(): Promise<Unit> =
        formTest {
            val none =
                pollRatingResult(
                    outcome = network.lapis.cloud.shared.domain.PollDecisionOutcome.NO_CLEAR_RESULT,
                    winner = null,
                    tieAtLowest = true,
                    options =
                        listOf(
                            pollRatingOption("o-a", rank = 1, cumulative = 12, mean = 1.7, tied = true),
                            pollRatingOption("o-b", rank = 1, cumulative = 12, mean = 1.7, tied = true),
                            pollRatingOption("o-p", rank = 3, cumulative = 40, mean = 5.7),
                        ),
                )
            withRatingResult("poll-rating-noclear", decisionPoll(), none) { el ->
                assertTrue(el.flatText().contains("Gleichstand, kein eindeutiges Ergebnis."))
                assertEquals(0, el.badges().count { it == "Geringster Widerstand" })
            }
            val byMax =
                pollRatingResult(
                    decidedByLowestMax = true,
                    tieAtLowest = true,
                    options =
                        listOf(
                            pollRatingOption("o-a", rank = 1, cumulative = 12, mean = 1.7, max = 2, tied = true),
                            pollRatingOption("o-b", rank = 1, cumulative = 12, mean = 1.7, max = 8, tied = true),
                            pollRatingOption("o-p", rank = 3, cumulative = 40, mean = 5.7),
                        ),
                )
            withRatingResult("poll-rating-bymax", decisionPoll(), byMax) { el ->
                assertTrue(el.flatText().contains("Gleichstand beim kumulierten Widerstand, entschieden durch den geringsten Höchstwert."))
            }
        }

    @Test
    fun aRanking_listsSharedRanks_withoutAnyBadgeOrBar(): Promise<Unit> =
        formTest {
            val poll =
                pollSkDto(
                    kind = network.lapis.cloud.shared.domain.PollKind.SK_PRIORITY,
                    status = PollStatus.CLOSED,
                    responseCount = 7,
                    options =
                        listOf(
                            pollOptionDto("o-a", "Im Juli", 0),
                            pollOptionDto("o-b", "Im August", 1),
                            pollOptionDto("o-c", "Im September", 2),
                        ),
                )
            val result =
                pollRatingResult(
                    kind = network.lapis.cloud.shared.domain.PollKind.SK_PRIORITY,
                    options =
                        listOf(
                            pollRatingOption("o-c", rank = 1, cumulative = 5, mean = 0.7),
                            pollRatingOption("o-a", rank = 2, cumulative = 20, mean = 2.9, tied = true),
                            pollRatingOption("o-b", rank = 2, cumulative = 20, mean = 2.9, tied = true),
                        ),
                )
            withRatingResult("poll-rating-ranking", poll, result) { el ->
                assertTrue(el.flatText().contains("Rangliste nach Widerstand"))
                assertEquals(0, el.badges().size, "no badge in a ranking")
                assertEquals(0, el.allOf(".lapis-poll-bar, .lapis-election-bar").size)
                val ranks = el.allOf(".lapis-sk-rank").map { it.flatText() }
                assertTrue(ranks[0].startsWith("1.") && ranks[0].contains("Im September"))
                assertTrue(ranks[1].startsWith("2.") && ranks[1].contains("gleichauf"))
                assertTrue(ranks[2].startsWith("2.") && ranks[2].contains("gleichauf"))
                assertFalse(ranks[0].contains("gleichauf"))
            }
        }

    @Test
    fun belowTheMinimum_onlyTheAnswerCountAndTheWithheldSentenceAreShown(): Promise<Unit> =
        formTest {
            val result = pollRatingResult(responseCount = 3, available = false, options = emptyList())
            withRatingResult("poll-rating-withheld", pollSkDto(status = PollStatus.CLOSED, responseCount = 3), result) { el ->
                val text = el.flatText()
                assertTrue(text.contains("Unverbindliches Stimmungsbild · 3 Antworten"))
                assertTrue(text.contains("Zu wenige Antworten für eine anonyme Auswertung (mindestens 5)."))
                assertEquals(0, el.allOf(".lapis-sk-rank").size)
            }
        }

    @Test
    fun theDistribution_isCollapsedUntilSwitchedOn(): Promise<Unit> =
        formTest {
            val result =
                pollRatingResult(
                    options =
                        listOf(
                            pollRatingOption("o-a", rank = 1, cumulative = 7, mean = 1.0, distribution = mapOf(1 to 7)),
                            pollRatingOption("o-b", rank = 2, cumulative = 40, mean = 5.7),
                            pollRatingOption("o-p", rank = 3, cumulative = 49, mean = 7.0),
                        ),
                )
            withRatingResult("poll-rating-distribution", decisionPoll(), result) { el ->
                assertEquals(0, el.allOf(".lapis-sk-hist").size)
                val toggle = el.allOf("button").first { it.textContent?.trim() == "Verteilung anzeigen" }
                assertEquals("false", toggle.getAttribute("aria-expanded"))
                toggle.click()
                awaitUntil("histogram", 2000) { el.allOf(".lapis-sk-hist").size == 1 }
                assertTrue(
                    el
                        .allOf(".lapis-sk-hist")
                        .single()
                        .flatText()
                        .contains("7"),
                )
            }
        }
}
