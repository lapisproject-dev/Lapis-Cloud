package network.lapis.cloud.server.mail

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/** Welle V1.9.81 -- every boundary of `LAPIS_MAIL_MAX_PER_HOUR` / `LAPIS_MAIL_RESERVE_PER_HOUR`. */
class MailBudgetConfigTest :
    FunSpec({
        fun load(vararg pairs: Pair<String, String>): MailBudgetConfig = MailBudgetConfig.load { pairs.toMap()[it] }

        test("unset, empty and blank mean Disabled") {
            load() shouldBe MailBudgetConfig.Disabled
            load(MailBudgetConfig.ENV_MAX to "") shouldBe MailBudgetConfig.Disabled
            load(MailBudgetConfig.ENV_MAX to "   ") shouldBe MailBudgetConfig.Disabled
        }

        test("the maximum must be an integer in 10..10000") {
            (load(MailBudgetConfig.ENV_MAX to "10") as MailBudgetConfig.Enabled).maxPerHour shouldBe 10
            (load(MailBudgetConfig.ENV_MAX to "10000") as MailBudgetConfig.Enabled).maxPerHour shouldBe 10_000
            listOf("9", "10001", "0", "-5", "abc", "12.5", "1e3", "250 mails").forEach { bad ->
                val failure = shouldThrow<IllegalStateException> { load(MailBudgetConfig.ENV_MAX to bad) }
                failure.message shouldContain MailBudgetConfig.ENV_MAX
            }
            // names the variable, never echoes the value (checked with values that cannot occur in the fixed text itself)
            listOf("10001", "-5", "abc", "12.5", "250 mails").forEach { bad ->
                shouldThrow<IllegalStateException> { load(MailBudgetConfig.ENV_MAX to bad) }.message!! shouldNotContain bad
            }
        }

        test("the default reserve is max(1, ceil(0.2 * max)): 10 -> 2, 11 -> 3, 250 -> 50") {
            MailBudgetConfig.defaultReserve(10) shouldBe 2
            MailBudgetConfig.defaultReserve(11) shouldBe 3
            MailBudgetConfig.defaultReserve(250) shouldBe 50
            MailBudgetConfig.defaultReserve(15) shouldBe 3
            MailBudgetConfig.defaultReserve(16) shouldBe 4
            (load(MailBudgetConfig.ENV_MAX to "250") as MailBudgetConfig.Enabled).let {
                it.reservePerHour shouldBe 50
                it.bulkPerHour shouldBe 200
            }
        }

        test("an explicit reserve must be in 1..max-1") {
            (
                load(
                    MailBudgetConfig.ENV_MAX to "250",
                    MailBudgetConfig.ENV_RESERVE to "1",
                ) as MailBudgetConfig.Enabled
            ).reservePerHour shouldBe
                1
            (
                load(
                    MailBudgetConfig.ENV_MAX to "250",
                    MailBudgetConfig.ENV_RESERVE to "249",
                ) as MailBudgetConfig.Enabled
            ).bulkPerHour shouldBe
                1
            listOf("0", "250", "251", "-1", "x").forEach { bad ->
                shouldThrow<IllegalStateException> {
                    load(MailBudgetConfig.ENV_MAX to "250", MailBudgetConfig.ENV_RESERVE to bad)
                }.message shouldContain MailBudgetConfig.ENV_RESERVE
            }
        }

        test("a reserve without a maximum fails the start") {
            shouldThrow<IllegalStateException> { load(MailBudgetConfig.ENV_RESERVE to "5") }.message shouldContain
                MailBudgetConfig.ENV_RESERVE
        }
    })
