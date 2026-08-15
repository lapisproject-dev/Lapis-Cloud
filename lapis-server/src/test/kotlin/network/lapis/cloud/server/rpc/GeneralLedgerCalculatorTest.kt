package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.PostingSide
import java.math.BigDecimal

/**
 * Pure tests of [GeneralLedgerCalculator] -- no DB access anywhere in this file, same rationale as
 * [JournalEntryBalanceTest].
 */
class GeneralLedgerCalculatorTest :
    FunSpec({
        fun line(
            side: PostingSide,
            amount: String,
            date: LocalDate = LocalDate(2026, 1, 1),
        ) = GeneralLedgerCalculator.LedgerLine(
            journalEntryId = "je",
            entryDate = date,
            description = "Testbuchung",
            side = side,
            amount = BigDecimal(amount),
        )

        test("ASSET (debit-normal) account: debit increases, credit decreases the running balance") {
            val lines =
                listOf(
                    line(PostingSide.DEBIT, "100.00"),
                    line(PostingSide.CREDIT, "30.00"),
                    line(PostingSide.DEBIT, "10.00"),
                )
            val normalSide = GeneralLedgerCalculator.normalBalanceSideOf(LedgerAccountType.ASSET)
            normalSide shouldBe PostingSide.DEBIT

            val result = GeneralLedgerCalculator.runningBalances(lines = lines, normalBalanceSide = normalSide)
            result.map { it.runningBalance.toPlainString() } shouldBe listOf("100.00", "70.00", "80.00")
        }

        test("INCOME (credit-normal) account: credit increases, debit decreases the running balance") {
            val lines =
                listOf(
                    line(PostingSide.CREDIT, "50.00"),
                    line(PostingSide.DEBIT, "20.00"),
                    line(PostingSide.CREDIT, "5.00"),
                )
            val normalSide = GeneralLedgerCalculator.normalBalanceSideOf(LedgerAccountType.INCOME)
            normalSide shouldBe PostingSide.CREDIT

            val result = GeneralLedgerCalculator.runningBalances(lines = lines, normalBalanceSide = normalSide)
            result.map { it.runningBalance.toPlainString() } shouldBe listOf("50.00", "30.00", "35.00")
        }

        test("opening balance is carried into the first running balance") {
            val lines = listOf(line(PostingSide.DEBIT, "25.00"))
            val result =
                GeneralLedgerCalculator.runningBalances(
                    lines = lines,
                    normalBalanceSide = PostingSide.DEBIT,
                    opening = BigDecimal("100.00"),
                )
            result.single().runningBalance.compareTo(BigDecimal("125.00")) shouldBe 0
        }

        test("LIABILITY/EQUITY are credit-normal, EXPENSE is debit-normal") {
            GeneralLedgerCalculator.normalBalanceSideOf(LedgerAccountType.LIABILITY) shouldBe PostingSide.CREDIT
            GeneralLedgerCalculator.normalBalanceSideOf(LedgerAccountType.EQUITY) shouldBe PostingSide.CREDIT
            GeneralLedgerCalculator.normalBalanceSideOf(LedgerAccountType.EXPENSE) shouldBe PostingSide.DEBIT
        }

        test("empty line list returns an empty result") {
            GeneralLedgerCalculator.runningBalances(lines = emptyList(), normalBalanceSide = PostingSide.DEBIT) shouldBe emptyList()
        }
    })
