package network.lapis.cloud.server.mail.newsletter

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.shared.domain.MailingDeliveryMode
import java.util.Base64

class MailingTrackingConfigTest :
    FunSpec({
        fun env(value: String?) = { key: String -> if (key == MailingTrackingConfig.ENV_KEY) value else null }

        fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

        test("smtp without a key fails fast") {
            shouldThrow<IllegalStateException> { MailingTrackingConfig.loadKey(mode = MailingDeliveryMode.SMTP, env = env(null)) }
            shouldThrow<IllegalStateException> { MailingTrackingConfig.loadKey(mode = MailingDeliveryMode.SMTP, env = env("   ")) }
        }

        test("a short, all-zero, all-ones or non-base64 key is rejected, and the value never appears in the message") {
            val secretish = b64(ByteArray(16) { 5 })
            val ex =
                shouldThrow<IllegalStateException> { MailingTrackingConfig.loadKey(mode = MailingDeliveryMode.SMTP, env = env(secretish)) }
            ex.message!! shouldNotContain secretish
            shouldThrow<IllegalStateException> {
                MailingTrackingConfig.loadKey(
                    mode = MailingDeliveryMode.SMTP,
                    env = env(b64(ByteArray(32))),
                )
            }
            shouldThrow<IllegalStateException> {
                MailingTrackingConfig.loadKey(mode = MailingDeliveryMode.SMTP, env = env(b64(ByteArray(32) { 0xFF.toByte() })))
            }
            val garbage = "!!!not base64!!!"
            val ex2 =
                shouldThrow<IllegalStateException> { MailingTrackingConfig.loadKey(mode = MailingDeliveryMode.SMTP, env = env(garbage)) }
            ex2.message!! shouldNotContain garbage
        }

        test("a valid key is accepted in smtp mode (standard and url-safe base64)") {
            val raw = ByteArray(32) { (it * 3 + 1).toByte() }
            MailingTrackingConfig.loadKey(mode = MailingDeliveryMode.SMTP, env = env(b64(raw))).toList() shouldBe raw.toList()
            MailingTrackingConfig
                .loadKey(mode = MailingDeliveryMode.SMTP, env = env(Base64.getUrlEncoder().encodeToString(raw)))
                .toList() shouldBe raw.toList()
        }

        test("log mode without a key yields an ephemeral 32-byte key") {
            val a = MailingTrackingConfig.loadKey(mode = MailingDeliveryMode.LOG, env = env(null))
            val b = MailingTrackingConfig.loadKey(mode = MailingDeliveryMode.LOG, env = env(null))
            a.size shouldBe 32
            (a.toList() == b.toList()) shouldBe false
        }

        test("log mode with an invalid key still fails (a configured key must be valid)") {
            shouldThrow<IllegalStateException> { MailingTrackingConfig.loadKey(mode = MailingDeliveryMode.LOG, env = env("abc")) }
        }
    })
