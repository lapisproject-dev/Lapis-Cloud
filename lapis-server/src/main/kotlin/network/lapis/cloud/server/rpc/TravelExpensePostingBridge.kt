package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.JournalEntrySnapshot
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.PostingInput
import network.lapis.cloud.shared.domain.PostingSide
import network.lapis.cloud.shared.domain.PostingSnapshot
import network.lapis.cloud.shared.domain.TravelExpenseLineKind
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.4.11 -- the FIRST bridge in this repo that books money OUT. Bypass idiom exactly
 * mirroring [DonationPostingBridge]/[ContributionPostingBridge]/[EventFeePostingBridge], only the
 * direction is reversed: "transaction-free by contract" (**must be called from inside the
 * caller's already-open `transaction {}`**) and the last lock-taking operation of that
 * transaction other than its own [AuditLogRecorder.record].
 *
 * Deliberately does NOT call [AccountingService.postJournalEntry]: that is role-gated on a
 * `CurrentMember` (TREASURER/ADMIN), but the decisive actor here is BOARD -- and the legitimacy
 * check has already happened in `TravelExpenseService.decideReport`.
 *
 * Buchungssatz (sphere [GemeinnuetzigkeitSphere.IDEELLER_BEREICH], hardcoded -- see class KDoc
 * "Sphere"):
 * ```
 * Soll  <travelExpenseAccountId>  <amount of line 1>     <- ONE debit posting PER expense line
 * Soll  <travelExpenseAccountId>  <amount of line 2>        (Kay-Ruling: the journal entry must
 * ...                                                          carry the breakdown)
 * Haben <paymentBankAccountId>    <total_amount>
 * ```
 * **`posting` has NO memo/description column**, and adding one would mean changing a core
 * accounting table for this one wave. The breakdown therefore survives structurally as N debit
 * postings with their own amounts; the human-readable attribution lives in
 * `journal_entry.description` (carries the report id and a per-kind line count), from where the
 * path leads to `travel_expense_line`. Exactly how the three existing bridges already behave --
 * they simply never had more than three postings.
 *
 * **Degrades instead of failing** (`Failed(code)`, WARN log, NO audit entry) for:
 * `travel_expense_account_not_configured`, `payment_bank_account_not_configured`,
 * `ledger_account_inactive`, `travel_expense_account_not_expense_type`,
 * `cash_register_balance_insufficient`, `cash_voucher_required`. The last two are a DELIBERATE
 * departure from the three existing bridges' own posture ("[CashRegisterGuard]'s guards are NOT
 * degrading cases") -- this is the first bridge that ever books a CREDIT against a potential
 * cash-register account, and the board's APPROVE decision is a governance act that must survive a
 * transient cash shortfall: `retryPosting` books as soon as the till has cover again, same "a
 * failed recheck must not destroy the decision" doctrine V1.4.10 already established.
 * [JournalEntryBalance.validateBalanced] failing is NOT a degrading case (that would be a code
 * defect, not a state) -> [ConflictException], rolling back the whole caller transaction.
 *
 * Unlike the three role models, this returns a [TravelExpensePostingOutcome], not a bare `Uuid?`:
 * the caller must persist a *reason* into `execution_error` (the Relief pattern) -- the webhook
 * callers of the three role models had no such field.
 */
internal object TravelExpensePostingBridge {
    data class PostingLine(
        val kind: TravelExpenseLineKind,
        val amount: BigDecimal,
    )

    fun postTravelExpenseReimbursement(
        reportId: Uuid,
        subjectMemberId: Uuid,
        purposeDigest: String,
        lines: List<PostingLine>,
        totalAmount: BigDecimal,
        decidedAt: LocalDateTime,
        actorMemberId: Uuid,
        actorRole: AccountRole,
        voucherReference: String? = null,
    ): TravelExpensePostingOutcome {
        require(lines.isNotEmpty()) { "lines must not be empty" }
        require(totalAmount > BigDecimal.ZERO) { "totalAmount must be positive, was $totalAmount" }
        require(lines.sumOf { it.amount }.compareTo(totalAmount) == 0) {
            "sum of lines (${lines.sumOf { it.amount }}) must equal totalAmount ($totalAmount)"
        }

        val settingsRow =
            OrganizationSettingsTable
                .selectAll()
                .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                .singleOrNull()
        val expenseAccountId = settingsRow?.get(OrganizationSettingsTable.travelExpenseAccountId)
        val bankAccountId = settingsRow?.get(OrganizationSettingsTable.paymentBankAccountId)

        if (expenseAccountId == null) {
            logger.warn {
                "TravelExpensePostingBridge: report $reportId (subject $subjectMemberId) approved, but " +
                    "OrganizationSettings.travelExpenseAccountId is not configured -- no journal entry was booked."
            }
            return TravelExpensePostingOutcome.Failed("travel_expense_account_not_configured")
        }
        if (bankAccountId == null) {
            logger.warn {
                "TravelExpensePostingBridge: report $reportId (subject $subjectMemberId) approved, but " +
                    "OrganizationSettings.paymentBankAccountId is not configured -- no journal entry was booked."
            }
            return TravelExpensePostingOutcome.Failed("payment_bank_account_not_configured")
        }

        val referencedAccountIds = listOf(expenseAccountId, bankAccountId).distinct()
        val accountRows =
            referencedAccountIds.associateWith { accountId ->
                LedgerAccountTable.selectAll().where { LedgerAccountTable.id eq accountId }.singleOrNull()
            }
        val inactiveAccountIds =
            referencedAccountIds.filter {
                accountRows[it] == null ||
                    accountRows[it]?.get(LedgerAccountTable.active) == false
            }
        if (inactiveAccountIds.isNotEmpty()) {
            logger.warn {
                "TravelExpensePostingBridge: report $reportId approved, but the configured account mapping " +
                    "references LedgerAccount(s) $inactiveAccountIds that are missing or deactivated -- no journal " +
                    "entry was booked."
            }
            return TravelExpensePostingOutcome.Failed("ledger_account_inactive")
        }
        val expenseAccountType = accountRows.getValue(expenseAccountId)?.get(LedgerAccountTable.type)
        if (expenseAccountType != LedgerAccountType.EXPENSE) {
            logger.warn {
                "TravelExpensePostingBridge: report $reportId approved, but travelExpenseAccountId $expenseAccountId " +
                    "is of type $expenseAccountType, not EXPENSE -- no journal entry was booked."
            }
            return TravelExpensePostingOutcome.Failed("travel_expense_account_not_expense_type")
        }

        val postingInputs =
            lines.map { line ->
                PostingInput(
                    ledgerAccountId = expenseAccountId.toString(),
                    side = PostingSide.DEBIT,
                    amount = line.amount,
                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                )
            } +
                PostingInput(
                    ledgerAccountId = bankAccountId.toString(),
                    side = PostingSide.CREDIT,
                    amount = totalAmount,
                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                )

        requireBalanced(postingInputs)

        val resolvedVoucherReference = voucherReference ?: "TRAVEL-$reportId"

        // Q1 (Variante A): the two CashRegisterGuard checks are a DELIBERATE degrading case here,
        // unlike the three role-model bridges -- see class KDoc "Degrades instead of failing".
        val cashAccountIds = CashRegisterGuard.loadCashRegisterAccountIds(referencedAccountIds)
        try {
            CashRegisterGuard.requireVoucherForCashPostings(voucherReference = resolvedVoucherReference, cashAccountIds = cashAccountIds)
            CashRegisterGuard.requireNonNegativeCashBalances(postings = postingInputs, cashAccountIds = cashAccountIds)
        } catch (e: ConflictException) {
            val reason =
                if (e.message.contains("voucherReference", ignoreCase = true)) {
                    "cash_voucher_required"
                } else {
                    "cash_register_balance_insufficient"
                }
            logger.warn {
                "TravelExpensePostingBridge: report $reportId approved, but a cash-register guard rejected the " +
                    "booking ($reason) -- degrading, the APPROVED decision is preserved for retryPosting: ${e.message}"
            }
            return TravelExpensePostingOutcome.Failed(reason)
        }

        val journalEntryId = Uuid.random()
        val entryDate = decidedAt.date
        val mileageCount = lines.count { it.kind == TravelExpenseLineKind.MILEAGE }
        val perDiemCount = lines.count { it.kind == TravelExpenseLineKind.PER_DIEM }
        val receiptedCount = lines.count { it.kind == TravelExpenseLineKind.RECEIPTED }
        val description =
            "Reisekosten $purposeDigest ($mileageCount Fahrt / $perDiemCount Tagespauschale / $receiptedCount Beleg) Antrag $reportId"
                .take(JOURNAL_ENTRY_DESCRIPTION_MAX_LENGTH)

        JournalEntryTable.insert {
            it[id] = journalEntryId
            it[JournalEntryTable.entryDate] = entryDate
            it[JournalEntryTable.description] = description
            it[JournalEntryTable.voucherReference] = resolvedVoucherReference
            it[createdBy] = actorMemberId
            it[status] = JournalEntryStatus.POSTED
            it[postedAt] = decidedAt
            it[createdAt] = DbClock.nowLocalDateTime()
            // A reimbursement is not a donation -- same reasoning EventFeePostingBridge already gives.
            it[JournalEntryTable.donorMemberId] = null
            it[JournalEntryTable.externalDonorId] = null
            it[JournalEntryTable.donorCategory] = null
        }

        val postingSnapshots = mutableListOf<PostingSnapshot>()

        fun insertPosting(
            ledgerAccountId: Uuid,
            side: PostingSide,
            amount: BigDecimal,
        ) {
            PostingTable.insert {
                it[id] = Uuid.random()
                it[PostingTable.journalEntryId] = journalEntryId
                it[PostingTable.ledgerAccountId] = ledgerAccountId
                it[PostingTable.side] = side
                it[PostingTable.amount] = amount
                it[PostingTable.sphere] = GemeinnuetzigkeitSphere.IDEELLER_BEREICH
                it[costCenterId] = null
            }
            postingSnapshots +=
                PostingSnapshot(
                    ledgerAccountId = ledgerAccountId.toString(),
                    side = side,
                    amount = amount,
                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                    costCenterId = null,
                )
        }

        lines.forEach { line -> insertPosting(ledgerAccountId = expenseAccountId, side = PostingSide.DEBIT, amount = line.amount) }
        insertPosting(ledgerAccountId = bankAccountId, side = PostingSide.CREDIT, amount = totalAmount)

        // Last locking operation, see class KDoc.
        AuditLogRecorder.record(
            actorMemberId = actorMemberId,
            actorRole = actorRole,
            entityType = AuditEntityType.JOURNAL_ENTRY,
            entityId = journalEntryId,
            action = AuditAction.CREATE,
            before = null,
            after =
                Json.encodeToString(
                    JournalEntrySnapshot.serializer(),
                    JournalEntrySnapshot(
                        entryDate = entryDate,
                        description = description,
                        voucherReference = resolvedVoucherReference,
                        status = JournalEntryStatus.POSTED,
                        postedAt = decidedAt,
                        createdBy = actorMemberId.toString(),
                        donorMemberId = null,
                        externalDonorId = null,
                        donorCategory = null,
                        postings = postingSnapshots,
                    ),
                ),
        )

        return TravelExpensePostingOutcome.Posted(journalEntryId)
    }

    /** Reuses [JournalEntryBalance.validateBalanced] -- see [DonationPostingBridge.requireBalanced] KDoc for why this call-site wrapper exists. */
    private fun requireBalanced(postings: List<PostingInput>) {
        val result = JournalEntryBalance.validateBalanced(postings)
        if (!result.balanced) throw ConflictException(result.reason ?: "Journal entry not balanced")
    }
}

private const val JOURNAL_ENTRY_DESCRIPTION_MAX_LENGTH = 500
