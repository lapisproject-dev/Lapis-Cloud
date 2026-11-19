package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.embed.EmbedConfig
import network.lapis.cloud.server.embed.EmbedOriginAllowlist
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes

private const val ORIGIN = "https://partei.example"

/** V1.9.315 -- `GET /api/embed/v1/join`: exactly `{"open":...}`, allowlisted CORS, no cookie, rate-limited, behind the embed switch. */
class EmbedJoinRoutesTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        fun setEnabled(value: Boolean) =
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[membershipApplicationEnabled] = value
                }
            }

        afterTest { setEnabled(true) }

        fun config(enabled: Boolean = true) =
            EmbedConfig(
                enabled = enabled,
                allowlist = EmbedOriginAllowlist.parse(raw = ORIGIN, allowInsecure = false).allowlist,
                allowInsecureOrigins = false,
            )

        suspend fun app(
            keycloak: Boolean = false,
            limiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes),
            embedEnabled: Boolean = true,
            block: suspend ApplicationTestBuilder.() -> Unit,
        ) = testApplication {
            application {
                routing {
                    if (embedEnabled) registerEmbedJoinRoutes(config = config(), keycloakEnabled = keycloak, rateLimiter = limiter)
                }
            }
            block()
        }

        test("allowed origin: 200 {open:true}, CORS echo, Vary, no-store exactly once, nosniff, no cookie even with one sent") {
            app {
                val response =
                    client.get("/api/embed/v1/join") {
                        header(HttpHeaders.Origin, ORIGIN)
                        header(HttpHeaders.Cookie, "lapis_session=abc")
                    }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe "{\"open\":true}"
                response.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe ORIGIN
                response.headers[HttpHeaders.Vary]!!.contains("Origin") shouldBe true
                response.headers.getAll(HttpHeaders.CacheControl) shouldBe listOf("no-store")
                response.headers["X-Content-Type-Options"] shouldBe "nosniff"
                response.headers[HttpHeaders.SetCookie] shouldBe null
            }
        }

        test("switch off: {open:false}") {
            setEnabled(false)
            app { client.get("/api/embed/v1/join") { header(HttpHeaders.Origin, ORIGIN) }.bodyAsText() shouldBe "{\"open\":false}" }
        }

        test("Keycloak mode: {open:false} even with the switch on") {
            app(keycloak = true) {
                client.get("/api/embed/v1/join") { header(HttpHeaders.Origin, ORIGIN) }.bodyAsText() shouldBe "{\"open\":false}"
            }
        }

        test("foreign origin: 403; no Origin header: served") {
            app {
                client.get("/api/embed/v1/join") { header(HttpHeaders.Origin, "https://evil.example") }.status shouldBe
                    HttpStatusCode.Forbidden
                client.get("/api/embed/v1/join").status shouldBe HttpStatusCode.OK
            }
        }

        test("rate limit: the request over the limit is 429 with Retry-After") {
            app(limiter = FederationInboxRateLimiter(maxRequests = 2, window = 1.minutes)) {
                repeat(2) { client.get("/api/embed/v1/join") { header(HttpHeaders.Origin, ORIGIN) }.status shouldBe HttpStatusCode.OK }
                val limited = client.get("/api/embed/v1/join") { header(HttpHeaders.Origin, ORIGIN) }
                limited.status shouldBe HttpStatusCode.TooManyRequests
                (limited.headers[HttpHeaders.RetryAfter] != null) shouldBe true
            }
        }

        test("embed disabled: the route is not registered (404)") {
            app(embedEnabled = false) { client.get("/api/embed/v1/join").status shouldBe HttpStatusCode.NotFound }
        }
    })
