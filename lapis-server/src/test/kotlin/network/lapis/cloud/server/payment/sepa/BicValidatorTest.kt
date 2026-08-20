package network.lapis.cloud.server.payment.sepa

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pure tests of [BicValidator] -- no DB access anywhere in this file. Security Round 1 (2026-08-20,
 * MINOR-5): this class did not exist before -- the regex it now owns was previously private and
 * untested in isolation inside `SepaService`.
 */
class BicValidatorTest :
    FunSpec({
        test("8-character BIC (no branch code) is valid") {
            BicValidator.isValid("COBADEFF") shouldBe true
        }

        test("11-character BIC (with branch code) is valid") {
            BicValidator.isValid("COBADEFFXXX") shouldBe true
        }

        test("wrong length is rejected") {
            BicValidator.isValid("COBADEF") shouldBe false
            BicValidator.isValid("COBADEFFX") shouldBe false
            BicValidator.isValid("COBADEFFXXXX") shouldBe false
        }

        test("lowercase is rejected -- no implicit normalization, unlike IbanValidator") {
            BicValidator.isValid("cobadeff") shouldBe false
        }

        test("blank input never throws in isValid") {
            BicValidator.isValid("") shouldBe false
        }

        test("requireValid throws IllegalArgumentException for a malformed BIC, returns the value unchanged for a valid one") {
            shouldThrow<IllegalArgumentException> { BicValidator.requireValid("NOTABIC") }
            BicValidator.requireValid("COBADEFFXXX") shouldBe "COBADEFFXXX"
        }
    })
