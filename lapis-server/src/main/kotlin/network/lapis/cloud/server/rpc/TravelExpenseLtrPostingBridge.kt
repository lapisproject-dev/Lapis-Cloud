package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDate
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
import network.lapis.cloud.shared.domain.TravelExpenseSepaReference
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import kotlin.uuid.Uuid

/** The two accounts the settlement is booked between. */
internal data class TravelLtrAccounts(
    val payableAccountId: Uuid,
    val settlementAccountId: Uuid,
)

/**
 * V1.9.325 -- the booking of the moment the LTR is credited for a travel expense report that is refunded in LTR.
 *
 * At approval [TravelExpensePostingBridge] books the expense against a LIABILITY account (`travelLtrPayableAccountId`) instead of the bank. Crediting
 * the LTR settles that liability:
 * ```
 * Soll  <travelLtrPayableAccount>     <total - advance>
 * Haben <travelLtrSettlementAccount>  <total - advance>
 * ```
 * Both postings sit in [GemeinnuetzigkeitSphere.IDEELLER_BEREICH] (like the original booking). The liability is settled with exactly its book value;
 * the LTR amount is derived from it, and the rounding difference (at most 0.005 LTR) is NOT booked in euros -- no exchange difference arises.
 *
 * Unlike [TravelExpensePostingBridge] this bridge never degrades: the LTR is only credited together with its booking, so any problem is a
 * [ConflictException] that rolls the whole credit back. Cash-register accounts are refused on the credit side. **Transaction-free by contract**: it
 * must be called from inside the caller's open transaction, as the last lock-taking operation before its own audit entry.
 */
internal object TravelExpenseLtrPostingBridge {
    /** Account types the settlement account may have -- a single constant, the tax adviser decides (question 1 of the concept). */
    val SETTLEMENT_ACCOUNT_TYPES: Set<LedgerAccountType> =
        setOf(LedgerAccountType.LIABILITY, LedgerAccountType.EQUITY, LedgerAccountType.INCOME)

    /** Loads and validates the two configured accounts; `null` iff something is missing, inactive, of the wrong type or a cash register. */
    fun loadAccountsOrNull(): TravelLtrAccounts? {
        val row =
            OrganizationSettingsTable
                .selectAll()
                .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                .singleOrNull() ?: return null
        val payable = row[OrganizationSettingsTable.travelLtrPayableAccountId] ?: return null
        val settlement = row[OrganizationSettingsTable.travelLtrSettlementAccountId] ?: return null
        if (payable == settlement) return null
        val payableRow = LedgerAccountTable.selectAll().where { LedgerAccountTable.id eq payable }.singleOrNull() ?: return null
        val settlementRow = LedgerAccountTable.selectAll().where { LedgerAccountTable.id eq settlement }.singleOrNull() ?: return null
        if (!payableRow[LedgerAccountTable.active] || !settlementRow[LedgerAccountTable.active]) return null
        if (payableRow[LedgerAccountTable.type] != LedgerAccountType.LIABILITY) return null
        if (settlementRow[LedgerAccountTable.type] !in SETTLEMENT_ACCOUNT_TYPES) return null
        if (CashRegisterGuard.loadCashRegisterAccountIds(listOf(payable, settlement)).isNotEmpty()) return null
        return TravelLtrAccounts(payableAccountId = payable, settlementAccountId = settlement)
    }

    /** Books the settlement and returns the new journal entry id. [on] is the booking date. */
    fun postSettlement(
        reportId: Uuid,
        approvalJournalEntryId: Uuid,
        eurAmount: BigDecimal,
        on: LocalDate,
        actorMemberId: Uuid,
        actorRole: AccountRole,
    ): Uuid {
        require(eurAmount.signum() > 0) { "eurAmount must be positive" }
        val configured = loadAccountsOrNull() ?: throw ConflictException("LTR_ACCOUNTS_MISSING")
        // The liability to settle is the one the approval actually credited, NOT whatever is configured now (the admin may have switched it).
        val accounts =
            TravelLtrAccounts(
                payableAccountId =
                    bookedPayableAccountId(
                        approvalJournalEntryId = approvalJournalEntryId,
                        eurAmount = eurAmount,
                        configuredPayable = configured.payableAccountId,
                    ),
                settlementAccountId = configured.settlementAccountId,
            )
        if (accounts.payableAccountId == accounts.settlementAccountId) throw ConflictException("LTR_ACCOUNTS_MISSING")
        val reference = TravelExpenseSepaReference.of(reportId.toString())
        val inputs =
            listOf(
                PostingInput(
                    ledgerAccountId = accounts.payableAccountId.toString(),
                    side = PostingSide.DEBIT,
                    amount = eurAmount,
                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                ),
                PostingInput(
                    ledgerAccountId = accounts.settlementAccountId.toString(),
                    side = PostingSide.CREDIT,
                    amount = eurAmount,
                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                ),
            )
        val balance = JournalEntryBalance.validateBalanced(inputs)
        if (!balance.balanced) throw ConflictException(balance.reason ?: "Journal entry not balanced")
        return insertEntry(
            description = "Reisekostenerstattung in LTR $reference",
            voucherReference = "LTR-$reference",
            on = on,
            actorMemberId = actorMemberId,
            actorRole = actorRole,
            postings = inputs.map { Triple(Uuid.parse(it.ledgerAccountId), it.side, it.amount) },
        )
    }

    /**
     * The LIABILITY account credited with [eurAmount] by the approval booking. If several credits qualify, the currently configured payable account
     * wins when it is among them; otherwise the booking is ambiguous and the credit is refused.
     */
    internal fun bookedPayableAccountId(
        approvalJournalEntryId: Uuid,
        eurAmount: BigDecimal,
        configuredPayable: Uuid,
    ): Uuid {
        val candidates =
            PostingTable
                .selectAll()
                .where { PostingTable.journalEntryId eq approvalJournalEntryId }
                .filter { it[PostingTable.side] == PostingSide.CREDIT && it[PostingTable.amount].compareTo(eurAmount) == 0 }
                .map { it[PostingTable.ledgerAccountId] }
                .distinct()
                .filter { id ->
                    LedgerAccountTable
                        .selectAll()
                        .where { LedgerAccountTable.id eq id }
                        .singleOrNull()
                        ?.get(LedgerAccountTable.type) == LedgerAccountType.LIABILITY
                }
        return when {
            candidates.size == 1 -> candidates.single()
            configuredPayable in candidates -> configuredPayable
            else -> throw ConflictException("LTR_ACCOUNTS_MISSING")
        }
    }

    /** Exact reversal of [originalJournalEntryId] (every side swapped) as its own journal entry. */
    fun postReversal(
        reportId: Uuid,
        originalJournalEntryId: Uuid,
        on: LocalDate,
        actorMemberId: Uuid,
        actorRole: AccountRole,
    ): Uuid {
        val original = PostingTable.selectAll().where { PostingTable.journalEntryId eq originalJournalEntryId }.toList()
        if (original.isEmpty()) {
            throw ConflictException(
                "TravelExpenseLtrPostingBridge: journal entry $originalJournalEntryId has no postings",
            )
        }
        val reference = TravelExpenseSepaReference.of(reportId.toString())
        return insertEntry(
            description = "Storno Reisekostenerstattung in LTR $reference",
            voucherReference = "LTR-REV-$reference",
            on = on,
            actorMemberId = actorMemberId,
            actorRole = actorRole,
            postings =
                original.map {
                    Triple(
                        it[PostingTable.ledgerAccountId],
                        if (it[PostingTable.side] == PostingSide.DEBIT) PostingSide.CREDIT else PostingSide.DEBIT,
                        it[PostingTable.amount],
                    )
                },
        )
    }

    private fun insertEntry(
        description: String,
        voucherReference: String,
        on: LocalDate,
        actorMemberId: Uuid,
        actorRole: AccountRole,
        postings: List<Triple<Uuid, PostingSide, BigDecimal>>,
    ): Uuid {
        val journalEntryId = Uuid.random()
        val now: LocalDateTime = DbClock.nowLocalDateTime()
        JournalEntryTable.insert {
            it[id] = journalEntryId
            it[entryDate] = on
            it[JournalEntryTable.description] = description
            it[JournalEntryTable.voucherReference] = voucherReference
            it[createdBy] = actorMemberId
            it[status] = JournalEntryStatus.POSTED
            it[postedAt] = now
            it[createdAt] = now
            it[donorMemberId] = null
            it[externalDonorId] = null
            it[donorCategory] = null
        }
        val snapshots = mutableListOf<PostingSnapshot>()
        for ((accountId, side, amount) in postings) {
            PostingTable.insert {
                it[id] = Uuid.random()
                it[PostingTable.journalEntryId] = journalEntryId
                it[ledgerAccountId] = accountId
                it[PostingTable.side] = side
                it[PostingTable.amount] = amount
                it[sphere] = GemeinnuetzigkeitSphere.IDEELLER_BEREICH
                it[costCenterId] = null
            }
            snapshots +=
                PostingSnapshot(
                    ledgerAccountId = accountId.toString(),
                    side = side,
                    amount = amount,
                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                    costCenterId = null,
                )
        }
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
                        entryDate = on,
                        description = description,
                        voucherReference = voucherReference,
                        status = JournalEntryStatus.POSTED,
                        postedAt = now,
                        createdBy = actorMemberId.toString(),
                        donorMemberId = null,
                        externalDonorId = null,
                        donorCategory = null,
                        postings = snapshots,
                    ),
                ),
        )
        return journalEntryId
    }
}
