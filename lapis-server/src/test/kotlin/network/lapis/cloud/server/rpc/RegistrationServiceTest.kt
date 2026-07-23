package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
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
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipAgreementAcknowledgmentTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.security.PasswordHasher
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AdminCreateMemberInput
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.RegistrationInput
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

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val MEMBER_ID = "00000000-0000-0000-0000-000000000004"
private const val STRONG_PASSWORD = "a-genuinely-strong-password-1"

/**
 * Exercises [RegistrationService] end to end, mirroring [CrowdfundingServiceTest]'s house style
 * (throwaway routes calling the service class directly). DevSeedData's ADMIN/BOARD/MEMBER accounts
 * are used only as the *actors* performing privileged actions (approve/reject/createMemberDirect)
 * -- every applicant/self-registered member is a fresh test member. [afterSpec] hard-deletes every
 * row this file created.
 */
class RegistrationServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec { cleanUpRegistrationTestData(createdMemberIds) }

        fun createTestMember(
            email: String,
            status: MemberStatus = MemberStatus.ANTRAG,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Registration Testmitglied"
                    it[MemberTable.email] = email
                    it[MemberTable.status] = status
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
            return id
        }

        fun findMemberIdByEmail(email: String): Uuid? =
            transaction {
                MemberTable
                    .selectAll()
                    .where { MemberTable.email eq email }
                    .singleOrNull()
                    ?.get(MemberTable.id)
            }

        fun statusOf(memberId: Uuid): MemberStatus =
            transaction {
                MemberTable.selectAll().where { MemberTable.id eq memberId }.single()[MemberTable.status]
            }

        test("getMembershipAgreement is reachable without any authentication") {
            testApplication {
                application {
                    install(StatusPages) { installRegistrationExceptionHandlers() }
                    routing { registerRegistrationTestRoutes(LoginRateLimiter()) }
                }
                val response = client.get("/test/agreement")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText().contains(":") shouldBe true
            }
        }

        test("registerApplication: happy path creates an ANTRAG member+account+acknowledgment") {
            testApplication {
                application {
                    install(StatusPages) { installRegistrationExceptionHandlers() }
                    routing { registerRegistrationTestRoutes(LoginRateLimiter()) }
                }
                val email = "reg-happy@example.org"

                val response = client.post("/test/register?email=$email&displayName=Neu+Mitglied")
                response.status shouldBe HttpStatusCode.OK

                val memberId = requireNotNull(findMemberIdByEmail(email))
                createdMemberIds += memberId
                statusOf(memberId) shouldBe MemberStatus.ANTRAG

                val ackCount =
                    transaction {
                        MembershipAgreementAcknowledgmentTable
                            .selectAll()
                            .where { MembershipAgreementAcknowledgmentTable.memberId eq memberId }
                            .count()
                    }
                ackCount shouldBe 1L

                val hasPasswordLogin =
                    transaction { AccountTable.selectAll().where { AccountTable.memberId eq memberId }.single()[AccountTable.passwordHash] }
                (hasPasswordLogin != null) shouldBe true
                PasswordHasher.verify(STRONG_PASSWORD, hasPasswordLogin) shouldBe true
            }
        }

        test("registerApplication: a stale/mismatched agreement version+hash is rejected, no row created") {
            testApplication {
                application {
                    install(StatusPages) { installRegistrationExceptionHandlers() }
                    routing { registerRegistrationTestRoutes(LoginRateLimiter()) }
                }
                val email = "reg-bad-agreement@example.org"

                val response =
                    client.post(
                        "/test/register?email=$email&agreementVersion=stale-version&agreementSha256=deadbeef",
                    )
                response.status shouldBe HttpStatusCode.Conflict
                findMemberIdByEmail(email) shouldBe null
            }
        }

        test("registerApplication: a weak password is rejected by PasswordPolicy, no row created") {
            testApplication {
                application {
                    install(StatusPages) { installRegistrationExceptionHandlers() }
                    routing { registerRegistrationTestRoutes(LoginRateLimiter()) }
                }
                val email = "reg-weak-password@example.org"

                val response = client.post("/test/register?email=$email&password=short")
                response.status shouldBe HttpStatusCode.BadRequest
                findMemberIdByEmail(email) shouldBe null
            }
        }

        test("registerApplication: a blank displayName is rejected, no row created") {
            testApplication {
                application {
                    install(StatusPages) { installRegistrationExceptionHandlers() }
                    routing { registerRegistrationTestRoutes(LoginRateLimiter()) }
                }
                val email = "reg-blank-name@example.org"

                val response = client.post("/test/register?email=$email&displayName=")
                response.status shouldBe HttpStatusCode.Conflict
                findMemberIdByEmail(email) shouldBe null
            }
        }

        test(
            "registerApplication: a duplicate email gets the IDENTICAL success response, no second row created (account-enumeration hardening)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installRegistrationExceptionHandlers() }
                    routing { registerRegistrationTestRoutes(LoginRateLimiter()) }
                }
                val email = "reg-duplicate@example.org"

                val first = client.post("/test/register?email=$email")
                first.status shouldBe HttpStatusCode.OK
                val memberId = requireNotNull(findMemberIdByEmail(email))
                createdMemberIds += memberId

                val second = client.post("/test/register?email=$email")
                second.status shouldBe first.status
                second.bodyAsText() shouldBe first.bodyAsText()

                val rowCount = transaction { MemberTable.selectAll().where { MemberTable.email eq email }.count() }
                rowCount shouldBe 1L
            }
        }

        test("registerApplication: repeated attempts eventually trip the rate limiter") {
            testApplication {
                application {
                    install(StatusPages) { installRegistrationExceptionHandlers() }
                    routing { registerRegistrationTestRoutes(LoginRateLimiter()) }
                }
                val statuses =
                    (1..10).map { i ->
                        client.post("/test/register?email=reg-rate-$i@example.org").status
                    }
                statuses.contains(HttpStatusCode.Conflict) shouldBe true
                // Every row created before the limiter tripped needs cleanup too.
                (1..10).forEach { i ->
                    findMemberIdByEmail("reg-rate-$i@example.org")?.let { createdMemberIds += it }
                }
            }
        }

        test("listPendingApplications: MEMBER is forbidden, BOARD sees only ANTRAG applicants") {
            testApplication {
                application {
                    install(StatusPages) { installRegistrationExceptionHandlers() }
                    routing { registerRegistrationTestRoutes(LoginRateLimiter()) }
                }
                val applicant = createTestMember("reg-list-applicant@example.org", MemberStatus.ANTRAG)
                val activeMember = createTestMember("reg-list-active@example.org", MemberStatus.AKTIV)

                val forbidden = client.get("/test/pending") { header("X-Member-Id", MEMBER_ID) }
                forbidden.status shouldBe HttpStatusCode.Forbidden

                val listed = client.get("/test/pending") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                (listed.contains(applicant.toString())) shouldBe true
                (listed.contains(activeMember.toString())) shouldBe false
            }
        }

        test("approveApplication: MEMBER is forbidden, BOARD approves ANTRAG -> AKTIV, a second decision conflicts") {
            testApplication {
                application {
                    install(StatusPages) { installRegistrationExceptionHandlers() }
                    routing { registerRegistrationTestRoutes(LoginRateLimiter()) }
                }
                val applicant = createTestMember("reg-approve@example.org", MemberStatus.ANTRAG)

                val forbidden = client.post("/test/approve/$applicant") { header("X-Member-Id", MEMBER_ID) }
                forbidden.status shouldBe HttpStatusCode.Forbidden

                val approved = client.post("/test/approve/$applicant") { header("X-Member-Id", BOARD_ID) }
                approved.status shouldBe HttpStatusCode.OK
                statusOf(applicant) shouldBe MemberStatus.AKTIV

                val secondDecision = client.post("/test/approve/$applicant") { header("X-Member-Id", BOARD_ID) }
                secondDecision.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("rejectApplication: a blank reason is rejected, a non-blank reason moves ANTRAG -> ABGELEHNT with the reason persisted") {
            testApplication {
                application {
                    install(StatusPages) { installRegistrationExceptionHandlers() }
                    routing { registerRegistrationTestRoutes(LoginRateLimiter()) }
                }
                val applicant = createTestMember("reg-reject@example.org", MemberStatus.ANTRAG)

                val blankReason = client.post("/test/reject/$applicant?reason=") { header("X-Member-Id", BOARD_ID) }
                blankReason.status shouldBe HttpStatusCode.Conflict
                statusOf(applicant) shouldBe MemberStatus.ANTRAG

                val rejected =
                    client.post("/test/reject/$applicant?reason=Unvollstaendige+Unterlagen") { header("X-Member-Id", BOARD_ID) }
                rejected.status shouldBe HttpStatusCode.OK
                statusOf(applicant) shouldBe MemberStatus.ABGELEHNT

                val reason =
                    transaction { MemberTable.selectAll().where { MemberTable.id eq applicant }.single()[MemberTable.rejectionReason] }
                reason shouldBe "Unvollstaendige Unterlagen"
            }
        }

        test(
            "approveApplication/rejectApplication: two concurrent board decisions on the SAME applicant -- exactly one wins, never a lost update",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installRegistrationExceptionHandlers() }
                    routing { registerRegistrationTestRoutes(LoginRateLimiter()) }
                }
                val applicant = createTestMember("reg-race@example.org", MemberStatus.ANTRAG)

                val outcomes = runConcurrentApproveAndReject(client, applicant)

                outcomes.count { it == HttpStatusCode.OK } shouldBe 1
                outcomes.count { it == HttpStatusCode.Conflict } shouldBe 1

                val finalStatus = statusOf(applicant)
                (finalStatus == MemberStatus.AKTIV || finalStatus == MemberStatus.ABGELEHNT) shouldBe true
            }
        }

        test(
            "createMemberDirect: MEMBER is forbidden, BOARD creating an ADMIN account is forbidden, BOARD creating role=MEMBER succeeds directly at AKTIV",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installRegistrationExceptionHandlers() }
                    routing { registerRegistrationTestRoutes(LoginRateLimiter()) }
                }

                val forbiddenByMember =
                    client.post("/test/create-direct?email=reg-direct-1@example.org&role=MEMBER") {
                        header("X-Member-Id", MEMBER_ID)
                    }
                forbiddenByMember.status shouldBe HttpStatusCode.Forbidden

                val forbiddenEscalation =
                    client.post("/test/create-direct?email=reg-direct-2@example.org&role=ADMIN") {
                        header("X-Member-Id", BOARD_ID)
                    }
                forbiddenEscalation.status shouldBe HttpStatusCode.Forbidden

                val email = "reg-direct-3@example.org"
                val happyPath = client.post("/test/create-direct?email=$email&role=MEMBER") { header("X-Member-Id", BOARD_ID) }
                happyPath.status shouldBe HttpStatusCode.OK
                val memberId = requireNotNull(findMemberIdByEmail(email))
                createdMemberIds += memberId
                statusOf(memberId) shouldBe MemberStatus.AKTIV

                val loginWorks = PasswordHasher.verify(STRONG_PASSWORD, storedPasswordHashDirect(memberId))
                loginWorks shouldBe true
            }
        }

        test("createMemberDirect: ADMIN creating role=ADMIN succeeds (only escalated roles require ADMIN specifically)") {
            testApplication {
                application {
                    install(StatusPages) { installRegistrationExceptionHandlers() }
                    routing { registerRegistrationTestRoutes(LoginRateLimiter()) }
                }
                val email = "reg-direct-admin@example.org"
                val response = client.post("/test/create-direct?email=$email&role=ADMIN") { header("X-Member-Id", ADMIN_ID) }
                response.status shouldBe HttpStatusCode.OK
                val memberId = requireNotNull(findMemberIdByEmail(email))
                createdMemberIds += memberId
            }
        }

        test("createMemberDirect: a duplicate email is rejected with a conflict") {
            testApplication {
                application {
                    install(StatusPages) { installRegistrationExceptionHandlers() }
                    routing { registerRegistrationTestRoutes(LoginRateLimiter()) }
                }
                val email = "reg-direct-dup@example.org"
                val first = client.post("/test/create-direct?email=$email&role=MEMBER") { header("X-Member-Id", BOARD_ID) }
                first.status shouldBe HttpStatusCode.OK
                createdMemberIds += requireNotNull(findMemberIdByEmail(email))

                val second = client.post("/test/create-direct?email=$email&role=MEMBER") { header("X-Member-Id", BOARD_ID) }
                second.status shouldBe HttpStatusCode.Conflict
            }
        }

        test(
            "leaveMembership: happy path AKTIV -> AUSGETRETEN, every session is revoked; a second call conflicts; an ANTRAG member cannot leave",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installRegistrationExceptionHandlers() }
                    routing { registerRegistrationTestRoutes(LoginRateLimiter()) }
                }
                val member = createTestMember("reg-leave@example.org", MemberStatus.AKTIV)
                val session = SessionStore.createSession(member)
                val otherSession = SessionStore.createSession(member)

                val response = client.post("/test/leave") { header("Authorization", "Bearer ${session.rawToken}") }
                response.status shouldBe HttpStatusCode.OK
                statusOf(member) shouldBe MemberStatus.AUSGETRETEN

                SessionStore.resolve(session.rawToken) shouldBe null
                SessionStore.resolve(otherSession.rawToken) shouldBe null

                val applicant = createTestMember("reg-leave-antrag@example.org", MemberStatus.ANTRAG)
                val applicantSession = SessionStore.createSession(applicant)
                val secondLeave = client.post("/test/leave") { header("Authorization", "Bearer ${applicantSession.rawToken}") }
                secondLeave.status shouldBe HttpStatusCode.Conflict
            }
        }
    })

private fun storedPasswordHashDirect(memberId: Uuid): String? =
    transaction { AccountTable.selectAll().where { AccountTable.memberId eq memberId }.single()[AccountTable.passwordHash] }

/**
 * Fires an approve and a reject call for the SAME applicant from two independent OS threads,
 * synchronized via [CountDownLatch] so both are issued as close to simultaneously as possible --
 * mirrors [PeerTransferServiceTest]'s own `runConcurrentOppositeTransfers` helper shape.
 */
private fun runConcurrentApproveAndReject(
    client: HttpClient,
    applicantId: Uuid,
    timeoutSeconds: Long = 20,
): List<HttpStatusCode> {
    val startLatch = CountDownLatch(2)
    val doneLatch = CountDownLatch(2)
    val results = mutableListOf<HttpStatusCode>()
    val failures = mutableListOf<Throwable>()

    fun actionThread(
        path: String,
        actorId: String,
    ): Thread =
        Thread {
            try {
                startLatch.countDown()
                startLatch.await(timeoutSeconds, TimeUnit.SECONDS)
                runBlocking {
                    val response = client.post(path) { header("X-Member-Id", actorId) }
                    synchronized(results) { results += response.status }
                }
            } catch (t: Throwable) {
                synchronized(failures) { failures += t }
            } finally {
                doneLatch.countDown()
            }
        }

    val approveThread = actionThread("/test/approve/$applicantId", BOARD_ID)
    val rejectThread = actionThread("/test/reject/$applicantId?reason=Race-Reject", ADMIN_ID)
    approveThread.start()
    rejectThread.start()

    val completed = doneLatch.await(timeoutSeconds, TimeUnit.SECONDS)
    check(completed) { "Concurrent approve/reject did not complete within ${timeoutSeconds}s -- likely deadlock" }
    if (failures.isNotEmpty()) throw failures.first()
    return results.toList()
}

private fun cleanUpRegistrationTestData(memberIds: List<Uuid>) {
    if (memberIds.isEmpty()) return
    transaction {
        SessionTable.deleteWhere { SessionTable.memberId inList memberIds }
        MembershipAgreementAcknowledgmentTable.deleteWhere { MembershipAgreementAcknowledgmentTable.memberId inList memberIds }
        AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
        MemberTable.deleteWhere { MemberTable.id inList memberIds }
    }
}

private fun StatusPagesConfig.installRegistrationExceptionHandlers() {
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

/** Shared throwaway routes for [RegistrationService] -- mirrors [CrowdfundingServiceTest]'s `registerCrowdfundingTestRoutes` style. */
private fun Route.registerRegistrationTestRoutes(rateLimiter: LoginRateLimiter) {
    get("/test/agreement") {
        val dto = RegistrationService(call, rateLimiter).getMembershipAgreement()
        call.respondText("${dto.version}:${dto.sha256}")
    }
    post("/test/register") {
        val q = call.request.queryParameters
        RegistrationService(call, rateLimiter).registerApplication(
            RegistrationInput(
                displayName = q["displayName"] ?: "Testmitglied",
                email = q["email"]!!,
                password = q["password"] ?: STRONG_PASSWORD,
                agreementVersion = q["agreementVersion"] ?: MembershipAgreementDisclaimer.VERSION,
                agreementSha256 = q["agreementSha256"] ?: MembershipAgreementDisclaimer.SHA256,
            ),
        )
        call.respondText("OK")
    }
    get("/test/pending") {
        val list = RegistrationService(call, rateLimiter).listPendingApplications()
        call.respondText(list.joinToString(",") { it.id })
    }
    post("/test/approve/{id}") {
        val dto = RegistrationService(call, rateLimiter).approveApplication(call.parameters["id"]!!)
        call.respondText(dto.status.name)
    }
    post("/test/reject/{id}") {
        val reason = call.request.queryParameters["reason"] ?: ""
        val dto = RegistrationService(call, rateLimiter).rejectApplication(call.parameters["id"]!!, reason)
        call.respondText(dto.status.name)
    }
    post("/test/create-direct") {
        val q = call.request.queryParameters
        val dto =
            RegistrationService(call, rateLimiter).createMemberDirect(
                AdminCreateMemberInput(
                    displayName = q["displayName"] ?: "Direktmitglied",
                    email = q["email"]!!,
                    role = AccountRole.valueOf(q["role"] ?: "MEMBER"),
                    temporaryPassword = q["password"] ?: STRONG_PASSWORD,
                ),
            )
        call.respondText("${dto.id}:${dto.status}:${dto.role}")
    }
    post("/test/leave") {
        val dto = RegistrationService(call, rateLimiter).leaveMembership()
        call.respondText(dto.status.name)
    }
}
