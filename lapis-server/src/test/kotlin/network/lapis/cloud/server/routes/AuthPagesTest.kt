package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.formUrlEncode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.server.module

/**
 * Welle V1.9.135 -- the five sign-in / consent / error pages: shared shell, field order, escaping, fixed error texts and
 * the (newly introduced) security headers. Header behaviour is asserted on the header strings only, never in a browser.
 */
class AuthPagesTest :
    FunSpec({
        val branding = TEST_AUTH_BRANDING
        val logoBranding = ResolvedBranding(title = "Lapis Cloud", logoAvailable = true, logoPath = "/x")
        val base = "http://localhost"
        val xss = "<script>alert(1)</script>\"'"

        fun consent(
            clientName: String = "Test Client",
            redirectUri: String = "https://app.example.org/cb?x=1",
            nonce: String = "",
            b: ResolvedBranding = branding,
        ) = consentPageHtml(
            clientName = clientName,
            scopes = setOf("openid", "profile"),
            clientId = "cid",
            redirectUri = redirectUri,
            scopeParam = "openid profile",
            state = "st",
            codeChallenge = "ch",
            nonce = nonce,
            branding = b,
            baseUrl = base,
        )

        fun mcp(
            clientName: String = "Agent",
            nonce: String? = null,
            scope: String = "mcp:member_read",
            redirectUri: String = "http://127.0.0.1:1/cb",
        ) = mcpConsentPageHtml(
            clientName = clientName,
            clientId = "cid",
            redirectUri = redirectUri,
            scopeParam = scope,
            state = "st",
            codeChallenge = "ch",
            nonce = nonce,
            resource = "http://localhost/mcp",
            branding = branding,
            baseUrl = base,
        )

        val allPages =
            mapOf(
                "consent" to consent(),
                "form" to rpLoginFormHtml(branding = branding, baseUrl = base),
                "error" to authErrorPageHtml(message = "Fehler", branding = branding, baseUrl = base),
                "mcp" to mcp(),
            )

        allPages.forEach { (name, html) ->
            test("$name page: doctype, lang, chrome without navigation, card, footer, no script or inline style") {
                html.startsWith("<!doctype html>") shouldBe true
                html shouldContain "<html lang=\"de\">"
                html shouldContain "class=\"chrome\""
                html shouldContain "Lapis Cloud"
                html shouldContain "href=\"/s/assets/style.css\""
                html shouldContain "<main id=\"main\">"
                html shouldContain "class=\"auth-card\""
                html shouldContain "/impressum"
                html shouldContain "/datenschutz"
                html shouldContain "name=\"robots\" content=\"noindex, nofollow\""
                listOf("chrome-nav", "chrome-cta", "<script", "style=", "<style").forEach { html shouldNotContain it }
            }
        }

        test("logo branding renders an img instead of the wordmark") {
            consent(b = logoBranding) shouldContain "/api/branding/logo"
        }

        test("home-server form: label, input and cancel link") {
            val html = rpLoginFormHtml(branding = branding, baseUrl = base)
            html shouldContain "action=\"/federation/oidc/rp/login\""
            html shouldContain "for=\"home_server\""
            html shouldContain "id=\"home_server\""
            html shouldContain "name=\"home_server\""
            html shouldContain "type=\"url\""
            html shouldContain "required"
            html shouldContain "href=\"$base\""
        }

        test("guest consent: six hidden fields in order, nonce present even when empty, allow before deny, no cancel link") {
            val html = consent()
            val names = listOf("client_id", "redirect_uri", "scope", "state", "code_challenge", "nonce")
            val idx = names.map { html.indexOf("type=\"hidden\" name=\"$it\" value=\"") }
            idx.all { it >= 0 } shouldBe true
            idx.sorted() shouldContainExactly idx
            html shouldContain "type=\"hidden\" name=\"nonce\" value=\"\""
            (html.indexOf("value=\"allow\"") < html.indexOf("value=\"deny\"")) shouldBe true
            html shouldNotContain "Abbrechen"
        }

        test("guest consent: redirect host shown without port or path; the whole URI only in the hidden field") {
            val html = consent(redirectUri = "https://app.example.org:8443/cb?x=1")
            html shouldContain "<strong>app.example.org</strong>"
            html shouldNotContain "8443</strong>"
            consent(redirectUri = "not a uri") shouldNotContain "weitergeleitet"
        }

        test("MCP consent: fixed heading, resource and connection label, nonce only when set") {
            val html = mcp()
            html shouldContain "Ein KI-Agent möchte auf Ihre Daten zugreifen."
            html shouldContain "type=\"hidden\" name=\"resource\" value=\"http://localhost/mcp\""
            html shouldContain "name=\"connection_label\""
            html shouldContain "maxlength=\"60\""
            html shouldNotContain "name=\"nonce\""
            mcp(nonce = "n1") shouldContain "type=\"hidden\" name=\"nonce\" value=\"n1\""
            (html.indexOf("value=\"allow\"") < html.indexOf("value=\"deny\"")) shouldBe true
        }

        test("MCP consent: client name is filtered of control characters and capped at 80 characters") {
            val html = mcp(clientName = "A\u0007" + "b".repeat(200))
            html shouldNotContain "\u0007"
            html shouldContain "<strong>A" + "b".repeat(79) + "</strong>"
            html shouldNotContain "b".repeat(80)
        }

        test("guest consent: client name gets the same filter") {
            val html = consent(clientName = "A\u0007" + "b".repeat(200))
            html shouldNotContain "\u0007"
            html shouldNotContain "b".repeat(80)
        }

        test("XSS: hostile values never break out of attributes or inject markup") {
            val pages =
                listOf(
                    consent(clientName = xss, redirectUri = "https://x.example/$xss", nonce = xss),
                    mcp(clientName = xss, nonce = xss, scope = "mcp:member_read $xss", redirectUri = "https://x.example/$xss"),
                )
            pages.forEach { html ->
                html shouldNotContain "<script>alert(1)"
                html shouldNotContain "onerror="
                Regex("value=\"([^\"]*)\"").findAll(html).forEach { it.groupValues[1] shouldNotContain "<" }
            }
        }

        test("error texts: every OIDC/Keycloak message is German and carries no URL or exception hint") {
            val html = authErrorPageHtml(message = "Die Heimatserver-Adresse ist ungültig.", branding = branding, baseUrl = base)
            html shouldContain "role=\"alert\""
            html shouldContain "Zur Startseite"
            listOf("http://evil", "Exception", "Invalid", "Could not").forEach { html shouldNotContain it }
        }

        // ---- header strings via routes ----

        val selfCsp =
            "default-src 'none'; style-src 'self'; img-src data:; base-uri 'none'; form-action 'self'; frame-ancestors 'none'"

        val unrestrictedCsp = "default-src 'none'; style-src 'self'; img-src data:; base-uri 'none'; frame-ancestors 'none'"

        test("GET rp/login: no form-action (POST 302s to the home server), full header set, Referrer-Policy same-origin") {
            testApplication {
                application { module() }
                val response = client.get("/federation/oidc/rp/login")
                response.status shouldBe HttpStatusCode.OK
                response.headers["Content-Security-Policy"] shouldBe unrestrictedCsp
                response.headers["Content-Security-Policy"]!! shouldNotContain "form-action"
                response.headers["X-Frame-Options"] shouldBe "DENY"
                response.headers["X-Content-Type-Options"] shouldBe "nosniff"
                response.headers["Cache-Control"] shouldBe "no-store"
                response.headers["X-Robots-Tag"] shouldBe "noindex, nofollow"
                response.headers["Referrer-Policy"] shouldBe "same-origin"
                response.headers.getAll("Content-Security-Policy")!!.size shouldBe 1
            }
        }

        test("POST rp/login with an unusable address: error page with headers that does not echo the input") {
            testApplication {
                application { module() }
                val response =
                    client.post("/federation/oidc/rp/login") {
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(Parameters.build { append("home_server", "https://evil.example/<b>") }.formUrlEncode())
                    }
                val body = response.bodyAsText()
                response.headers["Content-Security-Policy"] shouldBe selfCsp
                body shouldContain "Anmeldung fehlgeschlagen"
                body shouldNotContain "evil.example"
                body shouldNotContain "<b>"
            }
        }

        test("applyAuthPageHeaders UNRESTRICTED omits form-action") {
            testApplication {
                application {
                    routing {
                        get("/t") {
                            call.applyAuthPageHeaders(formAction = AuthFormAction.UNRESTRICTED, imgSrcSelf = true)
                            call.respondText("ok")
                        }
                    }
                }
                val csp = client.get("/t").headers["Content-Security-Policy"]!!
                csp shouldNotContain "form-action"
                csp shouldContain "frame-ancestors 'none'"
                csp shouldContain "img-src 'self' data:"
            }
        }

        test("header application uses no referrer-less policy (would turn the form POST Origin into null)") {
            testApplication {
                application { module() }
                client
                    .get("/federation/oidc/rp/login") {
                        header(HttpHeaders.Accept, "text/html")
                    }.headers["Referrer-Policy"] shouldNotContain "no-referrer"
            }
        }
    })
