package network.lapis.cloud.server.routes

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.newsletter.testTrackingToken
import network.lapis.cloud.server.security.LoginRateLimiter
import kotlin.time.Duration.Companion.minutes

/**
 * Welle V1.9.136 -- the member-card verification pages and the mailing-link 404/429 pages share the auth-page shell. No
 * database needed: every case here is answered before any lookup (malformed/missing code, garbage token, rate limits).
 */
class PublicPagesShellTest :
    FunSpec({
        val branding = TEST_AUTH_BRANDING
        val logoBranding = ResolvedBranding(title = "Lapis Cloud", logoAvailable = true, logoPath = "/x")
        val base = "https://test.example"
        val card =
            MemberCardPublicHtml.ValidCard(
                displayName = "Erika Mustermann",
                memberNumber = "M-2026-00001",
                statusLabel = "Aktiv",
                joinedAt = LocalDate(2026, 2, 1),
            )
        val pages =
            mapOf(
                "valid" to MemberCardPublicHtml.validPage(branding = branding, baseUrl = base, card = card),
                "invalid" to MemberCardPublicHtml.invalidPage(branding = branding, baseUrl = base),
                "card429" to MemberCardPublicHtml.tooManyRequestsPage(branding = branding, baseUrl = base),
                "card500" to MemberCardPublicHtml.errorPage(branding = branding, baseUrl = base),
                "mail404" to MailingTrackingHtml.notFoundPage(branding = branding, baseUrl = base),
                "mail429" to MailingTrackingHtml.tooManyRequestsPage(branding = branding, baseUrl = base),
            )

        fun HttpResponse.count(name: String): Int = headers.getAll(name)?.size ?: 0

        test("every page carries the full shell") {
            pages.forEach { (name, html) ->
                withClue("page: $name") {
                    html shouldContain "has-chrome auth-page"
                    html shouldContain "id=\"main\""
                    html shouldContain "auth-card"
                    html shouldContain "/s/assets/style.css"
                    html shouldContain "/impressum"
                    html shouldContain "/datenschutz"
                    html shouldContain "lang=\"de\""
                    html shouldContain "noindex, nofollow"
                    html shouldNotContain "embed-page"
                    html shouldNotContain "<script"
                }
            }
        }

        test("valid page: success callout, exactly three facts, no actions") {
            val html = pages.getValue("valid")
            html shouldContain "auth-callout--success"
            html shouldContain "<dl class=\"auth-facts\">"
            Regex("<dt>").findAll(html).count() shouldBe 3
            listOf("Mitgliedsnummer", "Status", "Mitglied seit", "M-2026-00001", "Aktiv", "01.02.2026", "Erika Mustermann")
                .forEach { html shouldContain it }
            html shouldNotContain "auth-actions"
        }

        test("invalid page is neutral (no error callout, no alert role) and offers the home action") {
            val html = pages.getValue("invalid")
            html shouldNotContain "auth-callout--error"
            html shouldNotContain "role=\"alert\""
            html shouldContain "Zur Startseite"
            html shouldBe MemberCardPublicHtml.invalidPage(branding = branding, baseUrl = base) // deterministic: no per-call variance
        }

        test("a logo variant renders the logo while the text variant does not") {
            MemberCardPublicHtml.invalidPage(branding = logoBranding, baseUrl = base) shouldContain "/api/branding/logo"
            MemberCardPublicHtml.invalidPage(branding = branding, baseUrl = base) shouldNotContain "/api/branding/logo"
        }

        fun memberCardApp(
            b: ResolvedBranding = branding,
            pageLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 100, window = 1.minutes),
            block: suspend io.ktor.client.HttpClient.() -> Unit,
        ) = testApplication {
            application {
                routing {
                    registerMemberCardPublicRoutes(
                        branding = b,
                        baseUrl = base,
                        pageRateLimiter = pageLimiter,
                        codeFailureLimiter = LoginRateLimiter(maxFailures = 1_000, window = 15.minutes),
                    )
                }
            }
            client.block()
        }

        test("member card: negative page headers, each exactly once, XSS code never reflected") {
            val evil = "%3Cscript%3Ealert(1)%3C%2Fscript%3E%22%27"
            memberCardApp {
                val r = get("/ausweis?code=$evil")
                r.status shouldBe HttpStatusCode.OK
                val body = r.bodyAsText()
                body shouldNotContain "<script"
                body shouldNotContain "alert(1)"
                listOf(HttpHeaders.CacheControl, "X-Robots-Tag", "Referrer-Policy", "X-Frame-Options", "Content-Security-Policy")
                    .forEach { r.count(it) shouldBe 1 }
                r.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                r.headers["X-Robots-Tag"] shouldBe "noindex, nofollow"
                r.headers["Referrer-Policy"] shouldBe "no-referrer"
                r.headers["X-Frame-Options"] shouldBe "DENY"
                r.headers["Content-Security-Policy"]!! shouldContain "frame-ancestors 'none'"
                r.headers["Content-Security-Policy"]!! shouldContain "img-src data:;"
            }
        }

        test("member card: all negative inputs give a byte-identical body") {
            memberCardApp {
                val bodies = listOf("/ausweis", "/ausweis?code=nope", "/ausweis?code=ZZZZZZZZZZZZZZZZ").map { get(it).bodyAsText() }
                bodies.distinct().size shouldBe 1
            }
        }

        test("member card: with a logo the CSP allows img-src 'self'") {
            memberCardApp(b = logoBranding) {
                get("/ausweis").headers["Content-Security-Policy"]!! shouldContain "img-src 'self' data:;"
            }
        }

        test("member card: 429 has Retry-After and the shell") {
            memberCardApp(pageLimiter = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes)) {
                get("/ausweis").status shouldBe HttpStatusCode.OK
                val r = get("/ausweis")
                r.status shouldBe HttpStatusCode.TooManyRequests
                r.headers[HttpHeaders.RetryAfter] shouldBe "60"
                r.bodyAsText() shouldContain "has-chrome auth-page"
            }
        }

        fun mailingApp(
            b: ResolvedBranding = branding,
            clickLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 100, window = 1.minutes),
            block: suspend io.ktor.client.HttpClient.() -> Unit,
        ) = testApplication {
            application {
                routing {
                    registerMailingTrackingRoutes(
                        trackingToken = testTrackingToken(),
                        clickRateLimiter = clickLimiter,
                        pixelRateLimiter = FederationInboxRateLimiter(maxRequests = 100, window = 1.minutes),
                        branding = b,
                        baseUrl = base,
                    )
                }
            }
            client.block()
        }

        test("mailing: neutral 404 with shell, no Location, identical for different bad tokens, headers once") {
            mailingApp {
                val a = get("/m/c/garbage")
                val b = get("/m/c/%3Cscript%3Ealert(1)%3C%2Fscript%3E")
                a.status shouldBe HttpStatusCode.NotFound
                b.status shouldBe HttpStatusCode.NotFound
                a.headers[HttpHeaders.Location] shouldBe null
                val body = a.bodyAsText()
                body shouldContain "has-chrome auth-page"
                body shouldContain "Link nicht gefunden"
                body shouldBe b.bodyAsText()
                body shouldNotContain "garbage"
                listOf(HttpHeaders.CacheControl, "X-Robots-Tag", "Referrer-Policy", "X-Content-Type-Options", "X-Frame-Options")
                    .forEach { a.count(it) shouldBe 1 }
                a.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                a.headers["X-Robots-Tag"] shouldBe "noindex"
                a.headers["Referrer-Policy"] shouldBe "no-referrer"
                a.headers["Content-Security-Policy"]!! shouldContain "frame-ancestors 'none'"
            }
        }

        test("mailing: 429 has Retry-After, shell and logo CSP") {
            mailingApp(b = logoBranding, clickLimiter = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes)) {
                get("/m/c/garbage").status shouldBe HttpStatusCode.NotFound
                val r = get("/m/c/garbage")
                r.status shouldBe HttpStatusCode.TooManyRequests
                r.headers[HttpHeaders.RetryAfter] shouldBe "60"
                r.bodyAsText() shouldContain "has-chrome auth-page"
                r.headers["Content-Security-Policy"]!! shouldContain "img-src 'self' data:;"
                r.count("Referrer-Policy") shouldBe 1
            }
        }
    })
