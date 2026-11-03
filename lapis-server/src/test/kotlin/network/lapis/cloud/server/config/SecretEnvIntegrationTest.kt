package network.lapis.cloud.server.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import network.lapis.cloud.server.bootstrap.flywayRepairSettings
import network.lapis.cloud.server.bootstrap.resetPasswordInputs
import network.lapis.cloud.server.db.dbConnectionSettings
import network.lapis.cloud.server.mail.SmtpConfig
import network.lapis.cloud.server.mail.SmtpConfigState
import network.lapis.cloud.server.payment.psp.PspConfig
import network.lapis.cloud.server.payment.psp.PspConfigState
import network.lapis.cloud.server.webhook.WebhookConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/** V1.9.266 -- the real configs see a `_FILE` value exactly like the same value in the variable. */
class SecretEnvIntegrationTest :
    FunSpec({
        val dir: Path = Files.createTempDirectory("secret-env-int")
        afterSpec { dir.toFile().deleteRecursively() }

        fun file(
            content: String,
            label: String = "f${System.nanoTime()}",
        ): String = dir.resolve(label).also { Files.write(it, content.toByteArray()) }.toString()

        fun env(vararg pairs: Pair<String, String>) = SecretEnv.resolving(mapOf(*pairs)::get)

        test("database settings read the password from a file") {
            val s =
                dbConnectionSettings(env = env("LAPIS_DB_PASSWORD_FILE" to file("dbpw\n"), "LAPIS_DB_USER" to "u"), inMemoryName = "mem")
            s.password shouldBe "dbpw"
            s.username shouldBe "u"
        }

        test("database settings without any secret keep the old defaults") {
            val s = dbConnectionSettings(env = env(), inMemoryName = "mem")
            s.password shouldBe ""
            s.username shouldBe "sa"
            s.poolSize shouldBe 10
        }

        test("encryption key from a file drives WebhookConfig") {
            val key = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
            val cfg =
                WebhookConfig.load(
                    env("LAPIS_WEBHOOKS_ENABLED" to "true", "LAPIS_SECRET_ENCRYPTION_KEY_FILE" to file("$key\n")),
                )
            cfg.enabled shouldBe true
        }

        test("an enabled WebhookConfig with a broken key file fails instead of looking unconfigured") {
            shouldThrow<SecretFileException> {
                WebhookConfig.load(
                    env("LAPIS_WEBHOOKS_ENABLED" to "true", "LAPIS_SECRET_ENCRYPTION_KEY_FILE" to file("")),
                )
            }
        }

        test("SMTP with the password from a file is Configured") {
            val state =
                SmtpConfig.load(
                    env(
                        "LAPIS_SMTP_HOST" to "smtp.example.org",
                        "LAPIS_SMTP_USERNAME" to "user",
                        "LAPIS_SMTP_PASSWORD_FILE" to file("smtp-secret\n"),
                        "LAPIS_SMTP_FROM_ADDRESS" to "noreply@example.org",
                        "LAPIS_SMTP_FROM_NAME" to "Lapis",
                    ),
                )
            state.shouldBeInstanceOf<SmtpConfigState.Configured>()
            state.config.toString().contains("smtp-secret") shouldBe false
        }

        test("Stripe: both secrets from files are Configured") {
            val state =
                PspConfig.load(
                    env(
                        "LAPIS_STRIPE_SECRET_KEY_FILE" to file("sk_test_abc\n"),
                        "LAPIS_STRIPE_WEBHOOK_SIGNING_SECRET_FILE" to file("whsec_abc\n"),
                    ),
                )
            state.shouldBeInstanceOf<PspConfigState.Configured>()
        }

        test("Stripe: only the key file is Incomplete, exactly like with variables") {
            val fromFile = PspConfig.load(env("LAPIS_STRIPE_SECRET_KEY_FILE" to file("sk_test_abc")))
            val fromVar = PspConfig.load(env("LAPIS_STRIPE_SECRET_KEY" to "sk_test_abc"))
            fromFile.shouldBeInstanceOf<PspConfigState.Incomplete>()
            fromFile shouldBe fromVar
        }

        test("Stripe: a missing secret file throws and is not NotConfigured") {
            shouldThrow<SecretFileException> {
                PspConfig.load(env("LAPIS_STRIPE_SECRET_KEY_FILE" to dir.resolve("absent").toString()))
            }
        }

        test("flyway repair settings and reset-password inputs read files") {
            val f = file("pw\n")
            val e =
                env(
                    "LAPIS_DB_URL" to "jdbc:postgresql://x/y",
                    "LAPIS_DB_USER" to "u",
                    "LAPIS_DB_PASSWORD_FILE" to f,
                    "LAPIS_BOOTSTRAP_ADMIN_EMAIL" to "a@b.c",
                    "LAPIS_BOOTSTRAP_ADMIN_PASSWORD_FILE" to f,
                )
            flywayRepairSettings(e).password shouldBe "pw"
            resetPasswordInputs(e).password shouldBe "pw"
        }

        test("a required password is still required") {
            shouldThrow<IllegalStateException> { resetPasswordInputs(env("LAPIS_BOOTSTRAP_ADMIN_EMAIL" to "a@b.c")) }
        }
    })
