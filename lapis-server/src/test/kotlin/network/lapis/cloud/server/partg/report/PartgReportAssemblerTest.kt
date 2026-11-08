package network.lapis.cloud.server.partg.report

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import network.lapis.cloud.shared.domain.PartgCell
import network.lapis.cloud.shared.domain.PartgReportScope
import network.lapis.cloud.shared.domain.PartgUnit
import network.lapis.cloud.shared.domain.PartgUnitLevel
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.PartyReportInvalidInputException

class PartgReportAssemblerTest :
    FunSpec({
        test("the units keep the order of the store; the whole party and the three levels exist for the scope ALL") {
            val doc = ReportFixtures.doc()
            doc.units.map { it.label } shouldBe
                listOf(
                    "Bundesverband",
                    "Landesverband Alpha",
                    "Nachgeordnete Gebietsverbände in Alpha (zusammengefasst)",
                    "Landesverband Beta",
                    "Nachgeordnete Gebietsverbände in Beta (zusammengefasst)",
                )
            doc.unitSum?.label shouldBe "Summe der Einheiten (nicht konsolidiert)"
            doc.levels.map { it.level } shouldBe listOf(PartgUnitLevel.FEDERAL, PartgUnitLevel.STATE, PartgUnitLevel.SUBORDINATE)
            doc.completeness.units.size shouldBe 5
        }

        test("a unit that entered nothing is NotEntered, never 0; the whole party is partial") {
            val doc = ReportFixtures.doc()
            doc.units[2].current.getValue("E11") shouldBe PartgCell.NotEntered
            doc.unitSum!!
                .current
                .getValue("E01")
                .shouldBeInstanceOf<PartgCell.Partial>()
            doc.units[1]
                .current
                .getValue("E11")
                .shouldBeInstanceOf<PartgCell.Value>()
        }

        test("a single unit scope filters to that unit and has no whole party") {
            val key = PartgUnit.State(ReportFixtures.CHAPTER_A)
            val doc = ReportFixtures.doc(scope = PartgReportScope.Single(key))
            doc.units.size shouldBe 1
            doc.unitSum shouldBe null
            doc.levels shouldBe emptyList()
            doc.completeness.units
                .single()
                .unit.unitKey shouldBe key.key()
        }

        test("an unknown unit is NotFound, too many units are refused") {
            shouldThrow<NotFoundException> {
                ReportFixtures.doc(scope = PartgReportScope.Single(PartgUnit.State("33333333-3333-4333-8333-333333333333")))
            }
            val many =
                List(
                    202,
                ) {
                    ReportFixtures.unitDto(
                        level = PartgUnitLevel.STATE,
                        chapter = "00000000-0000-4000-8000-%012d".format(it),
                        name = "n$it",
                    )
                }
            shouldThrow<PartyReportInvalidInputException> { ReportFixtures.doc(units = many) }
        }

        test("codes that are not LEAF positions in the input are ignored") {
            val entered =
                mapOf(ReportFixtures.YEAR to mapOf("FEDERAL" to mapOf("E01" to 100L, "E11" to 999_999L, "RES" to 5L, "ZZZ" to 1L)))
            val doc = ReportFixtures.doc(scope = PartgReportScope.Single(PartgUnit.Federal), entered = entered)
            doc.units
                .single()
                .current
                .getValue("E01") shouldBe PartgCell.Value(100L)
            doc.units
                .single()
                .current
                .getValue("E11")
                .shouldBeInstanceOf<PartgCell.Partial>()
                .cents shouldBe 100L
        }
    })
