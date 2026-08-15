package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.BalanceSheetDto
import network.lapis.cloud.shared.domain.CostCenterReportDto
import network.lapis.cloud.shared.domain.CostCenterResultDto
import network.lapis.cloud.shared.domain.FourSphereIncomeStatementDto
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.IncomeStatementDto
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.SphereResultDto
import network.lapis.cloud.shared.domain.StatementLineDto
import java.math.BigDecimal

/**
 * Pure GuV (income statement) / Bilanz (balance sheet) derivation, extracted so it is unit-testable
 * without a database -- same "pure logic extracted to a sibling file" idiom as
 * [GeneralLedgerCalculator]/[JournalEntryBalance].
 *
 * Every [AccountBalance.netBalance] passed in is assumed already signed by that account's
 * normal-balance side (see [GeneralLedgerCalculator.normalBalanceSideOf]) -- this object does not
 * re-derive the debit/credit-normal mapping, it only groups/sums/reconciles.
 *
 * **BigDecimal pitfall, deliberately guarded against** (same class of bug as
 * [JournalEntryBalance]'s KDoc): every sum and the [BalanceSheetDto.balanced] flag are compared via
 * `BigDecimal.compareTo`, never `equals`/`==`.
 */
internal object FinancialStatementCalculator {
    private val ZERO = BigDecimal.ZERO

    /**
     * One ledger account's net balance over the caller-chosen scope, already signed by
     * [type]'s normal-balance side -- the caller (`AccountingService.loadAccountBalances`) computes
     * this from summed `PostingTable` rows joined to `JournalEntryTable`/`LedgerAccountTable`.
     */
    data class AccountBalance(
        val id: String,
        val accountNumber: String,
        val name: String,
        val type: LedgerAccountType,
        val accountClass: Int,
        val netBalance: BigDecimal,
    )

    /**
     * GuV over `[from, to]`: filters [balances] to `INCOME`/`EXPENSE`, sorts each group by
     * [AccountBalance.accountNumber], sums, and computes `result = totalIncome - totalExpense`.
     * Accounts with a zero net balance in scope are omitted (they carry no line-item information).
     */
    fun incomeStatement(
        balances: List<AccountBalance>,
        from: LocalDate?,
        to: LocalDate,
    ): IncomeStatementDto {
        val incomeLines = linesOf(balances = balances, type = LedgerAccountType.INCOME)
        val expenseLines = linesOf(balances = balances, type = LedgerAccountType.EXPENSE)
        val totalIncome = incomeLines.sumBalances()
        val totalExpense = expenseLines.sumBalances()
        return IncomeStatementDto(
            from = from,
            to = to,
            incomeLines = incomeLines,
            expenseLines = expenseLines,
            totalIncome = totalIncome,
            totalExpense = totalExpense,
            result = totalIncome - totalExpense,
        )
    }

    /**
     * Bilanz as of [asOf]: groups [balances] into `ASSET` (Aktiva) versus `LIABILITY`/`EQUITY`
     * (Passiva), and derives [BalanceSheetDto.accumulatedResult] as
     * Σ(`INCOME` netBalance) − Σ(`EXPENSE` netBalance) from that *same* (cumulative-through-[asOf])
     * list -- see class/file KDoc for why this derived equity line is required for [balanced] to
     * ever be `true`. Accounts with a zero net balance in scope are omitted.
     */
    fun balanceSheet(
        balances: List<AccountBalance>,
        asOf: LocalDate,
    ): BalanceSheetDto {
        val assetLines = linesOf(balances = balances, type = LedgerAccountType.ASSET)
        val liabilityLines = linesOf(balances = balances, type = LedgerAccountType.LIABILITY)
        val equityLines = linesOf(balances = balances, type = LedgerAccountType.EQUITY)

        val totalAssets = assetLines.sumBalances()
        val totalLiabilities = liabilityLines.sumBalances()
        val bookedEquity = equityLines.sumBalances()

        val totalIncome = balances.filter { it.type == LedgerAccountType.INCOME }.sumNetBalances()
        val totalExpense = balances.filter { it.type == LedgerAccountType.EXPENSE }.sumNetBalances()
        val accumulatedResult = totalIncome - totalExpense

        val totalEquityAndLiabilities = totalLiabilities + bookedEquity + accumulatedResult

        return BalanceSheetDto(
            asOf = asOf,
            assetLines = assetLines,
            liabilityLines = liabilityLines,
            equityLines = equityLines,
            totalAssets = totalAssets,
            totalLiabilities = totalLiabilities,
            bookedEquity = bookedEquity,
            accumulatedResult = accumulatedResult,
            totalEquityAndLiabilities = totalEquityAndLiabilities,
            balanced = totalAssets.compareTo(totalEquityAndLiabilities) == 0,
        )
    }

    /**
     * One ledger account's net balance within one [sphere], carrying the same already-signed
     * [account] shape [loadAccountBalances]/[incomeStatement] use -- the caller
     * (`AccountingService.loadSphereAccountBalances`) groups summed `PostingTable` rows by
     * (sphere, ledgerAccountId) instead of by ledgerAccountId alone.
     */
    data class SphereAccountBalance(
        val sphere: GemeinnuetzigkeitSphere,
        val account: AccountBalance,
    )

    /**
     * Vier-Sphären-Ergebnisrechnung over `[from, to]`: for each of the four
     * [GemeinnuetzigkeitSphere] literals (fixed enum order), filters [balances] to that sphere and
     * derives an [SphereResultDto] with the same [linesOf]/sum discipline [incomeStatement] uses --
     * zero-filled (never omitted) if a sphere has no in-scope activity, so [FourSphereIncomeStatementDto.spheres]
     * always has exactly four entries. Overall totals are the sum across the four per-sphere
     * results, which reconciles with the plain, sphere-agnostic [incomeStatement] result for the
     * identical `[from, to]` window over the same postings.
     */
    fun fourSphereIncomeStatement(
        balances: List<SphereAccountBalance>,
        from: LocalDate?,
        to: LocalDate,
    ): FourSphereIncomeStatementDto {
        val spheres =
            GemeinnuetzigkeitSphere.entries.map { sphere ->
                val sphereBalances = balances.filter { it.sphere == sphere }.map { it.account }
                val incomeLines = linesOf(balances = sphereBalances, type = LedgerAccountType.INCOME)
                val expenseLines = linesOf(balances = sphereBalances, type = LedgerAccountType.EXPENSE)
                val totalIncome = incomeLines.sumBalances()
                val totalExpense = expenseLines.sumBalances()
                SphereResultDto(
                    sphere = sphere,
                    incomeLines = incomeLines,
                    expenseLines = expenseLines,
                    totalIncome = totalIncome,
                    totalExpense = totalExpense,
                    result = totalIncome - totalExpense,
                )
            }
        val totalIncome = spheres.fold(ZERO) { acc, sphereResult -> acc + sphereResult.totalIncome }
        val totalExpense = spheres.fold(ZERO) { acc, sphereResult -> acc + sphereResult.totalExpense }
        return FourSphereIncomeStatementDto(
            from = from,
            to = to,
            spheres = spheres,
            totalIncome = totalIncome,
            totalExpense = totalExpense,
            result = totalIncome - totalExpense,
        )
    }

    /**
     * One ledger account's net balance within one cost center (or unassigned, if [costCenterId] is
     * `null`), carrying the same already-signed [account] shape [SphereAccountBalance] uses -- the
     * caller (`AccountingService.loadCostCenterAccountBalances`) groups summed `PostingTable` rows
     * by (costCenterId, ledgerAccountId) instead of by ledgerAccountId alone. [costCenterCode]/
     * [costCenterName] are `null` iff [costCenterId] is `null`.
     */
    data class CostCenterAccountBalance(
        val costCenterId: String?,
        val costCenterCode: String?,
        val costCenterName: String?,
        val account: AccountBalance,
    )

    /**
     * Kostenstellen-/Projektbuchhaltung report (V0.3.6) over `[from, to]`: groups [balances] by
     * cost center, derives one [CostCenterResultDto] per cost center with at least one in-scope
     * non-zero-balance line (sorted by code) -- deliberately NOT zero-filled for every existing
     * cost center, unlike [fourSphereIncomeStatement]'s fixed four-entry zero-fill, because
     * [network.lapis.cloud.shared.domain.CostCenterDto] is open-ended/user-created and could
     * number in the dozens/hundreds over time. Every balance whose `costCenterId` is `null` is
     * aggregated into a separate unassigned bucket instead. Overall totals are
     * Σ(`costCenters` totals) + the unassigned bucket, which reconciles with the plain,
     * cost-center-agnostic [incomeStatement] result for the identical `[from, to]` window over the
     * same postings.
     */
    fun costCenterReport(
        balances: List<CostCenterAccountBalance>,
        from: LocalDate?,
        to: LocalDate,
    ): CostCenterReportDto {
        val assigned = balances.filter { it.costCenterId != null }
        val unassigned = balances.filter { it.costCenterId == null }

        val costCenters =
            assigned
                .groupBy { Triple(it.costCenterId!!, it.costCenterCode!!, it.costCenterName!!) }
                .map { (key, group) ->
                    val (costCenterId, code, name) = key
                    val accountBalances = group.map { it.account }
                    val incomeLines = linesOf(balances = accountBalances, type = LedgerAccountType.INCOME)
                    val expenseLines = linesOf(balances = accountBalances, type = LedgerAccountType.EXPENSE)
                    val totalIncome = incomeLines.sumBalances()
                    val totalExpense = expenseLines.sumBalances()
                    CostCenterResultDto(
                        costCenterId = costCenterId,
                        code = code,
                        name = name,
                        incomeLines = incomeLines,
                        expenseLines = expenseLines,
                        totalIncome = totalIncome,
                        totalExpense = totalExpense,
                        result = totalIncome - totalExpense,
                    )
                }.sortedBy { it.code }

        val unassignedAccountBalances = unassigned.map { it.account }
        val unassignedIncome = linesOf(balances = unassignedAccountBalances, type = LedgerAccountType.INCOME).sumBalances()
        val unassignedExpense = linesOf(balances = unassignedAccountBalances, type = LedgerAccountType.EXPENSE).sumBalances()

        val totalIncome = costCenters.fold(unassignedIncome) { acc, result -> acc + result.totalIncome }
        val totalExpense = costCenters.fold(unassignedExpense) { acc, result -> acc + result.totalExpense }

        return CostCenterReportDto(
            from = from,
            to = to,
            costCenters = costCenters,
            unassignedIncome = unassignedIncome,
            unassignedExpense = unassignedExpense,
            unassignedResult = unassignedIncome - unassignedExpense,
            totalIncome = totalIncome,
            totalExpense = totalExpense,
            result = totalIncome - totalExpense,
        )
    }

    private fun linesOf(
        balances: List<AccountBalance>,
        type: LedgerAccountType,
    ): List<StatementLineDto> =
        balances
            .filter { it.type == type && it.netBalance.compareTo(ZERO) != 0 }
            .sortedBy { it.accountNumber }
            .map {
                StatementLineDto(
                    ledgerAccountId = it.id,
                    accountNumber = it.accountNumber,
                    name = it.name,
                    type = it.type,
                    accountClass = it.accountClass,
                    balance = it.netBalance,
                )
            }

    private fun List<StatementLineDto>.sumBalances(): BigDecimal = fold(ZERO) { acc, line -> acc + line.balance }

    private fun List<AccountBalance>.sumNetBalances(): BigDecimal = fold(ZERO) { acc, balance -> acc + balance.netBalance }
}
