package network.lapis.cloud.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.256 -- the cell states and percentages of the report core. "Not entered" is never 0. */
class PartgReportComputationTest {
    private val allLeaves = PartgPositions.LEAF_CODES

    @Test
    fun aUnitWithoutValuesHasOnlyNotEnteredCells() {
        val cells = PartgReportComputation.cells(emptyMap())
        assertEquals(42, cells.size)
        assertTrue(cells.values.all { it == PartgCell.NotEntered })
    }

    @Test
    fun allZeroLeavesGiveZeroValuesNotNotEntered() {
        val cells = PartgReportComputation.cells(allLeaves.associateWith { 0L })
        assertTrue(cells.values.all { it == PartgCell.Value(0) })
    }

    @Test
    fun oneMissingLeafMakesItsSumsPartialButNotTheOtherSide() {
        val entered = allLeaves.filter { it != "E05A" }.associateWith { 100L }
        val cells = PartgReportComputation.cells(entered)
        assertEquals(PartgCell.NotEntered, cells.getValue("E05A"))
        val e11 = assertIs<PartgCell.Partial>(cells.getValue("E11"))
        assertEquals(1000L, e11.cents)
        assertEquals(listOf("E05A"), e11.missing)
        assertEquals(listOf("E05A"), assertIs<PartgCell.Partial>(cells.getValue("RES")).missing)
        assertIs<PartgCell.Value>(cells.getValue("NET"))
        assertIs<PartgCell.Value>(cells.getValue("A04"))
    }

    @Test
    fun negativeResultAndNegativeNetAssets() {
        val entered = allLeaves.associateWith { 0L } + mapOf("A01" to 500L, "E01" to 100L, "SP01" to 900L, "BP01" to 10L)
        val cells = PartgReportComputation.cells(entered)
        assertEquals(PartgCell.Value(-400L), cells.getValue("RES"))
        assertEquals(PartgCell.Value(-890L), cells.getValue("NET"))
    }

    @Test
    fun aggregateOfTwoUnitsWithOneGap() {
        val a = mapOf("E01" to 10L)
        val b = emptyMap<String, Long>()
        val cells = PartgReportComputation.aggregate(listOf(a, b))
        assertEquals(PartgCell.Partial(cents = 10L, missing = listOf("E01")), cells.getValue("E01"))
        assertEquals(PartgCell.NotEntered, cells.getValue("E02"))
    }

    @Test
    fun aggregateOfTwoFullUnitsAddsUp() {
        val a = mapOf("E01" to 10L)
        val b = mapOf("E01" to 5L)
        assertEquals(PartgCell.Value(15L), PartgReportComputation.aggregate(listOf(a, b)).getValue("E01"))
    }

    @Test
    fun aggregateOfNothingIsNotEntered() {
        assertEquals(PartgCell.NotEntered, PartgReportComputation.aggregate(emptyList()).getValue("E11"))
    }

    @Test
    fun tooManyUnitsAreRefused() {
        assertFailsWith<IllegalArgumentException> {
            PartgReportComputation.aggregate(List(PartgReportComputation.MAX_UNITS + 1) { emptyMap() })
        }
        PartgReportComputation.aggregate(List(PartgReportComputation.MAX_UNITS) { emptyMap() })
    }

    @Test
    fun percentIsNotComputableWithoutAFullValueAndABase() {
        assertEquals(PartgPercent.NotComputable, PartgReportComputation.percent(numerator = PartgCell.Value(1), base = PartgCell.Value(0)))
        assertEquals(
            PartgPercent.NotComputable,
            PartgReportComputation.percent(numerator = PartgCell.Partial(cents = 1, missing = listOf("E01")), base = PartgCell.Value(5)),
        )
        assertEquals(
            PartgPercent.NotComputable,
            PartgReportComputation.percent(numerator = PartgCell.Value(1), base = PartgCell.NotEntered),
        )
    }

    @Test
    fun percentRoundsHalfUpInTenths() {
        fun p(
            n: Long,
            b: Long,
        ) = PartgReportComputation.percent(numerator = PartgCell.Value(n), base = PartgCell.Value(b))
        assertEquals(PartgPercent.Tenths(333), p(1, 3))
        assertEquals(PartgPercent.Tenths(125), p(1, 8))
        assertEquals(PartgPercent.Tenths(1), p(1, 2000)) // 0.05 percent rounds up to 0.1
        assertEquals(PartgPercent.Tenths(0), p(1, 2001))
        assertEquals(PartgPercent.Tenths(-1), p(-1, 2000))
        assertEquals(PartgPercent.Tenths(1000), p(5, 5))
    }

    @Test
    fun hugeValuesDoNotOverflow() {
        val base = 201L * 99_999_999_999_999L * 35L
        assertEquals(
            PartgPercent.Tenths(500),
            PartgReportComputation.percent(numerator = PartgCell.Value(base / 2), base = PartgCell.Value(base)),
        )
        assertNull(PartgReportComputation.tenthsHalfUp(num = Long.MAX_VALUE / 2, base = 1))
    }

    @Test
    fun tenthsMatchesALongArithmeticReference() {
        // reference with 128-bit-free arithmetic for small operands: (n * 2000 + b) / (2 * b) is round-half-up of n * 1000 / b
        val random = kotlin.random.Random(42)
        repeat(10_000) {
            val n = random.nextLong(0, 1_000_000_000_000L)
            val b = random.nextLong(1, 1_000_000_000_000L)
            if (n / b <= 1_000_000L && n < 9_000_000_000_000_000L / 2000) {
                val expected = (n * 2000 + b) / (2 * b)
                assertEquals(expected, PartgReportComputation.tenthsHalfUp(num = n, base = b), "n=$n b=$b")
            }
        }
    }

    @Test
    fun scopeParsingIsStrict() {
        assertEquals(PartgReportScope.All, PartgReportScope.parse("ALL"))
        assertEquals(PartgReportScope.Single(PartgUnit.Federal), PartgReportScope.parse("FEDERAL"))
        assertNull(PartgReportScope.parse("all"))
        assertNull(PartgReportScope.parse("../../etc"))
        assertNull(PartgReportScope.parse("STATE:" + "A".repeat(36)))
    }
}

/** For every sum and every leaf: raising the leaf by 1 changes the sum exactly when the leaf is in `inputsOf(sum)`. */
class PartgInputsOfParityTest {
    @Test
    fun inputsOfMatchesTheOneSumFormula() {
        val base = PartgPositions.LEAF_CODES.associateWith { 7L }
        val before = PartgPositions.sums(base)
        for (sum in before.keys) {
            val inputs = PartgReportComputation.inputsOf(sum)
            for (leaf in PartgPositions.LEAF_CODES) {
                val after = PartgPositions.sums(base + (leaf to 8L))
                val changes = after.getValue(sum) != before.getValue(sum)
                assertEquals(changes, leaf in inputs, "sum=$sum leaf=$leaf")
            }
        }
        assertEquals(PartgPositions.ALL.count { it.kind == PartgKind.SUM }, before.size)
    }
}
