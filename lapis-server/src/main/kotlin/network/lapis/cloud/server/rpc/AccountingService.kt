package network.lapis.cloud.server.rpc

import dev.kilua.rpc.AbstractServiceException
import dev.kilua.rpc.annotations.RpcServiceException
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AnnualFinancialStatementDto
import network.lapis.cloud.shared.domain.BalanceSheetDto
import network.lapis.cloud.shared.domain.FourSphereIncomeStatementDto
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.GeneralLedgerDto
import network.lapis.cloud.shared.domain.IncomeStatementDto
import network.lapis.cloud.shared.domain.JournalEntryDto
import network.lapis.cloud.shared.domain.JournalEntryInput
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.LedgerAccountDto
import network.lapis.cloud.shared.domain.LedgerAccountInput
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.PostingDto
import network.lapis.cloud.shared.domain.PostingInput
import network.lapis.cloud.shared.domain.ReserveType
import network.lapis.cloud.shared.domain.UseOfFundsStatementDto
import network.lapis.cloud.shared.rpc.IAccountingService
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.time.Clock
import kotlin.uuid.Uuid

private val TREASURY_ROLES = arrayOf(AccountRole.TREASURER, AccountRole.ADMIN)
private val ACCOUNTING_READ_ROLES = arrayOf(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)

/** Smallest/largest calendar year [getAnnualFinancialStatement] accepts as a `fiscalYear`. */
private val FISCAL_YEAR_RANGE = 1000..9999

@RpcServiceException
class BadRequestException(
    override val message: String,
) : AbstractServiceException()

/**
 * SKR42 chart of accounts + double-entry bookkeeping (V0.3.1, chart swapped from SKR49 in
 * V0.3.1.1). Implements [IAccountingService] --
 * see that interface's KDoc for the full lifecycle. Bookkeeping is treasury-only, not
 * member-public: every method requires at least [ACCOUNTING_READ_ROLES] (TREASURER/BOARD/ADMIN),
 * every write requires [TREASURY_ROLES] (TREASURER/ADMIN) -- same "TREASURY_ROLES + requireRole"
 * idiom [ContributionService] establishes.
 *
 * The balance invariant (Σdebit = Σcredit) is validated via the pure [JournalEntryBalance] helper
 * and enforced inside the same `transaction {}` that writes the [network.lapis.cloud.server.db
 * .generated.JournalEntryTable]/[network.lapis.cloud.server.db.generated.PostingTable] rows -- an
 * unbalanced attempt throws [ConflictException] and the whole transaction rolls back, so a
 * partially-written unbalanced entry can never be observed.
 *
 * DSGVO: [network.lapis.cloud.server.dsgvo.AccountingPersonalData] owns
 * [JournalEntryTable.createdBy] -- see that contributor's KDoc for why GoBD/§257 HGB retention
 * overrides DSGVO erasure here (accounting records are never anonymized/deleted).
 */
class AccountingService(
    private val call: ApplicationCall,
) : IAccountingService {
    override suspend fun createLedgerAccount(input: LedgerAccountInput): LedgerAccountDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*TREASURY_ROLES)
        requireReserveTypeOnlyOnEquity(input.type, input.reserveType)
        return transaction {
            val duplicate =
                LedgerAccountTable
                    .selectAll()
                    .where { LedgerAccountTable.accountNumber eq input.accountNumber }
                    .count() > 0
            if (duplicate) {
                throw ConflictException("LedgerAccount with accountNumber ${input.accountNumber} already exists")
            }
            val id = Uuid.random()
            try {
                LedgerAccountTable.insert {
                    it[LedgerAccountTable.id] = id
                    it[accountNumber] = input.accountNumber
                    it[name] = input.name
                    it[accountClass] = input.accountClass
                    it[type] = input.type
                    it[active] = input.active
                    it[reserveType] = input.reserveType
                }
            } catch (e: ExposedSQLException) {
                // Application-level pre-check above is racy under concurrency on its own -- the
                // DB-level UNIQUE (uq_ledger_account_number) is the real backstop, same
                // "pre-check + ExposedSQLException backstop" idiom as ElectionService's receipt
                // code / duplicate-vote handling.
                throw ConflictException("LedgerAccount with accountNumber ${input.accountNumber} already exists")
            }
            loadLedgerAccount(id)
        }
    }

    override suspend fun deactivateLedgerAccount(id: String): LedgerAccountDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*TREASURY_ROLES)
        val accountId = id.toAccountingUuid("LedgerAccount")
        return transaction {
            val updated =
                LedgerAccountTable.update({ LedgerAccountTable.id eq accountId }) {
                    it[active] = false
                }
            if (updated == 0) throw NotFoundException("LedgerAccount $id not found")
            loadLedgerAccount(accountId)
        }
    }

    override suspend fun listLedgerAccounts(activeOnly: Boolean): List<LedgerAccountDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*ACCOUNTING_READ_ROLES)
        return transaction {
            val baseQuery = LedgerAccountTable.selectAll()
            val query = if (activeOnly) baseQuery.where { LedgerAccountTable.active eq true } else baseQuery
            query.orderBy(LedgerAccountTable.accountNumber, SortOrder.ASC).map { it.toLedgerAccountDto() }
        }
    }

    override suspend fun saveDraftEntry(input: JournalEntryInput): JournalEntryDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*TREASURY_ROLES)
        return transaction {
            insertJournalEntry(input, current.memberId, JournalEntryStatus.DRAFT, postedAt = null)
        }
    }

    override suspend fun postJournalEntry(input: JournalEntryInput): JournalEntryDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*TREASURY_ROLES)
        return transaction {
            requireBalanced(input.postings)
            requireActiveLedgerAccounts(input.postings.map { it.ledgerAccountId.toAccountingUuid("LedgerAccount") })
            insertJournalEntry(input, current.memberId, JournalEntryStatus.POSTED, postedAt = nowLocalDateTime())
        }
    }

    override suspend fun postDraftEntry(id: String): JournalEntryDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*TREASURY_ROLES)
        val entryId = id.toAccountingUuid("JournalEntry")
        return transaction {
            val entryRow = requireJournalEntryRow(entryId)
            if (entryRow[JournalEntryTable.status] != JournalEntryStatus.DRAFT) {
                throw ConflictException("JournalEntry $id is ${entryRow[JournalEntryTable.status]}, expected DRAFT")
            }
            val postings =
                PostingTable
                    .selectAll()
                    .where { PostingTable.journalEntryId eq entryId }
                    .map { row ->
                        PostingInput(
                            ledgerAccountId = row[PostingTable.ledgerAccountId].toString(),
                            side = row[PostingTable.side],
                            amount = row[PostingTable.amount],
                            // JournalEntryBalance ignores sphere, but PostingInput now requires it
                            // -- see class KDoc for the no-silent-default rationale.
                            sphere = row[PostingTable.sphere],
                        )
                    }
            requireBalanced(postings)
            requireActiveLedgerAccounts(postings.map { it.ledgerAccountId.toAccountingUuid("LedgerAccount") })
            JournalEntryTable.update({ JournalEntryTable.id eq entryId }) {
                it[status] = JournalEntryStatus.POSTED
                it[postedAt] = nowLocalDateTime()
            }
            loadJournalEntry(entryId)
        }
    }

    override suspend fun getJournalEntry(id: String): JournalEntryDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*ACCOUNTING_READ_ROLES)
        val entryId = id.toAccountingUuid("JournalEntry")
        return transaction { loadJournalEntry(entryId) }
    }

    override suspend fun listJournal(
        from: LocalDate?,
        to: LocalDate?,
        status: JournalEntryStatus?,
    ): List<JournalEntryDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*ACCOUNTING_READ_ROLES)
        return transaction {
            val conditions = mutableListOf<Op<Boolean>>()
            if (from != null) conditions += (JournalEntryTable.entryDate greaterEq from)
            if (to != null) conditions += (JournalEntryTable.entryDate lessEq to)
            if (status != null) conditions += (JournalEntryTable.status eq status)
            val baseQuery = JournalEntryTable.selectAll()
            val query = if (conditions.isEmpty()) baseQuery else baseQuery.where { conditions.reduce { a, b -> a and b } }
            query
                .orderBy(JournalEntryTable.entryDate to SortOrder.ASC, JournalEntryTable.createdAt to SortOrder.ASC)
                .map { it[JournalEntryTable.id] }
                .map { loadJournalEntry(it) }
        }
    }

    override suspend fun getGeneralLedgerAccount(
        ledgerAccountId: String,
        from: LocalDate?,
        to: LocalDate?,
    ): GeneralLedgerDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*ACCOUNTING_READ_ROLES)
        val accountId = ledgerAccountId.toAccountingUuid("LedgerAccount")
        return transaction {
            val accountRow =
                LedgerAccountTable.selectAll().where { LedgerAccountTable.id eq accountId }.singleOrNull()
                    ?: throw NotFoundException("LedgerAccount $ledgerAccountId not found")

            val conditions =
                mutableListOf<Op<Boolean>>(
                    PostingTable.ledgerAccountId eq accountId,
                    // Only POSTED entries move the ledger -- a DRAFT entry's postings are
                    // provisional and must never contribute to a running balance.
                    JournalEntryTable.status eq JournalEntryStatus.POSTED,
                )
            if (from != null) conditions += (JournalEntryTable.entryDate greaterEq from)
            if (to != null) conditions += (JournalEntryTable.entryDate lessEq to)

            val rows =
                (PostingTable innerJoin JournalEntryTable)
                    .selectAll()
                    .where { conditions.reduce { a, b -> a and b } }
                    .orderBy(JournalEntryTable.entryDate to SortOrder.ASC, JournalEntryTable.createdAt to SortOrder.ASC)
                    .toList()

            val lines =
                rows.map { row ->
                    GeneralLedgerCalculator.LedgerLine(
                        journalEntryId = row[JournalEntryTable.id].toString(),
                        entryDate = row[JournalEntryTable.entryDate],
                        description = row[JournalEntryTable.description],
                        side = row[PostingTable.side],
                        amount = row[PostingTable.amount],
                    )
                }
            val type = accountRow[LedgerAccountTable.type]
            val normalSide = GeneralLedgerCalculator.normalBalanceSideOf(type)
            val ledgerLines = GeneralLedgerCalculator.runningBalances(lines, normalSide)

            GeneralLedgerDto(
                ledgerAccountId = accountId.toString(),
                accountNumber = accountRow[LedgerAccountTable.accountNumber],
                name = accountRow[LedgerAccountTable.name],
                type = type,
                openingBalance = BigDecimal.ZERO,
                closingBalance = ledgerLines.lastOrNull()?.runningBalance ?: BigDecimal.ZERO,
                lines = ledgerLines,
            )
        }
    }

    override suspend fun getIncomeStatement(
        from: LocalDate?,
        to: LocalDate,
    ): IncomeStatementDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*ACCOUNTING_READ_ROLES)
        return transaction {
            FinancialStatementCalculator.incomeStatement(loadAccountBalances(from, to), from, to)
        }
    }

    override suspend fun getBalanceSheet(asOf: LocalDate): BalanceSheetDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*ACCOUNTING_READ_ROLES)
        return transaction {
            // Cumulative from inception (from = null) -- see BalanceSheetDto KDoc for why the
            // Bilanz is never windowed to a fiscal-year `from`.
            FinancialStatementCalculator.balanceSheet(loadAccountBalances(from = null, to = asOf), asOf)
        }
    }

    override suspend fun getFourSphereIncomeStatement(
        from: LocalDate?,
        to: LocalDate,
    ): FourSphereIncomeStatementDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*ACCOUNTING_READ_ROLES)
        return transaction {
            FinancialStatementCalculator.fourSphereIncomeStatement(loadSphereAccountBalances(from, to), from, to)
        }
    }

    override suspend fun getUseOfFundsStatement(
        fromFiscalYear: Int,
        toFiscalYear: Int,
    ): UseOfFundsStatementDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*ACCOUNTING_READ_ROLES)
        if (fromFiscalYear !in FISCAL_YEAR_RANGE || toFiscalYear !in FISCAL_YEAR_RANGE) {
            throw BadRequestException(
                "fromFiscalYear/toFiscalYear must be 4-digit calendar years in $FISCAL_YEAR_RANGE, got $fromFiscalYear/$toFiscalYear",
            )
        }
        if (fromFiscalYear > toFiscalYear) {
            throw BadRequestException("fromFiscalYear ($fromFiscalYear) must not be after toFiscalYear ($toFiscalYear)")
        }
        return transaction {
            UseOfFundsCalculator.statement(loadYearFacts(throughYear = toFiscalYear), fromFiscalYear, toFiscalYear)
        }
    }

    override suspend fun getAnnualFinancialStatement(fiscalYear: Int): AnnualFinancialStatementDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*ACCOUNTING_READ_ROLES)
        if (fiscalYear !in FISCAL_YEAR_RANGE) {
            throw BadRequestException("fiscalYear must be a 4-digit calendar year in $FISCAL_YEAR_RANGE, got $fiscalYear")
        }
        val periodStart = LocalDate(fiscalYear, 1, 1)
        val periodEnd = LocalDate(fiscalYear, 12, 31)
        return transaction {
            val incomeStatement =
                FinancialStatementCalculator.incomeStatement(loadAccountBalances(periodStart, periodEnd), periodStart, periodEnd)
            val balanceSheet =
                FinancialStatementCalculator.balanceSheet(loadAccountBalances(from = null, to = periodEnd), periodEnd)
            AnnualFinancialStatementDto(
                fiscalYear = fiscalYear,
                periodStart = periodStart,
                periodEnd = periodEnd,
                incomeStatement = incomeStatement,
                balanceSheet = balanceSheet,
                periodResult = incomeStatement.result,
                accumulatedResult = balanceSheet.accumulatedResult,
            )
        }
    }

    /**
     * Loads one net balance per [LedgerAccountTable] with at least one in-scope posting, signed by
     * that account's normal-balance side (see [GeneralLedgerCalculator.normalBalanceSideOf]).
     * Same POSTED-only + optional date-range filter as [getGeneralLedgerAccount] -- a
     * [JournalEntryStatus.DRAFT] entry's postings are provisional and must never contribute to a
     * statement. Accounts with no in-scope postings are simply absent from the result (see
     * [FinancialStatementCalculator] KDoc).
     */
    private fun loadAccountBalances(
        from: LocalDate?,
        to: LocalDate?,
    ): List<FinancialStatementCalculator.AccountBalance> {
        val conditions =
            mutableListOf<Op<Boolean>>(
                JournalEntryTable.status eq JournalEntryStatus.POSTED,
            )
        if (from != null) conditions += (JournalEntryTable.entryDate greaterEq from)
        if (to != null) conditions += (JournalEntryTable.entryDate lessEq to)

        val rows =
            (PostingTable innerJoin JournalEntryTable innerJoin LedgerAccountTable)
                .selectAll()
                .where { conditions.reduce { a, b -> a and b } }
                .toList()

        return rows
            .groupBy { it[PostingTable.ledgerAccountId] }
            .map { (accountId, group) ->
                val type = group.first()[LedgerAccountTable.type]
                val normalSide = GeneralLedgerCalculator.normalBalanceSideOf(type)
                val netBalance =
                    group.fold(BigDecimal.ZERO) { acc, row ->
                        val amount = row[PostingTable.amount]
                        val signed = if (row[PostingTable.side] == normalSide) amount else amount.negate()
                        acc + signed
                    }
                FinancialStatementCalculator.AccountBalance(
                    id = accountId.toString(),
                    accountNumber = group.first()[LedgerAccountTable.accountNumber],
                    name = group.first()[LedgerAccountTable.name],
                    type = type,
                    accountClass = group.first()[LedgerAccountTable.accountClass],
                    netBalance = netBalance,
                )
            }
    }

    /**
     * Same POSTED-only + optional date-range join as [loadAccountBalances], but grouped by
     * (sphere, ledgerAccountId) instead of ledgerAccountId alone -- feeds
     * [FinancialStatementCalculator.fourSphereIncomeStatement]. No sphere validation is needed
     * here: [PostingTable.sphere] is NOT NULL at the DB layer, so non-null is structural, not
     * something this loader must re-check.
     */
    private fun loadSphereAccountBalances(
        from: LocalDate?,
        to: LocalDate?,
    ): List<FinancialStatementCalculator.SphereAccountBalance> {
        val conditions =
            mutableListOf<Op<Boolean>>(
                JournalEntryTable.status eq JournalEntryStatus.POSTED,
            )
        if (from != null) conditions += (JournalEntryTable.entryDate greaterEq from)
        if (to != null) conditions += (JournalEntryTable.entryDate lessEq to)

        val rows =
            (PostingTable innerJoin JournalEntryTable innerJoin LedgerAccountTable)
                .selectAll()
                .where { conditions.reduce { a, b -> a and b } }
                .toList()

        return rows
            .groupBy { it[PostingTable.sphere] to it[PostingTable.ledgerAccountId] }
            .map { (key, group) ->
                val (sphere, accountId) = key
                val type = group.first()[LedgerAccountTable.type]
                val normalSide = GeneralLedgerCalculator.normalBalanceSideOf(type)
                val netBalance =
                    group.fold(BigDecimal.ZERO) { acc, row ->
                        val amount = row[PostingTable.amount]
                        val signed = if (row[PostingTable.side] == normalSide) amount else amount.negate()
                        acc + signed
                    }
                FinancialStatementCalculator.SphereAccountBalance(
                    sphere = sphere,
                    account =
                        FinancialStatementCalculator.AccountBalance(
                            id = accountId.toString(),
                            accountNumber = group.first()[LedgerAccountTable.accountNumber],
                            name = group.first()[LedgerAccountTable.name],
                            type = type,
                            accountClass = group.first()[LedgerAccountTable.accountClass],
                            netBalance = netBalance,
                        ),
                )
            }
    }

    /**
     * Loads one [UseOfFundsCalculator.YearFacts] per calendar year with any `POSTED` activity
     * through [throughYear] (inclusive) -- feeds [UseOfFundsCalculator.statement]. Only `POSTED`
     * postings contribute (same "DRAFT is provisional" rule as every other loader). `INCOME`/
     * `EXPENSE` postings are summed already signed by their normal-balance side (see
     * [GeneralLedgerCalculator.normalBalanceSideOf]) into that year's flow + per-sphere
     * disaggregation; `EQUITY` postings on a [LedgerAccountTable.reserveType]-tagged account are
     * summed the same way into that year's net reserve allocation (positive = net Zuführung,
     * negative = net Auflösung/dissolution) -- see [UseOfFundsCalculator] KDoc. `EQUITY` postings on
     * a non-reserve account (`reserveType == null`, e.g. a plain Vereinsvermögen/Ergebnisvortrag
     * account) do not affect the statement at all. [UseOfFundsCalculator.YearFacts.reserveClosingByType]
     * is the *cumulative* reserve balance per type through the end of that year -- carried forward
     * incrementally here, one calendar year at a time in ascending order, so it is correct even for
     * a year with zero reserve activity of its own. Years with zero activity at all are simply
     * absent from the returned list -- [UseOfFundsCalculator.statement] fills such gap years in
     * itself (see that object's KDoc).
     */
    private fun loadYearFacts(throughYear: Int): List<UseOfFundsCalculator.YearFacts> {
        val periodEnd = LocalDate(throughYear, 12, 31)
        val rows =
            (PostingTable innerJoin JournalEntryTable innerJoin LedgerAccountTable)
                .selectAll()
                .where { (JournalEntryTable.status eq JournalEntryStatus.POSTED) and (JournalEntryTable.entryDate lessEq periodEnd) }
                .toList()

        val rowsByYear = rows.groupBy { it[JournalEntryTable.entryDate].year }
        val cumulativeReserveClosing = mutableMapOf<ReserveType, BigDecimal>()

        return rowsByYear.keys.sorted().map { year ->
            var income = BigDecimal.ZERO
            var expense = BigDecimal.ZERO
            val incomeBySphere = mutableMapOf<GemeinnuetzigkeitSphere, BigDecimal>()
            val expenseBySphere = mutableMapOf<GemeinnuetzigkeitSphere, BigDecimal>()
            val reserveAllocationByType = mutableMapOf<ReserveType, BigDecimal>()

            rowsByYear.getValue(year).forEach { row ->
                val type = row[LedgerAccountTable.type]
                val amount = row[PostingTable.amount]
                val normalSide = GeneralLedgerCalculator.normalBalanceSideOf(type)
                val signed = if (row[PostingTable.side] == normalSide) amount else amount.negate()

                when (type) {
                    LedgerAccountType.INCOME -> {
                        income += signed
                        val sphere = row[PostingTable.sphere]
                        incomeBySphere[sphere] = (incomeBySphere[sphere] ?: BigDecimal.ZERO) + signed
                    }
                    LedgerAccountType.EXPENSE -> {
                        expense += signed
                        val sphere = row[PostingTable.sphere]
                        expenseBySphere[sphere] = (expenseBySphere[sphere] ?: BigDecimal.ZERO) + signed
                    }
                    LedgerAccountType.EQUITY -> {
                        val reserveType = row[LedgerAccountTable.reserveType]
                        if (reserveType != null) {
                            reserveAllocationByType[reserveType] = (reserveAllocationByType[reserveType] ?: BigDecimal.ZERO) + signed
                        }
                    }
                    LedgerAccountType.ASSET, LedgerAccountType.LIABILITY -> Unit
                }
            }

            reserveAllocationByType.forEach { (type, delta) ->
                cumulativeReserveClosing[type] = (cumulativeReserveClosing[type] ?: BigDecimal.ZERO) + delta
            }

            UseOfFundsCalculator.YearFacts(
                fiscalYear = year,
                income = income,
                expense = expense,
                incomeBySphere = incomeBySphere,
                expenseBySphere = expenseBySphere,
                reserveAllocationByType = reserveAllocationByType,
                reserveClosingByType = cumulativeReserveClosing.toMap(),
            )
        }
    }

    /** Inserts a new [JournalEntryTable] row plus its [PostingTable] rows in the caller's transaction. */
    private fun insertJournalEntry(
        input: JournalEntryInput,
        createdBy: Uuid,
        status: JournalEntryStatus,
        postedAt: LocalDateTime?,
    ): JournalEntryDto {
        val id = Uuid.random()
        JournalEntryTable.insert {
            it[JournalEntryTable.id] = id
            it[entryDate] = input.entryDate
            it[description] = input.description
            it[voucherReference] = input.voucherReference
            it[JournalEntryTable.createdBy] = createdBy
            it[JournalEntryTable.status] = status
            it[JournalEntryTable.postedAt] = postedAt
            it[createdAt] = nowLocalDateTime()
        }
        input.postings.forEach { posting ->
            PostingTable.insert {
                it[PostingTable.id] = Uuid.random()
                it[journalEntryId] = id
                it[ledgerAccountId] = posting.ledgerAccountId.toAccountingUuid("LedgerAccount")
                it[side] = posting.side
                it[amount] = posting.amount
                it[sphere] = posting.sphere
            }
        }
        return loadJournalEntry(id)
    }

    /** Throws [ConflictException] naming the imbalance reason if [postings] does not balance -- see [JournalEntryBalance]. */
    private fun requireBalanced(postings: List<PostingInput>) {
        val result = JournalEntryBalance.validateBalanced(postings)
        if (!result.balanced) throw ConflictException(result.reason ?: "Journal entry not balanced")
    }

    /**
     * §62 AO [ReserveType] is only meaningful on an `EQUITY`-typed [LedgerAccountTable] row (see
     * [ReserveType] KDoc: reserves are modelled as ordinary equity accounts) -- this is a
     * cross-column rule that no single-row `CHECK` constraint can express, so it is enforced here,
     * the same class of service-layer guard as [JournalEntryBalance]'s balance invariant.
     */
    private fun requireReserveTypeOnlyOnEquity(
        type: LedgerAccountType,
        reserveType: ReserveType?,
    ) {
        if (reserveType != null && type != LedgerAccountType.EQUITY) {
            throw BadRequestException("reserveType $reserveType may only be set on an EQUITY LedgerAccount, got $type")
        }
    }

    /** Every referenced [LedgerAccountTable] row must exist and be [LedgerAccountTable.active]. */
    private fun requireActiveLedgerAccounts(ledgerAccountIds: List<Uuid>) {
        ledgerAccountIds.distinct().forEach { accountId ->
            val row =
                LedgerAccountTable.selectAll().where { LedgerAccountTable.id eq accountId }.singleOrNull()
                    ?: throw NotFoundException("LedgerAccount $accountId not found")
            if (!row[LedgerAccountTable.active]) {
                throw ConflictException("LedgerAccount $accountId is not active")
            }
        }
    }

    private fun requireJournalEntryRow(id: Uuid): ResultRow =
        JournalEntryTable.selectAll().where { JournalEntryTable.id eq id }.singleOrNull()
            ?: throw NotFoundException("JournalEntry $id not found")

    private fun loadLedgerAccount(id: Uuid): LedgerAccountDto =
        LedgerAccountTable
            .selectAll()
            .where { LedgerAccountTable.id eq id }
            .singleOrNull()
            ?.toLedgerAccountDto() ?: throw NotFoundException("LedgerAccount $id not found")

    private fun loadJournalEntry(id: Uuid): JournalEntryDto {
        val entryRow = requireJournalEntryRow(id)
        val postings =
            (PostingTable innerJoin LedgerAccountTable)
                .selectAll()
                .where { PostingTable.journalEntryId eq id }
                .map { it.toPostingDto() }
        return entryRow.toJournalEntryDto(postings)
    }

    private fun memberDisplayName(memberId: Uuid): String =
        MemberTable
            .selectAll()
            .where { MemberTable.id eq memberId }
            .singleOrNull()
            ?.get(MemberTable.displayName)
            .orEmpty()

    private fun nowLocalDateTime(): LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

    private fun String.toAccountingUuid(kind: String): Uuid =
        runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid $kind id: $this") }

    private fun ResultRow.toLedgerAccountDto(): LedgerAccountDto =
        LedgerAccountDto(
            id = this[LedgerAccountTable.id].toString(),
            accountNumber = this[LedgerAccountTable.accountNumber],
            name = this[LedgerAccountTable.name],
            accountClass = this[LedgerAccountTable.accountClass],
            type = this[LedgerAccountTable.type],
            active = this[LedgerAccountTable.active],
            reserveType = this[LedgerAccountTable.reserveType],
        )

    private fun ResultRow.toPostingDto(): PostingDto =
        PostingDto(
            id = this[PostingTable.id].toString(),
            ledgerAccountId = this[PostingTable.ledgerAccountId].toString(),
            ledgerAccountNumber = this[LedgerAccountTable.accountNumber],
            ledgerAccountName = this[LedgerAccountTable.name],
            side = this[PostingTable.side],
            amount = this[PostingTable.amount],
            sphere = this[PostingTable.sphere],
        )

    private fun ResultRow.toJournalEntryDto(postings: List<PostingDto>): JournalEntryDto {
        val createdBy = this[JournalEntryTable.createdBy]
        return JournalEntryDto(
            id = this[JournalEntryTable.id].toString(),
            entryDate = this[JournalEntryTable.entryDate],
            description = this[JournalEntryTable.description],
            voucherReference = this[JournalEntryTable.voucherReference],
            createdBy = createdBy.toString(),
            createdByDisplayName = memberDisplayName(createdBy),
            status = this[JournalEntryTable.status],
            postedAt = this[JournalEntryTable.postedAt],
            createdAt = this[JournalEntryTable.createdAt],
            postings = postings,
        )
    }
}
