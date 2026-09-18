package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MemberCardCodeTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.installMemberCardExceptionHandlers
import network.lapis.cloud.server.rpc.registerMemberCardReissueTestRoute
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Welle "Digitaler Mitgliedsausweis (PDF)" -- coverage for the three rate limiters `Application.kt`
 * wires for this feature (`memberCardIssueRateLimiter`, `memberCardPublicPageRateLimiter`,
 * `memberCardCodeFailureLimiter`), none of which had a single test touching them before this file
 * (Review MAJOR, 2026-09). Routes are installed directly with deliberately tiny-budget limiter
 * instances, same idiom [EmbedRateLimitTest]/[PublicApiRateLimitTest] already establish, rather than
 * looping the real production caps (10/60min etc.) dozens of times per test.
 */
class MemberCardRateLimitTest :
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

        fun createMember(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Ausweis Ratelimit Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 2, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += id
            return id
        }

        test("POST card.pdf is 429 once memberCardIssueRateLimiter's budget is exhausted, with Retry-After") {
            testApplication {
                application {
                    routing {
                        registerMemberCardRoutes(
                            baseUrl = "https://example.org",
                            brandTitle = "Testverband",
                            rateLimiter = FederationInboxRateLimiter(maxRequests = 2, window = 1.hours),
                        )
                    }
                }
                val member = createMember("ratelimit-issue-1@example.org")

                repeat(2) {
                    client.post("/api/members/$member/card.pdf") { header("X-Member-Id", member.toString()) }.status shouldBe
                        HttpStatusCode.OK
                }
                val third = client.post("/api/members/$member/card.pdf") { header("X-Member-Id", member.toString()) }
                third.status shouldBe HttpStatusCode.TooManyRequests
                third.headers[HttpHeaders.RetryAfter] shouldBe "3600"
            }
        }

        test("reissueMemberCard (RPC) draws from the SAME budget as POST card.pdf -- exhausting one blocks the other") {
            testApplication {
                val sharedLimiter = FederationInboxRateLimiter(maxRequests = 2, window = 1.hours)
                application {
                    install(StatusPages) { installMemberCardExceptionHandlers() }
                    routing {
                        registerMemberCardRoutes(
                            baseUrl = "https://example.org",
                            brandTitle = "Testverband",
                            rateLimiter = sharedLimiter,
                        )
                        registerMemberCardReissueTestRoute(issueRateLimiter = sharedLimiter)
                    }
                }
                val member = createMember("ratelimit-issue-2@example.org")

                // Spend the entire 2-request budget through the HTTP download route...
                repeat(2) {
                    client.post("/api/members/$member/card.pdf") { header("X-Member-Id", member.toString()) }.status shouldBe
                        HttpStatusCode.OK
                }
                // ...and confirm the RPC path -- reachable through a completely different code path,
                // see [network.lapis.cloud.server.rpc.MemberService.reissueMemberCard] -- is ALSO
                // rejected, because it shares the identical [FederationInboxRateLimiter] instance and
                // the identical `"member-card:<id>"` key. Before the fix this call would have
                // succeeded unconditionally (see the class KDoc "Security fix (Review MAJOR, 2026-09)").
                client
                    .post("/test/member-card/reissue/$member") { header("X-Member-Id", member.toString()) }
                    .status shouldBe HttpStatusCode.Conflict
            }
        }

        test("GET /ausweis is 429 once memberCardPublicPageRateLimiter's per-IP budget is exhausted") {
            testApplication {
                application {
                    routing {
                        registerMemberCardPublicRoutes(
                            brandTitle = "Testverband",
                            pageRateLimiter = FederationInboxRateLimiter(maxRequests = 2, window = 1.minutes, maxTrackedKeys = 50_000),
                            codeFailureLimiter = LoginRateLimiter(maxFailures = 1_000, window = 15.minutes),
                        )
                    }
                }

                repeat(2) { client.get("/ausweis?code=ZZZZZZZZZZZZZZZZ").status shouldBe HttpStatusCode.OK }
                val third = client.get("/ausweis?code=ZZZZZZZZZZZZZZZZ")
                third.status shouldBe HttpStatusCode.TooManyRequests
            }
        }

        test("GET /ausweis is 429 once memberCardCodeFailureLimiter's failures-only budget is exhausted, even with page budget to spare") {
            testApplication {
                application {
                    routing {
                        registerMemberCardPublicRoutes(
                            brandTitle = "Testverband",
                            // Page budget deliberately generous so ONLY the failure limiter can be
                            // the one that trips -- isolates the two limiters from each other.
                            pageRateLimiter = FederationInboxRateLimiter(maxRequests = 1_000, window = 1.minutes, maxTrackedKeys = 50_000),
                            codeFailureLimiter = LoginRateLimiter(maxFailures = 2, window = 15.minutes),
                        )
                    }
                }

                repeat(2) { client.get("/ausweis?code=ZZZZZZZZZZZZZZZZ").status shouldBe HttpStatusCode.OK }
                val third = client.get("/ausweis?code=ZZZZZZZZZZZZZZZZ")
                third.status shouldBe HttpStatusCode.TooManyRequests
            }
        }
    })
