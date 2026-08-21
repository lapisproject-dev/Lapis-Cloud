package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
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
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.BoardMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipAgreementAcknowledgmentTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.db.generated.TransparenzregisterReminderTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.FakeFriendVerificationMailer
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.security.PasswordHasher
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AdminCreateMemberInput
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.CommitteeInput
import network.lapis.cloud.shared.domain.CommitteeMembershipInput
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.RegistrationInput
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import network.lapis.cloud.shared.rpc.WeakPasswordException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
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
        val createdCommitteeIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec { cleanUpRegistrationTestData(memberIds = createdMemberIds, committeeIds = createdCommitteeIds) }

        fun createTestMember(
            email: String,
            status: MemberStatus = MemberStatus.APPLICATION,
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
                statusOf(memberId) shouldBe MemberStatus.APPLICATION

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
                PasswordHasher.verify(rawPassword = STRONG_PASSWORD, storedHash = hasPasswordLogin) shouldBe true
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

        test(
            "registerApplication: a malformed email (embedded CR/LF, header/log injection attempt) is rejected, no row created (security-review fix V1.2.3)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installRegistrationExceptionHandlers() }
                    routing { registerRegistrationTestRoutes(LoginRateLimiter()) }
                }
                // %0D%0A decodes to an actual CR/LF pair by the time RegistrationService sees
                // `queryParameters["email"]` -- same attack shape as the CRLF-log-injection finding
                // (a forged fake log line appended after a real recipient address).
                val forgedEmail =
                    "a@evil.tld%0D%0A16:05:11.000%20%5Bmain%5D%20ERROR%20n.l.c.s.security.SessionStore%20-%20forged"

                val response = client.post("/test/register?email=$forgedEmail")
                response.status shouldBe HttpStatusCode.Conflict
                findMemberIdByEmail("a@evil.tld") shouldBe null
            }
        }

        test("registerApplication: a plain malformed email (no @) is rejected, no row created") {
            testApplication {
                application {
                    install(StatusPages) { installRegistrationExceptionHandlers() }
                    routing { registerRegistrationTestRoutes(LoginRateLimiter()) }
                }
                client.post("/test/register?email=not-an-email-at-all").status shouldBe HttpStatusCode.Conflict
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

        test(
            "registerApplication: the duplicate-email no-op path pays the same bcrypt cost as the " +
                "new-application path (timing side-channel closed, same shape as the FRIEND-wave F1 fix)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installRegistrationExceptionHandlers() }
                    routing { registerRegistrationTestRoutes(LoginRateLimiter()) }
                }
                val email = "reg-timing@example.org"

                // First call creates the application.
                val first = client.post("/test/register?email=$email")
                first.status shouldBe HttpStatusCode.OK
                val memberId = requireNotNull(findMemberIdByEmail(email))
                createdMemberIds += memberId

                // Second call hits the duplicate-email no-op branch. Before this fix that branch
                // returned after a single, sub-millisecond SELECT COUNT -- well before
                // PasswordHasher.hash (bcrypt cost 12, tens-to-hundreds of ms) was ever reached, so
                // an attacker could distinguish "this email is already an applicant/member" from "this
                // email is new" by response latency alone, without ever reading the (identical)
                // response body -- enumerating this political party's applicant/member roster.
                val start = System.nanoTime()
                val second = client.post("/test/register?email=$email")
                val elapsedMillis = (System.nanoTime() - start) / 1_000_000
                second.status shouldBe HttpStatusCode.OK

                // Conservative floor: a plain SELECT COUNT on the in-memory test DB completes in
                // low single-digit milliseconds, while bcrypt at cost 12 takes tens of milliseconds
                // at an absolute minimum on any real hardware. If the fix regresses (the hash moves
                // back inside the "new member" branch only), this duplicate-path call becomes fast
                // again and this assertion starts failing.
                (elapsedMillis >= 20L) shouldBe true
            }
        }

        test(
            "registerApplication: two concurrent requests with the SAME email -- both succeed identically (no 500), exactly one account created (V0.13.1 race fix)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installRegistrationExceptionHandlers() }
                    routing { registerRegistrationTestRoutes(LoginRateLimiter()) }
                }
                val email = "reg-concurrent-dup@example.org"

                val outcomes = runConcurrentDuplicateRegistrations(client = client, path = "/test/register", email = email)

                // BEFORE the V0.13.1 fix, the loser of this race hit an uncaught ExposedSQLException
                // from MemberTable's UNIQUE(email) constraint and got a raw 500 instead of the
                // account-enumeration-hardening no-op every OTHER duplicate-email path already gets.
                outcomes.count { it == HttpStatusCode.OK } shouldBe 2

                val memberId = requireNotNull(findMemberIdByEmail(email))
                createdMemberIds += memberId

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
                val applicant = createTestMember("reg-list-applicant@example.org", MemberStatus.APPLICATION)
                val activeMember = createTestMember("reg-list-active@example.org", MemberStatus.ACTIVE)

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
                val applicant = createTestMember("reg-approve@example.org", MemberStatus.APPLICATION)

                val forbidden = client.post("/test/approve/$applicant") { header("X-Member-Id", MEMBER_ID) }
                forbidden.status shouldBe HttpStatusCode.Forbidden

                val approved = client.post("/test/approve/$applicant") { header("X-Member-Id", BOARD_ID) }
                approved.status shouldBe HttpStatusCode.OK
                statusOf(applicant) shouldBe MemberStatus.ACTIVE

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
                val applicant = createTestMember("reg-reject@example.org", MemberStatus.APPLICATION)

                val blankReason = client.post("/test/reject/$applicant?reason=") { header("X-Member-Id", BOARD_ID) }
                blankReason.status shouldBe HttpStatusCode.Conflict
                statusOf(applicant) shouldBe MemberStatus.APPLICATION

                val rejected =
                    client.post("/test/reject/$applicant?reason=Unvollstaendige+Unterlagen") { header("X-Member-Id", BOARD_ID) }
                rejected.status shouldBe HttpStatusCode.OK
                statusOf(applicant) shouldBe MemberStatus.REJECTED

                val reason =
                    transaction { MemberTable.selectAll().where { MemberTable.id eq applicant }.single()[MemberTable.rejectionReason] }
                reason shouldBe "Unvollstaendige Unterlagen"
            }
        }

        test(
            "rejectApplication: every live session the applicant already established is revoked (session-hygiene gap closed, V0.7.2 audit commit 5082d55)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installRegistrationExceptionHandlers() }
                    routing { registerRegistrationTestRoutes(LoginRateLimiter()) }
                }
                val applicant = createTestMember("reg-reject-session@example.org", MemberStatus.APPLICATION)
                val session = SessionStore.createSession(applicant)
                val otherSession = SessionStore.createSession(applicant)

                // Sanity: both sessions are genuinely live before the rejection.
                SessionStore.resolve(session.rawToken) shouldNotBe null
                SessionStore.resolve(otherSession.rawToken) shouldNotBe null

                val rejected =
                    client.post("/test/reject/$applicant?reason=Unvollstaendige+Unterlagen") { header("X-Member-Id", BOARD_ID) }
                rejected.status shouldBe HttpStatusCode.OK
                statusOf(applicant) shouldBe MemberStatus.REJECTED

                // Real behavioral assertion, same mechanism resolveCurrentMember uses in production --
                // not a "was the method called" check.
                SessionStore.resolve(session.rawToken) shouldBe null
                SessionStore.resolve(otherSession.rawToken) shouldBe null
            }
        }

        test("rejectApplication: an applicant with no live session (never logged in) is still rejected without error") {
            testApplication {
                application {
                    install(StatusPages) { installRegistrationExceptionHandlers() }
                    routing { registerRegistrationTestRoutes(LoginRateLimiter()) }
                }
                val applicant = createTestMember("reg-reject-no-session@example.org", MemberStatus.APPLICATION)

                val rejected = client.post("/test/reject/$applicant?reason=Kein+Login") { header("X-Member-Id", BOARD_ID) }
                rejected.status shouldBe HttpStatusCode.OK
                statusOf(applicant) shouldBe MemberStatus.REJECTED
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
                val applicant = createTestMember("reg-race@example.org", MemberStatus.APPLICATION)

                val outcomes = runConcurrentApproveAndReject(client = client, applicantId = applicant)

                outcomes.count { it == HttpStatusCode.OK } shouldBe 1
                outcomes.count { it == HttpStatusCode.Conflict } shouldBe 1

                val finalStatus = statusOf(applicant)
                (finalStatus == MemberStatus.ACTIVE || finalStatus == MemberStatus.REJECTED) shouldBe true
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
                statusOf(memberId) shouldBe MemberStatus.ACTIVE

                val loginWorks = PasswordHasher.verify(rawPassword = STRONG_PASSWORD, storedHash = storedPasswordHashDirect(memberId))
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
                val member = createTestMember("reg-leave@example.org", MemberStatus.ACTIVE)
                val session = SessionStore.createSession(member)
                val otherSession = SessionStore.createSession(member)

                val response = client.post("/test/leave") { header("Authorization", "Bearer ${session.rawToken}") }
                response.status shouldBe HttpStatusCode.OK
                statusOf(member) shouldBe MemberStatus.WITHDRAWN

                SessionStore.resolve(session.rawToken) shouldBe null
                SessionStore.resolve(otherSession.rawToken) shouldBe null

                val applicant = createTestMember("reg-leave-antrag@example.org", MemberStatus.APPLICATION)
                val applicantSession = SessionStore.createSession(applicant)
                val secondLeave = client.post("/test/leave") { header("Authorization", "Bearer ${applicantSession.rawToken}") }
                secondLeave.status shouldBe HttpStatusCode.Conflict
            }
        }

        test(
            "leaveMembership: stale-roster fix -- an open (non-EXECUTIVE_BOARD) Committee membership is ended when the member leaves (V0.13.1)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installRegistrationExceptionHandlers() }
                    routing {
                        registerRegistrationTestRoutes(LoginRateLimiter())
                        registerCommitteeHelperRoutesForRegistrationTests()
                    }
                }
                val member = createTestMember("reg-leave-committee@example.org", MemberStatus.ACTIVE)
                val session = SessionStore.createSession(member)

                val committeeId =
                    client.post("/test/gov/create-committee/WORKING_GROUP") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                createdCommitteeIds += Uuid.parse(committeeId)
                client
                    .post("/test/gov/add-member/$committeeId/$member") { header("X-Member-Id", BOARD_ID) }
                    .status shouldBe HttpStatusCode.OK

                val beforeLeave = client.get("/test/gov/list-members/$committeeId") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                beforeLeave.contains(member.toString()) shouldBe true

                client.post("/test/leave") { header("Authorization", "Bearer ${session.rawToken}") }.status shouldBe HttpStatusCode.OK

                // The real behavioral assertion: GovernanceService.listCommitteeMembers(activeOnly =
                // true) -- exactly the query this fix targets -- must no longer list the departed
                // member.
                val afterLeave = client.get("/test/gov/list-members/$committeeId") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                afterLeave.contains(member.toString()) shouldBe false

                val untilSet =
                    transaction {
                        CommitteeMembershipTable
                            .selectAll()
                            .where { CommitteeMembershipTable.memberId eq member }
                            .single()[CommitteeMembershipTable.until]
                    }
                untilSet shouldNotBe null
            }
        }

        test(
            "leaveMembership: stale-roster fix -- EXECUTIVE_BOARD cascade (BoardMembershipTable ended + GoBD audit UPDATE entry) when the member leaves (V0.13.1)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installRegistrationExceptionHandlers() }
                    routing {
                        registerRegistrationTestRoutes(LoginRateLimiter())
                        registerCommitteeHelperRoutesForRegistrationTests()
                    }
                }
                val member = createTestMember("reg-leave-board@example.org", MemberStatus.ACTIVE)
                val session = SessionStore.createSession(member)

                val committeeId =
                    client.post("/test/gov/create-committee/EXECUTIVE_BOARD") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                createdCommitteeIds += Uuid.parse(committeeId)
                client
                    .post("/test/gov/add-member/$committeeId/$member") { header("X-Member-Id", BOARD_ID) }
                    .status shouldBe HttpStatusCode.OK

                val boardMembershipId =
                    transaction {
                        BoardMembershipTable.selectAll().where { BoardMembershipTable.memberId eq member }.single()[
                            BoardMembershipTable.id,
                        ]
                    }
                transaction {
                    BoardMembershipTable.selectAll().where { BoardMembershipTable.id eq boardMembershipId }.single()[
                        BoardMembershipTable.endedAt,
                    ]
                } shouldBe null

                client.post("/test/leave") { header("Authorization", "Bearer ${session.rawToken}") }.status shouldBe HttpStatusCode.OK

                // Same EXECUTIVE_BOARD cascade endCommitteeMembership itself has always performed --
                // see GovernanceServiceTest's own "addCommitteeMember/endCommitteeMembership ... write
                // BOARD_MEMBERSHIP CREATE/UPDATE audit entries" test for the direct-call-site version
                // of this same assertion.
                val endedAt =
                    transaction {
                        BoardMembershipTable.selectAll().where { BoardMembershipTable.id eq boardMembershipId }.single()[
                            BoardMembershipTable.endedAt,
                        ]
                    }
                endedAt shouldNotBe null

                val updateAuditCount =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.BOARD_MEMBERSHIP) and
                                    (AuditLogEntryTable.entityId eq boardMembershipId) and
                                    (AuditLogEntryTable.action eq AuditAction.UPDATE)
                            }.count()
                    }
                updateAuditCount shouldBe 1L
            }
        }

        test(
            "rejectApplication: stale-roster fix -- ends an open Committee membership when the application is rejected (V0.13.1)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installRegistrationExceptionHandlers() }
                    routing {
                        registerRegistrationTestRoutes(LoginRateLimiter())
                        registerCommitteeHelperRoutesForRegistrationTests()
                    }
                }
                // An APPLICATION applicant is not expected to already hold a Committee seat --
                // addCommitteeMember itself requires ACTIVE (see its own status-gate tests in
                // GovernanceServiceTest). Seeded defensively anyway (see rejectApplication KDoc "stale
                // roster" fix): create the member ACTIVE, seat it, THEN downgrade it back to
                // APPLICATION directly (bypassing the normal transitions -- this is test setup for an
                // otherwise-unreachable-in-practice prior state, not a real business flow) for the
                // actual rejectApplication call under test.
                val applicant = createTestMember("reg-reject-committee@example.org", MemberStatus.ACTIVE)
                val committeeId =
                    client.post("/test/gov/create-committee/WORKING_GROUP") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                createdCommitteeIds += Uuid.parse(committeeId)
                client
                    .post("/test/gov/add-member/$committeeId/$applicant") { header("X-Member-Id", BOARD_ID) }
                    .status shouldBe HttpStatusCode.OK
                transaction { MemberTable.update({ MemberTable.id eq applicant }) { it[status] = MemberStatus.APPLICATION } }

                val beforeReject = client.get("/test/gov/list-members/$committeeId") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                beforeReject.contains(applicant.toString()) shouldBe true

                val rejected = client.post("/test/reject/$applicant?reason=Testablehnung") { header("X-Member-Id", BOARD_ID) }
                rejected.status shouldBe HttpStatusCode.OK
                statusOf(applicant) shouldBe MemberStatus.REJECTED

                val afterReject = client.get("/test/gov/list-members/$committeeId") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                afterReject.contains(applicant.toString()) shouldBe false
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

/**
 * Fires TWO requests to [path] with the SAME [email] from two independent OS threads, synchronized
 * via [CountDownLatch] so both are issued as close to simultaneously as possible -- same shape as
 * [runConcurrentApproveAndReject] above, reused here to exercise the V0.13.1
 * concurrent-duplicate-registration race fix in [RegistrationService.registerApplication].
 */
private fun runConcurrentDuplicateRegistrations(
    client: HttpClient,
    path: String,
    email: String,
    timeoutSeconds: Long = 20,
): List<HttpStatusCode> {
    val startLatch = CountDownLatch(2)
    val doneLatch = CountDownLatch(2)
    val results = mutableListOf<HttpStatusCode>()
    val failures = mutableListOf<Throwable>()

    fun requestThread(): Thread =
        Thread {
            try {
                startLatch.countDown()
                startLatch.await(timeoutSeconds, TimeUnit.SECONDS)
                runBlocking {
                    val response = client.post("$path?email=$email")
                    synchronized(results) { results += response.status }
                }
            } catch (t: Throwable) {
                synchronized(failures) { failures += t }
            } finally {
                doneLatch.countDown()
            }
        }

    val first = requestThread()
    val second = requestThread()
    first.start()
    second.start()

    val completed = doneLatch.await(timeoutSeconds, TimeUnit.SECONDS)
    check(completed) { "Concurrent duplicate registrations did not complete within ${timeoutSeconds}s -- likely deadlock" }
    if (failures.isNotEmpty()) throw failures.first()
    return results.toList()
}

/**
 * [committeeIds] cleanup added for the V0.13.1 "stale roster" fix tests, which seat members into
 * fresh throwaway Committees via [registerCommitteeHelperRoutesForRegistrationTests] -- mirrors
 * [cleanUpGovernanceTestData]'s own ordering (null out [AuditLogEntryTable.actorMemberId] FKs
 * before deleting [MemberTable] rows; [AuditLogEntryTable] rows themselves are never deleted, see
 * `AuditLogRecorder` KDoc) but simplified: this file's Committees are never used for
 * Meetings/Motions/Votes/Resolutions, so none of [cleanUpGovernanceTestData]'s handling for those
 * applies here.
 */
private fun cleanUpRegistrationTestData(
    memberIds: List<Uuid>,
    committeeIds: List<Uuid> = emptyList(),
) {
    if (memberIds.isEmpty() && committeeIds.isEmpty()) return
    transaction {
        if (memberIds.isNotEmpty()) {
            AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList memberIds }) {
                it[actorMemberId] = null
            }
            TransparenzregisterReminderTable.deleteWhere { TransparenzregisterReminderTable.memberId inList memberIds }
            BoardMembershipTable.deleteWhere { BoardMembershipTable.memberId inList memberIds }
            CommitteeMembershipTable.deleteWhere { CommitteeMembershipTable.memberId inList memberIds }
        }
        if (committeeIds.isNotEmpty()) {
            CommitteeMembershipTable.deleteWhere { CommitteeMembershipTable.committeeId inList committeeIds }
            CommitteeTable.deleteWhere { CommitteeTable.id inList committeeIds }
        }
        if (memberIds.isNotEmpty()) {
            SessionTable.deleteWhere { SessionTable.memberId inList memberIds }
            MembershipAgreementAcknowledgmentTable.deleteWhere { MembershipAgreementAcknowledgmentTable.memberId inList memberIds }
            AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
            MemberTable.deleteWhere { MemberTable.id inList memberIds }
        }
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
    // V0.11.0: fresh throwaway instances per test-route-set, same convention `rateLimiter` above
    // already establishes -- these two are exercised directly by FriendRegistrationTest, not here.
    val friendRateLimiter = LoginRateLimiter()
    val friendIpRateLimiter = FederationInboxRateLimiter()

    fun registrationService(call: ApplicationCall) =
        RegistrationService(
            call = call,
            registrationRateLimiter = rateLimiter,
            friendRegistrationRateLimiter = friendRateLimiter,
            friendSignupIpRateLimiter = friendIpRateLimiter,
            friendVerificationMailer = FakeFriendVerificationMailer(),
        )
    get("/test/agreement") {
        val dto = registrationService(call).getMembershipAgreement()
        call.respondText("${dto.version}:${dto.sha256}")
    }
    post("/test/register") {
        val q = call.request.queryParameters
        registrationService(call).registerApplication(
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
        val list = registrationService(call).listPendingApplications()
        call.respondText(list.joinToString(",") { it.id })
    }
    post("/test/approve/{id}") {
        val dto = registrationService(call).approveApplication(call.parameters["id"]!!)
        call.respondText(dto.status.name)
    }
    post("/test/reject/{id}") {
        val reason = call.request.queryParameters["reason"] ?: ""
        val dto = registrationService(call).rejectApplication(memberId = call.parameters["id"]!!, reason = reason)
        call.respondText(dto.status.name)
    }
    post("/test/create-direct") {
        val q = call.request.queryParameters
        val dto =
            registrationService(call).createMemberDirect(
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
        val dto = registrationService(call).leaveMembership()
        call.respondText(dto.status.name)
    }
}

/**
 * Minimal throwaway [GovernanceService] routes for the V0.13.1 "stale roster" fix tests --
 * only what's needed to seat a member into a Committee and read the roster back, mirroring
 * [GovernanceServiceTest]'s own `registerGovernanceTestRoutes` route shapes (`create-committee`/
 * `add-member`) but pared down to this file's needs (fixed `MEMBER` role/`since`, no Meeting/Motion/
 * Vote routes).
 */
private fun Route.registerCommitteeHelperRoutesForRegistrationTests() {
    post("/test/gov/create-committee/{type}") {
        val service = GovernanceService(call = call)
        val c =
            service.createCommittee(
                CommitteeInput(
                    name = "RegistrationServiceTest Committee ${Uuid.random()}",
                    type = CommitteeType.valueOf(call.parameters["type"]!!),
                    description = "Stale-roster-fix test committee",
                    quorumPercent = 50,
                ),
            )
        call.respondText(c.id)
    }
    post("/test/gov/add-member/{committeeId}/{memberId}") {
        val service = GovernanceService(call = call)
        val m =
            service.addCommitteeMember(
                committeeId = call.parameters["committeeId"]!!,
                input =
                    CommitteeMembershipInput(
                        memberId = call.parameters["memberId"]!!,
                        role = CommitteeRole.MEMBER,
                        since = LocalDate(2026, 1, 1),
                    ),
            )
        call.respondText(m.id)
    }
    get("/test/gov/list-members/{committeeId}") {
        val service = GovernanceService(call = call)
        val list = service.listCommitteeMembers(committeeId = call.parameters["committeeId"]!!, activeOnly = true)
        call.respondText(list.joinToString(",") { it.memberId })
    }
}
