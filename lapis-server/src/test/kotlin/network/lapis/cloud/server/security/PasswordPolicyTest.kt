package network.lapis.cloud.server.security

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.rpc.WeakPasswordException

/** Pure tests of [PasswordPolicy] -- no DB access. */
class PasswordPolicyTest :
    FunSpec({
        test("validate() accepts a password within the length bounds and unequal to the email") {
            PasswordPolicy.validate("a-perfectly-fine-password", "someone@example.org")
        }

        test("validate() rejects a password shorter than MIN_LENGTH") {
            val tooShort = "a".repeat(PasswordPolicy.MIN_LENGTH - 1)
            shouldThrow<WeakPasswordException> {
                PasswordPolicy.validate(tooShort, "someone@example.org")
            }
        }

        test("validate() accepts a password exactly MIN_LENGTH long") {
            val exact = "a".repeat(PasswordPolicy.MIN_LENGTH)
            PasswordPolicy.validate(exact, "someone@example.org")
        }

        test("validate() rejects a password longer than MAX_LENGTH") {
            val tooLong = "a".repeat(PasswordPolicy.MAX_LENGTH + 1)
            shouldThrow<WeakPasswordException> {
                PasswordPolicy.validate(tooLong, "someone@example.org")
            }
        }

        test("validate() accepts a password exactly MAX_LENGTH long") {
            val exact = "a".repeat(PasswordPolicy.MAX_LENGTH)
            PasswordPolicy.validate(exact, "someone@example.org")
        }

        test("validate() rejects a password identical to the email, case-insensitively") {
            shouldThrow<WeakPasswordException> {
                PasswordPolicy.validate("someone@example.org", "someone@example.org")
            }
            shouldThrow<WeakPasswordException> {
                PasswordPolicy.validate("SOMEONE@EXAMPLE.ORG", "someone@example.org")
            }
        }

        test("WeakPasswordException carries a specific, distinguishing message per violation") {
            val tooShortMessage =
                shouldThrow<WeakPasswordException> {
                    PasswordPolicy.validate("short", "someone@example.org")
                }.message
            tooShortMessage shouldBe "Password must be at least ${PasswordPolicy.MIN_LENGTH} characters long"
        }
    })
