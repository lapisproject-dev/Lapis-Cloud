package network.lapis.cloud.server.mail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf

private fun envOf(vararg pairs: Pair<String, String>): (String) -> String? {
    val map = pairs.toMap()
    return { key -> map[key] }
}

private val MINIMAL_VALID_ENV =
    arrayOf(
        SmtpConfig.ENV_HOST to "mxe9fb.netcup.net",
        SmtpConfig.ENV_USERNAME to "no_reply@example.org",
        SmtpConfig.ENV_PASSWORD to "s3cr3t",
        SmtpConfig.ENV_FROM_ADDRESS to "no_reply@example.org",
        SmtpConfig.ENV_FROM_NAME to "Partei der Vernunft",
    )

/**
 * Exercises [SmtpConfig.load] purely through its injected `env` function -- never `System.getenv`
 * (same reasoning `ConferenceStreamingConfigTest`/`OracleSourceConfigTest` document). No network,
 * no filesystem -- see [SmtpConfig.load] KDoc "Pure string validation ONLY".
 */
class SmtpConfigTest :
    FunSpec({
        test("everything unset -> NotConfigured, never throws") {
            SmtpConfig.load(envOf()).shouldBeInstanceOf<SmtpConfigState.NotConfigured>()
        }

        test("all five required values set -> Configured, port defaults to 465") {
            val state = SmtpConfig.load(envOf(*MINIMAL_VALID_ENV))
            val configured = state.shouldBeInstanceOf<SmtpConfigState.Configured>()
            configured.config.port shouldBe SmtpConfig.DEFAULT_PORT
            configured.config.transportSecurity shouldBe SmtpTransportSecurity.IMPLICIT_TLS
        }

        test("only HOST set -> Incomplete, missing lists exactly USERNAME/PASSWORD/FROM_ADDRESS/FROM_NAME") {
            val state = SmtpConfig.load(envOf(SmtpConfig.ENV_HOST to "mxe9fb.netcup.net"))
            val incomplete = state.shouldBeInstanceOf<SmtpConfigState.Incomplete>()
            incomplete.missing shouldContain SmtpConfig.ENV_USERNAME
            incomplete.missing shouldContain SmtpConfig.ENV_PASSWORD
            incomplete.missing shouldContain SmtpConfig.ENV_FROM_ADDRESS
            incomplete.missing shouldContain SmtpConfig.ENV_FROM_NAME
            incomplete.missing.shouldNotContain(SmtpConfig.ENV_HOST)
        }

        test("PORT = 'abc' -> Incomplete, invalid names LAPIS_SMTP_PORT") {
            val state = SmtpConfig.load(envOf(*MINIMAL_VALID_ENV, SmtpConfig.ENV_PORT to "abc"))
            val incomplete = state.shouldBeInstanceOf<SmtpConfigState.Incomplete>()
            incomplete.invalid.any { it.contains(SmtpConfig.ENV_PORT) } shouldBe true
        }

        test("PORT = '0' -> Incomplete (out of range)") {
            val state = SmtpConfig.load(envOf(*MINIMAL_VALID_ENV, SmtpConfig.ENV_PORT to "0"))
            state.shouldBeInstanceOf<SmtpConfigState.Incomplete>()
        }

        test("PORT = '70000' -> Incomplete (out of range)") {
            val state = SmtpConfig.load(envOf(*MINIMAL_VALID_ENV, SmtpConfig.ENV_PORT to "70000"))
            state.shouldBeInstanceOf<SmtpConfigState.Incomplete>()
        }

        test("FROM_ADDRESS without an '@' -> Incomplete") {
            val state =
                SmtpConfig.load(
                    envOf(
                        SmtpConfig.ENV_HOST to "mxe9fb.netcup.net",
                        SmtpConfig.ENV_USERNAME to "no_reply@example.org",
                        SmtpConfig.ENV_PASSWORD to "s3cr3t",
                        SmtpConfig.ENV_FROM_ADDRESS to "kein-email",
                        SmtpConfig.ENV_FROM_NAME to "Partei der Vernunft",
                    ),
                )
            val incomplete = state.shouldBeInstanceOf<SmtpConfigState.Incomplete>()
            incomplete.invalid shouldContain SmtpConfig.ENV_FROM_ADDRESS
        }

        test("FROM_ADDRESS with an embedded CRLF -> Incomplete (header-injection guard)") {
            val state =
                SmtpConfig.load(
                    envOf(
                        SmtpConfig.ENV_HOST to "mxe9fb.netcup.net",
                        SmtpConfig.ENV_USERNAME to "no_reply@example.org",
                        SmtpConfig.ENV_PASSWORD to "s3cr3t",
                        SmtpConfig.ENV_FROM_ADDRESS to "a@b.de\r\nBcc: evil@x.example",
                        SmtpConfig.ENV_FROM_NAME to "Partei der Vernunft",
                    ),
                )
            state.shouldBeInstanceOf<SmtpConfigState.Incomplete>()
        }

        test("blank-only values count as unset") {
            val state =
                SmtpConfig.load(
                    envOf(
                        SmtpConfig.ENV_HOST to "   ",
                        SmtpConfig.ENV_USERNAME to "no_reply@example.org",
                        SmtpConfig.ENV_PASSWORD to "s3cr3t",
                        SmtpConfig.ENV_FROM_ADDRESS to "no_reply@example.org",
                        SmtpConfig.ENV_FROM_NAME to "Partei der Vernunft",
                    ),
                )
            val incomplete = state.shouldBeInstanceOf<SmtpConfigState.Incomplete>()
            incomplete.missing shouldContain SmtpConfig.ENV_HOST
        }

        test("all seven LAPIS_SMTP_* set to empty string -> NotConfigured, not Incomplete") {
            // The exact shape docker-compose.yml's `${LAPIS_SMTP_HOST:-}`-style passthrough produces
            // for every deployment that leaves SMTP unconfigured (see deploy/production/docker-
            // compose.yml "Echter E-Mail-Versand"): all seven LAPIS_SMTP_* variables are SET in the
            // container's environment, each to the empty string -- never simply absent the way
            // `envOf()` on its own only ever produces. `value()`'s `.takeUnless { it.isBlank() }`
            // must treat every one of these as unset so `anySet` stays false; if that guard were
            // ever weakened to a bare `env(key) != null`, this flips silently to `Incomplete` for
            // EVERY such deployment and `SmtpStartupCheck` crash-loops the container on startup --
            // exactly the Round-1 KRITISCH finding this test exists to catch a regression of.
            val state =
                SmtpConfig.load(
                    envOf(
                        SmtpConfig.ENV_HOST to "",
                        SmtpConfig.ENV_PORT to "",
                        SmtpConfig.ENV_USERNAME to "",
                        SmtpConfig.ENV_PASSWORD to "",
                        SmtpConfig.ENV_FROM_ADDRESS to "",
                        SmtpConfig.ENV_FROM_NAME to "",
                        SmtpConfig.ENV_REPLY_TO to "",
                    ),
                )
            state.shouldBeInstanceOf<SmtpConfigState.NotConfigured>()
        }

        test("FROM_NAME missing, everything else set -> Incomplete, missing names exactly LAPIS_SMTP_FROM_NAME") {
            val state =
                SmtpConfig.load(
                    envOf(
                        SmtpConfig.ENV_HOST to "mxe9fb.netcup.net",
                        SmtpConfig.ENV_USERNAME to "no_reply@example.org",
                        SmtpConfig.ENV_PASSWORD to "s3cr3t",
                        SmtpConfig.ENV_FROM_ADDRESS to "no_reply@example.org",
                    ),
                )
            val incomplete = state.shouldBeInstanceOf<SmtpConfigState.Incomplete>()
            incomplete.missing shouldContain SmtpConfig.ENV_FROM_NAME
        }

        test("FROM_NAME with an embedded CRLF -> Incomplete, invalid names LAPIS_SMTP_FROM_NAME (header-injection guard)") {
            val state =
                SmtpConfig.load(
                    envOf(
                        *MINIMAL_VALID_ENV.filterNot { it.first == SmtpConfig.ENV_FROM_NAME }.toTypedArray(),
                        SmtpConfig.ENV_FROM_NAME to "Partei\r\nBcc: evil@x.example",
                    ),
                )
            val incomplete = state.shouldBeInstanceOf<SmtpConfigState.Incomplete>()
            incomplete.invalid shouldContain SmtpConfig.ENV_FROM_NAME
        }

        test("FROM_NAME blank-only -> Incomplete, missing (not invalid) names LAPIS_SMTP_FROM_NAME") {
            val state =
                SmtpConfig.load(
                    envOf(
                        *MINIMAL_VALID_ENV.filterNot { it.first == SmtpConfig.ENV_FROM_NAME }.toTypedArray(),
                        SmtpConfig.ENV_FROM_NAME to "   ",
                    ),
                )
            val incomplete = state.shouldBeInstanceOf<SmtpConfigState.Incomplete>()
            incomplete.missing shouldContain SmtpConfig.ENV_FROM_NAME
        }

        test("REPLY_TO valid -> Configured, replyTo set") {
            val state =
                SmtpConfig.load(envOf(*MINIMAL_VALID_ENV, SmtpConfig.ENV_REPLY_TO to "kontakt@example.org"))
            val configured = state.shouldBeInstanceOf<SmtpConfigState.Configured>()
            configured.config.replyTo shouldBe "kontakt@example.org"
        }

        test("REPLY_TO without an '@' -> Incomplete, invalid names LAPIS_SMTP_REPLY_TO") {
            val state = SmtpConfig.load(envOf(*MINIMAL_VALID_ENV, SmtpConfig.ENV_REPLY_TO to "kein-email"))
            val incomplete = state.shouldBeInstanceOf<SmtpConfigState.Incomplete>()
            incomplete.invalid shouldContain SmtpConfig.ENV_REPLY_TO
        }

        test("REPLY_TO with an embedded CRLF -> Incomplete, invalid names LAPIS_SMTP_REPLY_TO") {
            val state =
                SmtpConfig.load(
                    envOf(*MINIMAL_VALID_ENV, SmtpConfig.ENV_REPLY_TO to "a@b.de\r\nBcc: evil@x.example"),
                )
            val incomplete = state.shouldBeInstanceOf<SmtpConfigState.Incomplete>()
            incomplete.invalid shouldContain SmtpConfig.ENV_REPLY_TO
        }

        test("only REPLY_TO set -> Incomplete, not NotConfigured (ALL_ENV_KEYS includes it)") {
            val state = SmtpConfig.load(envOf(SmtpConfig.ENV_REPLY_TO to "kontakt@example.org"))
            state.shouldBeInstanceOf<SmtpConfigState.Incomplete>()
        }

        test("toString never includes username/password values, but does include host/port/fromName") {
            val configured =
                SmtpConfig.load(envOf(*MINIMAL_VALID_ENV)).shouldBeInstanceOf<SmtpConfigState.Configured>()
            val text = configured.config.toString()
            text shouldContain "username=<redacted>"
            text shouldContain "password=<redacted>"
            text.shouldNotContain("s3cr3t")
            text shouldContain "host=mxe9fb.netcup.net"
            text shouldContain "port=${SmtpConfig.DEFAULT_PORT}"
            text shouldContain "fromDisplayName=Partei der Vernunft"
        }

        test("Incomplete never carries a value, only variable names") {
            val state =
                SmtpConfig.load(
                    envOf(SmtpConfig.ENV_HOST to "suspicious-value-should-not-leak"),
                )
            val incomplete = state.shouldBeInstanceOf<SmtpConfigState.Incomplete>()
            (incomplete.missing + incomplete.invalid).none { it.contains("suspicious-value-should-not-leak") } shouldBe true
        }

        // ── Transport-security derivation ────────────────────────────────────────────────────

        test("port 465 -> IMPLICIT_TLS") {
            val state =
                SmtpConfig.load(
                    envOf(*MINIMAL_VALID_ENV, SmtpConfig.ENV_PORT to "465"),
                )
            val configured = state.shouldBeInstanceOf<SmtpConfigState.Configured>()
            configured.config.transportSecurity shouldBe SmtpTransportSecurity.IMPLICIT_TLS
        }

        test("port 587 -> STARTTLS_REQUIRED") {
            val state =
                SmtpConfig.load(
                    envOf(*MINIMAL_VALID_ENV, SmtpConfig.ENV_PORT to "587"),
                )
            val configured = state.shouldBeInstanceOf<SmtpConfigState.Configured>()
            configured.config.transportSecurity shouldBe SmtpTransportSecurity.STARTTLS_REQUIRED
        }
    })
