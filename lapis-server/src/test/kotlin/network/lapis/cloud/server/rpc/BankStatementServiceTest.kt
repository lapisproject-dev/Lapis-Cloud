package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.BankStatementDonationAssignmentInput
import network.lapis.cloud.shared.domain.BankStatementLineQuery
import network.lapis.cloud.shared.domain.DonorCategory
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Review fix (MEDIUM, "Fehlende Testabdeckung", Runde-2/3-Fund #9): before this file,
 * [BankStatementService] -- the RPC surface deciding whether BOARD may only READ bank-statement
 * data or also POST money into the general ledger via it -- had zero test coverage. This is exactly
 * the separation [BANK_STATEMENT_WRITE_ROLES]' own KDoc calls out as deliberate (narrower than
 * [BANK_STATEMENT_READ_ROLES], excludes BOARD); without a test, a future edit that widened the write
 * role set (or forgot to add the gate to a new method) would silently let BOARD book money. Own
 * throwaway routing calling [BankStatementService] directly -- same "kilua-rpc's own generated
 * dispatch is not exercised here" house style [SepaServiceTest]/[DunningServiceTest] establish.
 * Every write-path assertion below uses a well-formed but non-existent id: a 403 (not 404) proves
 * the role gate ran BEFORE any store lookup; conversely a 404 for TREASURER/ADMIN proves the
 * request actually reached [BankStatementStore].
 */
class BankStatementServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterEach {
            transaction {
                createdMemberIds.forEach {
                    AccountTable.deleteWhere { AccountTable.memberId eq it }
                    MemberTable.deleteWhere { MemberTable.id eq it }
                }
            }
            createdMemberIds.clear()
        }

        fun createMember(role: AccountRole): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "BankStatementService-Testmitglied"
                    it[email] = "bank-svc-${Uuid.random()}@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                }
            }
            createdMemberIds += id
            return id
        }

        test("READ methods (listImports/listLines/suggestMatches/searchAssignmentTargets): BOARD is allowed, a plain MEMBER is forbidden") {
            testApplication {
                application {
                    install(StatusPages) { installBankStatementTestExceptionHandlers() }
                    routing { registerBankStatementTestRoutes() }
                }
                val board = createMember(AccountRole.BOARD)
                val member = createMember(AccountRole.MEMBER)
                val randomLineId = Uuid.random().toString()

                client.get("/test/bank/imports") { header("X-Member-Id", board.toString()) }.status shouldBe HttpStatusCode.OK
                client.get("/test/bank/imports") { header("X-Member-Id", member.toString()) }.status shouldBe HttpStatusCode.Forbidden

                client.get("/test/bank/lines") { header("X-Member-Id", board.toString()) }.status shouldBe HttpStatusCode.OK
                client.get("/test/bank/lines") { header("X-Member-Id", member.toString()) }.status shouldBe HttpStatusCode.Forbidden

                // suggestMatches: a well-formed but non-existent lineId reaches NotFoundException
                // for BOARD (passed the role gate), while MEMBER never gets that far.
                client
                    .get("/test/bank/suggest?lineId=$randomLineId") { header("X-Member-Id", board.toString()) }
                    .status shouldBe HttpStatusCode.NotFound
                client
                    .get("/test/bank/suggest?lineId=$randomLineId") { header("X-Member-Id", member.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden

                client
                    .get("/test/bank/search?term=Mustermann") { header("X-Member-Id", board.toString()) }
                    .status shouldBe HttpStatusCode.OK
                client
                    .get("/test/bank/search?term=Mustermann") { header("X-Member-Id", member.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden
            }
        }

        test(
            "WRITE methods (assignLineToContribution/assignLineToDonation/ignoreLine): BOARD is forbidden " +
                "despite being a READ role, TREASURER/ADMIN pass the gate and reach the store",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installBankStatementTestExceptionHandlers() }
                    routing { registerBankStatementTestRoutes() }
                }
                val board = createMember(AccountRole.BOARD)
                val treasurer = createMember(AccountRole.TREASURER)
                val admin = createMember(AccountRole.ADMIN)
                val randomLineId = Uuid.random().toString()
                val randomContributionId = Uuid.random().toString()

                // Plan OF-1's central guarantee: BOARD can see bank-statement data (READ role above)
                // but must NEVER be able to post money into the ledger through this RPC surface.
                client
                    .post("/test/bank/ignore?lineId=$randomLineId&reason=Testgrund") { header("X-Member-Id", board.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden
                client
                    .post("/test/bank/assign?lineId=$randomLineId&contributionId=$randomContributionId") {
                        header("X-Member-Id", board.toString())
                    }.status shouldBe HttpStatusCode.Forbidden
                client
                    .post("/test/bank/donate?lineId=$randomLineId&donorMemberId=$treasurer") {
                        header("X-Member-Id", board.toString())
                    }.status shouldBe HttpStatusCode.Forbidden

                client
                    .post("/test/bank/ignore?lineId=$randomLineId&reason=Testgrund") { header("X-Member-Id", treasurer.toString()) }
                    .status shouldBe HttpStatusCode.NotFound
                client
                    .post("/test/bank/assign?lineId=$randomLineId&contributionId=$randomContributionId") {
                        header("X-Member-Id", admin.toString())
                    }.status shouldBe HttpStatusCode.NotFound
                client
                    .post("/test/bank/donate?lineId=$randomLineId&donorMemberId=$treasurer") {
                        header("X-Member-Id", admin.toString())
                    }.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("malformed ids: a TREASURER passes the role gate but a non-UUID lineId is a 400, not a 404/500") {
            testApplication {
                application {
                    install(StatusPages) { installBankStatementTestExceptionHandlers() }
                    routing { registerBankStatementTestRoutes() }
                }
                val treasurer = createMember(AccountRole.TREASURER)

                client
                    .post("/test/bank/ignore?lineId=not-a-uuid&reason=Testgrund") { header("X-Member-Id", treasurer.toString()) }
                    .status shouldBe HttpStatusCode.BadRequest
            }
        }
    })

private fun Route.registerBankStatementTestRoutes() {
    fun service(callCtx: ApplicationCall) = BankStatementService(call = callCtx, secretBox = null)

    get("/test/bank/imports") {
        val dto = service(call).listImports(limit = 50, offset = 0)
        call.respondText("${dto.totalCount}")
    }
    get("/test/bank/lines") {
        val dto = service(call).listLines(BankStatementLineQuery())
        call.respondText("${dto.totalCount}")
    }
    get("/test/bank/suggest") {
        val lineId = call.request.queryParameters["lineId"]!!
        val candidates = service(call).suggestMatches(lineId)
        call.respondText("${candidates.size}")
    }
    get("/test/bank/search") {
        val term = call.request.queryParameters["term"]!!
        val candidates = service(call).searchAssignmentTargets(term = term, limit = 10)
        call.respondText("${candidates.size}")
    }
    post("/test/bank/ignore") {
        val q = call.request.queryParameters
        val dto = service(call).ignoreLine(lineId = q["lineId"]!!, reason = q["reason"]!!)
        call.respondText(dto.status.name)
    }
    post("/test/bank/assign") {
        val q = call.request.queryParameters
        val dto =
            service(call).assignLineToContribution(
                lineId = q["lineId"]!!,
                contributionId = q["contributionId"]!!,
                note = null,
            )
        call.respondText(dto.status.name)
    }
    post("/test/bank/donate") {
        val q = call.request.queryParameters
        val dto =
            service(call).assignLineToDonation(
                lineId = q["lineId"]!!,
                input =
                    BankStatementDonationAssignmentInput(
                        donorMemberId = q["donorMemberId"],
                        externalDonorId = null,
                        donorCategory = DonorCategory.GERMAN_NATURAL_PERSON,
                        note = null,
                    ),
            )
        call.respondText(dto.status.name)
    }
}

private fun StatusPagesConfig.installBankStatementTestExceptionHandlers() {
    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
    exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
    exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
}
