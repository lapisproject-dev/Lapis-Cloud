package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.McpMemberBlockTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.mcp.config.McpConfig
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.security.PasswordHasher
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.McpAccessStateDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Exercises [McpAccessService] directly, mirroring [ApiKeyServiceTest]'s house style (throwaway
 * routes calling the service class directly, `X-Member-Id` trusted-header auth) -- no such test
 * existed for this class before (finding: the kill-switch's own write-rate-limiter direction bug,
 * see [McpAccessService] KDoc "The switch always wins").
 */
class McpAccessServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                McpMemberBlockTable.deleteWhere { McpMemberBlockTable.memberId inList createdMemberIds }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
        }

        fun createActiveMember(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "McpAccessService Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.MEMBER
                    it[passwordHash] = PasswordHasher.hash("irrelevant-password-1234")
                }
            }
            createdMemberIds += id
            return id
        }

        suspend fun testApp(
            writeRateLimiter: LoginRateLimiter,
            block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit,
        ) {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ForbiddenException> {
                            call,
                            cause,
                            ->
                            call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                        }
                    }
                    routing { registerMcpAccessTestRoutes(writeRateLimiter) }
                }
                block()
            }
        }

        test("turning access OFF is never blocked by the switch's own write rate limiter, however many times it was toggled") {
            // maxFailures=1 -- the very next ON call after this test's own setup would already be
            // rate-limited, so any OFF call succeeding here can only be because OFF is exempt.
            val limiter = LoginRateLimiter(maxFailures = 1, window = 15.minutes)
            testApp(writeRateLimiter = limiter) {
                val memberId = createActiveMember("mcp-access-off-exempt-${Uuid.random()}@example.org")

                // Exhaust the limiter via repeated OFF toggles -- if OFF were still counted (the
                // bug), the member would lock themselves out of their own kill switch.
                repeat(5) {
                    val response = client.post("/test/set/false") { header("X-Member-Id", memberId.toString()) }
                    response.status shouldBe HttpStatusCode.OK
                    val dto = Json.decodeFromString(McpAccessStateDto.serializer(), response.bodyAsText())
                    dto.accessAllowed shouldBe false
                }
            }
        }

        test("turning access ON is still rate-limited -- the switch's write-storm guard is not removed entirely") {
            val limiter = LoginRateLimiter(maxFailures = 1, window = 15.minutes)
            testApp(writeRateLimiter = limiter) {
                val memberId = createActiveMember("mcp-access-on-limited-${Uuid.random()}@example.org")

                val first = client.post("/test/set/true") { header("X-Member-Id", memberId.toString()) }
                first.status shouldBe HttpStatusCode.OK

                val second = client.post("/test/set/true") { header("X-Member-Id", memberId.toString()) }
                second.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test(
            "a burst of ON+OFF toggles still counts toward the shared limiter window -- OFF is never " +
                "blocked itself, but it is not free: it still trips the ON-side block for a later, legitimate ON",
        ) {
            // maxFailures=1 -- the very first call already fills the window, so the SECOND call
            // (regardless of direction) is the one that would be rejected if it were the ON
            // direction. This pins the round-4 fix: recordFailure() runs on BOTH directions (no
            // more reset() on OFF), so an attacker cannot clear the counter by toggling OFF and
            // toggle forever without ever tripping the limiter.
            val limiter = LoginRateLimiter(maxFailures = 1, window = 15.minutes)
            testApp(writeRateLimiter = limiter) {
                val memberId = createActiveMember("mcp-access-toggle-recovery-${Uuid.random()}@example.org")

                // ON fills the window (count=1).
                client.post("/test/set/true") { header("X-Member-Id", memberId.toString()) }.status shouldBe HttpStatusCode.OK
                // OFF is still exempt from the check -- the kill switch always wins -- but it still
                // RECORDS a failure (count=2), it does not reset the counter back to zero.
                client.post("/test/set/false") { header("X-Member-Id", memberId.toString()) }.status shouldBe HttpStatusCode.OK
                // A subsequent ON is now rate-limited, because the window's failure count (2) was
                // never cleared by the OFF call above.
                client.post("/test/set/true") { header("X-Member-Id", memberId.toString()) }.status shouldBe HttpStatusCode.Forbidden
                // OFF remains unblockable no matter how exhausted the window is.
                client.post("/test/set/false") { header("X-Member-Id", memberId.toString()) }.status shouldBe HttpStatusCode.OK
            }
        }
    })

private fun Route.registerMcpAccessTestRoutes(writeRateLimiter: LoginRateLimiter) {
    post("/test/set/{allowed}") {
        val allowed = call.parameters["allowed"]!!.toBoolean()
        val svc =
            McpAccessService(
                call = call,
                config = McpConfig.load { if (it == McpConfig.ENV_ENABLED) "true" else null },
                writeRateLimiter = writeRateLimiter,
            )
        val result = svc.setMcpAccessAllowed(allowed = allowed)
        call.respondText(Json.encodeToString(McpAccessStateDto.serializer(), result))
    }
}
