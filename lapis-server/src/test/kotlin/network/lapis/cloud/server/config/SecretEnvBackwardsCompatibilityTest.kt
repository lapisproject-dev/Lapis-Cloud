package network.lapis.cloud.server.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.mail.SmtpConfig
import network.lapis.cloud.server.payment.psp.PaypalConfig
import network.lapis.cloud.server.payment.psp.PspConfig

/** V1.9.266 -- without any `_FILE` variable nothing changes, byte for byte. */
class SecretEnvBackwardsCompatibilityTest :
    FunSpec({
        val values = listOf(null, "", " ", "abc", " a b ", "line\n", "x\r\n")

        test("every secret name and some others resolve exactly like the raw environment") {
            val names = SecretEnv.SECRET_NAMES + listOf("LAPIS_DB_USER", "LAPIS_DB_URL", "SOMETHING_ELSE")
            for (n in names) {
                for (v in values) {
                    val raw: Map<String, String> = if (v == null) emptyMap() else mapOf(n to v)
                    SecretEnv.resolving(raw::get)(n) shouldBe raw[n]
                }
            }
        }

        test("configs load identically through the resolver") {
            val raw =
                mapOf(
                    "LAPIS_SMTP_HOST" to "h",
                    "LAPIS_SMTP_USERNAME" to "u",
                    "LAPIS_SMTP_PASSWORD" to "p",
                    "LAPIS_SMTP_FROM_ADDRESS" to "a@b.c",
                    "LAPIS_SMTP_FROM_NAME" to "n",
                    "LAPIS_STRIPE_SECRET_KEY" to "sk_test_1",
                    "LAPIS_STRIPE_WEBHOOK_SIGNING_SECRET" to "whsec_1",
                    "LAPIS_PAYPAL_CLIENT_ID" to "id",
                    "LAPIS_PAYPAL_CLIENT_SECRET" to "s",
                )
            val resolving = SecretEnv.resolving(raw::get)
            SmtpConfig.load(resolving).toString() shouldBe SmtpConfig.load(raw::get).toString()
            PspConfig.load(resolving).toString() shouldBe PspConfig.load(raw::get).toString()
            PaypalConfig.load(resolving).toString() shouldBe PaypalConfig.load(raw::get).toString()
            val empty = emptyMap<String, String>()
            SmtpConfig.load(SecretEnv.resolving(empty::get)) shouldBe SmtpConfig.load(empty::get)
            PspConfig.load(SecretEnv.resolving(empty::get)) shouldBe PspConfig.load(empty::get)
        }
    })
