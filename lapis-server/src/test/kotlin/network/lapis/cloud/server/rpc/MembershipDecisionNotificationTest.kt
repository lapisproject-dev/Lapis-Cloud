package network.lapis.cloud.server.rpc

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MemberStatusHistoryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.keycloak.KeycloakConfig
import network.lapis.cloud.server.mail.FakeFriendVerificationMailer
import network.lapis.cloud.server.mail.MembershipDecisionMailer
import network.lapis.cloud.server.mail.NoOpMembershipDecisionMailer
import network.lapis.cloud.server.mail.RecordingMembershipDecisionMailer
import network.lapis.cloud.server.mail.RejectionVariant
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.security.PasswordHasher
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.DecisionNotificationOutcome
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import kotlin.uuid.Uuid
import ch.qos.logback.classic.Logger as LogbackLogger

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val MEMBER_ID = "00000000-0000-0000-0000-000000000004"

/**
 * V1.9.118 -- the notification mail of a membership decision: outcome per situation, mail content hand-over (variant, language),
 * "after the commit", idempotence, roles and log privacy.
 */
class MembershipDecisionNotificationTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        var mailer: MembershipDecisionMailer = NoOpMembershipDecisionMailer

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) { it[actorMemberId] = null }
                SessionTable.deleteWhere { SessionTable.memberId inList createdMemberIds }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberStatusHistoryTable.deleteWhere { MemberStatusHistoryTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
        }

        fun createApplicant(
            email: String,
            displayName: String = "Erika Musterfrau",
            friend: Boolean = false,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[MemberTable.displayName] = displayName
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.APPLICATION
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                    if (friend) it[friendSince] = LocalDate(2026, 1, 1)
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.MEMBER
                    it[passwordHash] = PasswordHasher.hash("a-genuinely-strong-password-1")
                }
            }
            createdMemberIds += id
            return id
        }

        fun statusOf(id: Uuid): MemberStatus =
            transaction { MemberTable.selectAll().where { MemberTable.id eq id }.single()[MemberTable.status] }

        suspend fun <T> withMailer(
            m: MembershipDecisionMailer,
            block: suspend () -> T,
        ): T {
            mailer = m
            try {
                return block()
            } finally {
                mailer = NoOpMembershipDecisionMailer
            }
        }

        fun routes(): Route.() -> Unit =
            {
                fun service(call: ApplicationCall) =
                    RegistrationService(
                        call = call,
                        registrationRateLimiter = LoginRateLimiter(),
                        friendRegistrationRateLimiter = LoginRateLimiter(),
                        friendSignupIpRateLimiter = FederationInboxRateLimiter(),
                        friendVerificationMailer = FakeFriendVerificationMailer(),
                        keycloakConfig = KeycloakConfig.load(),
                        membershipDecisionMailer = mailer,
                    )
                post("/test/approve/{id}") {
                    val q = call.request.queryParameters
                    val r =
                        service(call).approveApplication(
                            memberId = call.parameters["id"]!!,
                            notify = q["notify"]?.toBooleanStrict() ?: true,
                            mailLanguage = q["lang"] ?: "de",
                        )
                    call.respondText("${r.member.status}:${r.notification}")
                }
                post("/test/reject/{id}") {
                    val q = call.request.queryParameters
                    val r =
                        service(call).rejectApplication(
                            memberId = call.parameters["id"]!!,
                            reason = q["reason"] ?: "Grund",
                            notify = q["notify"]?.toBooleanStrict() ?: true,
                            mailLanguage = q["lang"] ?: "de",
                        )
                    call.respondText("${r.member.status}:${r.notification}")
                }
            }

        fun io.ktor.server.application.Application.setup() {
            install(StatusPages) {
                exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
            }
            routing(routes())
        }

        test("approval sends one mail to the applicant's own stored address and reports QUEUED") {
            val rec = RecordingMembershipDecisionMailer()
            withMailer(rec) {
                testApplication {
                    application { setup() }
                    val id = createApplicant("decision-approve@example.org")
                    val r = client.post("/test/approve/$id?lang=fr") { header("X-Member-Id", BOARD_ID) }
                    r.status shouldBe HttpStatusCode.OK
                    r.bodyAsText() shouldBe "ACTIVE:QUEUED"
                    rec.sent shouldHaveSize 1
                    rec.sent.single().let {
                        it.email shouldBe "decision-approve@example.org"
                        it.displayName shouldBe "Erika Musterfrau"
                        it.approved shouldBe true
                        it.language shouldBe "fr"
                    }
                    statusOf(id) shouldBe MemberStatus.ACTIVE
                }
            }
        }

        test("rejection of a plain applicant sends the REJECTED variant, a former friend gets the FRIEND variant") {
            val rec = RecordingMembershipDecisionMailer()
            withMailer(rec) {
                testApplication {
                    application { setup() }
                    val plain = createApplicant("decision-reject@example.org")
                    val friend = createApplicant("decision-reject-friend@example.org", friend = true)
                    client.post("/test/reject/$plain?lang=en") { header("X-Member-Id", BOARD_ID) }.bodyAsText() shouldBe "REJECTED:QUEUED"
                    client.post("/test/reject/$friend") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe "FRIEND:QUEUED"
                    rec.sent shouldHaveSize 2
                    rec.sent[0].variant shouldBe RejectionVariant.REJECTED
                    rec.sent[0].language shouldBe "en"
                    rec.sent[1].variant shouldBe RejectionVariant.FRIEND
                    rec.sent[1].email shouldBe "decision-reject-friend@example.org"
                    rec.sent.none { it.approved } shouldBe true
                }
            }
        }

        test("notify=false: NOT_REQUESTED, no mail, the decision still holds") {
            val rec = RecordingMembershipDecisionMailer()
            withMailer(rec) {
                testApplication {
                    application { setup() }
                    val a = createApplicant("decision-nonotify-a@example.org")
                    val b = createApplicant("decision-nonotify-b@example.org")
                    client.post("/test/approve/$a?notify=false") { header("X-Member-Id", BOARD_ID) }.bodyAsText() shouldBe
                        "ACTIVE:NOT_REQUESTED"
                    client.post("/test/reject/$b?notify=false") { header("X-Member-Id", BOARD_ID) }.bodyAsText() shouldBe
                        "REJECTED:NOT_REQUESTED"
                    rec.sent.shouldBeEmpty()
                }
            }
        }

        test("without configured mail delivery: MAIL_DISABLED, the decision succeeds") {
            withMailer(NoOpMembershipDecisionMailer) {
                testApplication {
                    application { setup() }
                    val a = createApplicant("decision-disabled-a@example.org")
                    val b = createApplicant("decision-disabled-b@example.org")
                    client.post("/test/approve/$a") { header("X-Member-Id", BOARD_ID) }.bodyAsText() shouldBe "ACTIVE:MAIL_DISABLED"
                    client.post("/test/reject/$b") { header("X-Member-Id", BOARD_ID) }.bodyAsText() shouldBe "REJECTED:MAIL_DISABLED"
                }
            }
        }

        test("a blank or malformed stored address: NO_VALID_ADDRESS, nothing is sent, the decision succeeds") {
            val rec = RecordingMembershipDecisionMailer()
            withMailer(rec) {
                testApplication {
                    application { setup() }
                    listOf("", "not-an-address", "evil@example.org\r\nBcc: x@example.org").forEachIndexed { index, bad ->
                        val id = createApplicant("decision-bad-$index@example.org")
                        transaction { MemberTable.update({ MemberTable.id eq id }) { it[email] = bad } }
                        client.post("/test/approve/$id") { header("X-Member-Id", BOARD_ID) }.bodyAsText() shouldBe "ACTIVE:NO_VALID_ADDRESS"
                    }
                    rec.sent.shouldBeEmpty()
                }
            }
        }

        test("the mailer throws or refuses: FAILED, the decision succeeds, and the log carries neither address nor name nor message") {
            val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as LogbackLogger
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            val previous = root.level
            root.level = Level.TRACE
            root.addAppender(appender)
            try {
                val throwing = RecordingMembershipDecisionMailer(throwOnSend = true)
                val refusing = RecordingMembershipDecisionMailer(accepts = false)
                testApplication {
                    application { setup() }
                    val a = createApplicant("decision-throws@example.org", displayName = "Ludwig Geheimname")
                    val b = createApplicant("decision-refuses@example.org", displayName = "Ludwig Geheimname")
                    withMailer(throwing) {
                        client.post("/test/approve/$a") { header("X-Member-Id", BOARD_ID) }.bodyAsText() shouldBe "ACTIVE:FAILED"
                    }
                    withMailer(refusing) {
                        client.post("/test/reject/$b") { header("X-Member-Id", BOARD_ID) }.bodyAsText() shouldBe "REJECTED:FAILED"
                    }
                    statusOf(a) shouldBe MemberStatus.ACTIVE
                    statusOf(b) shouldBe MemberStatus.REJECTED
                }
                val text = appender.list.joinToString("\n") { it.formattedMessage + (it.throwableProxy?.message ?: "") }
                text shouldNotContain "decision-throws@example.org"
                text shouldNotContain "decision-refuses@example.org"
                text shouldNotContain "Geheimname"
                text shouldNotContain "boom"
            } finally {
                root.detachAppender(appender)
                root.level = previous
            }
        }

        test("the mail is sent after the commit: at send time the status change is already visible") {
            val applicantHolder = arrayOfNulls<Uuid>(1)
            val seenStatuses = mutableListOf<MemberStatus>()
            val rec = RecordingMembershipDecisionMailer(onSend = { seenStatuses += statusOf(applicantHolder[0]!!) })
            withMailer(rec) {
                testApplication {
                    application { setup() }
                    val id = createApplicant("decision-order@example.org")
                    applicantHolder[0] = id
                    client.post("/test/approve/$id") { header("X-Member-Id", BOARD_ID) }
                    seenStatuses shouldBe listOf(MemberStatus.ACTIVE)
                }
            }
        }

        test("a second decision conflicts and sends no further mail") {
            val rec = RecordingMembershipDecisionMailer()
            withMailer(rec) {
                testApplication {
                    application { setup() }
                    val id = createApplicant("decision-twice@example.org")
                    client.post("/test/approve/$id") { header("X-Member-Id", BOARD_ID) }.status shouldBe HttpStatusCode.OK
                    client.post("/test/approve/$id") { header("X-Member-Id", BOARD_ID) }.status shouldBe HttpStatusCode.Conflict
                    client.post("/test/reject/$id") { header("X-Member-Id", BOARD_ID) }.status shouldBe HttpStatusCode.Conflict
                    rec.sent shouldHaveSize 1
                }
            }
        }

        test("a MEMBER may not decide and nothing is sent; a rejection without reason sends nothing") {
            val rec = RecordingMembershipDecisionMailer()
            withMailer(rec) {
                testApplication {
                    application { setup() }
                    val id = createApplicant("decision-forbidden@example.org")
                    client.post("/test/approve/$id") { header("X-Member-Id", MEMBER_ID) }.status shouldBe HttpStatusCode.Forbidden
                    client.post("/test/reject/$id") { header("X-Member-Id", MEMBER_ID) }.status shouldBe HttpStatusCode.Forbidden
                    client.post("/test/reject/$id?reason=") { header("X-Member-Id", BOARD_ID) }.status shouldBe HttpStatusCode.Conflict
                    statusOf(id) shouldBe MemberStatus.APPLICATION
                    rec.sent.shouldBeEmpty()
                }
            }
        }

        test("a rejection still revokes the applicant's sessions") {
            val rec = RecordingMembershipDecisionMailer()
            withMailer(rec) {
                testApplication {
                    application { setup() }
                    val id = createApplicant("decision-sessions@example.org")
                    val session = SessionStore.createSession(id)
                    SessionStore.resolve(session.rawToken) shouldNotBe null
                    client.post("/test/reject/$id") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.OK
                    SessionStore.resolve(session.rawToken) shouldBe null
                    rec.sent shouldHaveSize 1
                }
            }
        }

        test("DecisionNotificationOutcome has exactly the five documented values") {
            DecisionNotificationOutcome.entries.map { it.name } shouldBe
                listOf("QUEUED", "NOT_REQUESTED", "MAIL_DISABLED", "NO_VALID_ADDRESS", "FAILED")
        }
    })
