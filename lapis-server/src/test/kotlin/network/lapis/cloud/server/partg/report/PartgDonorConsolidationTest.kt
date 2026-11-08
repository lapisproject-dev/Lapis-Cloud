package network.lapis.cloud.server.partg.report

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.partg.PartgAnnexStore
import network.lapis.cloud.server.partg.PartgConsolidationStore
import network.lapis.cloud.shared.domain.PartgConsolidationRules
import network.lapis.cloud.shared.domain.PartgContributionKind
import network.lapis.cloud.shared.domain.PartgPersonKind
import network.lapis.cloud.shared.domain.PartgUnit
import kotlin.uuid.Uuid

/** V1.9.290 (R5) -- statement B over all units: groups, display row, ignored broken groups and the candidate rules (never stored, never named). */
class PartgDonorConsolidationTest :
    FunSpec({
        val units = ReportFixtures.units()
        val federal = PartgUnit.FEDERAL_KEY
        val stateA = PartgUnit.State(ReportFixtures.CHAPTER_A).key()
        val stateB = PartgUnit.State(ReportFixtures.CHAPTER_B).key()
        val created = LocalDateTime(2026, 1, 10, 12, 0)

        fun donor(
            id: Uuid = Uuid.random(),
            name: String = "Erika Muster",
            postal: String? = "38100",
            country: String = "DE",
            cents: Long = 1_200_000L,
            kind: PartgPersonKind = PartgPersonKind.NATURAL,
            contribution: PartgContributionKind = PartgContributionKind.DONATION,
            updatedAt: LocalDateTime? = null,
        ) = PartgAnnexStore.DonorRow(
            id = id,
            annexId = Uuid.random(),
            personKind = kind,
            name = name,
            street = "Weg 1",
            postalCode = postal,
            city = "Ort",
            country = country,
            contributionKind = contribution,
            amountCents = cents,
            updatedAt = updatedAt,
        )

        fun annex(vararg donors: PartgAnnexStore.DonorRow) =
            PartgAnnexStore.AnnexData(
                revision = 1,
                deductions = emptyMap(),
                memberCount = null,
                donors = PartgAnnexStore.sortDonors(donors.toList()),
            )

        fun combination(
            vararg ids: Uuid,
            at: LocalDateTime = created,
        ) = PartgConsolidationStore.CombinationRow(id = Uuid.random(), createdAt = at, donorIds = ids.toList())

        test("a group is ONE person: the display row has the highest amount, the sum counts, the kinds join") {
            val a = donor(id = Uuid.parse("00000000-0000-4000-8000-00000000000a"), cents = 1_500_000L, name = "Erika Muster")
            val b =
                donor(
                    id = Uuid.parse("00000000-0000-4000-8000-00000000000b"),
                    cents = 1_100_000L,
                    name = "E. Muster",
                    contribution = PartgContributionKind.FEE,
                )
            val result =
                PartgDonorConsolidation.compute(
                    units = units,
                    annex = mapOf(federal to annex(a), stateA to annex(b)),
                    combinations = listOf(combination(a.id, b.id)),
                )
            val person = result.donors.single()
            person.totalCents shouldBe 2_600_000L
            person.name shouldBe "Erika Muster"
            person.displayDonorId shouldBe a.id.toString()
            person.contributionKind shouldBe PartgContributionKind.BOTH
            person.unitKeys shouldBe listOf(federal, stateA)
            person.donorIds.size shouldBe 2
            result.brokenCombinations shouldBe 0
        }

        test("on a tie the smaller id is the display row") {
            val a = donor(id = Uuid.parse("00000000-0000-4000-8000-0000000000aa"), cents = 1_100_000L, name = "Zweite Zeile")
            val b = donor(id = Uuid.parse("00000000-0000-4000-8000-000000000001"), cents = 1_100_000L, name = "Erste Zeile")
            val result =
                PartgDonorConsolidation.compute(
                    units = units,
                    annex = mapOf(federal to annex(a), stateA to annex(b)),
                    combinations = listOf(combination(a.id, b.id)),
                )
            result.donors.single().name shouldBe "Erste Zeile"
        }

        test("a group with fewer than two existing rows is ignored, its row stays single, and it is counted as broken") {
            val a = donor()
            val gone = Uuid.random()
            val result =
                PartgDonorConsolidation.compute(
                    units = units,
                    annex = mapOf(federal to annex(a)),
                    combinations = listOf(combination(a.id, gone)),
                )
            result.donors.single().combinationId shouldBe null
            result.brokenCombinations shouldBe 1
        }

        test("a row changed after the group was made is counted as broken (P21)") {
            val a = donor(updatedAt = LocalDateTime(2026, 1, 11, 0, 0))
            val b = donor()
            val result =
                PartgDonorConsolidation.compute(
                    units = units,
                    annex = mapOf(federal to annex(a), stateA to annex(b)),
                    combinations = listOf(combination(a.id, b.id, at = created)),
                )
            result.brokenCombinations shouldBe 1
            // the group still counts as one person
            result.donors.single().totalCents shouldBe a.amountCents + b.amountCents
        }

        test("the list is sorted by amount (descending), name, id -- in Kotlin") {
            val big = donor(cents = 9_000_000L, name = "Zeta", postal = null)
            val sameA = donor(cents = 1_100_000L, name = "Beta", postal = null)
            val sameB = donor(cents = 1_100_000L, name = "Alpha", postal = null)
            val result =
                PartgDonorConsolidation.compute(
                    units = units,
                    annex = mapOf(federal to annex(sameA, big), stateA to annex(sameB)),
                    combinations = emptyList(),
                )
            result.donors.map { it.name } shouldBe listOf("Zeta", "Alpha", "Beta")
        }

        test("candidates: diacritics and white space do not matter") {
            val a = donor(name = "Müller  Hans")
            val b = donor(name = "muller hans")
            val result =
                PartgDonorConsolidation.compute(
                    units = units,
                    annex = mapOf(federal to annex(a), stateA to annex(b)),
                    combinations = emptyList(),
                )
            result.candidateTotal shouldBe 1
            result.candidates
                .single()
                .donorIds
                .toSet() shouldBe setOf(a.id.toString(), b.id.toString())
            result.candidates.single().unitKeys shouldBe listOf(federal, stateA)
        }

        test("no candidate for a different postal code, a missing postal code, a different country, kind or unit-internal pair") {
            val base = donor(name = "Hans Muster")

            fun candidates(
                other: PartgAnnexStore.DonorRow,
                sameUnit: Boolean = false,
            ) = PartgDonorConsolidation
                .compute(
                    units = units,
                    annex =
                        if (sameUnit) {
                            mapOf(
                                federal to annex(base, other),
                            )
                        } else {
                            mapOf(federal to annex(base), stateA to annex(other))
                        },
                    combinations = emptyList(),
                ).candidateTotal
            candidates(donor(name = "Hans Muster", postal = "10115")) shouldBe 0
            candidates(donor(name = "Hans Muster", postal = null)) shouldBe 0
            candidates(donor(name = "Hans Muster", postal = "  ")) shouldBe 0
            candidates(donor(name = "Hans Muster", country = "AT")) shouldBe 0
            candidates(donor(name = "Hans Muster", kind = PartgPersonKind.LEGAL)) shouldBe 0
            candidates(donor(name = "Hans Muster"), sameUnit = true) shouldBe 0
            candidates(donor(name = "Hans Muster")) shouldBe 1
        }

        test("rows already in a group are not offered again; the candidate carries ids and units, never a name") {
            val a = donor(name = "Hans Muster")
            val b = donor(name = "Hans Muster")
            val c = donor(name = "Hans Muster")
            val result =
                PartgDonorConsolidation.compute(
                    units = units,
                    annex = mapOf(federal to annex(a), stateA to annex(b), stateB to annex(c)),
                    combinations = listOf(combination(a.id, b.id)),
                )
            result.candidateTotal shouldBe 0
            val free =
                PartgDonorConsolidation.compute(
                    units = units,
                    annex = mapOf(federal to annex(a), stateA to annex(b), stateB to annex(c)),
                    combinations = emptyList(),
                )
            free.candidates
                .single()
                .toString()
                .contains("Hans") shouldBe false
        }

        test("candidates are capped at 500 but the total is exact") {
            val federalRows = (1..600).map { donor(name = "Person $it", cents = 1_000_001L + it) }
            val stateRows = federalRows.map { donor(name = it.name, cents = 1_000_001L) }
            val result =
                PartgDonorConsolidation.compute(
                    units = units,
                    annex = mapOf(federal to annex(*federalRows.toTypedArray()), stateA to annex(*stateRows.toTypedArray())),
                    combinations = emptyList(),
                )
            result.candidateTotal shouldBe 600
            result.candidates.size shouldBe PartgConsolidationRules.MAX_CANDIDATES
        }
    })
