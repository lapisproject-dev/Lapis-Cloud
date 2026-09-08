package network.lapis.cloud.server

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication

class ApplicationTest :
    FunSpec({
        test("ping route responds with greeting") {
            testApplication {
                application { module() }

                val response = client.get("/api/ping")

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe "Hello from Lapis Cloud"
            }
        }

        // Welle V1.4.6 "Öffentliche Startseite": "/" is no longer the KVision/Kotlin-JS client
        // bundle -- it is now `registerPublicLandingRoutes`' server-rendered landing page, which
        // needs NO client build at all (it renders from DB aggregates, not from a static file on
        // disk). This test's own name/reasoning is intentionally rewritten, not just its assertion --
        // a reviewer seeing "/" still return 200 here is NOT a regression of the old "404 when no
        // client build" behavior, that behavior moved to "/app" (see the next test below).
        test("root route serves the server-rendered landing page, independent of any client build") {
            testApplication {
                application { module() }

                val response = client.get("/")

                response.status shouldBe HttpStatusCode.OK
                (response.headers[HttpHeaders.ContentType] ?: "") shouldContain "text/html"
                response.bodyAsText() shouldContain "Lapis Cloud"
            }
        }

        // V1.2.3 Echter SMTP-Versand -- `module()` calls `SmtpConfig.load()` +
        // `SmtpStartupCheck.verifyAndLog(...)` unconditionally during startup (see Application.kt
        // wiring). This test environment never sets any `LAPIS_SMTP_*` variable, so every test in
        // this file already exercises the `SmtpConfigState.NotConfigured` path end to end -- the
        // server boots and answers `/api/ping` exactly as before this wave, proving that path adds
        // no startup requirement. The `SmtpConfigState.Incomplete` fail-fast path (thrown
        // `IllegalStateException`, never reachable here since no `LAPIS_SMTP_*` var is set) is
        // covered directly, without booting a server, by `network.lapis.cloud.server.mail
        // .SmtpStartupCheckTest`. Since V1.2.3's Design-Review, `module()` also derives a
        // `MailBranding` from `smtpConfigState` -- this test's `NotConfigured` path implicitly
        // exercises `MailBranding.notConfigured()` too; dedicated coverage for the branding VALUE
        // itself lives in `network.lapis.cloud.server.mail.MailTemplatesTest`.
        test("server boots fine with zero LAPIS_SMTP_* env vars set (NotConfigured path)") {
            testApplication {
                application { module() }

                val response = client.get("/api/ping")

                response.status shouldBe HttpStatusCode.OK
            }
        }

        // V1.2.5 White-Label-Branding -- with zero LAPIS_BRAND_* env vars set (this test
        // environment's default), "/app" must keep returning 404 exactly like this file's original
        // "root route 404s when no client build is present" test did for "/": `module()`'s
        // `get("/app")` handler MUST preserve the same 404-on-no-build behavior (see Application.kt
        // `serveIndexHtml` KDoc, V1.2.5 plan stolperfalle 8.1) -- not fall through to a naive `200`
        // with an empty body. Welle V1.4.6 moved the SPA from "/" to "/app" -- this test moved with
        // it; "/" itself is covered by the "root route serves the server-rendered landing page"
        // test above instead.
        test("/app 404s when no client build is present, via the branding-aware handler") {
            testApplication {
                application { module() }

                val response = client.get("/app")

                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        // Welle V1.4.6: "/app/" (trailing slash) MUST 308-redirect to "/app", never fall through to
        // staticFiles' own index-file lookup for that path -- see Application.kt's `get("/app/")`
        // KDoc for why (a relative `main.bundle.js` src resolves wrong under a trailing slash).
        test("/app/ (trailing slash) 308-redirects to /app") {
            testApplication {
                application { module() }
                val noRedirectClient = createClient { followRedirects = false }

                val response = noRedirectClient.get("/app/")

                response.status shouldBe HttpStatusCode(308, "Permanent Redirect")
                response.headers["Location"] shouldBe "http://localhost:8080/app"
            }
        }

        // Same reasoning as above, for the second literal route module() now registers.
        test("/index.html also 404s when no client build is present") {
            testApplication {
                application { module() }

                val response = client.get("/index.html")

                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        // V1.2.5 White-Label-Branding -- with zero LAPIS_BRAND_* env vars set, no logo is
        // configured at all, so this route must 404, never attempt to stream a nonexistent file.
        test("branding logo route 404s when LAPIS_BRAND_LOGO_PATH is unset") {
            testApplication {
                application { module() }

                val response = client.get("/api/branding/logo")

                response.status shouldBe HttpStatusCode.NotFound
            }
        }
    })
