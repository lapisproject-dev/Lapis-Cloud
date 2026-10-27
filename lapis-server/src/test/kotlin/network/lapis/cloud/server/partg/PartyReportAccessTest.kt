package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PartgUnit
import network.lapis.cloud.shared.domain.PartgValueChangeDto
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.PartyReportNotEnabledException
import kotlin.uuid.Uuid

/**
 * V1.9.225 -- every RPC method: BOARD and ADMIN pass, everybody else gets Forbidden, a non-party gets NotEnabled; the ROLE is checked before the
 * switch (a treasurer of a non-party learns nothing); the data survives switching the party flag off and on.
 */
class PartyReportAccessTest :
    FunSpec({
        val fx = PartgFixtures()
        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fx.cleanUp() }
        beforeTest { fx.setParty(true) }

        val year: Int by lazy { PartgYearRules.maxYear() - 1 }

        /** Every method of the interface as (name, call). */
        val methods: List<Pair<String, suspend (network.lapis.cloud.server.rpc.PartyReportService) -> Any?>> =
            listOf(
                "listPositions" to { s -> s.listPositions() },
                "updatePositionHint" to { s -> s.updatePositionHint(code = "E01", hint = null) },
                "listUnits" to { s -> s.listUnits() },
                "overview" to { s -> s.overview(year) },
                "getSheet" to { s -> s.getSheet(year = year, unitKey = "FEDERAL") },
                "saveSheet" to
                    { s ->
                        s.saveSheet(
                            year = year,
                            unitKey = "FEDERAL",
                            expectedRevision = 0,
                            expectedPriorRevision = 0,
                            changes = emptyList(),
                        )
                    },
            )

        test("the list of methods covers the whole interface") {
            val declared = network.lapis.cloud.shared.rpc.IPartyReportService::class.members.map { it.name }.toSet()
            (methods.map { it.first }.toSet() == declared.intersect(methods.map { it.first }.toSet())) shouldBe true
            methods.size shouldBe 6
        }

        test("board and admin may call every method of a party") {
            partgTestApplication(fixtures = fx) {
                for (role in listOf(AccountRole.BOARD, AccountRole.ADMIN)) {
                    val member = fx.member(role = role)
                    for ((name, op) in methods) {
                        val result = attempt(member = member) { op(service(it)) }
                        (name to result.isSuccess) shouldBe (name to true)
                    }
                }
            }
        }

        test("treasurer, member and guest are forbidden on every method, also on a party") {
            partgTestApplication(fixtures = fx) {
                val denied =
                    listOf(
                        fx.member(role = AccountRole.TREASURER),
                        fx.member(role = AccountRole.MEMBER),
                        fx.member(role = AccountRole.MEMBER, status = MemberStatus.GUEST),
                    )
                for (member in denied) {
                    for ((name, op) in methods) {
                        val result = attempt(member = member) { op(service(it)) }
                        (name to result.exceptionOrNull()).second.shouldBeInstanceOf<ForbiddenException>()
                    }
                }
            }
        }

        test("a non-party answers NotEnabled to the board, but the ROLE is checked first (a treasurer gets Forbidden)") {
            fx.setParty(false)
            partgTestApplication(fixtures = fx) {
                val board = fx.member(role = AccountRole.BOARD)
                val treasurer = fx.member(role = AccountRole.TREASURER)
                for ((_, op) in methods) {
                    attempt(member = board) { op(service(it)) }.exceptionOrNull().shouldBeInstanceOf<PartyReportNotEnabledException>()
                    attempt(member = treasurer) { op(service(it)) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                }
            }
        }

        test("stored values survive switching the party flag off and are readable again after switching it on") {
            partgTestApplication(fixtures = fx) {
                val board = fx.member(role = AccountRole.BOARD)
                call(member = board) {
                    service(
                        it,
                    ).saveSheet(
                        year = year,
                        unitKey = PartgUnit.Federal.key(),
                        expectedRevision = 0,
                        expectedPriorRevision = 0,
                        changes = listOf(PartgValueChangeDto(year = year, positionCode = "E01", amountCents = 12_345L)),
                    )
                }
                fx.setParty(false)
                attempt(
                    member = board,
                ) {
                    service(
                        it,
                    ).getSheet(year = year, unitKey = "FEDERAL")
                }.exceptionOrNull().shouldBeInstanceOf<PartyReportNotEnabledException>()
                fx.setParty(true)
                call(member = board) { service(it).getSheet(year = year, unitKey = "FEDERAL") }.values shouldBe mapOf("E01" to 12_345L)
            }
        }

        test("an unknown caller id is not a board member") {
            partgTestApplication(fixtures = fx) {
                val result = attempt(member = Uuid.random()) { service(it).listUnits() }
                (result.exceptionOrNull() != null) shouldBe true
            }
        }
    })
