package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
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
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MintLtrInput
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.uuid.Uuid

private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val TREASURER_ID = "00000000-0000-0000-0000-000000000003"
private const val MEMBER_ID = "00000000-0000-0000-0000-000000000004"

/**
 * Exercises [LtrLedgerService] end to end, mirroring [CrowdfundingServiceTest]'s house style.
 * DevSeedData's TREASURER/BOARD accounts are used only as the *actors* minting LTR; every member
 * whose balance/entries are asserted is a fresh test member.
 */
class LtrLedgerServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            if (createdMemberIds.isNotEmpty()) {
                transaction {
                    LtrLedgerEntryTable.deleteWhere { LtrLedgerEntryTable.memberId inList createdMemberIds }
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
        }

        fun createTestMember(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Ledger Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.AKTIV
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += id
            return id
        }

        test("freeBalance is ZERO for a member with no ledger entries at all") {
            testApplication {
                application {
                    install(StatusPages) { installLtrLedgerExceptionHandlers() }
                    routing { registerLtrLedgerTestRoutes() }
                }
                val member = createTestMember("ltr-empty@example.org")
                val balance = client.get("/test/balance/$member") { header("X-Member-Id", member.toString()) }.bodyAsText()
                BigDecimal(balance).compareTo(BigDecimal.ZERO) shouldBe 0
            }
        }

        test(
            "mintLtr: MEMBER forbidden, TREASURER/BOARD/ADMIN allowed, produces a POSITIVE ledger entry, sums correctly with a later debit",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installLtrLedgerExceptionHandlers() }
                    routing { registerLtrLedgerTestRoutes() }
                }
                val target = createTestMember("ltr-mint-target@example.org")

                val forbidden = client.post("/test/mint/$target/10.00") { header("X-Member-Id", MEMBER_ID) }
                forbidden.status shouldBe HttpStatusCode.Forbidden

                val minted = client.post("/test/mint/$target/10.00") { header("X-Member-Id", TREASURER_ID) }
                minted.status shouldBe HttpStatusCode.OK

                val row =
                    transaction {
                        LtrLedgerEntryTable
                            .selectAll()
                            .where {
                                (LtrLedgerEntryTable.memberId eq target) and (LtrLedgerEntryTable.entryType eq LtrLedgerEntryType.MINT)
                            }.single()
                    }
                // Vorzeichen-Regression: a MINT credit must be strictly positive.
                (row[LtrLedgerEntryTable.amountLtr].signum() > 0) shouldBe true
                row[LtrLedgerEntryTable.amountLtr].compareTo(BigDecimal("10.00")) shouldBe 0

                val secondMint = client.post("/test/mint/$target/5.00") { header("X-Member-Id", BOARD_ID) }
                secondMint.status shouldBe HttpStatusCode.OK

                val balance = client.get("/test/balance/$target") { header("X-Member-Id", target.toString()) }.bodyAsText()
                BigDecimal(balance).compareTo(BigDecimal("15.00")) shouldBe 0
            }
        }

        test("mintLtr rejects a zero/negative amount") {
            testApplication {
                application {
                    install(StatusPages) { installLtrLedgerExceptionHandlers() }
                    routing { registerLtrLedgerTestRoutes() }
                }
                val target = createTestMember("ltr-mint-zero@example.org")
                val rejected = client.post("/test/mint/$target/0.00") { header("X-Member-Id", TREASURER_ID) }
                rejected.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("getMemberBalance/listMemberEntries: self always allowed, another member's data requires TREASURY roles") {
            testApplication {
                application {
                    install(StatusPages) { installLtrLedgerExceptionHandlers() }
                    routing { registerLtrLedgerTestRoutes() }
                }
                val self = createTestMember("ltr-self@example.org")
                val other = createTestMember("ltr-other@example.org")

                val selfBalance = client.get("/test/balance/$self") { header("X-Member-Id", self.toString()) }
                selfBalance.status shouldBe HttpStatusCode.OK

                val forbiddenBalance = client.get("/test/balance/$other") { header("X-Member-Id", self.toString()) }
                forbiddenBalance.status shouldBe HttpStatusCode.Forbidden

                val privilegedBalance = client.get("/test/balance/$other") { header("X-Member-Id", TREASURER_ID) }
                privilegedBalance.status shouldBe HttpStatusCode.OK

                val selfEntries = client.get("/test/entries/$self") { header("X-Member-Id", self.toString()) }
                selfEntries.status shouldBe HttpStatusCode.OK

                val forbiddenEntries = client.get("/test/entries/$other") { header("X-Member-Id", self.toString()) }
                forbiddenEntries.status shouldBe HttpStatusCode.Forbidden
            }
        }
    })

private fun StatusPagesConfig.installLtrLedgerExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
    }
    exception<ForbiddenException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.Forbidden)
    }
    exception<NotFoundException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.NotFound)
    }
    exception<ConflictException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.Conflict)
    }
}

/** Shared throwaway routes for [LtrLedgerService] -- mirrors [CrowdfundingServiceTest]'s `registerCrowdfundingTestRoutes` style. */
private fun Route.registerLtrLedgerTestRoutes() {
    get("/test/my-balance") {
        val service = LtrLedgerService(call)
        call.respondText(service.getMyBalance().freeBalanceLtr.toString())
    }
    get("/test/balance/{memberId}") {
        val service = LtrLedgerService(call)
        call.respondText(service.getMemberBalance(call.parameters["memberId"]!!).freeBalanceLtr.toString())
    }
    get("/test/entries/{memberId}") {
        val service = LtrLedgerService(call)
        call.respondText(service.listMemberEntries(call.parameters["memberId"]!!).joinToString(",") { it.id })
    }
    post("/test/mint/{memberId}/{amount}") {
        val service = LtrLedgerService(call)
        val e = service.mintLtr(MintLtrInput(memberId = call.parameters["memberId"]!!, amountLtr = BigDecimal(call.parameters["amount"]!!)))
        call.respondText(e.id)
    }
}
