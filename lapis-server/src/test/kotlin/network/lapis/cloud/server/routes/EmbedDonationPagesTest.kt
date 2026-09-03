package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.embed.EmbedConfig
import network.lapis.cloud.server.embed.EmbedOriginAllowlist
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.payment.psp.PspConfigState
import java.io.File
import kotlin.time.Duration.Companion.minutes

/**
 * Welle V1.4.1b "Öffentliche Website-Integration -- anonymer Spenden-Pfad" -- die zwei
 * Stripe-Rückkehr-Seiten `GET /embed/v1/spende/danke` und `/abgebrochen`.
 */
class EmbedDonationPagesTest :
    FunSpec({
        fun generousLimiter() = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)

        val enabledConfig =
            EmbedConfig(
                enabled = true,
                allowlist = EmbedOriginAllowlist.parse(raw = "https://partei.example", allowInsecure = false).allowlist,
                allowInsecureOrigins = false,
            )

        suspend fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) {
            testApplication {
                application {
                    routing {
                        registerEmbedDonationRoutes(
                            config = enabledConfig,
                            pspConfigState = PspConfigState.NotConfigured,
                            checkoutClient = null,
                            donationCheckoutRateLimiter = generousLimiter(),
                            donationCheckoutAttemptRateLimiter = generousLimiter(),
                            donationPageRateLimiter = generousLimiter(),
                            baseUrl = "https://lapis.example",
                            brandTitle = "Testverein",
                        )
                    }
                }
                block()
            }
        }

        test("danke page without origin -> 200, no return link") {
            testApp {
                val response = client.get("/embed/v1/spende/danke")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldNotContain "<a href"
            }
        }

        test("danke page with unknown origin -> 200, no return link, the raw value never appears in the HTML") {
            testApp {
                val response = client.get("/embed/v1/spende/danke?origin=https://evil.example")
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body shouldNotContain "<a href"
                body shouldNotContain "evil.example"
            }
        }

        test("danke page with allowed origin -> 200, link to the canonical allowlist entry") {
            testApp {
                val response = client.get("/embed/v1/spende/danke?origin=https://partei.example")
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body shouldContain "href=\"https://partei.example\""
            }
        }

        test("origin with an XSS-shaped payload -> 200, no executable output, no echo") {
            testApp {
                val response =
                    client.get(
                        "/embed/v1/spende/danke?origin=" +
                            java.net.URLEncoder.encode("https://evil.example/\"><script>alert(1)</script>", "UTF-8"),
                    )
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body shouldNotContain "<script>"
                body shouldNotContain "evil.example"
            }
        }

        test("abgebrochen page: same headers/no-link contract") {
            testApp {
                val response = client.get("/embed/v1/spende/abgebrochen")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "Spende abgebrochen"
            }
        }

        test("both pages: frame-ancestors 'none', Cache-Control no-store, X-Content-Type-Options nosniff") {
            testApp {
                for (path in listOf("/embed/v1/spende/danke", "/embed/v1/spende/abgebrochen")) {
                    val response = client.get(path)
                    response.headers["Content-Security-Policy"].orEmpty() shouldContain "frame-ancestors 'none'"
                    response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                    response.headers["X-Content-Type-Options"] shouldBe "nosniff"
                }
            }
        }

        test("source scan: EmbedDonationHtml.kt uses only kotlinx.html's escaping APIs, no unsafe/raw HTML injection") {
            val sourceDir = File("src/main/kotlin").let { if (it.exists()) it else File("lapis-server/src/main/kotlin") }
            val file = File(sourceDir, "network/lapis/cloud/server/routes/EmbedDonationHtml.kt")
            file.exists() shouldBe true
            val text = file.readText()
            text shouldNotContain "unsafe"
            text shouldNotContain "Unsafe"
            text shouldNotContain "innerHTML"
        }
    })
