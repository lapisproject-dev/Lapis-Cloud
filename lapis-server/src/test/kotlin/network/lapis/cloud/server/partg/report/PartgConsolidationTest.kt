package network.lapis.cloud.server.partg.report

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import network.lapis.cloud.server.partg.PartgConsolidationStore
import network.lapis.cloud.shared.domain.PartgConsolidatedCell
import network.lapis.cloud.shared.domain.PartgExclusionReason
import network.lapis.cloud.shared.domain.PartgNotComputableReason
import network.lapis.cloud.shared.domain.PartgPositions
import network.lapis.cloud.shared.domain.PartgTransferKind
import network.lapis.cloud.shared.domain.PartgUnit
import kotlin.uuid.Uuid

/**
 * V1.9.290 (R5) -- the consolidation core: elimination is the minimum of two stated sides between two INCLUDED units, nothing is capped or
 * counted as 0, an excluded unit is a different thing than a unit that lacks one value, and the invariant RES/NET holds.
 */
class PartgConsolidationTest :
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

        fun data(vararg pairs: PartgConsolidationStore.TransferRow) =
            mapOf(year to PartgConsolidationStore.ConsolidationData(revision = 1, transfers = pairs.toList(), combinations = emptyList()))

        /** Federal and state A completely entered, state B and both summaries without a sheet. */
        fun entered(
            federalExtra: Map<String, Long> = emptyMap(),
            stateAExtra: Map<String, Long> = emptyMap(),
            skipStateA: Set<String> = emptySet(),
        ) = mapOf(
            year to
                mapOf(
                    federal to ReportFixtures.leafValues() + federalExtra,
                    stateA to ReportFixtures.leafValues(skip = skipStateA, factor = 2) + stateAExtra,
                ),
        )

        fun cell(
            result: ConsolidationResult,
            code: String,
        ) = result.rows.single { it.code == code }

        test("happy path: the minimum is eliminated from BOTH positions, the sums follow, RES and NET stay equal") {
            val values =
                entered(
                    federalExtra = mapOf("A03" to 100_000L, "BP05" to 50_000L),
                    stateAExtra =
                        mapOf(
                            "E10" to 90_000L,
                            "SP04" to 50_000L,
                        ),
                )
            val result =
                PartgConsolidation.compute(
                    year = year,
                    units = units,
                    entered = values,
                    consolidation =
                        data(
                            pair(PartgTransferKind.SUBSIDY, federal, stateA, payerAmount = 100_000L, payeeAmount = 90_000L),
                            pair(PartgTransferKind.BALANCE, federal, stateA, payerAmount = 50_000L, payeeAmount = 50_000L),
                        ),
                )
            result.included shouldBe listOf(federal, stateA)
            val a03 = cell(result, "A03")
            val e10 = cell(result, "E10")
            a03.eliminatedCents shouldBe 90_000L
            e10.eliminatedCents shouldBe 90_000L
            (a03.consolidated as PartgConsolidatedCell.Value).cents shouldBe (a03.unitSum as PartgConsolidatedCell.Value).cents - 90_000L
            (e10.consolidated as PartgConsolidatedCell.Value).cents shouldBe (e10.unitSum as PartgConsolidatedCell.Value).cents - 90_000L
            (cell(result, "BP05").consolidated as PartgConsolidatedCell.Value).cents shouldBe
                (cell(result, "BP05").unitSum as PartgConsolidatedCell.Value).cents - 50_000L
            // the invariant: the result and the net assets do not change by a symmetric elimination
            cell(result, PartgPositions.RESULT).consolidated shouldBe cell(result, PartgPositions.RESULT).unitSum
            cell(result, PartgPositions.NET).consolidated shouldBe cell(result, PartgPositions.NET).unitSum
            // the totals move by exactly the eliminated amount
            (cell(result, "E11").unitSum as PartgConsolidatedCell.Value).cents -
                (cell(result, "E11").consolidated as PartgConsolidatedCell.Value).cents shouldBe 90_000L
            cell(result, "E11").eliminatedCents shouldBe 90_000L
            cell(result, PartgPositions.RESULT).eliminatedCents shouldBe 0L
        }

        test("the difference between the two sides stays visible in the list, it is never booked away") {
            val values = entered(federalExtra = mapOf("A03" to 100_000L), stateAExtra = mapOf("E10" to 90_000L))
            val result =
                PartgConsolidation.compute(
                    year = year,
                    units = units,
                    entered = values,
                    consolidation = data(pair(PartgTransferKind.SUBSIDY, federal, stateA, payerAmount = 100_000L, payeeAmount = 90_000L)),
                )
            result.unmatched.single { it.positionCode == "A03" && it.unitKey == federal }.differenceCents shouldBe 10_000L
            // the other side agrees with the eliminated part: nothing left
            result.unmatched.none { it.positionCode == "E10" && it.unitKey == stateA } shouldBe true
        }

        test("a pair with one amount only eliminates nothing") {
            val values = entered(federalExtra = mapOf("A03" to 100_000L), stateAExtra = mapOf("E10" to 90_000L))
            val result =
                PartgConsolidation.compute(
                    year = year,
                    units = units,
                    entered = values,
                    consolidation = data(pair(PartgTransferKind.SUBSIDY, federal, stateA, payerAmount = 100_000L, payeeAmount = null)),
                )
            cell(result, "A03").eliminatedCents shouldBe 0L
            cell(result, "E10").eliminatedCents shouldBe 0L
            result.transfers.single().match shouldBe network.lapis.cloud.shared.domain.PartgMatchState.COUNTERPART_MISSING
        }

        test("a pair that names a unit without data eliminates nothing") {
            val values = entered(federalExtra = mapOf("A03" to 100_000L))
            val result =
                PartgConsolidation.compute(
                    year = year,
                    units = units,
                    entered = values,
                    consolidation = data(pair(PartgTransferKind.SUBSIDY, federal, stateB, payerAmount = 100_000L, payeeAmount = 100_000L)),
                )
            cell(result, "A03").eliminatedCents shouldBe 0L
            cell(result, "E10").eliminatedCents shouldBe 0L
        }

        test("an elimination larger than the sum makes the cell not computable, it is never capped") {
            val values = entered(federalExtra = mapOf("A03" to 100L), stateAExtra = mapOf("A03" to 100L, "E10" to 900_000_000L))
            val result =
                PartgConsolidation.compute(
                    year = year,
                    units = units,
                    entered = values,
                    consolidation = data(pair(PartgTransferKind.SUBSIDY, federal, stateA, payerAmount = 500_000L, payeeAmount = 500_000L)),
                )
            val a03 = cell(result, "A03").consolidated.shouldBeInstanceOf<PartgConsolidatedCell.NotComputable>()
            a03.reason shouldBe PartgNotComputableReason.ELIMINATION_EXCEEDS
            // the sums that depend on it are not computable either, the other side is fine
            cell(result, PartgPositions.EXPENSE_TOTAL).consolidated.shouldBeInstanceOf<PartgConsolidatedCell.NotComputable>()
            cell(result, PartgPositions.RESULT).consolidated.shouldBeInstanceOf<PartgConsolidatedCell.NotComputable>()
            cell(result, "E10").consolidated.shouldBeInstanceOf<PartgConsolidatedCell.Value>()
        }

        test("a value missing in an included unit is not computable and names the unit; an excluded unit is not part of that") {
            val values = entered(skipStateA = setOf("E01"))
            val result = PartgConsolidation.compute(year = year, units = units, entered = values, consolidation = emptyMap())
            val e01 = cell(result, "E01").consolidated.shouldBeInstanceOf<PartgConsolidatedCell.NotComputable>()
            e01.reason shouldBe PartgNotComputableReason.MISSING_IN_UNITS
            e01.unitKeys shouldBe listOf(stateA)
            // the sum depends on it
            val total = cell(result, PartgPositions.INCOME_TOTAL).consolidated.shouldBeInstanceOf<PartgConsolidatedCell.NotComputable>()
            total.unitKeys shouldBe listOf(stateA)
            // state B has no data at all: excluded (NO_DATA), NOT counted as 0 and NOT a reason for n. b.
            result.excluded.single { it.unitKey == stateB }.reason shouldBe PartgExclusionReason.NO_DATA
            (cell(result, "E02").consolidated as PartgConsolidatedCell.Value).cents shouldBe
                ReportFixtures.leafValues().getValue("E02") + ReportFixtures.leafValues(skip = setOf("E01"), factor = 2).getValue("E02")
        }

        test("a unit that books in Lapis with bookings but no stored value is excluded with its own reason") {
            val result =
                PartgConsolidation.compute(
                    year = year,
                    units = units,
                    entered = entered(),
                    consolidation = emptyMap(),
                    takeOverPending = mapOf(year to setOf(stateB)),
                )
            result.excluded.single { it.unitKey == stateB }.reason shouldBe PartgExclusionReason.BOOKINGS_NOT_TAKEN_OVER
            result.excluded.single { it.unitKey == PartgUnit.Subordinate(ReportFixtures.CHAPTER_A).key() }.reason shouldBe
                PartgExclusionReason.NO_DATA
        }

        test("nobody has data: every cell is not computable and the included list is empty") {
            val result = PartgConsolidation.compute(year = year, units = units, entered = emptyMap(), consolidation = emptyMap())
            result.included shouldBe emptyList()
            result.rows.all { it.consolidated is PartgConsolidatedCell.NotComputable } shouldBe true
        }

        test("the year before is consolidated with its OWN pairs; without pairs but with transfer positions the footnote flag is set") {
            val prior = mapOf(federal to ReportFixtures.leafValues() + ("A03" to 5_000L), stateA to ReportFixtures.leafValues(factor = 2))
            val values = entered() + (year - 1 to prior)
            val without = PartgConsolidation.compute(year = year, units = units, entered = values, consolidation = emptyMap())
            without.priorTransfersUnmatched shouldBe true
            val withPair =
                PartgConsolidation.compute(
                    year = year,
                    units = units,
                    entered = values,
                    consolidation =
                        mapOf(
                            year - 1 to
                                PartgConsolidationStore.ConsolidationData(
                                    revision = 1,
                                    transfers = listOf(pair(PartgTransferKind.SUBSIDY, federal, stateA, 5_000L, 5_000L)),
                                    combinations = emptyList(),
                                ),
                        ),
                )
            withPair.priorTransfersUnmatched shouldBe false
            (cell(withPair, "A03").priorConsolidated as PartgConsolidatedCell.Value).cents shouldBe
                5_000L + ReportFixtures.leafValues(factor = 2).getValue("A03") - 5_000L
        }
    })
