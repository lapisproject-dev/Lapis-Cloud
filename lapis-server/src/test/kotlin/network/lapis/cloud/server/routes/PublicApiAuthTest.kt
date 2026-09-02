package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.ApiKeyTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.ApiKeyStore
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val ADMIN_ID = Uuid.parse("00000000-0000-0000-0000-000000000001")

/**
 * V1.3.1 "API-Fundament, lesend" -- every 401 branch [PublicApiSupport.requirePublicApiPrincipal]
 * can produce, plus the design-team-required `WWW-Authenticate` header on all of them, and the
 * `key_revoked`/`key_expired`-only-after-a-hash-match discipline (Design-Team decision #4).
 */
class PublicApiAuthTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        fun generousLimiter() = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)

        suspend fun testApp(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) {
            testApplication {
                application {
                    routing {
                        registerPublicApiRoutes(preAuthRateLimiter = generousLimiter(), postAuthRateLimiter = generousLimiter())
                    }
                }
                block()
            }
        }

        test("missing Authorization header -> 401 unauthorized, with WWW-Authenticate") {
            testApp {
                val response = client.get("/api/v1/members")
                response.status shouldBe HttpStatusCode.Unauthorized
                Json.decodeFromString(PublicApiErrorDto.serializer(), response.bodyAsText()).error shouldBe "unauthorized"
                response.headers[HttpHeaders.WWWAuthenticate].shouldNotBeNullString()
            }
        }

        test("wrong Authorization schema (Basic, not Bearer) -> 401 unauthorized") {
            testApp {
                val response = client.get("/api/v1/members") { header("Authorization", "Basic dXNlcjpwYXNz") }
                response.status shouldBe HttpStatusCode.Unauthorized
                Json.decodeFromString(PublicApiErrorDto.serializer(), response.bodyAsText()).error shouldBe "unauthorized"
            }
        }

        test("a Bearer token without the lapis_ prefix (e.g. a session-shaped token) -> 401 unauthorized") {
            testApp {
                val response = client.get("/api/v1/members") { header("Authorization", "Bearer some-other-token-abc123") }
                response.status shouldBe HttpStatusCode.Unauthorized
                Json.decodeFromString(PublicApiErrorDto.serializer(), response.bodyAsText()).error shouldBe "unauthorized"
            }
        }

        test("a well-formed but unknown lapis_-prefixed key -> 401 unauthorized (not key_revoked/key_expired -- no hash match)") {
            testApp {
                val response = client.get("/api/v1/members") { header("Authorization", "Bearer lapis_${"A".repeat(43)}") }
                response.status shouldBe HttpStatusCode.Unauthorized
                Json.decodeFromString(PublicApiErrorDto.serializer(), response.bodyAsText()).error shouldBe "unauthorized"
            }
        }

        test("a revoked key -> 401 key_revoked, with WWW-Authenticate") {
            testApp {
                val issued = ApiKeyStore.issue(label = "To Revoke", createdByMemberId = ADMIN_ID)
                ApiKeyStore.revoke(id = issued.id, revokedByMemberId = ADMIN_ID)
                val response = client.get("/api/v1/members") { header("Authorization", "Bearer ${issued.rawKey}") }
                response.status shouldBe HttpStatusCode.Unauthorized
                Json.decodeFromString(PublicApiErrorDto.serializer(), response.bodyAsText()).error shouldBe "key_revoked"
                response.headers[HttpHeaders.WWWAuthenticate].shouldNotBeNullString()
            }
        }

        test("an expired key -> 401 key_expired") {
            testApp {
                val issued = ApiKeyStore.issue(label = "To Expire", createdByMemberId = ADMIN_ID)
                val past = (Clock.System.now() - 1.hours).toLocalDateTime(TimeZone.UTC)
                transaction { ApiKeyTable.update({ ApiKeyTable.id eq issued.id }) { it[expiresAt] = past } }
                val response = client.get("/api/v1/members") { header("Authorization", "Bearer ${issued.rawKey}") }
                response.status shouldBe HttpStatusCode.Unauthorized
                Json.decodeFromString(PublicApiErrorDto.serializer(), response.bodyAsText()).error shouldBe "key_expired"
            }
        }

        test("a valid key -> 200, no WWW-Authenticate header on a successful response") {
            testApp {
                val issued = ApiKeyStore.issue(label = "Valid", createdByMemberId = ADMIN_ID)
                val response = client.get("/api/v1/members") { header("Authorization", "Bearer ${issued.rawKey}") }
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.WWWAuthenticate] shouldBe null
            }
        }

        test("every response (success and every 401 variant) carries Cache-Control: no-store and Vary: Authorization") {
            testApp {
                val issued = ApiKeyStore.issue(label = "Headers", createdByMemberId = ADMIN_ID)
                val ok = client.get("/api/v1/members") { header("Authorization", "Bearer ${issued.rawKey}") }
                ok.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                ok.headers[HttpHeaders.Vary] shouldBe HttpHeaders.Authorization

                val unauthorized = client.get("/api/v1/members")
                unauthorized.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                unauthorized.headers[HttpHeaders.Vary] shouldBe HttpHeaders.Authorization
            }
        }
    })

private fun String?.shouldNotBeNullString() {
    (this != null) shouldBe true
}
