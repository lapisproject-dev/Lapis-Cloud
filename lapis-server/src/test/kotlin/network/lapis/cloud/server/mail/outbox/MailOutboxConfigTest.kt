package network.lapis.cloud.server.mail.outbox

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.util.Base64

/** The decision matrix of [MailOutboxConfig.load] (SMTP x budget x encryption key). */
class MailOutboxConfigTest :
    FunSpec({
        val goodKey = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

        fun load(
            smtp: Boolean,
            budget: Boolean,
            key: String?,
        ) = MailOutboxConfig.load(smtpConfigured = smtp, budgetEnabled = budget, env = {
            if (it ==
                MailOutboxConfig.ENV_KEY
            ) {
                key
            } else {
                null
            }
        })

        test("no SMTP: no outbox, whatever the key says -- the NoOp transport never writes a row") {
            load(smtp = false, budget = false, key = null).shouldBeNull()
            load(smtp = false, budget = false, key = goodKey).shouldBeNull()
            load(smtp = false, budget = false, key = "not even base64 !!").shouldBeNull()
        }

        test("no SMTP but a budget: fail fast") {
            shouldThrow<IllegalStateException> { load(smtp = false, budget = true, key = goodKey) }.message shouldContain
                "LAPIS_MAIL_MAX_PER_HOUR"
        }

        test("SMTP, no budget, no key: today's in-memory path (null) plus a start WARN") {
            load(smtp = true, budget = false, key = null).shouldBeNull()
            load(smtp = true, budget = false, key = "   ").shouldBeNull()
        }

        test("SMTP and a budget but no key: fail fast, the message names the variables") {
            val failure = shouldThrow<IllegalStateException> { load(smtp = true, budget = true, key = null) }
            failure.message shouldContain "LAPIS_SECRET_ENCRYPTION_KEY"
            failure.message shouldContain "LAPIS_MAIL_MAX_PER_HOUR"
        }

        test("SMTP and a key that is set but invalid: fail fast, with or without a budget, never echoing the value") {
            listOf(
                "%%% not base64 %%%",
                Base64.getEncoder().encodeToString(ByteArray(16)),
                Base64.getEncoder().encodeToString(ByteArray(33)),
            ).forEach { bad ->
                listOf(true, false).forEach { budget ->
                    val failure = shouldThrow<IllegalStateException> { load(smtp = true, budget = budget, key = bad) }
                    failure.message shouldContain "LAPIS_SECRET_ENCRYPTION_KEY"
                    failure.message shouldNotContain bad
                }
            }
        }

        test("SMTP and a valid key: the outbox is wired, with or without a budget") {
            load(smtp = true, budget = false, key = goodKey).shouldNotBeNull()
            load(smtp = true, budget = true, key = goodKey).shouldNotBeNull()
        }

        test("the setup never prints key material") {
            load(smtp = true, budget = false, key = goodKey).toString() shouldNotContain goodKey
        }
    })
