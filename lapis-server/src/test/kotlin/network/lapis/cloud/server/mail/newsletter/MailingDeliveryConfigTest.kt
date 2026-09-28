package network.lapis.cloud.server.mail.newsletter

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.MailingDeliveryMode
import kotlin.time.Duration.Companion.milliseconds

class MailingDeliveryConfigTest :
    FunSpec({
        test("default (no env var) is LOG") {
            MailingDeliveryConfig.load(env = { null }) shouldBe MailingDeliveryMode.LOG
        }

        test("'log' (any case) is LOG") {
            MailingDeliveryConfig.load(env = { "LOG" }) shouldBe MailingDeliveryMode.LOG
            MailingDeliveryConfig.load(env = { "Log" }) shouldBe MailingDeliveryMode.LOG
        }

        test("'smtp' (any case) is SMTP") {
            MailingDeliveryConfig.load(env = { "smtp" }) shouldBe MailingDeliveryMode.SMTP
            MailingDeliveryConfig.load(env = { "SMTP" }) shouldBe MailingDeliveryMode.SMTP
        }

        test("an invalid value throws IllegalStateException") {
            shouldThrow<IllegalStateException> { MailingDeliveryConfig.load(env = { "bogus" }) }
        }

        test("send delay default is 250ms") {
            MailingDeliveryConfig.loadSendDelay(env = { null }) shouldBe 250.milliseconds
        }

        test("send delay honors a configured value") {
            MailingDeliveryConfig.loadSendDelay(env = { "500" }) shouldBe 500.milliseconds
        }

        test("a negative or non-numeric send delay throws") {
            shouldThrow<IllegalStateException> { MailingDeliveryConfig.loadSendDelay(env = { "-1" }) }
            shouldThrow<IllegalStateException> { MailingDeliveryConfig.loadSendDelay(env = { "bogus" }) }
        }
    })
