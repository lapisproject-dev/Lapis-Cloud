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
import network.lapis.cloud.shared.domain.PostingInput
import network.lapis.cloud.shared.domain.PostingSide
import network.lapis.cloud.shared.domain.PostingSnapshot
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.4.3.1 "Veranstaltungen" -- event-fee analogue of [DonationPostingBridge], for a
 * confirmed participation-fee payment (`network.lapis.cloud.server.payment.psp.PspWebhookIngestion`,
 * the only caller). Same "transaction-free by contract" idiom -- **must be called from inside the
 * caller's already-open `transaction {}`**, and must be the LAST database operation of that
 * transaction that takes a NEW row lock other than [AuditLogRecorder.record] itself (same
 * deadlock-avoidance contract [DonationPostingBridge] documents).
 *
 * **Deliberately does NOT carry a payer/donor identity** (no `donorMemberId`/`externalDonorId`/
 * `donorCategory` parameter) and does NOT call [PartyDonationComplianceCalculator] -- a
 * participation fee is a Leistungsentgelt, not a donation. See `39-events.kuml.kts` file header
 * "A participation fee is a Leistungsentgelt, not a donation" for the legal caveat this system does
 * NOT verify (a fee materially above the event's own cost may, by prevailing opinion, be a disguised
 * donation -- get this reviewed by counsel before relying on it).
 *
 * Buchungssatz (Sphäre `organization_settings.event_income_sphere`, default `ZWECKBETRIEB`):
 * ```
 * Soll  <paymentBankAccountId>   paidAmount - providerFee
 * Soll  <paymentFeeAccountId>    providerFee   (nur wenn != null && > 0)
 * Haben <eventIncomeAccountId>   paidAmount    (immer brutto)
 * ```
 * [providerFee] stays `null` for every current caller -- exists so a later fee-reconciliation wave
 * can pass it without a signature change, same as [DonationPostingBridge.postDonationPayment].
 *
 * **Verhält sich degradierend statt scheiternd** for an unconfigured/inactive account mapping --
 * `null` is returned, a WARN is logged, and NO audit entry is written, same treatment
 * [DonationPostingBridge] gives its own degrading cases. [CashRegisterGuard]'s guards and
 * [JournalEntryBalance.validateBalanced] are NOT degrading cases -- both throw [ConflictException]
 * on violation, rolling back the whole caller transaction, same "degradierend statt scheiternd gilt
 * NICHT ausnahmslos" rule [DonationPostingBridge] documents.
 */
object EventFeePostingBridge {
    fun postEventFeePayment(
        paymentTransactionId: Uuid,
        eventRegistrationId: Uuid,
        paidAmount: BigDecimal,
        paidAt: LocalDateTime,
        providerFee: BigDecimal?,
        actorMemberId: Uuid,
        actorRole: AccountRole,
        voucherReference: String?,
    ): Uuid? {
        require(paidAmount > BigDecimal.ZERO) { "paidAmount must be positive, was $paidAmount" }
        if (providerFee != null) {
            require(providerFee >= BigDecimal.ZERO) { "providerFee must not be negative, was $providerFee" }
            require(providerFee < paidAmount) { "providerFee ($providerFee) must be less than paidAmount ($paidAmount)" }
        }

        val settingsRow =
            OrganizationSettingsTable
                .selectAll()
                .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                .singleOrNull()
        val bankAccountId = settingsRow?.get(OrganizationSettingsTable.paymentBankAccountId)
        val feeAccountId = settingsRow?.get(OrganizationSettingsTable.paymentFeeAccountId)
        val incomeAccountId = settingsRow?.get(OrganizationSettingsTable.eventIncomeAccountId)
        val sphere = settingsRow?.get(OrganizationSettingsTable.eventIncomeSphere) ?: GemeinnuetzigkeitSphere.ZWECKBETRIEB

        if (bankAccountId == null ||
            incomeAccountId == null ||
            (providerFee != null && providerFee > BigDecimal.ZERO && feeAccountId == null)
        ) {
            logger.warn {
                "EventFeePostingBridge: payment_transaction $paymentTransactionId received (event_registration " +
                    "$eventRegistrationId), but the account mapping (OrganizationSettings.paymentBankAccountId/" +
                    "paymentFeeAccountId/eventIncomeAccountId) is not fully configured -- no journal entry was booked."
            }
            return null
        }

        val referencedAccountIds =
            listOfNotNull(bankAccountId, incomeAccountId, feeAccountId.takeIf { providerFee != null && providerFee > BigDecimal.ZERO })
        val inactiveAccountIds =
            referencedAccountIds.distinct().filter { accountId ->
                val row = LedgerAccountTable.selectAll().where { LedgerAccountTable.id eq accountId }.singleOrNull()
                row == null || !row[LedgerAccountTable.active]
            }
        if (inactiveAccountIds.isNotEmpty()) {
            logger.warn {
                "EventFeePostingBridge: payment_transaction $paymentTransactionId received, but the configured " +
                    "account mapping references LedgerAccount(s) $inactiveAccountIds that are missing or " +
                    "deactivated -- no journal entry was booked."
            }
            return null
        }

        val netAmount = if (providerFee != null) paidAmount - providerFee else paidAmount

        val postingInputs =
            listOfNotNull(
                PostingInput(ledgerAccountId = bankAccountId.toString(), side = PostingSide.DEBIT, amount = netAmount, sphere = sphere),
                if (providerFee != null && providerFee > BigDecimal.ZERO) {
                    PostingInput(
                        ledgerAccountId = requireNotNull(feeAccountId).toString(),
                        side = PostingSide.DEBIT,
                        amount = providerFee,
                        sphere = sphere,
                    )
                } else {
                    null
                },
                PostingInput(ledgerAccountId = incomeAccountId.toString(), side = PostingSide.CREDIT, amount = paidAmount, sphere = sphere),
            )

        requireBalanced(postingInputs)

        val resolvedVoucherReference = voucherReference ?: "EVENT-FEE-$paymentTransactionId"

        val cashAccountIds = CashRegisterGuard.loadCashRegisterAccountIds(referencedAccountIds)
        CashRegisterGuard.requireVoucherForCashPostings(voucherReference = resolvedVoucherReference, cashAccountIds = cashAccountIds)
        CashRegisterGuard.requireNonNegativeCashBalances(postings = postingInputs, cashAccountIds = cashAccountIds)

        val journalEntryId = Uuid.random()
        val entryDate = paidAt.date
        val description = "Anmeldegebühr $paymentTransactionId (GATEWAY)"

        JournalEntryTable.insert {
            it[id] = journalEntryId
            it[JournalEntryTable.entryDate] = entryDate
            it[JournalEntryTable.description] = description
            it[JournalEntryTable.voucherReference] = resolvedVoucherReference
            it[createdBy] = actorMemberId
            it[status] = JournalEntryStatus.POSTED
            it[postedAt] = paidAt
            it[createdAt] = DbClock.nowLocalDateTime()
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
                it[PostingTable.sphere] = sphere
                it[costCenterId] = null
            }
            postingSnapshots +=
                PostingSnapshot(
                    ledgerAccountId = ledgerAccountId.toString(),
                    side = side,
                    amount = amount,
                    sphere = sphere,
                    costCenterId = null,
                )
        }

        insertPosting(ledgerAccountId = bankAccountId, side = PostingSide.DEBIT, amount = netAmount)
        if (providerFee != null && providerFee > BigDecimal.ZERO) {
            insertPosting(ledgerAccountId = requireNotNull(feeAccountId), side = PostingSide.DEBIT, amount = providerFee)
        }
        insertPosting(ledgerAccountId = incomeAccountId, side = PostingSide.CREDIT, amount = paidAmount)

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
                        postedAt = paidAt,
                        createdBy = actorMemberId.toString(),
                        donorMemberId = null,
                        externalDonorId = null,
                        donorCategory = null,
                        postings = postingSnapshots,
                    ),
                ),
        )

        return journalEntryId
    }

    /** Reuses [JournalEntryBalance.validateBalanced] -- see [DonationPostingBridge.requireBalanced] KDoc for why this call-site wrapper exists rather than calling into [AccountingService] itself. */
    private fun requireBalanced(postings: List<PostingInput>) {
        val result = JournalEntryBalance.validateBalanced(postings)
        if (!result.balanced) throw ConflictException(result.reason ?: "Journal entry not balanced")
    }
}
