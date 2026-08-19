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

        fun createTestMember(
            email: String,
            status: MemberStatus = MemberStatus.ACTIVE,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Ledger Testmitglied"
                    it[MemberTable.email] = email
                    it[MemberTable.status] = status
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

        test(
            "Security-Audit-Runde 1, F4: mintLtr rejects a GUEST/APPLICATION/WITHDRAWN/REJECTED target with Conflict, " +
                "not merely NotFound -- the target EXISTS, only its status is unsuitable for receiving LTR",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installLtrLedgerExceptionHandlers() }
                    routing { registerLtrLedgerTestRoutes() }
                }
                listOf(MemberStatus.GUEST, MemberStatus.APPLICATION, MemberStatus.WITHDRAWN, MemberStatus.REJECTED).forEach { status ->
                    val target = createTestMember("ltr-mint-f4-${status.name.lowercase()}@example.org", status = status)
                    val rejected = client.post("/test/mint/$target/5.00") { header("X-Member-Id", TREASURER_ID) }
                    rejected.status shouldBe HttpStatusCode.Conflict
                }
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

        test("Welle V1.1.4: getMyBalance/listMyEntries -- FRIEND is admitted (0.00 with no entries, correct amount once minted)") {
            testApplication {
                application {
                    install(StatusPages) { installLtrLedgerExceptionHandlers() }
                    routing { registerLtrLedgerTestRoutes() }
                }
                val friend = createTestMember("ltr-friend-empty@example.org", status = MemberStatus.FRIEND)

                val emptyBalance = client.get("/test/my-balance") { header("X-Member-Id", friend.toString()) }
                emptyBalance.status shouldBe HttpStatusCode.OK
                BigDecimal(emptyBalance.bodyAsText()).compareTo(BigDecimal.ZERO) shouldBe 0

                val emptyEntries = client.get("/test/my-entries") { header("X-Member-Id", friend.toString()) }
                emptyEntries.status shouldBe HttpStatusCode.OK
                emptyEntries.bodyAsText() shouldBe ""

                val minted = client.post("/test/mint/$friend/12.50") { header("X-Member-Id", TREASURER_ID) }
                minted.status shouldBe HttpStatusCode.OK

                val fundedBalance = client.get("/test/my-balance") { header("X-Member-Id", friend.toString()) }.bodyAsText()
                BigDecimal(fundedBalance).compareTo(BigDecimal("12.50")) shouldBe 0

                val fundedEntries = client.get("/test/my-entries") { header("X-Member-Id", friend.toString()) }.bodyAsText()
                fundedEntries.split(",").size shouldBe 1
            }
        }

        test("Welle V1.1.4: getMyBalance/listMyEntries -- GUEST/APPLICATION/WITHDRAWN/REJECTED remain ForbiddenException") {
            testApplication {
                application {
                    install(StatusPages) { installLtrLedgerExceptionHandlers() }
                    routing { registerLtrLedgerTestRoutes() }
                }
                listOf(MemberStatus.GUEST, MemberStatus.APPLICATION, MemberStatus.WITHDRAWN, MemberStatus.REJECTED).forEach { status ->
                    val member = createTestMember("ltr-nonltr-${status.name.lowercase()}@example.org", status = status)
                    val balance = client.get("/test/my-balance") { header("X-Member-Id", member.toString()) }
                    balance.status shouldBe HttpStatusCode.Forbidden
                    val entries = client.get("/test/my-entries") { header("X-Member-Id", member.toString()) }
                    entries.status shouldBe HttpStatusCode.Forbidden
                }
            }
        }

        test("Welle V1.1.4: BOARD can mint LTR to a FRIEND target (regression guard for the acquisition path)") {
            testApplication {
                application {
                    install(StatusPages) { installLtrLedgerExceptionHandlers() }
                    routing { registerLtrLedgerTestRoutes() }
                }
                val friend = createTestMember("ltr-friend-mint-target@example.org", status = MemberStatus.FRIEND)
                val minted = client.post("/test/mint/$friend/3.00") { header("X-Member-Id", BOARD_ID) }
                minted.status shouldBe HttpStatusCode.OK
                val balance = client.get("/test/my-balance") { header("X-Member-Id", friend.toString()) }.bodyAsText()
                BigDecimal(balance).compareTo(BigDecimal("3.00")) shouldBe 0
            }
        }

        test("Welle V1.1.4: a FRIEND caller of mintLtr is still rejected -- role gate is unrelated to LTR_ELIGIBLE, unchanged") {
            testApplication {
                application {
                    install(StatusPages) { installLtrLedgerExceptionHandlers() }
                    routing { registerLtrLedgerTestRoutes() }
                }
                val friend = createTestMember("ltr-friend-mint-caller@example.org", status = MemberStatus.FRIEND)
                val target = createTestMember("ltr-friend-mint-caller-target@example.org")
                val rejected = client.post("/test/mint/$target/5.00") { header("X-Member-Id", friend.toString()) }
                rejected.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test(
            "Welle V1.1.4: getMemberBalance/listMemberEntries for SELF now require LTR_ELIGIBLE too -- a GUEST querying itself is Forbidden, a FRIEND querying itself is OK",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installLtrLedgerExceptionHandlers() }
                    routing { registerLtrLedgerTestRoutes() }
                }
                val friend = createTestMember("ltr-friend-self-balance@example.org", status = MemberStatus.FRIEND)
                val friendSelfBalance = client.get("/test/balance/$friend") { header("X-Member-Id", friend.toString()) }
                friendSelfBalance.status shouldBe HttpStatusCode.OK
                val friendSelfEntries = client.get("/test/entries/$friend") { header("X-Member-Id", friend.toString()) }
                friendSelfEntries.status shouldBe HttpStatusCode.OK

                val guest = createTestMember("ltr-guest-self-balance@example.org", status = MemberStatus.GUEST)
                val guestSelfBalance = client.get("/test/balance/$guest") { header("X-Member-Id", guest.toString()) }
                guestSelfBalance.status shouldBe HttpStatusCode.Forbidden
                val guestSelfEntries = client.get("/test/entries/$guest") { header("X-Member-Id", guest.toString()) }
                guestSelfEntries.status shouldBe HttpStatusCode.Forbidden

                // Foreign lookup by a privileged TREASURER is unaffected -- it never runs the
                // self-branch, only the role check.
                val privileged = client.get("/test/balance/$guest") { header("X-Member-Id", TREASURER_ID) }
                privileged.status shouldBe HttpStatusCode.OK
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
        val service = LtrLedgerService(call = call)
        call.respondText(service.getMyBalance().freeBalanceLtr.toString())
    }
    get("/test/my-entries") {
        val service = LtrLedgerService(call = call)
        call.respondText(service.listMyEntries().joinToString(",") { it.id })
    }
    get("/test/balance/{memberId}") {
        val service = LtrLedgerService(call = call)
        call.respondText(service.getMemberBalance(call.parameters["memberId"]!!).freeBalanceLtr.toString())
    }
    get("/test/entries/{memberId}") {
        val service = LtrLedgerService(call = call)
        call.respondText(service.listMemberEntries(memberId = call.parameters["memberId"]!!).joinToString(",") { it.id })
    }
    post("/test/mint/{memberId}/{amount}") {
        val service = LtrLedgerService(call = call)
        val e = service.mintLtr(MintLtrInput(memberId = call.parameters["memberId"]!!, amountLtr = BigDecimal(call.parameters["amount"]!!)))
        call.respondText(e.id)
    }
}
