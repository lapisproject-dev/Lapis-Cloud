package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.AmountParse
import network.lapis.cloud.shared.domain.PartgAmountError
import network.lapis.cloud.shared.domain.PartgUnit
import network.lapis.cloud.shared.domain.parseGermanAmount

class PartgAmountParserTest :
    FunSpec({
        fun ok(text: String) = (parseGermanAmount(text) as? AmountParse.Ok)?.cents

        fun err(text: String) = (parseGermanAmount(text) as? AmountParse.Error)?.reason

        test("valid German amounts") {
            ok("1234") shouldBe 123_400L
            ok("1234,5") shouldBe 123_450L
            ok("1.234,56") shouldBe 123_456L
            ok("0,01") shouldBe 1L
            ok("  12,00 € ") shouldBe 1_200L
            ok("12 €") shouldBe 1_200L
            ok("999.999.999.999,99") shouldBe 99_999_999_999_999L
            ok("007") shouldBe 700L
        }

        test("negative, text, too many decimals, overflow, wrong grouping, empty") {
            err("-5") shouldBe PartgAmountError.NEGATIVE
            err("abc") shouldBe PartgAmountError.NOT_A_NUMBER
            err("1,234.56") shouldBe PartgAmountError.NOT_A_NUMBER
            err("1e5") shouldBe PartgAmountError.NOT_A_NUMBER
            err("NaN") shouldBe PartgAmountError.NOT_A_NUMBER
            err("Infinity") shouldBe PartgAmountError.NOT_A_NUMBER
            err("+5") shouldBe PartgAmountError.NOT_A_NUMBER
            err("5,") shouldBe PartgAmountError.NOT_A_NUMBER
            err("12.34") shouldBe PartgAmountError.NOT_A_NUMBER
            err("1.23,5") shouldBe PartgAmountError.NOT_A_NUMBER
            err("1,234") shouldBe PartgAmountError.TOO_MANY_DECIMALS
            err("1.000.000.000.000") shouldBe PartgAmountError.OVERFLOW
            err("999.999.999.999,991") shouldBe PartgAmountError.TOO_MANY_DECIMALS
            err("") shouldBe PartgAmountError.EMPTY
            err("   ") shouldBe PartgAmountError.EMPTY
            err("€") shouldBe PartgAmountError.EMPTY
        }

        test("unit keys parse strictly") {
            val id = "0d2f1a4e-7c35-4b8e-9a61-3f5e8c2b1d90"
            PartgUnit.parse("FEDERAL") shouldBe PartgUnit.Federal
            PartgUnit.parse("STATE:$id") shouldBe PartgUnit.State(id)
            PartgUnit.parse("SUB:$id") shouldBe PartgUnit.Subordinate(id)
            PartgUnit.State(id).key() shouldBe "STATE:$id"
            PartgUnit.parse("STATE:${id.uppercase()}") shouldBe null
            PartgUnit.parse("STATE:") shouldBe null
            PartgUnit.parse("STATE:$id ") shouldBe null
            PartgUnit.parse("state:$id") shouldBe null
            PartgUnit.parse("OTHER:$id") shouldBe null
            PartgUnit.parse("FEDERAL ") shouldBe null
            PartgUnit.parse("SUB:" + "a".repeat(60)) shouldBe null
            PartgUnit.parse("") shouldBe null
        }
    })
