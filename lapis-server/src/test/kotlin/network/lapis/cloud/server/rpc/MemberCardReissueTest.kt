package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MemberCardCodeTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.FakeAdminPasswordResetNotificationMailer
import network.lapis.cloud.server.mail.FakeFriendVerificationMailer
import network.lapis.cloud.server.mail.FakePasswordResetMailer
import network.lapis.cloud.server.mail.SmtpConfigState
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * `IMemberService.reissueMemberCard` -- the RPC that kills a lost card without printing a new one.
 *
 * Driven through a throwaway Ktor route rather than by calling the service object directly, because
 * the method's whole authorization story is `resolveCurrentMember(call)` -- the same harness idiom
 * [MemberAdministrationTest] uses for every other `MemberService` method.
 */
class MemberCardReissueTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (createdMemberIds.isNotEmpty()) {
                    AuditLogEntryTable.deleteWhere { AuditLogEntryTable.actorMemberId inList createdMemberIds }
                    MemberCardCodeTable.deleteWhere { MemberCardCodeTable.memberId inList createdMemberIds }
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
        }

        fun createMember(
            email: String,
            role: AccountRole = AccountRole.MEMBER,
            status: MemberStatus = MemberStatus.ACTIVE,
            anonymizedAt: LocalDateTime? = null,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Reissue Testmitglied"
                    it[MemberTable.email] = email
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2026, 2, 1)
                    it[membershipTierId] = null
                    it[MemberTable.anonymizedAt] = anonymizedAt
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

        fun codeCounts(memberId: Uuid): Pair<Long, Long> =
            transaction {
                val all = MemberCardCodeTable.selectAll().where { MemberCardCodeTable.memberId eq memberId }.count()
                val active =
                    MemberCardCodeTable
                        .selectAll()
                        .where { (MemberCardCodeTable.memberId eq memberId) and (MemberCardCodeTable.revokedAt.isNull()) }
                        .count()
                all to active
            }

        test("a member may reissue their OWN card; the first call issues, the second rotates") {
            testApplication {
                application {
                    install(StatusPages) { installMemberCardExceptionHandlers() }
                    routing { registerMemberCardReissueTestRoute() }
                }
                val member = createMember("reissue-self-1@example.org")

                val first = client.post("/test/member-card/reissue/$member") { header("X-Member-Id", member.toString()) }
                first.status shouldBe HttpStatusCode.OK
                first.bodyAsText() shouldContain "previousCardRevoked=false"
                first.bodyAsText() shouldContain "M-2026-"
                codeCounts(member) shouldBe (1L to 1L)

                val second = client.post("/test/member-card/reissue/$member") { header("X-Member-Id", member.toString()) }
                second.bodyAsText() shouldContain "previousCardRevoked=true"
                // Two rows in history, exactly one of them still valid -- rotation, not accumulation.
                codeCounts(member) shouldBe (2L to 1L)
            }
        }

        test("BOARD may reissue anyone's card, a plain member may not reissue a foreign one") {
            testApplication {
                application {
                    install(StatusPages) { installMemberCardExceptionHandlers() }
                    routing { registerMemberCardReissueTestRoute() }
                }
                val subject = createMember("reissue-subject-2@example.org")
                val stranger = createMember("reissue-stranger-2@example.org")
                val board = createMember("reissue-board-2@example.org", role = AccountRole.BOARD)

                client
                    .post("/test/member-card/reissue/$subject") { header("X-Member-Id", stranger.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden
                client
                    .post("/test/member-card/reissue/$subject") { header("X-Member-Id", board.toString()) }
                    .status shouldBe HttpStatusCode.OK
            }
        }

        test("TREASURER is NOT privileged for an identity document") {
            testApplication {
                application {
                    install(StatusPages) { installMemberCardExceptionHandlers() }
                    routing { registerMemberCardReissueTestRoute() }
                }
                val subject = createMember("reissue-subject-3@example.org")
                val treasurer = createMember("reissue-treasurer-3@example.org", role = AccountRole.TREASURER)

                client
                    .post("/test/member-card/reissue/$subject") { header("X-Member-Id", treasurer.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("401 unauthenticated, 404 unknown member, 409 for a non-member status") {
            testApplication {
                application {
                    install(StatusPages) { installMemberCardExceptionHandlers() }
                    routing { registerMemberCardReissueTestRoute() }
                }
                val admin = createMember("reissue-admin-4@example.org", role = AccountRole.ADMIN)
                val friend = createMember("reissue-friend-4@example.org", status = MemberStatus.FRIEND)

                client.post("/test/member-card/reissue/${Uuid.random()}").status shouldBe HttpStatusCode.Unauthorized
                client
                    .post("/test/member-card/reissue/${Uuid.random()}") { header("X-Member-Id", admin.toString()) }
                    .status shouldBe HttpStatusCode.NotFound
                client
                    .post("/test/member-card/reissue/$friend") { header("X-Member-Id", admin.toString()) }
                    .status shouldBe HttpStatusCode.Conflict
                codeCounts(friend) shouldBe (0L to 0L)
            }
        }

        test("409 for an anonymized member even with a still-ACTIVE status -- DSGVO erasure does not touch status") {
            testApplication {
                application {
                    install(StatusPages) { installMemberCardExceptionHandlers() }
                    routing { registerMemberCardReissueTestRoute() }
                }
                val admin = createMember("reissue-admin-6@example.org", role = AccountRole.ADMIN)
                // Erasure (FoundationPersonalData.eraseMember) does NOT reset `status`, so a member
                // anonymized without a status change stays ACTIVE -- exactly the row this guard must
                // still refuse, mirroring the same case in MemberCardRoutesTest.
                val anonymized =
                    createMember(
                        "reissue-anonymized-6@example.org",
                        status = MemberStatus.ACTIVE,
                        anonymizedAt = LocalDateTime(2026, 3, 1, 0, 0),
                    )

                val response =
                    client.post("/test/member-card/reissue/$anonymized") { header("X-Member-Id", admin.toString()) }
                response.status shouldBe HttpStatusCode.Conflict
                response.bodyAsText() shouldContain "anonymized"
                codeCounts(anonymized) shouldBe (0L to 0L)
            }
        }

        test("the result never carries the raw card code") {
            testApplication {
                application {
                    install(StatusPages) { installMemberCardExceptionHandlers() }
                    routing { registerMemberCardReissueTestRoute() }
                }
                val member = createMember("reissue-nocode-5@example.org")
                val body = client.post("/test/member-card/reissue/$member") { header("X-Member-Id", member.toString()) }.bodyAsText()
                // The DTO has exactly four fields and none of them is a 16-character bearer code --
                // pinned by rendering the whole DTO and checking its shape.
                body shouldContain "memberId="
                body shouldContain "issuedAt="
                Regex("[0-9A-HJKMNP-TV-Z]{16}").find(body) shouldBe null
                body shouldNotBe ""
            }
        }
    })

/**
 * `issueRateLimiter` defaults to an effectively-unbounded instance so every OTHER test in this file
 * (none of which is about rate limiting) keeps working unmodified. [MemberCardRateLimitTest] passes
 * a deliberately tiny-budget instance -- the SAME instance a [registerMemberCardRoutes] test route
 * is wired with in the same `testApplication` -- to prove the RPC and the HTTP download route share
 * one budget (Security fix, Review MAJOR, 2026-09; see [MemberService.memberCardIssueRateLimiter]
 * KDoc).
 */
internal fun Route.registerMemberCardReissueTestRoute(issueRateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter()) {
    post("/test/member-card/reissue/{id}") {
        val service =
            MemberService(
                call = call,
                friendVerificationMailer = FakeFriendVerificationMailer(),
                memberCoreDataFriendMailRateLimiter = FederationInboxRateLimiter(),
                memberCoreDataFriendMailActorRateLimiter = FederationInboxRateLimiter(),
                passwordResetMailer = FakePasswordResetMailer(),
                adminPasswordResetNotificationMailer = FakeAdminPasswordResetNotificationMailer(),
                smtpConfigState = SmtpConfigState.NotConfigured,
                adminPasswordMailTargetRateLimiter = FederationInboxRateLimiter(),
                adminPasswordMailActorRateLimiter = FederationInboxRateLimiter(),
                adminPasswordNotificationTargetRateLimiter = FederationInboxRateLimiter(),
                memberCardIssueRateLimiter = issueRateLimiter,
            )
        val dto = service.reissueMemberCard(call.parameters["id"]!!)
        call.respondText(
            "memberId=${dto.memberId};memberNumber=${dto.memberNumber};issuedAt=${dto.issuedAt};" +
                "previousCardRevoked=${dto.previousCardRevoked}",
        )
    }
}

internal fun io.ktor.server.plugins.statuspages.StatusPagesConfig.installMemberCardExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Unauthorized) }
    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
    exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
}
