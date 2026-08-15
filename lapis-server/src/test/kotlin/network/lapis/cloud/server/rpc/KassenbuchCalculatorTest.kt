package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.PostingSide
import java.math.BigDecimal

/**
 * Pure tests of [KassenbuchCalculator] -- no DB access anywhere in this file, same rationale as
 * [GeneralLedgerCalculatorTest]/[JournalEntryBalanceTest].
 */
class KassenbuchCalculatorTest :
    FunSpec({
        fun line(
            side: PostingSide,
            amount: String,
            voucherReference: String? = "BELEG-1",
            date: LocalDate = LocalDate(2026, 1, 1),
            description: String = "Testbuchung",
        ) = KassenbuchCalculator.KassenbuchSourceLine(
            journalEntryId = "je",
            entryDate = date,
            description = description,
            voucherReference = voucherReference,
            side = side,
            amount = BigDecimal(amount),
        )

        test("empty input returns an empty list") {
            KassenbuchCalculator.kassenbuch(lines = emptyList()) shouldBe emptyList()
        }

        test("DEBIT lines become amountIn (Einnahme), CREDIT lines become amountOut (Ausgabe); the other side is always zero") {
            val lines =
                listOf(
                    line(PostingSide.DEBIT, "100.00"),
                    line(PostingSide.CREDIT, "30.00"),
                )
            val result = KassenbuchCalculator.kassenbuch(lines = lines)

            result[0].amountIn.compareTo(BigDecimal("100.00")) shouldBe 0
            result[0].amountOut.compareTo(BigDecimal.ZERO) shouldBe 0
            result[1].amountIn.compareTo(BigDecimal.ZERO) shouldBe 0
            result[1].amountOut.compareTo(BigDecimal("30.00")) shouldBe 0
        }

        test("running balance matches GeneralLedgerCalculator with the ASSET (DEBIT) normal-balance side") {
            val lines =
                listOf(
                    line(PostingSide.DEBIT, "100.00"),
                    line(PostingSide.CREDIT, "30.00"),
                    line(PostingSide.DEBIT, "10.00"),
                )
            val result = KassenbuchCalculator.kassenbuch(lines = lines)
            result.map { it.runningBalance.toPlainString() } shouldBe listOf("100.00", "70.00", "80.00")
        }

        test("opening balance is carried into the first running balance") {
            val result =
                KassenbuchCalculator.kassenbuch(
                    lines = listOf(line(PostingSide.DEBIT, "25.00")),
                    opening = BigDecimal("100.00"),
                )
            result.single().runningBalance.compareTo(BigDecimal("125.00")) shouldBe 0
        }

        test("kassenbuchNumber is 1-based and gapless, assigned strictly in input order") {
            val lines = (1..5).map { line(PostingSide.DEBIT, "1.00") }
            val result = KassenbuchCalculator.kassenbuch(lines = lines)
            result.map { it.kassenbuchNumber } shouldBe listOf(1, 2, 3, 4, 5)
        }

        test("voucherReference passes through unchanged per line, including null") {
            val lines =
                listOf(
                    line(PostingSide.DEBIT, "5.00", voucherReference = "BELEG-42"),
                    line(PostingSide.CREDIT, "5.00", voucherReference = null),
                )
            val result = KassenbuchCalculator.kassenbuch(lines = lines)
            result[0].voucherReference shouldBe "BELEG-42"
            result[1].voucherReference shouldBe null
        }
    })
