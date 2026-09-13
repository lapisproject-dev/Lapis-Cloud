package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import network.lapis.cloud.shared.domain.VatRate
import java.math.BigDecimal

/**
 * Pure tests of [VatCalculator] -- no DB access. Welle V1.4.13 "USt-Voranmeldung".
 */
class VatCalculatorTest :
    FunSpec({
        test("119.00 @ 19% -> net 100.00 / vat 19.00") {
            val gross = BigDecimal("119.00")
            VatCalculator.netOf(gross = gross, rate = VatRate.STANDARD) shouldBe BigDecimal("100.00")
            VatCalculator.vatAmountOf(gross = gross, rate = VatRate.STANDARD) shouldBe BigDecimal("19.00")
        }

        test("107.00 @ 7% -> net 100.00 / vat 7.00") {
            val gross = BigDecimal("107.00")
            VatCalculator.netOf(gross = gross, rate = VatRate.REDUCED) shouldBe BigDecimal("100.00")
            VatCalculator.vatAmountOf(gross = gross, rate = VatRate.REDUCED) shouldBe BigDecimal("7.00")
        }

        test("100.00 @ 19% -> net 84.03 / vat 15.97 (the canonical rounding example)") {
            val gross = BigDecimal("100.00")
            VatCalculator.netOf(gross = gross, rate = VatRate.STANDARD) shouldBe BigDecimal("84.03")
            VatCalculator.vatAmountOf(gross = gross, rate = VatRate.STANDARD) shouldBe BigDecimal("15.97")
        }

        test("0.01 @ 19% and 0.03 @ 7% never throw and satisfy the invariant") {
            val a = BigDecimal("0.01")
            val b = BigDecimal("0.03")
            (
                VatCalculator.netOf(
                    gross = a,
                    rate = VatRate.STANDARD,
                ) + VatCalculator.vatAmountOf(gross = a, rate = VatRate.STANDARD)
            ) shouldBe
                a
            (VatCalculator.netOf(gross = b, rate = VatRate.REDUCED) + VatCalculator.vatAmountOf(gross = b, rate = VatRate.REDUCED)) shouldBe
                b
        }

        listOf(VatRate.UNCLASSIFIED, VatRate.NOT_SUBJECT, VatRate.ZERO).forEach { rate ->
            test("$rate always yields vatAmount 0.00 and net == gross") {
                val gross = BigDecimal("123.45")
                VatCalculator.vatAmountOf(gross = gross, rate = rate) shouldBe BigDecimal("0.00")
                VatCalculator.netOf(gross = gross, rate = rate) shouldBe gross
            }
        }

        test("property: netOf(gross, rate) + vatAmountOf(gross, rate) == gross for every rate and a wide range of amounts") {
            checkAll(Arb.long(1L..99_999_999L)) { cents ->
                val gross = BigDecimal(cents).movePointLeft(2)
                VatRate.entries.forEach { rate ->
                    val net = VatCalculator.netOf(gross = gross, rate = rate)
                    val vat = VatCalculator.vatAmountOf(gross = gross, rate = rate)
                    (net + vat) shouldBe gross
                    (vat.signum() >= 0) shouldBe true
                    net.scale() shouldBe 2
                    vat.scale() shouldBe 2
                }
            }
        }
    })
