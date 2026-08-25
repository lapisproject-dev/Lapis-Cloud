package network.lapis.cloud.server

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
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

        // V0.7.3 Basis-Mehrseiten-UI: "/" now serves the KVision/Kotlin-JS client bundle via
        // staticFiles(), not the greeting -- see Application.kt "clientDistRoot" KDoc. In this test
        // environment no client build has run, so the directory is empty and "/" 404s; that is the
        // expected, harmless behavior (see staticFiles KDoc comment), not a regression.
        test("root route 404s when no client build is present") {
            testApplication {
                application { module() }

                val response = client.get("/")

                response.status shouldBe HttpStatusCode.NotFound
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
        // environment's default), "/" must keep returning 404 exactly like the "root route 404s
        // when no client build is present" test above: `module()`'s new `get("/")` handler
        // replaces `staticFiles`' own prior handling of that route, and it MUST preserve the same
        // 404-on-no-build behavior (see Application.kt `serveIndexHtml` KDoc, V1.2.5 plan
        // stolperfalle 8.1) -- not fall through to a naive `200` with an empty body.
        test("root route still 404s when no client build is present, now via the branding-aware handler") {
            testApplication {
                application { module() }

                val response = client.get("/")

                response.status shouldBe HttpStatusCode.NotFound
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
