package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.PartgPositions
import network.lapis.cloud.shared.domain.PartgUnit
import network.lapis.cloud.shared.domain.PartgValueChangeDto
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.PartyReportInvalidInputException

/** V1.9.256 -- `reportCompleteness` and its parity with the overview: both read the same snapshot. */
class PartyReportCompletenessTest :
    FunSpec({
        val fx = PartgFixtures()
        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fx.cleanUp() }
        beforeTest { fx.setParty(true) }

        val year: Int by lazy { PartgYearRules.maxYear() - 1 }

        test("the completeness lists the missing LEAF codes per unit in catalogue order and counts the year before") {
            partgTestApplication(fixtures = fx) {
                val board = fx.member(role = AccountRole.BOARD)
                call(member = board) {
                    service(it).saveSheet(
                        year = year,
                        unitKey = PartgUnit.Federal.key(),
                        expectedRevision = 0,
                        expectedPriorRevision = 0,
                        changes =
                            listOf(
                                PartgValueChangeDto(year = year, positionCode = "E01", amountCents = 100L),
                                PartgValueChangeDto(year = year, positionCode = "A01", amountCents = 50L),
                                PartgValueChangeDto(year = year - 1, positionCode = "E01", amountCents = 70L),
                            ),
                    )
                }
                val dto = call(member = board) { service(it).reportCompleteness(year) }
                dto.year shouldBe year
                dto.priorYear shouldBe year - 1
                dto.leafCount shouldBe 35
                val federal = dto.units.single { it.unit.unitKey == PartgUnit.FEDERAL_KEY }
                federal.missingCurrent.size shouldBe 33
                (("E01" in federal.missingCurrent) || ("A01" in federal.missingCurrent)) shouldBe false
                federal.missingCurrent shouldBe
                    PartgPositions.ALL
                        .filter { it.kind == network.lapis.cloud.shared.domain.PartgKind.LEAF }
                        .map { it.code }
                        .filter { it != "E01" && it != "A01" }
                federal.enteredPrior shouldBe 1
            }
        }

        test("overview and completeness count the same: filledCount equals leafCount minus the missing codes") {
            partgTestApplication(fixtures = fx) {
                val board = fx.member(role = AccountRole.BOARD)
                val chapter = fx.chapter()
                call(member = board) {
                    service(it).saveSheet(
                        year = year,
                        unitKey = PartgUnit.State(chapter.toString()).key(),
                        expectedRevision = 0,
                        expectedPriorRevision = 0,
                        changes = listOf(PartgValueChangeDto(year = year, positionCode = "E03", amountCents = 9L)),
                    )
                }
                val overview = call(member = board) { service(it).overview(year) }
                val completeness = call(member = board) { service(it).reportCompleteness(year) }
                overview.map { it.unit.unitKey } shouldBe completeness.units.map { it.unit.unitKey }
                overview.zip(completeness.units).forEach { (row, unit) ->
                    row.filledCount shouldBe row.leafCount - unit.missingCurrent.size
                }
            }
        }

        test("access rules and year check are those of the other methods") {
            partgTestApplication(fixtures = fx) {
                val treasurer = fx.member(role = AccountRole.TREASURER)
                attempt(member = treasurer) { service(it).reportCompleteness(year) }.exceptionOrNull().let {
                    (it is ForbiddenException) shouldBe true
                }
                val board = fx.member(role = AccountRole.BOARD)
                attempt(member = board) { service(it).reportCompleteness(1999) }.exceptionOrNull().let {
                    (it is PartyReportInvalidInputException) shouldBe true
                }
            }
        }
    })
