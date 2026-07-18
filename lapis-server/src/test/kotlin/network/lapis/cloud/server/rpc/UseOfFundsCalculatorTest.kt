package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.ReserveType
import java.math.BigDecimal

/**
 * Pure tests of [UseOfFundsCalculator] -- no DB access anywhere in this file, same rationale as
 * [FinancialStatementCalculatorTest]/[JournalEntryBalanceTest]/[GeneralLedgerCalculatorTest].
 */
class UseOfFundsCalculatorTest :
    FunSpec({
        fun facts(
            year: Int,
            income: String = "0",
            expense: String = "0",
            incomeBySphere: Map<GemeinnuetzigkeitSphere, String> = emptyMap(),
            expenseBySphere: Map<GemeinnuetzigkeitSphere, String> = emptyMap(),
            reserveAllocationByType: Map<ReserveType, String> = emptyMap(),
            reserveClosingByType: Map<ReserveType, String> = emptyMap(),
        ) = UseOfFundsCalculator.YearFacts(
            fiscalYear = year,
            income = BigDecimal(income),
            expense = BigDecimal(expense),
            incomeBySphere = incomeBySphere.mapValues { BigDecimal(it.value) },
            expenseBySphere = expenseBySphere.mapValues { BigDecimal(it.value) },
            reserveAllocationByType = reserveAllocationByType.mapValues { BigDecimal(it.value) },
            reserveClosingByType = reserveClosingByType.mapValues { BigDecimal(it.value) },
        )

        val zero = BigDecimal.ZERO

        test("income-only year: fundsReceived and obligation grow, nothing overdue, no expense/reserves") {
            val statement = UseOfFundsCalculator.statement(listOf(facts(2026, income = "500.00")), 2026, 2026)

            val year = statement.years.single()
            year.fiscalYear shouldBe 2026
            year.fundsReceived.compareTo(BigDecimal("500.00")) shouldBe 0
            year.fundsUsed.compareTo(zero) shouldBe 0
            year.fundsAllocatedToReserves.compareTo(zero) shouldBe 0
            year.timelyUseObligationRemaining.compareTo(BigDecimal("500.00")) shouldBe 0
            year.overdueAmount.compareTo(zero) shouldBe 0
            statement.timelyUseYears shouldBe 2
            statement.totalFundsReceived.compareTo(BigDecimal("500.00")) shouldBe 0
            statement.totalFundsUsed.compareTo(zero) shouldBe 0
            statement.closingTimelyUseObligation.compareTo(BigDecimal("500.00")) shouldBe 0
            statement.closingOverdue.compareTo(zero) shouldBe 0
            year.reserveMovements.size shouldBe 4
            year.receivedBySphere.size shouldBe 4
        }

        test("income then expense next year: FIFO consumes the oldest vintage, obligation shrinks") {
            val allFacts =
                listOf(
                    facts(2026, income = "500.00"),
                    facts(2027, expense = "200.00"),
                )
            val statement = UseOfFundsCalculator.statement(allFacts, 2026, 2027)

            val year2026 = statement.years.single { it.fiscalYear == 2026 }
            val year2027 = statement.years.single { it.fiscalYear == 2027 }
            year2026.timelyUseObligationRemaining.compareTo(BigDecimal("500.00")) shouldBe 0
            year2027.fundsUsed.compareTo(BigDecimal("200.00")) shouldBe 0
            year2027.timelyUseObligationRemaining.compareTo(BigDecimal("300.00")) shouldBe 0
            year2027.overdueAmount.compareTo(zero) shouldBe 0
            statement.closingTimelyUseObligation.compareTo(BigDecimal("300.00")) shouldBe 0
        }

        test("reserve allocation removes funds from the pot; reserveMovements per-type breakdown and closingBalance are correct") {
            val allFacts =
                listOf(
                    facts(2026, income = "1000.00"),
                    facts(
                        2027,
                        reserveAllocationByType = mapOf(ReserveType.PROJEKTRUECKLAGE to "400.00"),
                        reserveClosingByType = mapOf(ReserveType.PROJEKTRUECKLAGE to "400.00"),
                    ),
                )
            val statement = UseOfFundsCalculator.statement(allFacts, 2026, 2027)

            val year2027 = statement.years.single { it.fiscalYear == 2027 }
            year2027.fundsAllocatedToReserves.compareTo(BigDecimal("400.00")) shouldBe 0
            year2027.timelyUseObligationRemaining.compareTo(BigDecimal("600.00")) shouldBe 0
            val projektruecklage = year2027.reserveMovements.single { it.reserveType == ReserveType.PROJEKTRUECKLAGE }
            projektruecklage.allocated.compareTo(BigDecimal("400.00")) shouldBe 0
            projektruecklage.closingBalance.compareTo(BigDecimal("400.00")) shouldBe 0
            // The other three types are zero-filled.
            year2027.reserveMovements.filter { it.reserveType != ReserveType.PROJEKTRUECKLAGE }.forEach {
                it.allocated.compareTo(zero) shouldBe 0
                it.closingBalance.compareTo(zero) shouldBe 0
            }
        }

        test("reserve dissolution (negative allocation) returns funds to the pot as a current-year vintage") {
            // Income and the reserve allocation are the SAME amount (300) so the entire 2020
            // vintage is fully allocated in 2021, leaving nothing else in the pot to age -- this
            // isolates the dissolution re-dating behaviour from the unrelated "an un-reserved
            // vintage ages independently" behaviour covered by the overdue-boundary test below.
            val allFacts =
                listOf(
                    facts(2020, income = "300.00"),
                    facts(
                        2021,
                        reserveAllocationByType = mapOf(ReserveType.FREIE_RUECKLAGE to "300.00"),
                        reserveClosingByType = mapOf(ReserveType.FREIE_RUECKLAGE to "300.00"),
                    ),
                    facts(
                        2030,
                        reserveAllocationByType = mapOf(ReserveType.FREIE_RUECKLAGE to "-300.00"),
                        reserveClosingByType = mapOf(ReserveType.FREIE_RUECKLAGE to "0"),
                    ),
                )
            val statement = UseOfFundsCalculator.statement(allFacts, 2020, 2030)

            val year2030 = statement.years.single { it.fiscalYear == 2030 }
            year2030.fundsAllocatedToReserves.compareTo(BigDecimal("-300.00")) shouldBe 0
            // The dissolved 300 rejoins the pot dated 2030 -- NOT overdue despite the original
            // allocation being nine years old, because dissolution re-dates it (simplification).
            year2030.timelyUseObligationRemaining.compareTo(BigDecimal("300.00")) shouldBe 0
            year2030.overdueAmount.compareTo(zero) shouldBe 0
        }

        test("overdue boundary: a vintage is not yet overdue at Y0+2, but is overdue at Y0+3") {
            val allFacts = listOf(facts(2020, income = "100.00"))
            val throughY0Plus2 = UseOfFundsCalculator.statement(allFacts, 2020, 2022)
            val throughY0Plus3 = UseOfFundsCalculator.statement(allFacts, 2020, 2023)

            throughY0Plus2.years
                .last()
                .overdueAmount
                .compareTo(zero) shouldBe 0
            throughY0Plus3.years
                .last()
                .overdueAmount
                .compareTo(BigDecimal("100.00")) shouldBe 0
        }

        test("inception-anchored slicing: a later [from,to] window still reflects the carried-forward pot from an earlier inception year") {
            val allFacts =
                listOf(
                    facts(2020, income = "1000.00"),
                    facts(2021, expense = "400.00"),
                )
            val fullStatement = UseOfFundsCalculator.statement(allFacts, 2020, 2021)
            val windowedStatement = UseOfFundsCalculator.statement(allFacts, 2021, 2021)

            windowedStatement.years.size shouldBe 1
            windowedStatement.years.single().timelyUseObligationRemaining.compareTo(
                fullStatement.years.single { it.fiscalYear == 2021 }.timelyUseObligationRemaining,
            ) shouldBe 0
            windowedStatement.years
                .single()
                .timelyUseObligationRemaining
                .compareTo(BigDecimal("600.00")) shouldBe 0
        }

        test("per-sphere received/used sum to the aggregate; all four spheres present, zero-filled") {
            val statement =
                UseOfFundsCalculator.statement(
                    listOf(
                        facts(
                            2026,
                            income = "300.00",
                            expense = "120.00",
                            incomeBySphere =
                                mapOf(
                                    GemeinnuetzigkeitSphere.IDEELLER_BEREICH to "200.00",
                                    GemeinnuetzigkeitSphere.ZWECKBETRIEB to "100.00",
                                ),
                            expenseBySphere = mapOf(GemeinnuetzigkeitSphere.IDEELLER_BEREICH to "120.00"),
                        ),
                    ),
                    2026,
                    2026,
                )
            val year = statement.years.single()
            year.receivedBySphere.size shouldBe 4
            year.usedBySphere.size shouldBe 4
            year.receivedBySphere.map { it.sphere }.toSet() shouldBe GemeinnuetzigkeitSphere.entries.toSet()
            year.receivedBySphere.fold(zero) { acc, s -> acc + s.amount }.compareTo(year.fundsReceived) shouldBe 0
            year.usedBySphere.fold(zero) { acc, s -> acc + s.amount }.compareTo(year.fundsUsed) shouldBe 0
            year.receivedBySphere
                .single { it.sphere == GemeinnuetzigkeitSphere.VERMOEGENSVERWALTUNG }
                .amount
                .compareTo(zero) shouldBe 0
        }

        test(
            "reconciliation: closingTimelyUseObligation (floored 0) equals accumulatedResult minus total reserve closing (no dissolution)",
        ) {
            val allFacts =
                listOf(
                    facts(2026, income = "1000.00", expense = "200.00"),
                    facts(
                        2027,
                        income = "500.00",
                        expense = "100.00",
                        reserveAllocationByType =
                            mapOf(ReserveType.PROJEKTRUECKLAGE to "300.00", ReserveType.FREIE_RUECKLAGE to "150.00"),
                        reserveClosingByType =
                            mapOf(ReserveType.PROJEKTRUECKLAGE to "300.00", ReserveType.FREIE_RUECKLAGE to "150.00"),
                    ),
                )
            val statement = UseOfFundsCalculator.statement(allFacts, 2026, 2027)

            val totalIncome = allFacts.fold(zero) { acc, f -> acc + f.income }
            val totalExpense = allFacts.fold(zero) { acc, f -> acc + f.expense }
            val accumulatedResult = totalIncome - totalExpense
            val totalReserveClosing =
                statement.years
                    .last()
                    .reserveMovements
                    .fold(zero) { acc, m -> acc + m.closingBalance }

            statement.closingTimelyUseObligation.compareTo(accumulatedResult - totalReserveClosing) shouldBe 0
        }

        test("pot is floored at 0 when cumulative expense exceeds cumulative income") {
            val allFacts =
                listOf(
                    facts(2026, income = "100.00"),
                    facts(2027, expense = "900.00"),
                )
            val statement = UseOfFundsCalculator.statement(allFacts, 2026, 2027)

            statement.years
                .last()
                .timelyUseObligationRemaining
                .compareTo(zero) shouldBe 0
            statement.closingTimelyUseObligation.compareTo(zero) shouldBe 0
        }

        test("empty facts / zero year: all-zero DTO, four zero-filled reserveMovements and sphere lists") {
            val statement = UseOfFundsCalculator.statement(emptyList(), 2026, 2026)

            statement.years.size shouldBe 1
            val year = statement.years.single()
            year.fundsReceived.compareTo(zero) shouldBe 0
            year.fundsUsed.compareTo(zero) shouldBe 0
            year.fundsAllocatedToReserves.compareTo(zero) shouldBe 0
            year.timelyUseObligationRemaining.compareTo(zero) shouldBe 0
            year.overdueAmount.compareTo(zero) shouldBe 0
            year.reserveMovements.size shouldBe 4
            year.reserveMovements.forEach {
                it.allocated.compareTo(zero) shouldBe 0
                it.closingBalance.compareTo(zero) shouldBe 0
            }
            year.receivedBySphere.size shouldBe 4
            year.usedBySphere.size shouldBe 4
            statement.closingTimelyUseObligation.compareTo(zero) shouldBe 0
            statement.closingOverdue.compareTo(zero) shouldBe 0
        }

        test("BigDecimal scale discipline: differently-scaled but mathematically equal amounts compare equal") {
            val allFacts = listOf(facts(2026, income = "100.0"), facts(2027, expense = "100.00"))
            val statement = UseOfFundsCalculator.statement(allFacts, 2026, 2027)

            statement.years
                .last()
                .timelyUseObligationRemaining
                .compareTo(BigDecimal.ZERO) shouldBe 0
        }
    })
