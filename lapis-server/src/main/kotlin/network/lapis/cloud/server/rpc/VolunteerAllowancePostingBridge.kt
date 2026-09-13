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
import network.lapis.cloud.shared.domain.VolunteerAllowanceCategory
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.4.12 "Übungsleiter- und Ehrenamtspauschale" -- the SECOND bridge in this repo that
 * books money OUT (after [TravelExpensePostingBridge]). Same "transaction-free by contract" idiom
 * (**must be called from inside the caller's already-open `transaction {}`**) and the last
 * lock-taking operation of that transaction other than its own [AuditLogRecorder.record].
 *
 * Buchungssatz (sphere [GemeinnuetzigkeitSphere.IDEELLER_BEREICH], hardcoded, same as
 * [TravelExpensePostingBridge]):
 * ```
 * Soll  <volunteerAllowanceAccountId>  <amount>
 * Haben <paymentBankAccountId>         <amount>
 * ```
 * **Exactly TWO postings, always** -- unlike [TravelExpensePostingBridge] (one debit per expense
 * line), the tax-free/taxable split is NOT booked as separate postings: this codebase has no
 * payroll/Lohnsteuer concept, and splitting the debit into two ledger lines would misleadingly
 * imply one is already payroll-processed. The split survives structurally only in
 * `journal_entry.description` (which spells out `frei <freeAmount> / steuerpflichtig
 * <exceedingAmount>`) and in the payment row's own `free_amount_snapshot`/
 * `exceeding_amount_snapshot` columns -- **deliberate scope boundary**, not an oversight: actually
 * withholding and remitting Lohnsteuer for the exceeding portion is explicitly the organization's
 * own responsibility (see [VolunteerAllowanceCapDisclaimer.TEXT]).
 *
 * **Degrades instead of failing** (`Failed(code)`, WARN log, NO audit entry) for:
 * `volunteer_allowance_account_not_configured`, `payment_bank_account_not_configured`,
 * `ledger_account_inactive`, `volunteer_allowance_account_not_expense_type`,
 * `cash_register_balance_insufficient`, `cash_voucher_required` -- same posture
 * [TravelExpensePostingBridge] already establishes (a board APPROVE decision is a governance act
 * that must survive a transient cash shortfall; `retryPosting` books once cover exists again).
 * [JournalEntryBalance.validateBalanced] failing is NOT a degrading case (a code defect, not a
 * state) -> [ConflictException], rolling back the whole caller transaction.
 */
internal object VolunteerAllowancePostingBridge {
    fun postVolunteerAllowance(
        paymentId: Uuid,
        subjectMemberId: Uuid,
        category: VolunteerAllowanceCategory,
        activityDigest: String,
        amount: BigDecimal,
        freeAmount: BigDecimal,
        exceedingAmount: BigDecimal,
        decidedAt: LocalDateTime,
        actorMemberId: Uuid,
        actorRole: AccountRole,
        voucherReference: String? = null,
    ): VolunteerAllowancePostingOutcome {
        require(amount > BigDecimal.ZERO) { "amount must be positive, was $amount" }
        require(freeAmount.add(exceedingAmount).compareTo(amount) == 0) {
            "freeAmount ($freeAmount) + exceedingAmount ($exceedingAmount) must equal amount ($amount)"
        }

        val settingsRow =
            OrganizationSettingsTable
                .selectAll()
                .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                .singleOrNull()
        val expenseAccountId = settingsRow?.get(OrganizationSettingsTable.volunteerAllowanceAccountId)
        val bankAccountId = settingsRow?.get(OrganizationSettingsTable.paymentBankAccountId)

        if (expenseAccountId == null) {
            logger.warn {
                "VolunteerAllowancePostingBridge: payment $paymentId (subject $subjectMemberId) approved, but " +
                    "OrganizationSettings.volunteerAllowanceAccountId is not configured -- no journal entry was booked."
            }
            return VolunteerAllowancePostingOutcome.Failed("volunteer_allowance_account_not_configured")
        }
        if (bankAccountId == null) {
            logger.warn {
                "VolunteerAllowancePostingBridge: payment $paymentId (subject $subjectMemberId) approved, but " +
                    "OrganizationSettings.paymentBankAccountId is not configured -- no journal entry was booked."
            }
            return VolunteerAllowancePostingOutcome.Failed("payment_bank_account_not_configured")
        }

        val referencedAccountIds = listOf(expenseAccountId, bankAccountId).distinct()
        val accountRows =
            referencedAccountIds.associateWith { accountId ->
                LedgerAccountTable.selectAll().where { LedgerAccountTable.id eq accountId }.singleOrNull()
            }
        val inactiveAccountIds =
            referencedAccountIds.filter {
                accountRows[it] == null || accountRows[it]?.get(LedgerAccountTable.active) == false
            }
        if (inactiveAccountIds.isNotEmpty()) {
            logger.warn {
                "VolunteerAllowancePostingBridge: payment $paymentId approved, but the configured account mapping " +
                    "references LedgerAccount(s) $inactiveAccountIds that are missing or deactivated -- no journal " +
                    "entry was booked."
            }
            return VolunteerAllowancePostingOutcome.Failed("ledger_account_inactive")
        }
        val expenseAccountType = accountRows.getValue(expenseAccountId)?.get(LedgerAccountTable.type)
        if (expenseAccountType != LedgerAccountType.EXPENSE) {
            logger.warn {
                "VolunteerAllowancePostingBridge: payment $paymentId approved, but volunteerAllowanceAccountId " +
                    "$expenseAccountId is of type $expenseAccountType, not EXPENSE -- no journal entry was booked."
            }
            return VolunteerAllowancePostingOutcome.Failed("volunteer_allowance_account_not_expense_type")
        }

        val postingInputs =
            listOf(
                PostingInput(
                    ledgerAccountId = expenseAccountId.toString(),
                    side = PostingSide.DEBIT,
                    amount = amount,
                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                ),
                PostingInput(
                    ledgerAccountId = bankAccountId.toString(),
                    side = PostingSide.CREDIT,
                    amount = amount,
                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                ),
            )
        requireBalanced(postingInputs)

        val resolvedVoucherReference = voucherReference ?: "VOLALLOW-$paymentId"

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
                "VolunteerAllowancePostingBridge: payment $paymentId approved, but a cash-register guard rejected " +
                    "the booking ($reason) -- degrading, the APPROVED decision is preserved for retryPosting: ${e.message}"
            }
            return VolunteerAllowancePostingOutcome.Failed(reason)
        }

        val journalEntryId = Uuid.random()
        val entryDate = decidedAt.date
        val categoryLabel =
            when (category) {
                VolunteerAllowanceCategory.INSTRUCTOR -> "INSTRUCTOR"
                VolunteerAllowanceCategory.HONORARY -> "HONORARY"
            }
        val description =
            "Ehrenamtspauschale $categoryLabel $activityDigest (frei $freeAmount / steuerpflichtig $exceedingAmount) Zahlung $paymentId"
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
            // An allowance payment is not a donation -- same reasoning TravelExpensePostingBridge already gives.
            it[JournalEntryTable.donorMemberId] = null
            it[JournalEntryTable.externalDonorId] = null
            it[JournalEntryTable.donorCategory] = null
        }

        val postingSnapshots = mutableListOf<PostingSnapshot>()

        fun insertPosting(
            ledgerAccountId: Uuid,
            side: PostingSide,
            postingAmount: BigDecimal,
        ) {
            PostingTable.insert {
                it[id] = Uuid.random()
                it[PostingTable.journalEntryId] = journalEntryId
                it[PostingTable.ledgerAccountId] = ledgerAccountId
                it[PostingTable.side] = side
                it[PostingTable.amount] = postingAmount
                it[PostingTable.sphere] = GemeinnuetzigkeitSphere.IDEELLER_BEREICH
                it[costCenterId] = null
            }
            postingSnapshots +=
                PostingSnapshot(
                    ledgerAccountId = ledgerAccountId.toString(),
                    side = side,
                    amount = postingAmount,
                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                    costCenterId = null,
                )
        }

        insertPosting(ledgerAccountId = expenseAccountId, side = PostingSide.DEBIT, postingAmount = amount)
        insertPosting(ledgerAccountId = bankAccountId, side = PostingSide.CREDIT, postingAmount = amount)

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

        return VolunteerAllowancePostingOutcome.Posted(journalEntryId)
    }

    /** Reuses [JournalEntryBalance.validateBalanced] -- see [DonationPostingBridge.requireBalanced] KDoc for why this call-site wrapper exists. */
    private fun requireBalanced(postings: List<PostingInput>) {
        val result = JournalEntryBalance.validateBalanced(postings)
        if (!result.balanced) throw ConflictException(result.reason ?: "Journal entry not balanced")
    }
}

private const val JOURNAL_ENTRY_DESCRIPTION_MAX_LENGTH = 500
