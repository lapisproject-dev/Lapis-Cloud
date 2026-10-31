package network.lapis.cloud.server.partner

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** V1.9.244 -- the public feed route: no login, ETag/304, memoised, rate limited. */
class PartnerFeedRoutesTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        val fixed = Instant.parse("2026-11-05T10:00:00Z")

        fun withFeed(
            limiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes),
            block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit,
        ) = testApplication {
            application { routing { registerPartnerFeedRoutes(rateLimiter = limiter, clock = { fixed }) } }
            block()
        }

        test("200 without any session or cookie, JSON, cacheable, nosniff, no CORS") {
            withFeed {
                val response = client.get("/api/public/partner-feed/v1")
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.ContentType]!!.lowercase() shouldStartWith "application/json"
                response.headers[HttpHeaders.CacheControl] shouldBe "public, max-age=300"
                response.headers["X-Content-Type-Options"] shouldBe "nosniff"
                response.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe null
                val feed = PARTNER_FEED_JSON.decodeFromString(PartnerFeedV1.serializer(), response.bodyAsText())
                feed.schemaVersion shouldBe 1
                feed.kind shouldBe PARTNER_FEED_KIND
            }
        }

        test("the ETag survives a memo rebuild when only generatedAt changed") {
            var clockNow = fixed
            testApplication {
                application {
                    routing {
                        registerPartnerFeedRoutes(
                            rateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes),
                            clock = { clockNow },
                        )
                    }
                }
                val first = client.get("/api/public/partner-feed/v1")
                clockNow = fixed + 5.minutes
                val second =
                    client.get(
                        "/api/public/partner-feed/v1",
                    ) { header(HttpHeaders.IfNoneMatch, first.headers[HttpHeaders.ETag]!!) }
                second.status shouldBe HttpStatusCode.NotModified
            }
        }

        test("the ETag answers a repeated request with 304 and no body, also for a list and for * and a weak tag") {
            withFeed {
                val first = client.get("/api/public/partner-feed/v1")
                val etag = first.headers[HttpHeaders.ETag]!!
                val second = client.get("/api/public/partner-feed/v1") { header(HttpHeaders.IfNoneMatch, etag) }
                second.status shouldBe HttpStatusCode.NotModified
                second.bodyAsText() shouldBe ""
                client.get("/api/public/partner-feed/v1") { header(HttpHeaders.IfNoneMatch, "\"other\", $etag") }.status shouldBe
                    HttpStatusCode.NotModified
                client.get("/api/public/partner-feed/v1") { header(HttpHeaders.IfNoneMatch, "W/$etag") }.status shouldBe
                    HttpStatusCode.NotModified
                client.get("/api/public/partner-feed/v1") { header(HttpHeaders.IfNoneMatch, "*") }.status shouldBe
                    HttpStatusCode.NotModified
                client.get("/api/public/partner-feed/v1") { header(HttpHeaders.IfNoneMatch, "\"nope\"") }.status shouldBe HttpStatusCode.OK
            }
        }

        test("query parameters are ignored and the body is the same") {
            withFeed {
                val plain = client.get("/api/public/partner-feed/v1").bodyAsText()
                client.get("/api/public/partner-feed/v1?evil=1&x=http://127.0.0.1").bodyAsText() shouldBe plain
            }
        }

        test("the 31st request within a minute is 429 with Retry-After") {
            withFeed {
                repeat(30) { client.get("/api/public/partner-feed/v1").status shouldBe HttpStatusCode.OK }
                val limited = client.get("/api/public/partner-feed/v1")
                limited.status shouldBe HttpStatusCode.TooManyRequests
                limited.headers[HttpHeaders.RetryAfter] shouldBe "60"
                limited.bodyAsText() shouldBe """{"error":"rate_limited"}"""
            }
        }
    })
