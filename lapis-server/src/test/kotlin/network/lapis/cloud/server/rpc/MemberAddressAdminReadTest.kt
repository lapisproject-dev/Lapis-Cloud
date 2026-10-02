package network.lapis.cloud.server.rpc

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.FakeAdminPasswordResetNotificationMailer
import network.lapis.cloud.server.mail.FakeFriendVerificationMailer
import network.lapis.cloud.server.mail.FakePasswordResetMailer
import network.lapis.cloud.server.mail.SmtpConfigState
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.Logger.ROOT_LOGGER_NAME
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** V1.9.35 -- `getMemberAddressForAdministration`: BOARD/ADMIN only, one value-free audit entry per read, 30/h per actor. */
class MemberAddressAdminReadTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (createdMemberIds.isNotEmpty()) {
                    AuditLogEntryTable.deleteWhere { actorMemberId inList createdMemberIds }
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
        }

        val secretStreet = "Q7ZX-street"
        val secretCity = "Q7ZX-city"
        val secretNationality = "Q7ZX-nat"

        fun createMember(
            role: AccountRole = AccountRole.MEMBER,
            withData: Boolean = false,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Adresse Lesetest"
                    it[email] = "addr-read-${Uuid.random()}@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 2, 1)
                    it[membershipTierId] = null
                    if (withData) {
                        it[street] = secretStreet
                        it[postalCode] = "38100"
                        it[city] = secretCity
                        it[country] = "DE"
                        it[dateOfBirth] = LocalDate(1980, 5, 17)
                        it[nationality] = secretNationality
                    }
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

        fun service(
            call: io.ktor.server.application.ApplicationCall,
            limiter: FederationInboxRateLimiter,
        ) = MemberService(
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
            memberCardIssueRateLimiter = FederationInboxRateLimiter(),
            memberAddressAdminReadRateLimiter = limiter,
        )

        fun io.ktor.server.application.Application.setup(limiter: FederationInboxRateLimiter) {
            install(StatusPages) {
                exception<ForbiddenException> { call, _ -> call.respondText("forbidden", status = HttpStatusCode.Forbidden) }
                exception<NotFoundException> { call, _ -> call.respondText("notfound", status = HttpStatusCode.NotFound) }
                exception<ConflictException> { call, _ -> call.respondText("conflict", status = HttpStatusCode.Conflict) }
            }
            routing {
                post("/test/read/{id}") {
                    // a NEW MemberService per call, exactly like production -- the limiter is the shared singleton
                    val dto = service(call, limiter).getMemberAddressForAdministration(call.parameters["id"]!!)
                    call.respondText("${dto.street}|${dto.postalCode}|${dto.city}|${dto.country}|${dto.dateOfBirth}|${dto.nationality}")
                }
                post("/test/save/{id}") {
                    val q = call.request.queryParameters
                    service(
                        call,
                        limiter,
                    ).updateMemberAddress(
                        memberId = call.parameters["id"]!!,
                        street = q["street"],
                        postalCode = q["postalCode"],
                        city = q["city"],
                        country = q["country"],
                    )
                    call.respondText("ok")
                }
            }
        }

        fun readAudits(target: Uuid): List<org.jetbrains.exposed.v1.core.ResultRow> =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where {
                        (AuditLogEntryTable.entityId eq target) and
                            (AuditLogEntryTable.entityType eq AuditEntityType.MEMBER) and
                            (AuditLogEntryTable.afterSnapshot eq "ADDRESS_READ")
                    }.toList()
            }

        fun fresh() = FederationInboxRateLimiter(maxRequests = 30, window = 60.minutes)

        test("BOARD and ADMIN read; TREASURER and MEMBER (even for their own id) are Forbidden, with no audit and no limiter use") {
            testApplication {
                val limiter = fresh()
                application { setup(limiter) }
                val target = createMember(withData = true)
                listOf(AccountRole.BOARD, AccountRole.ADMIN).forEach { role ->
                    val actor = createMember(role)
                    val r = client.post("/test/read/$target") { header("X-Member-Id", actor.toString()) }
                    r.status shouldBe HttpStatusCode.OK
                    r.bodyAsText() shouldBe "$secretStreet|38100|$secretCity|DE|1980-05-17|$secretNationality"
                }
                readAudits(target).size shouldBe 2
                val treasurer = createMember(AccountRole.TREASURER)
                val plain = createMember(AccountRole.MEMBER, withData = true)
                repeat(40) {
                    client.post("/test/read/$target") { header("X-Member-Id", treasurer.toString()) }.status shouldBe
                        HttpStatusCode.Forbidden
                }
                client.post("/test/read/$plain") { header("X-Member-Id", plain.toString()) }.status shouldBe HttpStatusCode.Forbidden
                readAudits(plain).size shouldBe 0
                // the 40 refused calls did not eat the budget of anybody
                val board = createMember(AccountRole.BOARD)
                client.post("/test/read/$target") { header("X-Member-Id", board.toString()) }.status shouldBe HttpStatusCode.OK
            }
        }

        test("an unknown id gives a non-privileged caller Forbidden, never NotFound (no existence oracle)") {
            testApplication {
                application { setup(fresh()) }
                val plain = createMember(AccountRole.MEMBER)
                client.post("/test/read/${Uuid.random()}") { header("X-Member-Id", plain.toString()) }.status shouldBe
                    HttpStatusCode.Forbidden
                client.post("/test/read/not-a-uuid") { header("X-Member-Id", plain.toString()) }.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("one value-free audit entry per read; no field value in the audit, the response error paths or the log") {
            testApplication {
                application { setup(fresh()) }
                val target = createMember(withData = true)
                val board = createMember(AccountRole.BOARD)
                val root = LoggerFactory.getLogger(ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
                val appender = ListAppender<ILoggingEvent>().also { it.start() }
                root.addAppender(appender)
                try {
                    client.post("/test/read/$target") { header("X-Member-Id", board.toString()) }.status shouldBe HttpStatusCode.OK
                    client.post("/test/read/$target") { header("X-Member-Id", board.toString()) }.status shouldBe HttpStatusCode.OK
                } finally {
                    root.detachAppender(appender)
                }
                val audits = readAudits(target)
                audits.size shouldBe 2
                audits.forEach {
                    it[AuditLogEntryTable.action] shouldBe AuditAction.UPDATE
                    it[AuditLogEntryTable.beforeSnapshot] shouldBe null
                    it[AuditLogEntryTable.actorMemberId] shouldBe board
                    (it[AuditLogEntryTable.afterSnapshot] ?: "") shouldNotContain "Q7ZX"
                }
                appender.list.joinToString("\n") { it.formattedMessage } shouldNotContain "Q7ZX"
                appender.list.joinToString("\n") { it.formattedMessage } shouldNotContain target.toString()
            }
        }

        test("an unknown or anonymized member is NotFound and writes no audit entry") {
            testApplication {
                application { setup(fresh()) }
                val board = createMember(AccountRole.BOARD)
                client.post("/test/read/${Uuid.random()}") { header("X-Member-Id", board.toString()) }.status shouldBe
                    HttpStatusCode.NotFound
                client.post("/test/read/garbage") { header("X-Member-Id", board.toString()) }.status shouldBe HttpStatusCode.NotFound
                val gone = createMember(withData = true)
                transaction {
                    MemberTable.update({ MemberTable.id eq gone }) {
                        it[anonymizedAt] =
                            network.lapis.cloud.server.db.DbClock
                                .nowLocalDateTime()
                    }
                }
                client.post("/test/read/$gone") { header("X-Member-Id", board.toString()) }.status shouldBe HttpStatusCode.NotFound
                readAudits(gone).size shouldBe 0
            }
        }

        test("the 31st read within the window is a Conflict; the budget is per actor and shared across MemberService instances") {
            testApplication {
                application { setup(fresh()) }
                val target = createMember(withData = true)
                val board = createMember(AccountRole.BOARD)
                val other = createMember(AccountRole.ADMIN)
                repeat(30) {
                    client.post("/test/read/$target") { header("X-Member-Id", board.toString()) }.status shouldBe HttpStatusCode.OK
                }
                client.post("/test/read/$target") { header("X-Member-Id", board.toString()) }.status shouldBe HttpStatusCode.Conflict
                client.post("/test/read/$target") { header("X-Member-Id", other.toString()) }.status shouldBe HttpStatusCode.OK
                readAudits(target).size shouldBe 31
            }
        }

        test("read, then save the same values: nothing is lost") {
            testApplication {
                application { setup(fresh()) }
                val target = createMember(withData = true)
                val board = createMember(AccountRole.BOARD)
                client.post("/test/read/$target") { header("X-Member-Id", board.toString()) }.bodyAsText() shouldBe
                    "$secretStreet|38100|$secretCity|DE|1980-05-17|$secretNationality"
                client
                    .post {
                        url("/test/save/$target?street=$secretStreet&postalCode=38100&city=$secretCity&country=DE")
                        header("X-Member-Id", board.toString())
                    }.status shouldBe HttpStatusCode.OK
                client.post("/test/read/$target") { header("X-Member-Id", board.toString()) }.bodyAsText() shouldBe
                    "$secretStreet|38100|$secretCity|DE|1980-05-17|$secretNationality"
            }
        }
    })
