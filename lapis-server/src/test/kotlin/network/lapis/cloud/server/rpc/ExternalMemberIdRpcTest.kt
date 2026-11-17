package network.lapis.cloud.server.rpc

import dev.kilua.rpc.AbstractServiceException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.FakeAdminPasswordResetNotificationMailer
import network.lapis.cloud.server.mail.FakePasswordResetMailer
import network.lapis.cloud.server.mail.FakePeerNotificationMailer
import network.lapis.cloud.server.member.ExternalMemberIdConfig
import network.lapis.cloud.server.member.PeerFixture
import network.lapis.cloud.server.member.configuredSmtp
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberAdminQuery
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * V156 external member id through a real `MemberService`: who may set, replace and clear it (BOARD/ADMIN, never the own row, peer protection), the
 * validation, the duplicate refusal, the missing source, the DTO visibility per role (including the suspended-voting-right flag), the settings
 * and the audit entries (flags and source only, never the id). H2 only.
 */
class ExternalMemberIdRpcTest :
    FunSpec({
        val fx = PeerFixture()
        val mailer = FakePeerNotificationMailer()
        var config = ExternalMemberIdConfig.forTest(source = "Wabeo", requireForVoting = true)

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fx.cleanUp() }
        beforeTest {
            fx.isolateAdmins()
            config = ExternalMemberIdConfig.forTest(source = "Wabeo", requireForVoting = true)
        }
        afterTest { fx.restoreAdmins() }

        fun memberService(call: ApplicationCall): MemberService =
            MemberService(
                call = call,
                passwordResetMailer = FakePasswordResetMailer(),
                adminPasswordResetNotificationMailer = FakeAdminPasswordResetNotificationMailer(),
                smtpConfigState = configuredSmtp(),
                adminPasswordMailTargetRateLimiter = FederationInboxRateLimiter(maxRequests = 1000),
                adminPasswordMailActorRateLimiter = FederationInboxRateLimiter(maxRequests = 1000),
                adminPasswordNotificationTargetRateLimiter = FederationInboxRateLimiter(maxRequests = 1000),
                memberCardIssueRateLimiter = FederationInboxRateLimiter(maxRequests = 1000),
                memberAddressAdminReadRateLimiter = FederationInboxRateLimiter(maxRequests = 1000),
                peerNotificationMailer = mailer,
                externalMemberIdConfig = config,
            )

        fun Application.setup() {
            install(StatusPages) {
                exception<AbstractServiceException> {
                    call,
                    cause,
                    ->
                    call.respondText(cause::class.simpleName!!, status = HttpStatusCode.Conflict)
                }
            }
            routing {
                post("/t/set/{id}/{value}") {
                    val dto =
                        memberService(
                            call,
                        ).setExternalMemberId(memberId = call.parameters["id"]!!, externalId = call.parameters["value"]!!)
                    call.respondText("OK:${dto.externalMemberId}|${dto.externalMemberIdSource}|${dto.votingSuspendedForMissingExternalId}")
                }
                post("/t/clear/{id}") {
                    val dto = memberService(call).clearExternalMemberId(memberId = call.parameters["id"]!!)
                    call.respondText("OK:${dto.externalMemberId}|${dto.externalMemberIdSource}|${dto.votingSuspendedForMissingExternalId}")
                }
                post("/t/settings") {
                    val s = memberService(call).getExternalMemberIdSettings()
                    call.respondText("OK:${s.source}|${s.requiredForVoting}|${s.ownExternalMemberId}|${s.ownExternalMemberIdSource}")
                }
                post("/t/roster") {
                    val page = memberService(call).listMembersForAdministration(MemberAdminQuery(limit = 200))
                    call.respondText(
                        page.rows.joinToString(";") { "${it.id}:${it.externalMemberId}:${it.votingSuspendedForMissingExternalId}" },
                    )
                }
            }
        }

        suspend fun HttpClient.act(
            path: String,
            actor: Uuid,
        ): Pair<HttpStatusCode, String> {
            val r = post(path) { header("X-Member-Id", actor.toString()) }
            return r.status to r.bodyAsText()
        }

        fun row(id: Uuid) = transaction { MemberTable.selectAll().where { MemberTable.id eq id }.single() }

        fun auditOf(id: Uuid): String = fx.auditJsonFor(id).joinToString("\n")

        test("BOARD sets, replaces and clears the id; each real change writes one audit entry (no id value), a no-op none") {
            testApplication {
                application { setup() }
                val m = fx.member()
                val board = fx.member(role = AccountRole.BOARD)
                client.act("/t/set/$m/WB-1", board) shouldBe (HttpStatusCode.OK to "OK:WB-1|Wabeo|false")
                client.act("/t/set/$m/WB-1", board).first shouldBe HttpStatusCode.OK
                fx.auditJsonFor(m).count { it.contains("member.external_id.") } shouldBe 1
                client.act("/t/set/$m/WB-2", board).second shouldBe "OK:WB-2|Wabeo|false"
                client.act("/t/clear/$m", board).second shouldBe "OK:null|null|true"
                client.act("/t/clear/$m", board).first shouldBe HttpStatusCode.OK
                fx.auditJsonFor(m).count { it.contains("member.external_id.") } shouldBe 3
                val audit = auditOf(m)
                audit shouldContain "set_manually"
                audit shouldContain "replaced_manually"
                audit shouldContain "cleared_manually"
                audit shouldNotContain "WB-1"
                audit shouldNotContain "WB-2"
            }
        }

        test("an ADMIN may; MEMBER and TREASURER are Forbidden; nobody touches their own row; nothing is written") {
            testApplication {
                application { setup() }
                val m = fx.member()
                val plain = fx.member()
                val treasurer = fx.member(role = AccountRole.TREASURER)
                client.act("/t/set/$m/A1", fx.member(role = AccountRole.ADMIN)).first shouldBe HttpStatusCode.OK
                val other = fx.member()
                client.act("/t/set/$other/A2", plain) shouldBe (HttpStatusCode.Conflict to "ForbiddenException")
                client.act("/t/clear/$other", treasurer) shouldBe (HttpStatusCode.Conflict to "ForbiddenException")
                val board = fx.member(role = AccountRole.BOARD)
                client.act("/t/set/$board/A3", board) shouldBe (HttpStatusCode.Conflict to "ForbiddenException")
                client.act("/t/clear/$board", board) shouldBe (HttpStatusCode.Conflict to "ForbiddenException")
                row(other)[MemberTable.externalMemberId] shouldBe null
                row(board)[MemberTable.externalMemberId] shouldBe null
            }
        }

        test("peer protection: BOARD cannot set the id of an ADMIN, an ADMIN can") {
            testApplication {
                application { setup() }
                val admin = fx.member(role = AccountRole.ADMIN)
                client.act("/t/set/$admin/P1", fx.member(role = AccountRole.BOARD)) shouldBe
                    (HttpStatusCode.Conflict to "PeerProtectionDeniedException")
                row(admin)[MemberTable.externalMemberId] shouldBe null
                client.act("/t/set/$admin/P1", fx.admin(displayName = "Anderer Admin")).first shouldBe HttpStatusCode.OK
            }
        }

        test("an invalid id is a BadRequest, a duplicate of the same source is ExternalMemberIdTaken, an anonymised member a Conflict") {
            testApplication {
                application { setup() }
                val board = fx.member(role = AccountRole.BOARD)
                val a = fx.member()
                val b = fx.member()
                client.act("/t/set/$a/bad;id", board) shouldBe (HttpStatusCode.Conflict to "BadRequestException")
                client.act("/t/set/$a/-x", board) shouldBe (HttpStatusCode.Conflict to "BadRequestException")
                client.act("/t/set/$a/SAME-1", board).first shouldBe HttpStatusCode.OK
                client.act("/t/set/$b/SAME-1", board) shouldBe (HttpStatusCode.Conflict to "ExternalMemberIdTakenException")
                row(b)[MemberTable.externalMemberId] shouldBe null
                val anon = fx.member()
                transaction { MemberTable.update({ MemberTable.id eq anon }) { it[anonymizedAt] = LocalDateTime(2026, 1, 1, 0, 0) } }
                client.act("/t/set/$anon/Z9", board) shouldBe (HttpStatusCode.Conflict to "ConflictException")
            }
        }

        test("without a configured source the write RPCs refuse and the settings carry no source") {
            config = ExternalMemberIdConfig.DISABLED
            testApplication {
                application { setup() }
                val board = fx.member(role = AccountRole.BOARD)
                val m = fx.member()
                client.act("/t/set/$m/N1", board) shouldBe (HttpStatusCode.Conflict to "ExternalMemberIdSourceNotConfiguredException")
                client.act("/t/settings", m) shouldBe (HttpStatusCode.OK to "OK:null|false|null|null")
            }
        }

        test("settings: source, requirement and the caller's OWN id; the roster shows the id and the suspension flag to BOARD only") {
            testApplication {
                application { setup() }
                val board = fx.member(role = AccountRole.BOARD)
                val m = fx.member()
                val without = fx.member()
                client.act("/t/set/$m/OWN-7", board)
                client.act("/t/settings", m) shouldBe (HttpStatusCode.OK to "OK:Wabeo|true|OWN-7|Wabeo")
                client.act("/t/settings", without).second shouldBe "OK:Wabeo|true|null|null"
                val rosterBoard = client.act("/t/roster", board).second
                rosterBoard shouldContain "$m:OWN-7:false"
                rosterBoard shouldContain "$without:null:true"
                val treasurer = fx.member(role = AccountRole.TREASURER)
                val rosterTreasurer = client.act("/t/roster", treasurer).second
                rosterTreasurer shouldNotContain "OWN-7"
                rosterTreasurer shouldContain "$without:null:false"
            }
        }

        test("the suspension flag is off while the requirement is off") {
            config = ExternalMemberIdConfig.forTest(source = "Wabeo", requireForVoting = false)
            testApplication {
                application { setup() }
                val board = fx.member(role = AccountRole.BOARD)
                val m = fx.member()
                client.act("/t/roster", board).second shouldContain "$m:null:false"
                config.votingPolicy().requireExternalMemberId shouldBe false
                (fx.statusOf(m) == MemberStatus.ACTIVE) shouldBe true
            }
        }
    })
