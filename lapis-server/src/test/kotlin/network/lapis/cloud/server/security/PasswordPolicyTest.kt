package network.lapis.cloud.server.security

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.rpc.WeakPasswordException

/** Pure tests of [PasswordPolicy] -- no DB access. */
class PasswordPolicyTest :
    FunSpec({
        test("validate() accepts a password within the length bounds and unequal to the email") {
            PasswordPolicy.validate(newPassword = "a-perfectly-fine-password", email = "someone@example.org")
        }

        test("validate() rejects a password shorter than MIN_LENGTH") {
            val tooShort = "a".repeat(PasswordPolicy.MIN_LENGTH - 1)
            shouldThrow<WeakPasswordException> {
                PasswordPolicy.validate(newPassword = tooShort, email = "someone@example.org")
            }
        }

        test("validate() accepts a password exactly MIN_LENGTH long") {
            val exact = "a".repeat(PasswordPolicy.MIN_LENGTH)
            PasswordPolicy.validate(newPassword = exact, email = "someone@example.org")
        }

        test("validate() rejects a password longer than MAX_LENGTH") {
            val tooLong = "a".repeat(PasswordPolicy.MAX_LENGTH + 1)
            shouldThrow<WeakPasswordException> {
                PasswordPolicy.validate(newPassword = tooLong, email = "someone@example.org")
            }
        }

        test("validate() accepts a password exactly MAX_LENGTH long") {
            val exact = "a".repeat(PasswordPolicy.MAX_LENGTH)
            PasswordPolicy.validate(newPassword = exact, email = "someone@example.org")
        }

        test("validate() rejects a password identical to the email, case-insensitively") {
            shouldThrow<WeakPasswordException> {
                PasswordPolicy.validate(newPassword = "someone@example.org", email = "someone@example.org")
            }
            shouldThrow<WeakPasswordException> {
                PasswordPolicy.validate(newPassword = "SOMEONE@EXAMPLE.ORG", email = "someone@example.org")
            }
        }

        test("WeakPasswordException carries a specific, distinguishing message per violation") {
            val tooShortMessage =
                shouldThrow<WeakPasswordException> {
                    PasswordPolicy.validate(newPassword = "short", email = "someone@example.org")
                }.message
            tooShortMessage shouldBe "Password must be at least ${PasswordPolicy.MIN_LENGTH} characters long"
        }
    })
