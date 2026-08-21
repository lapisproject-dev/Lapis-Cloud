package network.lapis.cloud.server.mail

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/** [SmtpStartupCheck] exercised directly against constructed [SmtpConfigState] values -- no real env, no server boot. */
class SmtpStartupCheckTest :
    FunSpec({
        test("Incomplete -> throws IllegalStateException naming the missing/invalid variables") {
            val exception =
                shouldThrow<IllegalStateException> {
                    SmtpStartupCheck.verifyAndLog(
                        SmtpConfigState.Incomplete(
                            missing = listOf(SmtpConfig.ENV_USERNAME, SmtpConfig.ENV_PASSWORD),
                            invalid = listOf(SmtpConfig.ENV_PORT),
                        ),
                    )
                }
            exception.message shouldContain SmtpConfig.ENV_USERNAME
            exception.message shouldContain SmtpConfig.ENV_PASSWORD
            exception.message shouldContain SmtpConfig.ENV_PORT
        }

        // Password-leak guard on the fail-fast path (V1.2.3 Design-Review test plan item 7.2) --
        // Incomplete never carries a VALUE (see SmtpConfig.load KDoc), only variable NAMES, but this
        // pins that down directly against the thrown message text itself, not just SmtpConfig's own
        // Incomplete-construction guard.
        test("Incomplete -> the thrown message names LAPIS_SMTP_FROM_NAME but leaks no configured value") {
            val exception =
                shouldThrow<IllegalStateException> {
                    SmtpStartupCheck.verifyAndLog(
                        SmtpConfigState.Incomplete(
                            missing = listOf(SmtpConfig.ENV_FROM_NAME),
                            invalid = emptyList(),
                        ),
                    )
                }
            exception.message shouldContain SmtpConfig.ENV_FROM_NAME
            exception.message.shouldNotContain("s3cr3t")
        }

        test("NotConfigured -> never throws") {
            shouldNotThrowAny { SmtpStartupCheck.verifyAndLog(SmtpConfigState.NotConfigured) }
        }

        test("Configured -> never throws") {
            val config =
                SmtpConfig.load(
                    env = { key ->
                        mapOf(
                            SmtpConfig.ENV_HOST to "mxe9fb.netcup.net",
                            SmtpConfig.ENV_USERNAME to "no_reply@example.org",
                            SmtpConfig.ENV_PASSWORD to "s3cr3t",
                            SmtpConfig.ENV_FROM_ADDRESS to "no_reply@example.org",
                            SmtpConfig.ENV_FROM_NAME to "Partei der Vernunft",
                        )[key]
                    },
                )
            shouldNotThrowAny { SmtpStartupCheck.verifyAndLog(config) }
        }
    })
