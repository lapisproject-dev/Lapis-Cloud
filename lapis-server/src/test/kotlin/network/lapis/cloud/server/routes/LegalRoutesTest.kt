package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.branding.BrandConfig
import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.legal.LegalConfig
import kotlin.time.Duration.Companion.minutes

/**
 * Welle V1.4.7 "Rechtstexte" -- route-level tests for `GET /impressum`/`GET /datenschutz`, same
 * `testApplication` house style [PublicLandingRoutesTest]/[PublicTransparencyRoutesTest] establish.
 * Deliberately NO `DatabaseConfig.connect()`/`DevSeedData.seedIfEmpty()` in `beforeSpec` (unlike
 * those two files) -- [registerLegalRoutes]' whole point is that it never touches the database (see
 * its own KDoc "No transaction {}, no DB access at all"); a green run of this file WITHOUT a
 * database connection is itself part of that guarantee.
 */
class LegalRoutesTest :
    FunSpec({
        fun generousLimiter() = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)

        fun completeLegalConfig(): LegalConfig {
            val env =
                mapOf(
                    LegalConfig.ENV_OPERATOR_NAME to "Beispielverein e. V.",
                    LegalConfig.ENV_STREET to "Musterweg 1",
                    LegalConfig.ENV_POSTAL_CODE to "12345",
                    LegalConfig.ENV_CITY to "Musterstadt",
                    LegalConfig.ENV_COUNTRY to "Deutschland",
                    LegalConfig.ENV_CONTACT_EMAIL to "info@example.org",
                    LegalConfig.ENV_REPRESENTATIVE to "Max Muster",
                )
            return LegalConfig.load { key -> env[key] }
        }

        suspend fun testApp(
            readLimiter: FederationInboxRateLimiter = generousLimiter(),
            legal: LegalConfig = completeLegalConfig(),
            block: suspend ApplicationTestBuilder.() -> Unit,
        ) {
            testApplication {
                application {
                    install(XForwardedHeaders) { useLastProxy() }
                    install(AutoHeadResponse)
                    routing {
                        registerLegalRoutes(
                            readRateLimiter = readLimiter,
                            branding = ResolvedBranding(title = BrandConfig.DEFAULT_TITLE, logoAvailable = false, logoPath = null),
                            legal = legal,
                        )
                    }
                }
                block()
            }
        }

        test("R1: GET /impressum -> 200, HTML, chrome present, footer links present") {
            testApp {
                val response = client.get("/impressum")
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body shouldContain "<html"
                body shouldContain "chrome-inner"
                body shouldContain "href=\"http://localhost:8080/impressum\""
                body shouldContain "href=\"http://localhost:8080/datenschutz\""
            }
        }

        test("R2: GET /datenschutz -> 200, HTML, chrome present, footer links present") {
            testApp {
                val response = client.get("/datenschutz")
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body shouldContain "<html"
                body shouldContain "chrome-inner"
                body shouldContain "Datenschutzerklärung"
            }
        }

        test("R3: two calls are byte-identical; second call with If-None-Match -> 304") {
            testApp {
                val first = client.get("/impressum")
                val etag = first.headers[HttpHeaders.ETag] ?: error("no ETag header")
                val second = client.get("/impressum") { header(HttpHeaders.IfNoneMatch, etag) }
                second.status shouldBe HttpStatusCode.NotModified
            }
        }

        test("R4: GET /impressum?utm_source=x -> 308 to the canonical URL") {
            testApp {
                val noRedirectClient = createClient { followRedirects = false }
                val response = noRedirectClient.get("/impressum?utm_source=x")
                response.status shouldBe HttpStatusCode(308, "Permanent Redirect")
                response.headers[HttpHeaders.Location] shouldBe "http://localhost:8080/impressum"
            }
        }

        test("R5: ?lang=de -> 308 (default is unparameterized); ?lang=fr -> 200 with French chrome; ?lang=xx -> 308") {
            testApp {
                val noRedirectClient = createClient { followRedirects = false }
                val de = noRedirectClient.get("/impressum?lang=de")
                de.status shouldBe HttpStatusCode(308, "Permanent Redirect")

                val fr = client.get("/impressum?lang=fr")
                fr.status shouldBe HttpStatusCode.OK
                fr.bodyAsText() shouldContain "Mentions légales"

                val xx = noRedirectClient.get("/impressum?lang=xx")
                xx.status shouldBe HttpStatusCode(308, "Permanent Redirect")
            }
        }

        test("R6: rate limit exceeded -> 429 with Retry-After, independent of another route family's budget") {
            testApp(readLimiter = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes)) {
                client.get("/impressum").status shouldBe HttpStatusCode.OK
                val second = client.get("/impressum")
                second.status shouldBe HttpStatusCode.TooManyRequests
                second.headers[HttpHeaders.RetryAfter] shouldBe "60"
            }
        }

        test("R7: full security header set present; CSP has no script-src 'self'") {
            testApp {
                val response = client.get("/datenschutz")
                val csp = response.headers["Content-Security-Policy"] ?: error("no CSP header")
                csp shouldContain "default-src 'none'"
                csp shouldNotContain "script-src 'self'"
                response.headers["X-Content-Type-Options"] shouldBe "nosniff"
                response.headers["X-Frame-Options"] shouldBe "DENY"
            }
        }

        test("R8: Cache-Control public max-age=3600, no stale-while-revalidate, no Vary: Cookie") {
            testApp {
                val response = client.get("/impressum")
                val cacheControl = response.headers[HttpHeaders.CacheControl]
                cacheControl shouldBe "public, max-age=3600"
                val vary = response.headers[HttpHeaders.Vary]
                vary?.let { it shouldNotContain "Cookie" }
            }
        }

        test("R9: body is byte-identical with and without a session cookie") {
            testApp {
                val withoutCookie = client.get("/impressum").bodyAsText()
                val withCookie = client.get("/impressum") { header(HttpHeaders.Cookie, "session=irrelevant") }.bodyAsText()
                withoutCookie shouldBe withCookie
            }
        }

        test("R10: meta robots is noindex,follow") {
            testApp {
                val body = client.get("/impressum").bodyAsText()
                body shouldContain "noindex,follow"
            }
        }

        test("R11: canonical ignores a spoofed Host header") {
            testApp {
                val body = client.get("/impressum") { header(HttpHeaders.Host, "evil.example.org") }.bodyAsText()
                body shouldNotContain "evil.example.org"
                body shouldContain "http://localhost:8080/impressum"
            }
        }

        test("R12: renders without any database connection -- no transaction {} in the request path") {
            testApp {
                // If registerLegalRoutes ever performed a transaction {} call, this request would
                // throw (no DatabaseConfig.connect() was called anywhere in this test file) instead
                // of returning 200 -- see class KDoc.
                client.get("/impressum").status shouldBe HttpStatusCode.OK
                client.get("/datenschutz").status shouldBe HttpStatusCode.OK
            }
        }

        test("incomplete config still renders 200 with an operator notice, never 500") {
            testApp(legal = LegalConfig.load { null }) {
                val response = client.get("/impressum")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "legal-incomplete"
            }
        }
    })
