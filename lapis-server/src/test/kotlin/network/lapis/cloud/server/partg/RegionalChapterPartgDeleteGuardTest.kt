package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.RegionalChapterTable
import network.lapis.cloud.server.rpc.RegionalChapterService
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.PartgUnit
import network.lapis.cloud.shared.domain.PartgValueChangeDto
import network.lapis.cloud.shared.rpc.RegionalChapterHasPartyReportValuesException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** V1.9.225 -- a regional chapter with report values (or just a sheet header) cannot be deleted; without them it still can. */
class RegionalChapterPartgDeleteGuardTest :
    FunSpec({
        val fx = PartgFixtures()
        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fx.cleanUp() }
        beforeTest { fx.setParty(true) }

        val year: Int by lazy { PartgYearRules.maxYear() - 1 }

        fun exists(id: kotlin.uuid.Uuid) =
            transaction { RegionalChapterTable.selectAll().where { RegionalChapterTable.id eq id }.count() } > 0

        test("deleting a chapter with values is refused with the typed exception and the chapter stays") {
            val board = fx.member(role = AccountRole.BOARD)
            val admin = fx.member(role = AccountRole.ADMIN)
            val chapter = fx.chapter()
            partgTestApplication(fixtures = fx) {
                call(member = board) {
                    service(
                        it,
                    ).saveSheet(
                        year = year,
                        unitKey = PartgUnit.State(chapter.toString()).key(),
                        expectedRevision = 0,
                        expectedPriorRevision = 0,
                        changes = listOf(PartgValueChangeDto(year = year, positionCode = "E01", amountCents = 1L)),
                    )
                }
                val result =
                    attempt(member = admin) {
                        RegionalChapterService(
                            call = it,
                            crestStorage = null,
                            baseUrl = "http://localhost",
                        ).deleteChapter(chapter.toString())
                    }
                result.exceptionOrNull().shouldBeInstanceOf<RegionalChapterHasPartyReportValuesException>()
                exists(chapter) shouldBe true
            }
        }

        test("an empty sheet header blocks as well, and a chapter without any sheet can be deleted") {
            val board = fx.member(role = AccountRole.BOARD)
            val admin = fx.member(role = AccountRole.ADMIN)
            val withHeader = fx.chapter()
            val free = fx.chapter()
            partgTestApplication(fixtures = fx) {
                // a value saved, then removed again: the (now empty) header stays
                val unit = PartgUnit.Subordinate(withHeader.toString()).key()
                call(member = board) {
                    service(
                        it,
                    ).saveSheet(
                        year = year,
                        unitKey = unit,
                        expectedRevision = 0,
                        expectedPriorRevision = 0,
                        changes = listOf(PartgValueChangeDto(year = year, positionCode = "E01", amountCents = 1L)),
                    )
                }
                call(member = board) {
                    service(
                        it,
                    ).saveSheet(
                        year = year,
                        unitKey = unit,
                        expectedRevision = 1,
                        expectedPriorRevision = 0,
                        changes = listOf(PartgValueChangeDto(year = year, positionCode = "E01", amountCents = null)),
                    )
                }
                attempt(member = admin) {
                    RegionalChapterService(
                        call = it,
                        crestStorage = null,
                        baseUrl = "http://localhost",
                    ).deleteChapter(withHeader.toString())
                }.exceptionOrNull()
                    .shouldBeInstanceOf<RegionalChapterHasPartyReportValuesException>()
                attempt(member = admin) {
                    RegionalChapterService(call = it, crestStorage = null, baseUrl = "http://localhost").deleteChapter(free.toString())
                }.isSuccess shouldBe
                    true
                exists(free) shouldBe false
            }
        }
    })
