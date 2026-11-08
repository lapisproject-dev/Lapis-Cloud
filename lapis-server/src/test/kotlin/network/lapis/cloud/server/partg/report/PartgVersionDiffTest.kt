package network.lapis.cloud.server.partg.report

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.PartgCell
import network.lapis.cloud.shared.domain.PartgDiffMarker
import network.lapis.cloud.shared.domain.PartgDiffValueState
import network.lapis.cloud.shared.domain.PartgReportScope
import network.lapis.cloud.shared.domain.PartgUnit
import network.lapis.cloud.shared.domain.PartgUnitLevel

/** V1.9.291 (R6) -- the pure comparison of two kept versions. */
class PartgVersionDiffTest :
    FunSpec({
        val year = ReportFixtures.YEAR

        fun docOf(
            entered: Map<Int, Map<String, Map<String, Long>>> = ReportFixtures.entered(),
            units: List<network.lapis.cloud.shared.domain.PartgUnitDto> = ReportFixtures.units(),
        ) = ReportFixtures.doc(entered = entered, units = units)

        fun diff(
            a: PartgReportDocument,
            b: PartgReportDocument,
            maxRows: Int = PartgVersionDiff.DEFAULT_MAX_ROWS,
        ) = PartgVersionDiff.compute(
            a = a,
            b = b,
            versionIdA = "a",
            versionIdB = "b",
            versionNumberA = 1,
            versionNumberB = 2,
            maxRows = maxRows,
        )

        test("two equal documents have only SAME rows without cells and are comparable") {
            val result = diff(docOf(), docOf())
            result.comparable shouldBe true
            result.truncated shouldBe false
            result.rows.all { it.marker == PartgDiffMarker.SAME && it.cells.isEmpty() } shouldBe true
            result.rows.map { it.rowKind }.distinct() shouldBe listOf("UNIT", "SUM")
        }

        test("a changed leaf is CHANGED with the signed difference, in the unit row and in the sum row") {
            val before = ReportFixtures.entered()
            val federal = PartgUnit.FEDERAL_KEY
            val after =
                before.mapValues { (y, perUnit) ->
                    if (y == year) perUnit + (federal to (perUnit.getValue(federal) + ("E01" to 100L))) else perUnit
                }
            val a = docOf(entered = before)
            val b = docOf(entered = after)
            val result = diff(a, b)
            val unitRow = result.rows.single { it.rowKind == "UNIT" && it.rowKey == federal }
            unitRow.marker shouldBe PartgDiffMarker.CHANGED
            val cell = unitRow.cells.single { it.code == "E01" }
            cell.marker shouldBe PartgDiffMarker.CHANGED
            cell.deltaCents shouldBe 100L - before.getValue(year).getValue(federal).getValue("E01")
        }

        test("a removed unit and an added unit are REMOVED and ADDED; their cells list only figures") {
            val extra =
                ReportFixtures.unitDto(level = PartgUnitLevel.STATE, chapter = "33333333-3333-4333-8333-333333333333", name = "Gamma")
            val enteredWithExtra =
                ReportFixtures.entered().mapValues { (y, perUnit) ->
                    if (y == year) perUnit + (extra.unitKey to mapOf("E01" to 5L)) else perUnit
                }
            val small = docOf()
            val big = docOf(units = ReportFixtures.units() + extra, entered = enteredWithExtra)
            val added = diff(small, big).rows.single { it.rowKey == extra.unitKey }
            added.marker shouldBe PartgDiffMarker.ADDED
            added.cells.all { it.before.state == PartgDiffValueState.ABSENT } shouldBe true
            added.cells.any { it.code == "E01" && it.after.cents == 5L } shouldBe true
            added.cells.none { it.after.state == PartgDiffValueState.NOT_ENTERED } shouldBe true
            diff(big, small).rows.single { it.rowKey == extra.unitKey }.marker shouldBe PartgDiffMarker.REMOVED
        }

        test("a partial or not entered side has no difference, never a guessed 0") {
            val before = mapOf(year to mapOf(PartgUnit.FEDERAL_KEY to mapOf("E01" to 100L)))
            val after = mapOf(year to mapOf(PartgUnit.FEDERAL_KEY to mapOf("E01" to 100L, "E02" to 50L)))
            val units = listOf(ReportFixtures.unitDto(level = PartgUnitLevel.FEDERAL))
            val result = diff(docOf(entered = before, units = units), docOf(entered = after, units = units))
            val row = result.rows.single { it.rowKind == "UNIT" }
            val e02 = row.cells.single { it.code == "E02" }
            e02.before.state shouldBe PartgDiffValueState.NOT_ENTERED
            e02.after.state shouldBe PartgDiffValueState.VALUE
            e02.deltaCents shouldBe null
            row.cells
                .filter { it.before.state == PartgDiffValueState.PARTIAL || it.after.state == PartgDiffValueState.PARTIAL }
                .all { it.deltaCents == null } shouldBe true
        }

        test("a renamed unit stays the same row (matched by its key) and takes the newer label") {
            val renamed =
                ReportFixtures.units().map {
                    if (it.chapterName == "Alpha" &&
                        it.level == PartgUnitLevel.STATE
                    ) {
                        it.copy(chapterName = "Alpha neu")
                    } else {
                        it
                    }
                }
            val result = diff(docOf(), docOf(units = renamed))
            val row = result.rows.single { it.rowKey == PartgUnit.State(ReportFixtures.CHAPTER_A).key() }
            row.marker shouldBe PartgDiffMarker.SAME
            row.label shouldBe "Landesverband Alpha neu"
        }

        test("more rows than the cap are cut and flagged") {
            val result = diff(docOf(), docOf(), maxRows = 2)
            result.rows.size shouldBe 2
            result.truncated shouldBe true
        }

        test("different scopes are not comparable") {
            val single = ReportFixtures.doc(scope = PartgReportScope.Single(PartgUnit.Federal))
            diff(single, docOf()).comparable shouldBe false
        }

        test("a document without any difference lists no cell at all") {
            diff(docOf(), docOf()).rows.flatMap { it.cells }.shouldBeEmpty()
            PartgCell.NotEntered shouldBe PartgCell.NotEntered
        }
    })
