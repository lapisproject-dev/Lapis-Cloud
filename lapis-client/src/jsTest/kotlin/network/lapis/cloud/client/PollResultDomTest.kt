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
}
