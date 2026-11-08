package network.lapis.cloud.server.partg.report

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.partg.PartgAnnexStore
import network.lapis.cloud.server.partg.PartgConsolidationStore
import network.lapis.cloud.shared.domain.PartgAnnexRules
import network.lapis.cloud.shared.domain.PartgContributionKind
import network.lapis.cloud.shared.domain.PartgFindingCode
import network.lapis.cloud.shared.domain.PartgFindingDto
import network.lapis.cloud.shared.domain.PartgFindingLevel
import network.lapis.cloud.shared.domain.PartgFindingTarget
import network.lapis.cloud.shared.domain.PartgPersonKind
import network.lapis.cloud.shared.domain.PartgTransferKind
import network.lapis.cloud.shared.domain.PartgUnit
import kotlin.uuid.Uuid

/** V1.9.290 (R5) -- P17..P21: the boundary of P17 (strictly more than 100 cents), P18, P19, P20, P21, the shared cap and the content rule. */
class PartgPlausibilityR5Test :
    FunSpec({
        val year = ReportFixtures.YEAR
        val units = ReportFixtures.units()
        val federal = PartgUnit.FEDERAL_KEY
        val stateA = PartgUnit.State(ReportFixtures.CHAPTER_A).key()
        val stateB = PartgUnit.State(ReportFixtures.CHAPTER_B).key()

        fun pair(
            kind: PartgTransferKind,
            payer: String,
            payee: String,
            payerAmount: Long?,
            payeeAmount: Long?,
        ) = PartgConsolidationStore.TransferRow(
            id = Uuid.random(),
            kind = kind,
            payerUnitKey = payer,
            payeeUnitKey = payee,
            payerAmountCents = payerAmount,
            payeeAmountCents = payeeAmount,
            note = null,
        )

        fun data(
            vararg pairs: PartgConsolidationStore.TransferRow,
            combinations: List<PartgConsolidationStore.CombinationRow> = emptyList(),
        ) = mapOf(year to PartgConsolidationStore.ConsolidationData(revision = 1, transfers = pairs.toList(), combinations = combinations))

        fun enteredOf(
            federalValues: Map<String, Long>,
            stateAValues: Map<String, Long> = emptyMap(),
        ) = mapOf(year to mapOf(federal to federalValues, stateA to stateAValues))

        fun findings(
            entered: Map<Int, Map<String, Map<String, Long>>>,
            consolidation: Map<Int, PartgConsolidationStore.ConsolidationData>,
            annex: Map<String, PartgAnnexStore.AnnexData> = emptyMap(),
        ): List<PartgFindingDto> {
            val donors =
                PartgDonorConsolidation.compute(
                    units = units,
                    annex = annex,
                    combinations = consolidation[year]?.combinations.orEmpty(),
                )
            return PartgConsolidation
                .findings(year = year, units = units, entered = entered, consolidation = consolidation, donors = donors)
                .findings
        }

        test("P17: 100 cents of difference raise nothing, 101 cents do") {
            val onTheLine =
                findings(
                    entered = enteredOf(federalValues = mapOf("A03" to 100_100L), stateAValues = mapOf("E10" to 100_000L)),
                    consolidation = data(pair(PartgTransferKind.SUBSIDY, federal, stateA, 100_000L, 100_000L)),
                )
            onTheLine.filter { it.code == PartgFindingCode.P17 } shouldBe emptyList()
            val over =
                findings(
                    entered = enteredOf(federalValues = mapOf("A03" to 100_101L), stateAValues = mapOf("E10" to 100_000L)),
                    consolidation = data(pair(PartgTransferKind.SUBSIDY, federal, stateA, 100_000L, 100_000L)),
                )
            over.filter { it.code == PartgFindingCode.P17 }.map { it.unitKey to it.positionCode } shouldContainExactly
                listOf(federal to "A03")
            over.first { it.code == PartgFindingCode.P17 }.level shouldBe PartgFindingLevel.CHECK
            over.first { it.code == PartgFindingCode.P17 }.target shouldBe PartgFindingTarget.CONSOLIDATION
        }

        test("P17: a pair side whose position is not entered raises the hint") {
            val result =
                findings(
                    entered = enteredOf(federalValues = mapOf("E01" to 1L), stateAValues = mapOf("E10" to 100_000L)),
                    consolidation = data(pair(PartgTransferKind.SUBSIDY, federal, stateA, 100_000L, 100_000L)),
                )
            result.filter { it.code == PartgFindingCode.P17 }.map { it.unitKey to it.positionCode } shouldContainExactly
                listOf(federal to "A03")
        }

        test("P18: a pair with one amount or with a unit without data, counted per payer unit") {
            val result =
                findings(
                    entered = enteredOf(federalValues = mapOf("A03" to 300_000L), stateAValues = mapOf("E10" to 100_000L)),
                    consolidation =
                        data(
                            pair(PartgTransferKind.SUBSIDY, federal, stateA, 100_000L, null),
                            pair(PartgTransferKind.BALANCE, federal, stateB, 5L, 5L),
                        ),
                )
            val p18 = result.single { it.code == PartgFindingCode.P18 }
            p18.unitKey shouldBe federal
            p18.count shouldBe 2
            p18.level shouldBe PartgFindingLevel.CHECK
        }

        test("P19: a transfer position above zero without a pair, INFO; zero or entered-as-nothing raises none") {
            val result =
                findings(
                    entered = enteredOf(federalValues = mapOf("A03" to 1L, "E10" to 0L)),
                    consolidation = emptyMap(),
                )
            result.filter { it.code == PartgFindingCode.P19 }.map { it.unitKey to it.positionCode } shouldContainExactly
                listOf(federal to "A03")
            result.first { it.code == PartgFindingCode.P19 }.level shouldBe PartgFindingLevel.INFO
        }

        test("P20 (INFO, count of candidates) and P21 (CHECK, count of broken groups) carry no unit and no name") {
            fun donor(unitName: String) =
                PartgAnnexStore.DonorRow(
                    id = Uuid.random(),
                    annexId = Uuid.random(),
                    personKind = PartgPersonKind.NATURAL,
                    name = unitName,
                    street = null,
                    postalCode = "38100",
                    city = null,
                    country = "DE",
                    contributionKind = PartgContributionKind.DONATION,
                    amountCents = 1_200_000L,
                )

            fun annexOf(vararg d: PartgAnnexStore.DonorRow) =
                PartgAnnexStore.AnnexData(revision = 1, deductions = emptyMap(), memberCount = null, donors = d.toList())
            val one = donor("Hans Geheim")
            val two = donor("Hans Geheim")
            val result =
                findings(
                    entered = emptyMap(),
                    consolidation =
                        data(
                            combinations =
                                listOf(
                                    PartgConsolidationStore.CombinationRow(
                                        id = Uuid.random(),
                                        createdAt = LocalDateTime(2026, 1, 1, 0, 0),
                                        donorIds = listOf(Uuid.random()),
                                    ),
                                ),
                        ),
                    annex = mapOf(federal to annexOf(one), stateA to annexOf(two)),
                )
            val p20 = result.single { it.code == PartgFindingCode.P20 }
            p20.count shouldBe 1
            p20.unitKey shouldBe null
            p20.level shouldBe PartgFindingLevel.INFO
            val p21 = result.single { it.code == PartgFindingCode.P21 }
            p21.count shouldBe 1
            p21.unitKey shouldBe null
            p21.level shouldBe PartgFindingLevel.CHECK
            result.toString() shouldNotContain "Geheim"
        }

        test("the hints share the cap of 2000 with the others, and no hint carries a name, an address or an amount") {
            val many =
                (1..2500).map {
                    PartgFindingDto(
                        code = PartgFindingCode.P17,
                        level = PartgFindingLevel.CHECK,
                        unitKey = federal,
                        positionCode = "A03$it",
                        target = PartgFindingTarget.CONSOLIDATION,
                    )
                }
            val all =
                PartgPlausibility.check(
                    year = year,
                    units = units,
                    entered = emptyMap(),
                    annex = emptyMap(),
                    notes = emptyMap(),
                    consolidation = PartgConsolidationFindingsInput(many),
                )
            all.size shouldBe PartgAnnexRules.MAX_FINDINGS
            // a finding has only these fields: nothing in it can hold a name or an amount
            PartgFindingDto::class.java.declaredFields
                .map { it.name }
                .filterNot { it.startsWith("$") || it == "Companion" }
                .toSet() shouldBe setOf("code", "level", "unitKey", "rowNumbers", "positionCode", "gap", "target", "count")
        }
    })
