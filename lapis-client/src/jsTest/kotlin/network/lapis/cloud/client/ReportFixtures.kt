package network.lapis.cloud.client

import dev.kilua.rpc.types.Decimal
import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.AnnualFinancialStatementDto
import network.lapis.cloud.shared.domain.AnonymousDonationDutyDto
import network.lapis.cloud.shared.domain.BalanceSheetDto
import network.lapis.cloud.shared.domain.CostCenterReportDto
import network.lapis.cloud.shared.domain.CostCenterResultDto
import network.lapis.cloud.shared.domain.DonorCategory
import network.lapis.cloud.shared.domain.DonorDutyDto
import network.lapis.cloud.shared.domain.DonorType
import network.lapis.cloud.shared.domain.FourSphereIncomeStatementDto
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.GeneralLedgerDto
import network.lapis.cloud.shared.domain.GeneralLedgerLineDto
import network.lapis.cloud.shared.domain.IncomeStatementDto
import network.lapis.cloud.shared.domain.KassenbuchDto
import network.lapis.cloud.shared.domain.KassenbuchLineDto
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.PostingDto
import network.lapis.cloud.shared.domain.PostingSide
import network.lapis.cloud.shared.domain.ReserveMovementDto
import network.lapis.cloud.shared.domain.ReserveType
import network.lapis.cloud.shared.domain.SphereAmountDto
import network.lapis.cloud.shared.domain.SphereResultDto
import network.lapis.cloud.shared.domain.StatementLineDto
import network.lapis.cloud.shared.domain.UseOfFundsStatementDto
import network.lapis.cloud.shared.domain.UseOfFundsYearDto
import network.lapis.cloud.shared.domain.VatRate
import network.lapis.cloud.shared.domain.VatRateLineDto

/**
 * Shared fixtures of the W3 report tests (`ReportGoldenTest`, `ReportCellOracleDomTest`). Amounts are
 * built from `Double` with at most one decimal digit, so the digits the server would send are what
 * `Decimal.toString()` prints -- the tests never assume a formatting, they pin the rendered strings.
 */
internal fun d(value: Double): Decimal = value.toDecimal()

internal fun line(
    accountNumber: String,
    name: String,
    accountClass: Int,
    balance: Double,
    type: LedgerAccountType = LedgerAccountType.INCOME,
) = StatementLineDto(
    ledgerAccountId = "acc-$accountNumber",
    accountNumber = accountNumber,
    name = name,
    type = type,
    accountClass = accountClass,
    balance = d(balance),
)

/** Deliberately NOT in account-number order, with `0400` vs `400` -- the one sort the reports keep. */
internal val incomeLines =
    listOf(
        line("8400", "Erlöse 19 %", 8, 1200.5),
        line("0400", "Mitgliedsbeiträge", 4, 300.0),
        line("400", "Spenden", 4, 12.5),
    )

internal val expenseLines =
    listOf(
        line("6300", "Raummiete", 6, 250.0, LedgerAccountType.EXPENSE),
        line("6100", "Porto", 6, 40.5, LedgerAccountType.EXPENSE),
    )

internal val incomeStatement =
    IncomeStatementDto(
        from = LocalDate(2026, 1, 1),
        to = LocalDate(2026, 12, 31),
        incomeLines = incomeLines,
        expenseLines = expenseLines,
        totalIncome = d(1513.0),
        totalExpense = d(290.5),
        result = d(1222.5),
    )

internal val lossStatement =
    IncomeStatementDto(
        from = null,
        to = LocalDate(2026, 12, 31),
        incomeLines = emptyList(),
        expenseLines = expenseLines,
        totalIncome = d(0.0),
        totalExpense = d(290.5),
        result = d(-290.5),
    )

internal val balanceSheet =
    BalanceSheetDto(
        asOf = LocalDate(2026, 12, 31),
        assetLines =
            listOf(
                line("1200", "Bank", 1, 5000.0, LedgerAccountType.ASSET),
                line("1000", "Kasse", 1, 100.5, LedgerAccountType.ASSET),
            ),
        liabilityLines = listOf(line("1700", "Verbindlichkeiten LuL", 1, 300.0, LedgerAccountType.LIABILITY)),
        equityLines = emptyList(),
        totalAssets = d(5100.5),
        totalLiabilities = d(300.0),
        bookedEquity = d(0.0),
        accumulatedResult = d(4800.5),
        totalEquityAndLiabilities = d(5100.5),
        balanced = true,
    )

internal val annualStatement =
    AnnualFinancialStatementDto(
        fiscalYear = 2026,
        periodStart = LocalDate(2026, 1, 1),
        periodEnd = LocalDate(2026, 12, 31),
        incomeStatement = incomeStatement,
        balanceSheet = balanceSheet,
        periodResult = d(1222.5),
        accumulatedResult = d(-10.0),
    )

internal val fourSphereStatement =
    FourSphereIncomeStatementDto(
        from = null,
        to = LocalDate(2026, 12, 31),
        spheres =
            GemeinnuetzigkeitSphere.entries.mapIndexed { index, sphere ->
                SphereResultDto(
                    sphere = sphere,
                    incomeLines = if (index == 0) incomeLines else emptyList(),
                    expenseLines = if (index == 0) expenseLines else emptyList(),
                    totalIncome = d(100.0 * (index + 1)),
                    totalExpense = d(40.5 * (index + 1)),
                    result = if (index == 3) d(-12.5) else d(59.5 * (index + 1)),
                )
            },
        totalIncome = d(1000.0),
        totalExpense = d(405.0),
        result = d(595.0),
    )

internal val reserveMovements =
    listOf(
        ReserveMovementDto(ReserveType.PROJEKTRUECKLAGE, d(500.0), d(1500.0)),
        ReserveMovementDto(ReserveType.FREIE_RUECKLAGE, d(-20.5), d(80.0)),
    )

internal val sphereAmounts =
    listOf(
        SphereAmountDto(GemeinnuetzigkeitSphere.IDEELLER_BEREICH, d(700.0)),
        SphereAmountDto(GemeinnuetzigkeitSphere.ZWECKBETRIEB, d(0.0)),
    )

internal val useOfFunds =
    UseOfFundsStatementDto(
        fromFiscalYear = 2025,
        toFiscalYear = 2026,
        timelyUseYears = 2,
        years =
            listOf(
                UseOfFundsYearDto(2025, d(900.0), d(400.5), d(100.0), reserveMovements, sphereAmounts, sphereAmounts, d(399.5), d(0.0)),
                UseOfFundsYearDto(2026, d(800.0), d(300.0), d(-5.5), emptyList(), emptyList(), emptyList(), d(500.0), d(120.5)),
            ),
        totalFundsReceived = d(1700.0),
        totalFundsUsed = d(700.5),
        totalFundsAllocatedToReserves = d(94.5),
        closingTimelyUseObligation = d(899.5),
        closingOverdue = d(120.5),
    )

/**
 * A large amount with more decimals than a cent (audit H): the old renderers printed `"$amount €"` -- every digit, no
 * rounding -- and the tables must keep every digit too (W6a groups the integer part, nothing else). One single fiscal year (`years = listOf(oneYear)`), the shape in
 * which the zip of years and rows of the use-of-funds screen has exactly one element.
 */
internal val bigSingleYearUseOfFunds =
    UseOfFundsStatementDto(
        fromFiscalYear = 2026,
        toFiscalYear = 2026,
        timelyUseYears = 2,
        years =
            listOf(
                UseOfFundsYearDto(2026, d(1234567.891), d(300.0), d(-5.5), emptyList(), emptyList(), emptyList(), d(500.0), d(120.5)),
            ),
        totalFundsReceived = d(1234567.891),
        totalFundsUsed = d(300.0),
        totalFundsAllocatedToReserves = d(-5.5),
        closingTimelyUseObligation = d(500.0),
        closingOverdue = d(120.5),
    )

internal val bigAmountStatement =
    IncomeStatementDto(
        from = LocalDate(2026, 1, 1),
        to = LocalDate(2026, 12, 31),
        incomeLines = listOf(line("8400", "Erlöse 19 %", 8, 1234567.891)),
        expenseLines = listOf(line("6300", "Raummiete", 6, 300.0, LedgerAccountType.EXPENSE)),
        totalIncome = d(1234567.891),
        totalExpense = d(300.0),
        result = d(1234267.891),
    )

internal val journalPostings =
    listOf(
        PostingDto(
            id = "p-1",
            ledgerAccountId = "acc-1200",
            ledgerAccountNumber = "1200",
            ledgerAccountName = "Bank",
            side = PostingSide.DEBIT,
            amount = d(200.0),
            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
            vatRate = VatRate.STANDARD,
            vatAmount = d(31.9),
        ),
        PostingDto(
            id = "p-2",
            ledgerAccountId = "acc-8400",
            ledgerAccountNumber = "8400",
            ledgerAccountName = "Erlöse",
            side = PostingSide.CREDIT,
            amount = d(200.0),
            sphere = GemeinnuetzigkeitSphere.ZWECKBETRIEB,
            costCenterId = "cc-1",
            costCenterCode = "FEST",
            costCenterName = "Sommerfest",
            vatAmount = d(0.0),
        ),
    )

internal val vatLines =
    listOf(
        VatRateLineDto(VatRate.STANDARD, d(119.0), d(100.0), d(19.0), 3),
        VatRateLineDto(VatRate.REDUCED, d(107.0), d(100.0), d(7.0), 1),
    )

internal val generalLedger =
    GeneralLedgerDto(
        ledgerAccountId = "acc-1200",
        accountNumber = "1200",
        name = "Bank",
        type = LedgerAccountType.ASSET,
        openingBalance = d(1000.0),
        closingBalance = d(1150.5),
        lines =
            listOf(
                GeneralLedgerLineDto("je-1", LocalDate(2026, 3, 1), "Beitrag März", PostingSide.DEBIT, d(200.0), d(1200.0)),
                GeneralLedgerLineDto("je-2", LocalDate(2026, 3, 5), "Miete", PostingSide.CREDIT, d(49.5), d(1150.5)),
            ),
    )

internal val emptyGeneralLedger = generalLedger.copy(lines = emptyList(), closingBalance = d(1000.0))

internal val kassenbuch =
    KassenbuchDto(
        ledgerAccountId = "acc-1000",
        accountNumber = "1000",
        name = "Kasse",
        openingBalance = d(50.0),
        closingBalance = d(60.5),
        lines =
            listOf(
                KassenbuchLineDto(1, "je-1", LocalDate(2026, 4, 1), "Spende Fest", "B-17", d(30.5), d(0.0), d(80.5)),
                KassenbuchLineDto(2, "je-2", LocalDate(2026, 4, 2), "Getränke", null, d(0.0), d(20.0), d(60.5)),
            ),
    )

internal val costCenterReport =
    CostCenterReportDto(
        from = null,
        to = LocalDate(2026, 12, 31),
        costCenters =
            listOf(
                CostCenterResultDto("cc-1", "FEST", "Sommerfest", emptyList(), emptyList(), d(300.0), d(120.5), d(179.5)),
                CostCenterResultDto("cc-2", "WEB", "Website", emptyList(), emptyList(), d(0.0), d(80.0), d(-80.0)),
            ),
        unassignedIncome = d(10.0),
        unassignedExpense = d(5.5),
        unassignedResult = d(4.5),
        totalIncome = d(310.0),
        totalExpense = d(206.0),
        result = d(104.0),
    )

internal val donorDuties =
    listOf(
        DonorDutyDto(DonorType.EXTERNAL, "d-1", "Ada Lovelace", DonorCategory.GERMAN_NATURAL_PERSON, d(12000.5), true, true),
        DonorDutyDto(DonorType.MEMBER, "d-2", "Grace Hopper", DonorCategory.EU_NATURAL_PERSON, d(3500.0), false, true),
    )

internal val anonymousForwarding =
    listOf(AnonymousDonationDutyDto("je-9", LocalDate(2026, 5, 1), d(600.5)))
