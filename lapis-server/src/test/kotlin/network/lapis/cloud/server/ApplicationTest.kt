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
    })
