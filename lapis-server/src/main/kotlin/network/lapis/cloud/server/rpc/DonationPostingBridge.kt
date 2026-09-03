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
import network.lapis.cloud.shared.domain.DonorCategory
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.JournalEntrySnapshot
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.PartyDonationVerdictSnapshot
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
 * Welle V1.2.8 "PSP-Checkout (Stripe)" (GitHub Issue #6) -- donation analogue of
 * [ContributionPostingBridge], for a donation received through the payment gateway
 * (`network.lapis.cloud.server.payment.psp.PspWebhookIngestion`, the only caller this wave). Same
 * "transaction-free by contract" idiom -- **must be called from inside the caller's ALREADY-OPEN
 * `transaction {}`**, and must be the LAST database operation of that transaction that takes a NEW
 * row lock other than [AuditLogRecorder.record] itself (same deadlock-avoidance contract
 * [ContributionPostingBridge] documents: this function's OWN internal [AuditLogRecorder.record]
 * call is the true last lock-taker; nothing in the caller's transaction may lock a row after calling
 * this function).
 *
 * Bewusst KEIN Aufruf von [AccountingService.postJournalEntry]: der ist rollen-gegated auf einen
 * `CurrentMember` (this bridge instead trusts [actorMemberId]/[actorRole], already resolved by the
 * caller) -- a webhook delivery has no `CurrentMember` session at all. Reuses
 * [PartyDonationComplianceCalculator]/[priorPostedDonationTotalThisYear] (the SAME query
 * `AccountingService` itself now delegates to, see that top-level function's own KDoc) rather than
 * re-deriving either -- the §25 PartG rules must never have two independent implementations.
 *
 * Buchungssätze (SKR42, Sphäre [GemeinnuetzigkeitSphere.IDEELLER_BEREICH] -- a plain Verein-Spende
 * is always ideeller Bereich, same as a Mitgliedsbeitrag):
 * ```
 * Soll  <paymentBankAccountId>      <paidAmount - providerFee>
 * Soll  <paymentFeeAccountId>       <providerFee>            (nur wenn providerFee != null && > 0)
 * Haben <donationIncomeAccountId>   <paidAmount>              (immer der volle Brutto-Betrag)
 * ```
 * [providerFee] stays `null` for every V1.2.8 caller (see `ContributionPostingBridge` KDoc and this
 * wave's own documented scope cut "PSP-Gebühr wird nicht erfasst") -- the parameter exists already
 * so a later fee-reconciliation wave can pass it without a signature change.
 *
 * **Verhält sich degradierend statt scheiternd** for an unconfigured/inactive account mapping --
 * `null` is returned, a WARN is logged, and NO audit entry is written, same treatment
 * [ContributionPostingBridge] gives its own two degrading cases. The GoBD cash-register guards
 * ([CashRegisterGuard.requireVoucherForCashPostings]/`.requireNonNegativeCashBalances`) and the
 * balance invariant ([JournalEntryBalance.validateBalanced]) are NOT degrading cases -- both throw
 * [ConflictException] on violation, rolling back the whole caller transaction, same
 * "degradierend statt scheiternd gilt NICHT ausnahmslos" rule [ContributionPostingBridge] documents.
 *
 * **§25 PartG deviates from [AccountingService.postJournalEntry]'s own throwing behaviour on
 * purpose**: a `PROHIBITED` verdict here does NOT throw -- the money has already genuinely arrived
 * at the PSP; rolling back the whole webhook-ingestion transaction would lose the record that it
 * arrived at all. Instead this function returns `null` (degrading, same as an unconfigured mapping)
 * after the caller sets a `reconciliation_note` naming the reason -- the treasurer's reconciliation
 * queue (`PaymentTransactionsScreen`, `unreconciledOnly` filter) is the intended remediation path
 * for a donation this organization was legally required to refuse.
 */
object DonationPostingBridge {
    /**
     * **Exactly one donor identity, Welle V1.4.1b**: exactly one of [donorMemberId]/
     * [externalDonorId] must be non-null -- the Kotlin-side mirror of the
     * `chk_payment_checkout_session_donor_identity` CHECK constraint one hop upstream
     * (`V16__embed_anonymous_donation.sql`). [actorMemberId] stays non-null regardless: for the
     * member-less (anonymous) path the caller ([network.lapis.cloud.server.payment.psp
     * .PspWebhookIngestion]) resolves it via
     * [lastPaymentGatewayComplianceAcknowledgerMemberIdOrNull] before ever calling this function --
     * see that function's own KDoc for why a named, responsible human is used instead of a
     * `journal_entry.created_by` schema change.
     */
    fun postDonationPayment(
        paymentTransactionId: Uuid,
        paidAmount: BigDecimal,
        paidAt: LocalDateTime,
        providerFee: BigDecimal?,
        donorMemberId: Uuid?,
        externalDonorId: Uuid?,
        donorCategory: DonorCategory?,
        actorMemberId: Uuid,
        actorRole: AccountRole,
        voucherReference: String?,
    ): Uuid? {
        require((donorMemberId == null) != (externalDonorId == null)) {
            "exactly one of donorMemberId/externalDonorId must be set (was donorMemberId=$donorMemberId, " +
                "externalDonorId=$externalDonorId)"
        }
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
        val incomeAccountId = settingsRow?.get(OrganizationSettingsTable.donationIncomeAccountId)
        val isPoliticalParty = settingsRow?.get(OrganizationSettingsTable.isPoliticalParty) ?: false

        if (bankAccountId == null ||
            incomeAccountId == null ||
            (providerFee != null && providerFee > BigDecimal.ZERO && feeAccountId == null)
        ) {
            logger.warn {
                "DonationPostingBridge: payment_transaction $paymentTransactionId received, but the account mapping " +
                    "(OrganizationSettings.paymentBankAccountId/paymentFeeAccountId/donationIncomeAccountId) is not " +
                    "fully configured -- no journal entry was booked. An ADMIN must configure all three (the fee " +
                    "account only matters when a provider fee is actually charged) before this donation reaches the " +
                    "general ledger."
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
                "DonationPostingBridge: payment_transaction $paymentTransactionId received, but the configured " +
                    "account mapping references LedgerAccount(s) $inactiveAccountIds that are missing or " +
                    "deactivated -- no journal entry was booked."
            }
            return null
        }

        var verdictForAudit: PartyDonationVerdictSnapshot? = null
        if (isPoliticalParty) {
            if (donorCategory == null) {
                logger.warn {
                    "DonationPostingBridge: payment_transaction $paymentTransactionId is a donation to a political " +
                        "party organization but carries no donorCategory -- no journal entry was booked."
                }
                return null
            }
            val priorTotal =
                priorPostedDonationTotalThisYear(
                    donorMemberId = donorMemberId,
                    externalDonorId = externalDonorId,
                    year = paidAt.year,
                    excludeEntryId = null,
                )
            val verdict =
                PartyDonationComplianceCalculator.check(
                    amount = paidAmount,
                    category = donorCategory,
                    priorPostedTotalThisYear = priorTotal,
                )
            if (verdict.verdict == DonationVerdict.PROHIBITED) {
                // Deliberate divergence from AccountingService.postJournalEntry's own throwing
                // behaviour -- see class KDoc "§25 PartG deviates ... on purpose". The money already
                // arrived; the caller sets outcome = UNPOSTED with a reconciliation_note naming this
                // reason rather than rolling back the whole ingestion.
                logger.warn {
                    "DonationPostingBridge: payment_transaction $paymentTransactionId PROHIBITED under §25 PartG " +
                        "(donorCategory=$donorCategory, reason=${verdict.reason}) -- no journal entry was booked."
                }
                return null
            }
            verdictForAudit =
                PartyDonationVerdictSnapshot(
                    donorCategory = donorCategory,
                    donationAmount = paidAmount,
                    priorPostedTotalThisYear = priorTotal,
                    verdict = "ALLOWED",
                    duties = verdict.duties.toList(),
                )
        }

        val netAmount = if (providerFee != null) paidAmount - providerFee else paidAmount

        val postingInputs =
            listOfNotNull(
                PostingInput(
                    ledgerAccountId = bankAccountId.toString(),
                    side = PostingSide.DEBIT,
                    amount = netAmount,
                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                ),
                if (providerFee != null && providerFee > BigDecimal.ZERO) {
                    PostingInput(
                        ledgerAccountId = requireNotNull(feeAccountId).toString(),
                        side = PostingSide.DEBIT,
                        amount = providerFee,
                        sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                    )
                } else {
                    null
                },
                PostingInput(
                    ledgerAccountId = incomeAccountId.toString(),
                    side = PostingSide.CREDIT,
                    amount = paidAmount,
                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                ),
            )

        requireBalanced(postingInputs)

        val resolvedVoucherReference = voucherReference ?: "DONATION-$paymentTransactionId"

        val cashAccountIds = CashRegisterGuard.loadCashRegisterAccountIds(referencedAccountIds)
        CashRegisterGuard.requireVoucherForCashPostings(voucherReference = resolvedVoucherReference, cashAccountIds = cashAccountIds)
        CashRegisterGuard.requireNonNegativeCashBalances(postings = postingInputs, cashAccountIds = cashAccountIds)

        val journalEntryId = Uuid.random()
        val entryDate = paidAt.date
        val description = "Spende $paymentTransactionId (GATEWAY)"

        JournalEntryTable.insert {
            it[id] = journalEntryId
            it[JournalEntryTable.entryDate] = entryDate
            it[JournalEntryTable.description] = description
            it[JournalEntryTable.voucherReference] = resolvedVoucherReference
            it[createdBy] = actorMemberId
            it[status] = JournalEntryStatus.POSTED
            it[postedAt] = paidAt
            it[createdAt] = DbClock.nowLocalDateTime()
            it[JournalEntryTable.donorMemberId] = donorMemberId
            it[JournalEntryTable.externalDonorId] = externalDonorId
            it[JournalEntryTable.donorCategory] = donorCategory
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
                it[sphere] = GemeinnuetzigkeitSphere.IDEELLER_BEREICH
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

        insertPosting(ledgerAccountId = bankAccountId, side = PostingSide.DEBIT, amount = netAmount)
        if (providerFee != null && providerFee > BigDecimal.ZERO) {
            insertPosting(ledgerAccountId = requireNotNull(feeAccountId), side = PostingSide.DEBIT, amount = providerFee)
        }
        insertPosting(ledgerAccountId = incomeAccountId, side = PostingSide.CREDIT, amount = paidAmount)

        // Last locking operations, see class KDoc -- the JOURNAL_ENTRY entry first, then (only when
        // a verdict was actually computed) the PARTY_DONATION_VERDICT entry, mirroring
        // AccountingService.postJournalEntry's own two-entry ordering for a party donation.
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
                        donorMemberId = donorMemberId?.toString(),
                        externalDonorId = externalDonorId?.toString(),
                        donorCategory = donorCategory,
                        postings = postingSnapshots,
                    ),
                ),
        )
        if (verdictForAudit != null) {
            AuditLogRecorder.record(
                actorMemberId = actorMemberId,
                actorRole = actorRole,
                entityType = AuditEntityType.PARTY_DONATION_VERDICT,
                entityId = journalEntryId,
                action = AuditAction.CREATE,
                before = null,
                after = Json.encodeToString(PartyDonationVerdictSnapshot.serializer(), verdictForAudit),
            )
        }

        return journalEntryId
    }

    /** Reuses [JournalEntryBalance.validateBalanced] -- see [ContributionPostingBridge.requireBalanced] KDoc for why this call-site wrapper exists rather than calling into [AccountingService] itself. */
    private fun requireBalanced(postings: List<PostingInput>) {
        val result = JournalEntryBalance.validateBalanced(postings)
        if (!result.balanced) throw ConflictException(result.reason ?: "Journal entry not balanced")
    }
}
