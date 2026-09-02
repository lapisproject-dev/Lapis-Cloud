package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.ApiKeyStore
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val ADMIN_ID = Uuid.parse("00000000-0000-0000-0000-000000000001")

/**
 * V1.3.1 "API-Fundament, lesend" -- pre-auth (IP-keyed) and post-auth (API-key-keyed) rate
 * limiting, both bounds enforced independently, plus a real `Retry-After` (Design-Team decision --
 * echoes [FederationInboxRateLimiter.retryAfterSeconds], not a fixed literal `60`).
 */
class PublicApiRateLimitTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        fun generousLimiter() = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)

        suspend fun testApp(
            preAuthLimiter: FederationInboxRateLimiter,
            postAuthLimiter: FederationInboxRateLimiter,
            block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit,
        ) {
            testApplication {
                application {
                    install(XForwardedHeaders) { useLastProxy() }
                    routing {
                        registerPublicApiRoutes(preAuthRateLimiter = preAuthLimiter, postAuthRateLimiter = postAuthLimiter)
                    }
                }
                block()
            }
        }

        test(
            "pre-auth (IP-keyed) rate limit rejects the (N+1)th request BEFORE the API key is even checked -- 429 with a real Retry-After",
        ) {
            testApp(preAuthLimiter = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes), postAuthLimiter = generousLimiter()) {
                val key = ApiKeyStore.issue(label = "Rate Test", createdByMemberId = ADMIN_ID).rawKey
                val first = client.get("/api/v1/members") { header("Authorization", "Bearer $key") }
                first.status shouldBe HttpStatusCode.OK

                // No Authorization header at all this time -- if the pre-auth limiter really runs
                // BEFORE key resolution, this still 429s (not 401), proving the ordering.
                val second = client.get("/api/v1/members")
                second.status shouldBe HttpStatusCode.TooManyRequests
                val retryAfter = second.headers[HttpHeaders.RetryAfter]?.toLongOrNull()
                (retryAfter != null && retryAfter in 1..60) shouldBe true
                second.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                Json.decodeFromString(PublicApiErrorDto.serializer(), second.bodyAsText()).error shouldBe "rate_limited"
            }
        }

        test("post-auth (per-key) rate limit rejects the (N+1)th request from the SAME key, independent of the pre-auth budget") {
            testApp(preAuthLimiter = generousLimiter(), postAuthLimiter = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes)) {
                val key = ApiKeyStore.issue(label = "Post-Auth Rate Test", createdByMemberId = ADMIN_ID).rawKey
                val first = client.get("/api/v1/members") { header("Authorization", "Bearer $key") }
                first.status shouldBe HttpStatusCode.OK
                val second = client.get("/api/v1/members") { header("Authorization", "Bearer $key") }
                second.status shouldBe HttpStatusCode.TooManyRequests
                Json.decodeFromString(PublicApiErrorDto.serializer(), second.bodyAsText()).error shouldBe "rate_limited"
            }
        }

        test("post-auth rate limit budgets are separate PER KEY -- exhausting one key never blocks a different key") {
            testApp(preAuthLimiter = generousLimiter(), postAuthLimiter = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes)) {
                val keyA = ApiKeyStore.issue(label = "Key A", createdByMemberId = ADMIN_ID).rawKey
                val keyB = ApiKeyStore.issue(label = "Key B", createdByMemberId = ADMIN_ID).rawKey
                client.get("/api/v1/members") { header("Authorization", "Bearer $keyA") }.status shouldBe HttpStatusCode.OK
                client.get("/api/v1/members") { header("Authorization", "Bearer $keyA") }.status shouldBe HttpStatusCode.TooManyRequests
                // Key B still has its own, untouched budget.
                client.get("/api/v1/members") { header("Authorization", "Bearer $keyB") }.status shouldBe HttpStatusCode.OK
            }
        }
    })
