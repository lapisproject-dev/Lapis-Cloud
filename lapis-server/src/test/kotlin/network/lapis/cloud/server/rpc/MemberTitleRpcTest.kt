package network.lapis.cloud.server.rpc

import dev.kilua.rpc.AbstractServiceException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
import network.lapis.cloud.server.member.PeerFixture
import network.lapis.cloud.server.member.configuredSmtp
import network.lapis.cloud.shared.domain.AccountRole
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * V151 -- `updateMemberTitle` through a real `MemberService`: self or privileged, validation after authorization, peer protection, the
 * value-free audit entry (only on a real change), anonymized members refused, `display_name` never touched. H2 only.
 */
class MemberTitleRpcTest :
    FunSpec({
        val fx = PeerFixture()
        val mailer = FakePeerNotificationMailer()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fx.cleanUp() }
        beforeTest { fx.isolateAdmins() }
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
                post("/t/title/{id}") {
                    val dto =
                        memberService(call).updateMemberTitle(
                            memberId = call.parameters["id"]!!,
                            title = call.request.queryParameters["title"],
                        )
                    call.respondText("OK:${dto.title}|${dto.displayName}")
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

        fun titleOf(id: Uuid): String? = row(id)[MemberTable.title]

        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

        test("a member sets, changes and clears their own title; display_name never changes") {
            testApplication {
                application { setup() }
                val m = fx.member()
                val name = row(m)[MemberTable.displayName]
                client.act("/t/title/$m?title=${enc("  Prof.   Dr. ")}", m) shouldBe (HttpStatusCode.OK to "OK:Prof. Dr.|$name")
                titleOf(m) shouldBe "Prof. Dr."
                client.act("/t/title/$m?title=Dr.", m).second shouldBe "OK:Dr.|$name"
                client.act("/t/title/$m", m).second shouldBe "OK:null|$name"
                titleOf(m) shouldBe null
                row(m)[MemberTable.displayName] shouldBe name
            }
        }

        test("an administrator may set another member's title") {
            testApplication {
                application { setup() }
                val m = fx.member()
                client.act("/t/title/$m?title=Dr.", fx.member(role = AccountRole.BOARD)).first shouldBe HttpStatusCode.OK
                titleOf(m) shouldBe "Dr."
            }
        }

        test("tamper: a plain member writing another member's title is Forbidden and nothing is written") {
            testApplication {
                application { setup() }
                val victim = fx.member()
                val stranger = fx.member()
                client.act("/t/title/$victim?title=Dr.", stranger) shouldBe (HttpStatusCode.Conflict to "ForbiddenException")
                client.act("/t/title/$victim?title=${"x".repeat(80)}", stranger) shouldBe (HttpStatusCode.Conflict to "ForbiddenException")
                titleOf(victim) shouldBe null
            }
        }

        test("invalid titles are rejected value-free, nothing is written and no audit entry appears") {
            testApplication {
                application { setup() }
                val m = fx.member()
                listOf("x".repeat(51), "a\nb", "a\u0000b", "<script>", "a>b").forEach { bad ->
                    client.act("/t/title/$m?title=${enc(bad)}", m) shouldBe (HttpStatusCode.Conflict to "ConflictException")
                }
                titleOf(m) shouldBe null
                fx.auditJsonFor(m).joinToString("\n") shouldNotContain "TITLE_UPDATED"
            }
        }

        test("the audit entry is value-free and written only when the value really changes") {
            testApplication {
                application { setup() }
                val m = fx.member()
                client.act("/t/title/$m?title=Dipl.-Ing.", m).first shouldBe HttpStatusCode.OK
                client.act("/t/title/$m?title=Dipl.-Ing.", m).first shouldBe HttpStatusCode.OK
                val audit = fx.auditJsonFor(m)
                audit.count { it.contains("TITLE_UPDATED") } shouldBe 1
                audit.joinToString("\n") shouldNotContain "Dipl"
            }
        }

        test("peer protection: BOARD cannot write an ADMIN's title; an ADMIN can and the target is told") {
            testApplication {
                mailer.protectedDataNotices.clear()
                application { setup() }
                val board = fx.member(role = AccountRole.BOARD)
                val admin = fx.member(role = AccountRole.ADMIN)
                client.act("/t/title/$admin?title=Dr.", board) shouldBe (HttpStatusCode.Conflict to "PeerProtectionDeniedException")
                titleOf(admin) shouldBe null
                val otherAdmin = fx.admin(displayName = "Schreibender Admin")
                client.act("/t/title/$admin?title=Dr.", otherAdmin).first shouldBe HttpStatusCode.OK
                titleOf(admin) shouldBe "Dr."
                mailer.protectedDataNotices.map { it.actorName }.distinct() shouldBe listOf("Schreibender Admin")
            }
        }

        test("an anonymized member's title cannot be written") {
            testApplication {
                application { setup() }
                val m = fx.member()
                transaction {
                    MemberTable.update({ MemberTable.id eq m }) { it[anonymizedAt] = LocalDateTime(2026, 1, 1, 0, 0) }
                }
                client.act("/t/title/$m?title=Dr.", fx.member(role = AccountRole.BOARD)) shouldBe
                    (HttpStatusCode.Conflict to "ConflictException")
                titleOf(m) shouldBe null
            }
        }
    })
