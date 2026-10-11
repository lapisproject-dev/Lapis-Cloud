package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.ElectionResultDto
import network.lapis.cloud.shared.domain.IncumbentPolicy
import network.lapis.cloud.shared.domain.SeatOutcome
import network.lapis.cloud.shared.domain.SeatOutcomeDto
import network.lapis.cloud.shared.domain.SeatOutcomeReason
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.103: the "Amtsübernahme" section of an election result -- every line is a sign plus a sentence (never colour alone), the
 * administration-only lines carry the way to the committee, an ordinary member sees one neutral sentence instead of names and reasons,
 * the acceptance sentence is always there, and a legacy election shows nothing new.
 */
class ElectionSeatOutcomeDomTest {
    private val day = LocalDate(2026, 10, 11)

    private fun outcome(
        outcome: SeatOutcome,
        name: String = "Erika Beispiel",
        reason: SeatOutcomeReason? = null,
        role: CommitteeRole = CommitteeRole.CHAIR,
    ) = SeatOutcomeDto(
        memberId = "p-$name",
        memberDisplayName = name,
        committeeId = "c9",
        committeeName = "Vorstand",
        role = role,
        outcome = outcome,
        reason = reason,
        effectiveOn = day,
    )

    private fun result(
        outcomes: List<SeatOutcomeDto>,
        policy: IncumbentPolicy? = IncumbentPolicy.REPLACE,
        tie: Boolean = false,
        pending: Boolean = false,
    ) = ElectionResultDto(
        electionId = "e1",
        winnerOptionIds = emptyList(),
        tie = tie,
        majorityMet = null,
        perOptionVotes = emptyMap(),
        incumbentPolicy = policy,
        seatOutcomes = outcomes,
        pendingAdminReview = pending,
    )

    private val acceptance = "Ob die Gewählten die Wahl annehmen, wird außerhalb des Systems festgehalten"

    @Test
    fun anAdministrator_seesEveryOutcome_asASignPlusASentence(): Promise<Unit> =
        formTest {
            mountedForm("seat-outcome-admin") { root, element ->
                renderSeatSuccessionSection(
                    root,
                    result(
                        listOf(
                            outcome(SeatOutcome.SEATED, "Neu Gewählt"),
                            outcome(SeatOutcome.CONTINUED, "Wieder Gewählt"),
                            outcome(SeatOutcome.ENDED, "Bisherige Person"),
                            outcome(SeatOutcome.SKIPPED, "Nicht Aktiv", SeatOutcomeReason.MEMBER_NOT_ACTIVE),
                            outcome(SeatOutcome.PREDECESSOR_KEPT, "Bleibt Vorerst", SeatOutcomeReason.WINNER_SKIPPED),
                        ),
                    ),
                )
                val el = element()
                assertEquals("Amtsübernahme", el.querySelector("h2")?.textContent?.trim())
                val rows = el.allOf("li")
                assertEquals(5, rows.size)
                rows.forEach { row ->
                    assertEquals(
                        "true",
                        row.querySelector("span")?.getAttribute("aria-hidden"),
                        "the sign is decoration, the sentence says it",
                    )
                    assertTrue(
                        row.textContent
                            .orEmpty()
                            .trim()
                            .length > 10,
                    )
                }
                val text = el.flatText()
                assertTrue(text.contains("Neu Gewählt ist seit 11.10.2026 in der Rolle „Vorsitz“ im Gremium „Vorstand“."), text)
                assertTrue(text.contains("Wieder Gewählt bleibt in der Rolle „Vorsitz“ im Gremium „Vorstand“."), text)
                assertTrue(
                    text.contains("Bisherige Person: Rolle „Vorsitz“ im Gremium „Vorstand“ beendet am 11.10.2026 (Nachfolge durch Wahl)."),
                    text,
                )
                assertTrue(
                    text.contains("Nicht Aktiv wurde nicht übernommen: kein aktives Mitglied. Bitte im Gremium „Vorstand“ entscheiden."),
                    text,
                )
                assertTrue(text.contains("Bleibt Vorerst wurde nicht beendet, weil eine gewählte Person übersprungen wurde."), text)
                assertEquals(
                    2,
                    el.allOf("button").count { it.textContent?.trim() == "Zum Gremium" },
                    "only the administration lines link on",
                )
                assertTrue(text.contains(acceptance))
                assertFalse(text.contains("Die Besetzung wird von der Verwaltung geprüft."))
            }
        }

    @Test
    fun anOrdinaryMember_seesOneNeutralSentence_insteadOfNamesAndReasons(): Promise<Unit> =
        formTest {
            mountedForm("seat-outcome-member") { root, element ->
                renderSeatSuccessionSection(root, result(listOf(outcome(SeatOutcome.SEATED, "Neu Gewählt")), pending = true))
                val text = element().flatText()
                assertTrue(text.contains("Neu Gewählt ist seit"), text)
                assertTrue(text.contains("Die Besetzung wird von der Verwaltung geprüft."), text)
                assertFalse(text.contains("Bitte im Gremium"), "no administration wording")
                assertEquals(0, element().allOf("button").size)
                assertTrue(text.contains(acceptance), "the acceptance sentence is always there")
            }
        }

    @Test
    fun aTie_saysNoOfficeWasTaken_andStillShowsTheAcceptanceSentence(): Promise<Unit> =
        formTest {
            mountedForm("seat-outcome-tie") { root, element ->
                renderSeatSuccessionSection(root, result(emptyList(), tie = true))
                val text = element().flatText()
                assertTrue(text.contains("Kein eindeutiges Ergebnis: Es wurde kein Amt übernommen."), text)
                assertEquals(0, element().allOf("li").size)
                assertTrue(text.contains(acceptance))
            }
        }

    @Test
    fun aLegacyElection_hasNoSection(): Promise<Unit> =
        formTest {
            mountedForm("seat-outcome-legacy") { root, element ->
                renderSeatSuccessionSection(root, result(emptyList(), policy = null))
                assertEquals("", element().textContent.orEmpty().trim())
                assertNull(element().querySelector("h2"))
            }
        }

    @Test
    fun namesAreRenderedAsText_neverAsMarkup(): Promise<Unit> =
        formTest {
            mountedForm("seat-outcome-xss") { root, element ->
                renderSeatSuccessionSection(root, result(listOf(outcome(SeatOutcome.SEATED, "<img src=x onerror=window.__pwned=1>"))))
                assertNull(element().querySelector("img"))
                assertTrue(element().textContent.orEmpty().contains("<img src=x onerror=window.__pwned=1>"))
            }
        }

    @Test
    fun theLaterStartingSeats_haveTheirOwnSentences() {
        val skipped = seatOutcomeLine(outcome(SeatOutcome.SKIPPED, "A", SeatOutcomeReason.SEAT_STARTS_LATER))
        assertTrue(skipped.text.contains("besteht schon ein Sitz, der erst später beginnt"), skipped.text)
        assertTrue(skipped.adminOnly)
        val kept = seatOutcomeLine(outcome(SeatOutcome.PREDECESSOR_KEPT, "B", SeatOutcomeReason.SEAT_STARTS_LATER))
        assertTrue(kept.text.contains("beginnt erst nach der Auszählung"), kept.text)
        assertTrue(seatOutcomeLine(outcome(SeatOutcome.SEATED)).adminOnly.not())
    }
}
