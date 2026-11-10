package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
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
import network.lapis.cloud.server.mail.FakeAdminPasswordResetNotificationMailer
import network.lapis.cloud.server.mail.FakePasswordResetMailer
import network.lapis.cloud.server.mail.SmtpConfigState
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.VotingRightSource
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.LastAdminException
import network.lapis.cloud.shared.rpc.MemberAlreadyHasAccountException
import network.lapis.cloud.shared.rpc.MemberHasNoAccountException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle V1.9.298 -- the two ADMIN-only voting-right RPCs (who, which target, no-op, audit with a fixed key only, release) and the donor
 * account rules (a donor never holds BOARD/TREASURER/ADMIN; ACTIVE -> DONOR keeps the sessions and takes effect at once).
 */
class VotingRightAdminAndDonorAccountTest :
    FunSpec({
        val created = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec {
            transaction {
                if (created.isNotEmpty()) {
                    AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList created }) {
                        it[actorMemberId] =
                            null
                    }
                }
                created.forEach { id ->
                    SessionTable.deleteWhere { SessionTable.memberId eq id }
                    AccountTable.deleteWhere { AccountTable.memberId eq id }
                    MemberStatusHistoryTable.deleteWhere { MemberStatusHistoryTable.memberId eq id }
                    MemberTable.deleteWhere { MemberTable.id eq id }
                }
            }
        }

        fun member(
            status: MemberStatus = MemberStatus.ACTIVE,
            role: AccountRole? = AccountRole.MEMBER,
            anonymised: Boolean = false,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Stimmrecht Testperson"
                    it[email] = "vr-$id@example.org"
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                    if (anonymised) it[anonymizedAt] = kotlinx.datetime.LocalDateTime(2026, 1, 1, 0, 0)
                }
                if (role != null) {
                    AccountTable.insert {
                        it[AccountTable.id] = Uuid.random()
                        it[memberId] = id
                        it[AccountTable.role] = role
                    }
                }
            }
            created += id
            return id
        }

        fun row(id: Uuid) = transaction { MemberTable.selectAll().where { MemberTable.id eq id }.single() }

        fun auditTextOf(id: Uuid): String =
            transaction {
                AuditLogEntryTable.selectAll().where { AuditLogEntryTable.entityId eq id }.joinToString("|") {
                    it[AuditLogEntryTable.beforeSnapshot].orEmpty() + it[AuditLogEntryTable.afterSnapshot].orEmpty()
                }
            }

        fun service(call: io.ktor.server.application.ApplicationCall) =
            MemberService(
                call = call,
                passwordResetMailer = FakePasswordResetMailer(),
                adminPasswordResetNotificationMailer = FakeAdminPasswordResetNotificationMailer(),
                smtpConfigState = SmtpConfigState.NotConfigured,
                adminPasswordMailTargetRateLimiter = FederationInboxRateLimiter(),
                adminPasswordMailActorRateLimiter = FederationInboxRateLimiter(),
                adminPasswordNotificationTargetRateLimiter = FederationInboxRateLimiter(),
                memberCardIssueRateLimiter = FederationInboxRateLimiter(),
                memberAddressAdminReadRateLimiter = FederationInboxRateLimiter(),
            )

        fun io.ktor.server.application.Application.installRoutes() {
            install(StatusPages) {
                exception<UnauthenticatedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Unauthorized) }
                exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
                exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
                exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
                exception<MemberHasNoAccountException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
                exception<MemberAlreadyHasAccountException> {
                    call,
                    cause,
                    ->
                    call.respondText(cause.message, status = HttpStatusCode.Conflict)
                }
                exception<network.lapis.cloud.shared.rpc.PeerProtectionDeniedException> { call, cause ->
                    call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                }
                exception<LastAdminException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
            }
            routing {
                post("/t/vr/{id}/{value}") {
                    val dto =
                        service(
                            call,
                        ).setMemberVotingRight(memberId = call.parameters["id"]!!, votingRight = call.parameters["value"]!!.toBoolean())
                    call.respondText("${dto.votingRight}:${dto.votingRightSource}:${dto.votingRightChangedAt != null}")
                }
                post("/t/vr-release/{id}") {
                    val dto = service(call).releaseMemberVotingRightToSync(call.parameters["id"]!!)
                    call.respondText("${dto.votingRight}:${dto.votingRightSource}")
                }
                post("/t/role/{id}/{role}") {
                    val dto =
                        service(
                            call,
                        ).updateMemberRole(memberId = call.parameters["id"]!!, newRole = AccountRole.valueOf(call.parameters["role"]!!))
                    call.respondText("${dto.role}")
                }
                post("/t/grant/{id}/{role}") {
                    val dto =
                        service(call).grantMemberAccount(
                            memberId = call.parameters["id"]!!,
                            temporaryPassword = "a-genuinely-strong-password-1",
                            role = AccountRole.valueOf(call.parameters["role"]!!),
                        )
                    call.respondText("${dto.role}:${dto.status}")
                }
                post("/t/status/{id}/{status}") {
                    val dto =
                        service(call).updateMemberStatus(
                            memberId = call.parameters["id"]!!,
                            newStatus = MemberStatus.valueOf(call.parameters["status"]!!),
                            reason = "Test",
                            dateOfDeath = null,
                        )
                    call.respondText("${dto.status}")
                }
            }
        }

        test("setMemberVotingRight: ADMIN only, never the own row, only a member that is not anonymised") {
            val admin = member(role = AccountRole.ADMIN)
            val board = member(role = AccountRole.BOARD)
            val target = member()
            testApplication {
                application { installRoutes() }
                client.post("/t/vr/$target/false") { header("X-Member-Id", board.toString()) }.status shouldBe HttpStatusCode.Forbidden
                client.post("/t/vr/$admin/false") { header("X-Member-Id", admin.toString()) }.status shouldBe HttpStatusCode.Forbidden
                client.post("/t/vr/${member(anonymised = true)}/false") { header("X-Member-Id", admin.toString()) }.status shouldBe
                    HttpStatusCode.Conflict
                client
                    .post(
                        "/t/vr/${member(status = MemberStatus.DONOR)}/false",
                    ) { header("X-Member-Id", admin.toString()) }
                    .status shouldBe
                    HttpStatusCode.Conflict
                client
                    .post(
                        "/t/vr/${member(status = MemberStatus.FRIEND)}/false",
                    ) { header("X-Member-Id", admin.toString()) }
                    .status shouldBe
                    HttpStatusCode.Conflict
                client.post("/t/vr/${Uuid.random()}/false") { header("X-Member-Id", admin.toString()) }.status shouldBe
                    HttpStatusCode.NotFound
                row(target)[MemberTable.votingRight] shouldBe true
            }
        }

        test(
            "peer protection: one ADMIN never withdraws the voting right of a BOARD/TREASURER/ADMIN peer, but may grant it and withdraw a plain member's",
        ) {
            val admin = member(role = AccountRole.ADMIN)
            testApplication {
                application { installRoutes() }
                listOf(AccountRole.BOARD, AccountRole.TREASURER, AccountRole.ADMIN).forEach { role ->
                    val peer = member(role = role)
                    client.post("/t/vr/$peer/false") { header("X-Member-Id", admin.toString()) }.status shouldBe HttpStatusCode.Forbidden
                    row(peer)[MemberTable.votingRight] shouldBe true
                    transaction { AuditLogEntryTable.selectAll().where { AuditLogEntryTable.entityId eq peer }.count() } shouldBe 0L
                    // granting is unaffected
                    client.post("/t/vr/$peer/true") { header("X-Member-Id", admin.toString()) }.status shouldBe HttpStatusCode.OK
                }
                val plain = member(role = AccountRole.MEMBER)
                client.post("/t/vr/$plain/false") { header("X-Member-Id", admin.toString()) }.status shouldBe HttpStatusCode.OK
                row(plain)[MemberTable.votingRight] shouldBe false
            }
        }

        test("setMemberVotingRight / release: value, source, time, a fixed audit key, no-op writes nothing, release only from MANUAL") {
            val admin = member(role = AccountRole.ADMIN)
            val target = member()
            testApplication {
                application { installRoutes() }
                // release without a manual value
                client.post("/t/vr-release/$target") { header("X-Member-Id", admin.toString()) }.status shouldBe HttpStatusCode.Conflict
                val set = client.post("/t/vr/$target/false") { header("X-Member-Id", admin.toString()) }
                set.status shouldBe HttpStatusCode.OK
                set.bodyAsText() shouldBe "false:MANUAL:true"
                val audits = auditTextOf(target)
                audits shouldContain "member.voting_right.revoked"
                // the audit carries the key only -- no value as free text, no name
                (audits.contains("Stimmrecht Testperson")) shouldBe false
                val auditCount = transaction { AuditLogEntryTable.selectAll().where { AuditLogEntryTable.entityId eq target }.count() }
                // the same value again: no write, no audit
                client.post("/t/vr/$target/false") { header("X-Member-Id", admin.toString()) }.bodyAsText() shouldBe "false:MANUAL:true"
                transaction { AuditLogEntryTable.selectAll().where { AuditLogEntryTable.entityId eq target }.count() } shouldBe auditCount
                client.post("/t/vr/$target/true") { header("X-Member-Id", admin.toString()) }.bodyAsText() shouldBe "true:MANUAL:true"
                audits.contains("granted") shouldBe false // the first entry was the revoke
                auditTextOf(target) shouldContain "member.voting_right.granted"
                // release: value stays, source LUPABB
                client.post("/t/vr-release/$target") { header("X-Member-Id", admin.toString()) }.bodyAsText() shouldBe "true:LUPABB"
                auditTextOf(target) shouldContain "member.voting_right.released_to_sync"
                row(target)[MemberTable.votingRightSource] shouldBe VotingRightSource.LUPABB
            }
        }

        test(
            "a donor never holds an administrative role: grant with BOARD refused, grant with MEMBER works, role change to ADMIN refused",
        ) {
            val admin = member(role = AccountRole.ADMIN)
            val donor = member(status = MemberStatus.DONOR, role = null)
            testApplication {
                application { installRoutes() }
                client.post("/t/grant/$donor/BOARD") { header("X-Member-Id", admin.toString()) }.status shouldBe HttpStatusCode.Conflict
                client.post("/t/grant/$donor/ADMIN") { header("X-Member-Id", admin.toString()) }.status shouldBe HttpStatusCode.Conflict
                transaction { AccountTable.selectAll().where { AccountTable.memberId eq donor }.count() } shouldBe 0
                client.post("/t/grant/$donor/MEMBER") { header("X-Member-Id", admin.toString()) }.bodyAsText() shouldBe "MEMBER:DONOR"
                client.post("/t/role/$donor/ADMIN") { header("X-Member-Id", admin.toString()) }.status shouldBe HttpStatusCode.Conflict
                client.post("/t/role/$donor/TREASURER") { header("X-Member-Id", admin.toString()) }.status shouldBe HttpStatusCode.Conflict
            }
        }

        test(
            "a member with an administrative role cannot become a donor; a plain MEMBER account can, the sessions stay and the status counts at once",
        ) {
            val admin = member(role = AccountRole.ADMIN)
            val treasurer = member(role = AccountRole.TREASURER)
            val plain = member(role = AccountRole.MEMBER)
            testApplication {
                application { installRoutes() }
                client.post("/t/status/$treasurer/DONOR") { header("X-Member-Id", admin.toString()) }.status shouldBe
                    HttpStatusCode.Conflict
                row(treasurer)[MemberTable.status] shouldBe MemberStatus.ACTIVE
                SessionStore.createSession(plain)
                SessionStore.countActiveForMember(plain) shouldBe 1
                client.post("/t/status/$plain/DONOR") { header("X-Member-Id", admin.toString()) }.bodyAsText() shouldBe "DONOR"
                // V1.9.298: DONOR is no login-blocking status, so the session is NOT revoked; the status is read fresh on every request
                SessionStore.countActiveForMember(plain) shouldBe 1
                row(plain)[MemberTable.status] shouldBe MemberStatus.DONOR
            }
        }
    })
