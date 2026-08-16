package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.FriendTermsAcknowledgmentTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipAgreementAcknowledgmentTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.security.PasswordHasher
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import network.lapis.cloud.shared.rpc.WeakPasswordException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val STRONG_PASSWORD = "a-genuinely-strong-password-1"

/**
 * Exercises [RegistrationService.applyForMembership] end to end -- the caller's own
 * `FRIEND -> APPLICATION` upgrade -- mirroring [RegistrationServiceTest]'s house style. Covers the
 * full journey to [MemberStatus.ACTIVE] via [RegistrationService.approveApplication], and the
 * [MemberDto.friendSince]-driven [RegistrationService.rejectApplication] fallback to
 * [MemberStatus.FRIEND] rather than [MemberStatus.REJECTED] (Stolperfalle #21).
 */
class FriendUpgradePathTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec { cleanUpFriendUpgradeTestData(createdMemberIds) }

        fun createFriendMember(email: String): Pair<Uuid, String> {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Upgrade Testfreund"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.FRIEND
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                    it[friendSince] = LocalDate(2026, 1, 1)
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = AccountRole.MEMBER
                    it[passwordHash] = PasswordHasher.hash(STRONG_PASSWORD)
                }
            }
            createdMemberIds += id
            val rawToken = SessionStore.createSession(id).rawToken
            return id to rawToken
        }

        fun statusOf(memberId: Uuid): MemberStatus =
            transaction {
                MemberTable.selectAll().where { MemberTable.id eq memberId }.single()[MemberTable.status]
            }

        fun friendSinceOf(memberId: Uuid): LocalDate? =
            transaction {
                MemberTable.selectAll().where { MemberTable.id eq memberId }.single()[MemberTable.friendSince]
            }

        test(
            "applyForMembership: FRIEND -> APPLICATION flips status, writes a MembershipAgreementAcknowledgment row, preserves the session, friendSince retained",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installFriendUpgradeExceptionHandlers() }
                    routing { registerFriendUpgradeTestRoutes() }
                }
                val (memberId, rawToken) = createFriendMember("upgrade-happy@example.org")

                val response = client.post("/test/apply-for-membership") { header("Authorization", "Bearer $rawToken") }
                response.status shouldBe HttpStatusCode.OK
                statusOf(memberId) shouldBe MemberStatus.APPLICATION
                friendSinceOf(memberId) shouldBe LocalDate(2026, 1, 1)

                val ackCount =
                    transaction {
                        MembershipAgreementAcknowledgmentTable
                            .selectAll()
                            .where {
                                MembershipAgreementAcknowledgmentTable.memberId eq
                                    memberId
                            }.count()
                    }
                ackCount shouldBe 1L

                // Session preserved -- NOT revoked, unlike rejectApplication/leaveMembership.
                SessionStore.resolve(rawToken) shouldNotBe null
            }
        }

        test("applyForMembership: a stale/mismatched agreement version+hash is rejected, status unchanged") {
            testApplication {
                application {
                    install(StatusPages) { installFriendUpgradeExceptionHandlers() }
                    routing { registerFriendUpgradeTestRoutes() }
                }
                val (memberId, rawToken) = createFriendMember("upgrade-bad-agreement@example.org")

                val response =
                    client.post("/test/apply-for-membership?agreementVersion=stale&agreementSha256=deadbeef") {
                        header("Authorization", "Bearer $rawToken")
                    }
                response.status shouldBe HttpStatusCode.Conflict
                statusOf(memberId) shouldBe MemberStatus.FRIEND
            }
        }

        test("applyForMembership: a non-FRIEND caller (e.g. already ACTIVE) conflicts, status unchanged") {
            testApplication {
                application {
                    install(StatusPages) { installFriendUpgradeExceptionHandlers() }
                    routing { registerFriendUpgradeTestRoutes() }
                }
                val id = Uuid.random()
                transaction {
                    MemberTable.insert {
                        it[MemberTable.id] = id
                        it[displayName] = "Bereits Aktiv"
                        it[email] = "upgrade-already-active@example.org"
                        it[status] = MemberStatus.ACTIVE
                        it[joinedAt] = LocalDate(2026, 1, 1)
                        it[membershipTierId] = null
                    }
                    AccountTable.insert {
                        it[AccountTable.id] = Uuid.random()
                        it[memberId] = id
                        it[AccountTable.role] = AccountRole.MEMBER
                        it[passwordHash] = PasswordHasher.hash(STRONG_PASSWORD)
                    }
                }
                createdMemberIds += id
                val rawToken = SessionStore.createSession(id).rawToken

                val response = client.post("/test/apply-for-membership") { header("Authorization", "Bearer $rawToken") }
                response.status shouldBe HttpStatusCode.Conflict
                statusOf(id) shouldBe MemberStatus.ACTIVE
            }
        }

        test(
            "applyForMembership: two concurrent calls on the SAME friend -- exactly one succeeds, never a lost update / double acknowledgment",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installFriendUpgradeExceptionHandlers() }
                    routing { registerFriendUpgradeTestRoutes() }
                }
                val (memberId, rawToken) = createFriendMember("upgrade-race@example.org")

                val outcomes = runConcurrentApplyForMembership(client = client, rawToken = rawToken)
                outcomes.count { it == HttpStatusCode.OK } shouldBe 1
                outcomes.count { it == HttpStatusCode.Conflict } shouldBe 1

                statusOf(memberId) shouldBe MemberStatus.APPLICATION
                val ackCount =
                    transaction {
                        MembershipAgreementAcknowledgmentTable
                            .selectAll()
                            .where {
                                MembershipAgreementAcknowledgmentTable.memberId eq
                                    memberId
                            }.count()
                    }
                ackCount shouldBe 1L
            }
        }

        test(
            "Full upgrade journey: FRIEND applies -> APPLICATION -> BOARD approves -> ACTIVE; a SEPARATE friend applies -> BOARD rejects -> falls back to FRIEND (not REJECTED), can still log in",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installFriendUpgradeExceptionHandlers() }
                    routing {
                        registerFriendUpgradeTestRoutes()
                        registerRegistrationTestRoutes(LoginRateLimiter())
                    }
                }

                // Approve path.
                val (approveMemberId, approveToken) = createFriendMember("upgrade-journey-approve@example.org")
                client.post("/test/apply-for-membership") { header("Authorization", "Bearer $approveToken") }.status shouldBe
                    HttpStatusCode.OK
                statusOf(approveMemberId) shouldBe MemberStatus.APPLICATION

                val approved = client.post("/test/approve/$approveMemberId") { header("X-Member-Id", BOARD_ID) }
                approved.status shouldBe HttpStatusCode.OK
                statusOf(approveMemberId) shouldBe MemberStatus.ACTIVE

                // Reject path: friendSince fallback -- must land on FRIEND, not REJECTED.
                val (rejectMemberId, rejectToken) = createFriendMember("upgrade-journey-reject@example.org")
                client.post("/test/apply-for-membership") { header("Authorization", "Bearer $rejectToken") }.status shouldBe
                    HttpStatusCode.OK
                statusOf(rejectMemberId) shouldBe MemberStatus.APPLICATION

                val rejected =
                    client.post("/test/reject/$rejectMemberId?reason=Noch+nicht+bereit") { header("X-Member-Id", BOARD_ID) }
                rejected.status shouldBe HttpStatusCode.OK
                statusOf(rejectMemberId) shouldBe MemberStatus.FRIEND

                // The account still exists and its password credential is untouched -- "can still
                // log in" per the plan's own wording (login-gate behaviour is AuthRoutes' own
                // concern, exercised elsewhere; this asserts the password hash survives intact).
                val storedHash =
                    transaction {
                        AccountTable.selectAll().where { AccountTable.memberId eq rejectMemberId }.single()[AccountTable.passwordHash]
                    }
                PasswordHasher.verify(rawPassword = STRONG_PASSWORD, storedHash = storedHash) shouldBe true
            }
        }
    })

private fun runConcurrentApplyForMembership(
    client: HttpClient,
    rawToken: String,
    timeoutSeconds: Long = 20,
): List<HttpStatusCode> {
    val startLatch = CountDownLatch(2)
    val doneLatch = CountDownLatch(2)
    val results = mutableListOf<HttpStatusCode>()
    val failures = mutableListOf<Throwable>()

    fun actionThread(): Thread =
        Thread {
            try {
                startLatch.countDown()
                startLatch.await(timeoutSeconds, TimeUnit.SECONDS)
                runBlocking {
                    val response = client.post("/test/apply-for-membership") { header("Authorization", "Bearer $rawToken") }
                    synchronized(results) { results += response.status }
                }
            } catch (t: Throwable) {
                synchronized(failures) { failures += t }
            } finally {
                doneLatch.countDown()
            }
        }

    val first = actionThread()
    val second = actionThread()
    first.start()
    second.start()

    val completed = doneLatch.await(timeoutSeconds, TimeUnit.SECONDS)
    check(completed) { "Concurrent applyForMembership did not complete within ${timeoutSeconds}s -- likely deadlock" }
    if (failures.isNotEmpty()) throw failures.first()
    return results.toList()
}

private fun cleanUpFriendUpgradeTestData(memberIds: List<Uuid>) {
    if (memberIds.isEmpty()) return
    transaction {
        network.lapis.cloud.server.db.generated.SessionTable.deleteWhere {
            network.lapis.cloud.server.db.generated.SessionTable.memberId inList memberIds
        }
        FriendTermsAcknowledgmentTable.deleteWhere { FriendTermsAcknowledgmentTable.memberId inList memberIds }
        MembershipAgreementAcknowledgmentTable.deleteWhere { MembershipAgreementAcknowledgmentTable.memberId inList memberIds }
        AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
        MemberTable.deleteWhere { MemberTable.id inList memberIds }
    }
}

private fun StatusPagesConfig.installFriendUpgradeExceptionHandlers() {
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
    exception<WeakPasswordException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.BadRequest)
    }
}

private fun Route.registerFriendUpgradeTestRoutes() {
    fun registrationService(call: ApplicationCall) =
        RegistrationService(
            call = call,
            registrationRateLimiter = LoginRateLimiter(),
            friendRegistrationRateLimiter = LoginRateLimiter(),
            friendSignupIpRateLimiter = FederationInboxRateLimiter(),
        )
    post("/test/apply-for-membership") {
        val q = call.request.queryParameters
        val dto =
            registrationService(call).applyForMembership(
                agreementVersion = q["agreementVersion"] ?: MembershipAgreementDisclaimer.VERSION,
                agreementSha256 = q["agreementSha256"] ?: MembershipAgreementDisclaimer.SHA256,
            )
        call.respondText(dto.status.name)
    }
}

/** Reuses [RegistrationService.approveApplication]/[RegistrationService.rejectApplication] via the SAME throwaway-route shape [RegistrationServiceTest] establishes. */
private fun Route.registerRegistrationTestRoutes(rateLimiter: LoginRateLimiter) {
    fun registrationService(call: ApplicationCall) =
        RegistrationService(
            call = call,
            registrationRateLimiter = rateLimiter,
            friendRegistrationRateLimiter = LoginRateLimiter(),
            friendSignupIpRateLimiter = FederationInboxRateLimiter(),
        )
    post("/test/approve/{id}") {
        val dto = registrationService(call).approveApplication(call.parameters["id"]!!)
        call.respondText(dto.status.name)
    }
    post("/test/reject/{id}") {
        val reason = call.request.queryParameters["reason"] ?: ""
        val dto = registrationService(call).rejectApplication(memberId = call.parameters["id"]!!, reason = reason)
        call.respondText(dto.status.name)
    }
}
