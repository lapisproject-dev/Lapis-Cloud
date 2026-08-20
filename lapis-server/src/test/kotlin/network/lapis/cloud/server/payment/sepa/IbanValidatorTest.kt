package network.lapis.cloud.server.payment.sepa

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

/** Pure tests of [IbanValidator] -- no DB access anywhere in this file. */
class IbanValidatorTest :
    FunSpec({
        test("classic German test IBANs are valid") {
            IbanValidator.isValid("DE89370400440532013000") shouldBe true
            IbanValidator.isValid("DE02120300000000202051") shouldBe true
            IbanValidator.isValid("DE02100500000054540402") shouldBe true
        }

        test("normalization strips whitespace and upper-cases before validation") {
            IbanValidator.isValid("DE89 3704 0044 0532 0130 00") shouldBe true
            IbanValidator.isValid("de89370400440532013000") shouldBe true
        }

        test("valid IBANs from other SEPA countries") {
            IbanValidator.isValid("AT611904300234573201") shouldBe true
            IbanValidator.isValid("FR1420041010050500013M02606") shouldBe true
            IbanValidator.isValid("NL91ABNA0417164300") shouldBe true
            IbanValidator.isValid("CH9300762011623852957") shouldBe true
            IbanValidator.isValid("BE68539007547034") shouldBe true
        }

        test("GB IBAN is formally valid but outside the SEPA area") {
            IbanValidator.isValid("GB82WEST12345698765432") shouldBe true
            shouldThrow<IllegalArgumentException> { IbanValidator.requireValid("GB82WEST12345698765432") }
        }

        test("an altered check digit is rejected") {
            IbanValidator.isValid("DE89370400440532013001") shouldBe false
        }

        test("wrong length for the country is rejected") {
            IbanValidator.isValid("DE8937040044053201300") shouldBe false
            IbanValidator.isValid("DE893704004405320130000") shouldBe false
        }

        test("unknown country code is rejected (fail-closed)") {
            IbanValidator.isValid("XX89370400440532013000") shouldBe false
        }

        test("blank/too-short input never throws in isValid") {
            IbanValidator.isValid("") shouldBe false
            IbanValidator.isValid("   ") shouldBe false
            IbanValidator.isValid("DE") shouldBe false
        }

        test("an IBAN containing punctuation is rejected") {
            IbanValidator.isValid("DE89-3704-0044-0532-0130-00") shouldBe false
            IbanValidator.isValid("DE89/37040044053201300") shouldBe false
        }

        test("last4 extracts the final four characters of the normalized IBAN") {
            IbanValidator.last4("DE89370400440532013000") shouldBe "3000"
            IbanValidator.last4("de89 3704 0044 0532 0130 00") shouldBe "3000"
        }

        test("requireValid's exception message never contains the IBAN itself") {
            val exception = shouldThrow<IllegalArgumentException> { IbanValidator.requireValid("XX89370400440532013000") }
            exception.message.orEmpty() shouldNotContain "XX89370400440532013000"
        }

        test("a 34-character Maltese IBAN does not overflow the Mod-97 loop") {
            IbanValidator.isValid("MT84MALT011000012345MTLCAST001S") shouldBe true
        }

        test("requireValid returns the normalized IBAN for a genuinely valid SEPA IBAN") {
            IbanValidator.requireValid("DE89 3704 0044 0532 0130 00") shouldBe "DE89370400440532013000"
        }
    })
