package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDate
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
import network.lapis.cloud.shared.domain.OpenItemDirection
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
private const val JOURNAL_ENTRY_DESCRIPTION_MAX_LENGTH = 500

internal sealed interface OpenItemPostingOutcome {
    data class Posted(
        val journalEntryId: Uuid,
    ) : OpenItemPostingOutcome

    data class Failed(
        val reason: String,
    ) : OpenItemPostingOutcome
}

/**
 * Welle V1.4.15 "Kreditoren-/Debitorenbuchhaltung". `internal object`, "transaction-free by
 * contract" (must be called from inside the caller's already-open `transaction {}`) -- same idiom
 * [TravelExpensePostingBridge]/[DonationPostingBridge]/[ContributionPostingBridge] already
 * establish. [AuditLogRecorder.record] is always the LAST lock-taking operation of the caller's
 * transaction (see that object's own deadlock-avoidance contract).
 *
 * **Two-booking rule (Jobs/Atkinson-Ruling)**: unlike every prior wave in this repo, an open item
 * is booked at CREATION time already, not only when it is marked paid -- see
 * `network.lapis.cloud.server.rpc.OpenItemService` KDoc "Stolperfalle S-6": netting two open
 * items against each other requires BOTH sides already booked; if only settlement booked, a
 * pairing of two never-paid items would have nothing to net against.
 *
 * Buchungssaetze (`vatRate` never set -- stays `VatRate.UNCLASSIFIED`, same as all six existing
 * bridges, see [OpenItemService] KDoc "Stolperfalle" table):
 * ```
 * Kreditor anlegen   Soll <contraAccountId (EXPENSE)>       Haben <payablesAccountId (LIABILITY)>
 * Kreditor bezahlt   Soll <payablesAccountId>                Haben <bankAccountId>
 * Debitor anlegen    Soll <receivablesAccountId (ASSET)>     Haben <contraAccountId (INCOME)>
 * Debitor bezahlt    Soll <bankAccountId>                    Haben <receivablesAccountId>
 * Verrechnung        Soll <payablesAccountId>                Haben <receivablesAccountId>
 * Storno (jeder Fall) exakte Umkehrung als EIGENER Journal-Eintrag (GoBD -- nie Loeschen/Aendern)
 * ```
 *
 * **Degrades instead of failing** (`Failed(code)`, WARN log, NO audit entry) for every
 * configuration/state problem a fresh/unconfigured organization can hit --
 * `receivables_account_not_configured`, `payables_account_not_configured`,
 * `payment_bank_account_not_configured`, `ledger_account_inactive`, `contra_account_wrong_type`,
 * `receivables_account_not_asset_type`, `payables_account_not_liability_type`,
 * `cash_voucher_required`, `cash_register_balance_insufficient`. [JournalEntryBalance
 * .validateBalanced] failing is NOT a degrading case (a code defect, not a state) ->
 * [ConflictException], rolling back the whole caller transaction. Same for "debit account == credit
 * account" ([postEntry], Welle V1.4.22).
 *
 * **Scope of "degrades instead of failing" (precision added in V1.4.22).** The posture above describes
 * what THIS object does with a configuration problem it discovers while booking. It never promised
 * that every caller must reach it: since V1.4.22, `OpenItemService.settleOpenItem`/
 * `.retrySettlementPosting` reject an unusable money-side account (inactive, non-`ASSET`, or the
 * receivables/payables collective account) BEFORE calling [postSettlement], so such a settlement is
 * now refused outright instead of being recorded with `postingError = "ledger_account_inactive"` and a
 * settlement row that reduced the open amount without any journal entry behind it. That is a
 * deliberate behaviour change (the safer variant: no bookkeeping fact is created that a treasurer
 * would have to un-do), documented in the CHANGELOG and in `docs/architecture/open-items.adoc`. The
 * degradation path itself is unchanged for everything it still covers -- above all the
 * `*_account_not_configured` cases of item CREATION, where refusing would mean losing the open item.
 */
internal object OpenItemPostingBridge {
    fun postItemCreation(
        itemId: Uuid,
        direction: OpenItemDirection,
        counterpartyName: String,
        reference: String?,
        amount: BigDecimal,
        contraAccountId: Uuid,
        sphere: GemeinnuetzigkeitSphere,
        itemDate: LocalDate,
        actorMemberId: Uuid,
        actorRole: AccountRole,
    ): OpenItemPostingOutcome {
        val settingsRow =
            OrganizationSettingsTable
                .selectAll()
                .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                .singleOrNull()
        val receivablesAccountId = settingsRow?.get(OrganizationSettingsTable.receivablesAccountId)
        val payablesAccountId = settingsRow?.get(OrganizationSettingsTable.payablesAccountId)

        val counterAccountId: Uuid
        val counterExpectedType: LedgerAccountType
        when (direction) {
            OpenItemDirection.PAYABLE -> {
                counterAccountId = payablesAccountId ?: return degraded(reason = "payables_account_not_configured", itemId = itemId)
                counterExpectedType = LedgerAccountType.LIABILITY
            }
            OpenItemDirection.RECEIVABLE -> {
                counterAccountId = receivablesAccountId ?: return degraded(reason = "receivables_account_not_configured", itemId = itemId)
                counterExpectedType = LedgerAccountType.ASSET
            }
        }

        val referencedAccountIds = listOf(contraAccountId, counterAccountId).distinct()
        val accountRows =
            referencedAccountIds.associateWith { accountId ->
                LedgerAccountTable.selectAll().where { LedgerAccountTable.id eq accountId }.singleOrNull()
            }
        val inactiveAccountIds =
            referencedAccountIds.filter { accountRows[it] == null || accountRows[it]?.get(LedgerAccountTable.active) == false }
        if (inactiveAccountIds.isNotEmpty()) {
            logger.warn {
                "OpenItemPostingBridge: item $itemId, referenced LedgerAccount(s) $inactiveAccountIds missing/inactive -- no journal entry booked."
            }
            return OpenItemPostingOutcome.Failed("ledger_account_inactive")
        }

        val contraExpectedType = if (direction == OpenItemDirection.PAYABLE) LedgerAccountType.EXPENSE else LedgerAccountType.INCOME
        val contraActualType = accountRows.getValue(contraAccountId)?.get(LedgerAccountTable.type)
        if (contraActualType != contraExpectedType) {
            logger.warn {
                "OpenItemPostingBridge: item $itemId, contraAccountId $contraAccountId is $contraActualType, expected $contraExpectedType -- no journal entry booked."
            }
            return OpenItemPostingOutcome.Failed("contra_account_wrong_type")
        }
        val counterActualType = accountRows.getValue(counterAccountId)?.get(LedgerAccountTable.type)
        if (counterActualType != counterExpectedType) {
            logger.warn {
                "OpenItemPostingBridge: item $itemId, counterAccountId $counterAccountId is $counterActualType, expected $counterExpectedType -- no journal entry booked."
            }
            return OpenItemPostingOutcome.Failed(
                if (direction ==
                    OpenItemDirection.RECEIVABLE
                ) {
                    "receivables_account_not_asset_type"
                } else {
                    "payables_account_not_liability_type"
                },
            )
        }

        val (debitAccountId, creditAccountId) =
            when (direction) {
                OpenItemDirection.PAYABLE -> contraAccountId to counterAccountId
                OpenItemDirection.RECEIVABLE -> counterAccountId to contraAccountId
            }

        return postEntry(
            debitAccountId = debitAccountId,
            creditAccountId = creditAccountId,
            amount = amount,
            sphere = sphere,
            on = itemDate,
            description = "Offener Posten · $counterpartyName".take(JOURNAL_ENTRY_DESCRIPTION_MAX_LENGTH),
            voucherReference = reference ?: "OI-$itemId",
            actorMemberId = actorMemberId,
            actorRole = actorRole,
            failureContext = "item $itemId ($direction) creation",
        )
    }

    fun postSettlement(
        settlementId: Uuid,
        direction: OpenItemDirection,
        counterpartyName: String,
        reference: String?,
        amount: BigDecimal,
        bankAccountId: Uuid,
        settledOn: LocalDate,
        actorMemberId: Uuid,
        actorRole: AccountRole,
    ): OpenItemPostingOutcome {
        val settingsRow =
            OrganizationSettingsTable
                .selectAll()
                .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                .singleOrNull()
        val ledgerAccountId =
            when (direction) {
                OpenItemDirection.PAYABLE ->
                    settingsRow?.get(OrganizationSettingsTable.payablesAccountId)
                        ?: return OpenItemPostingOutcome.Failed("payables_account_not_configured")
                OpenItemDirection.RECEIVABLE ->
                    settingsRow?.get(OrganizationSettingsTable.receivablesAccountId)
                        ?: return OpenItemPostingOutcome.Failed("receivables_account_not_configured")
            }

        val referencedAccountIds = listOf(ledgerAccountId, bankAccountId).distinct()
        val accountRows =
            referencedAccountIds.associateWith { accountId ->
                LedgerAccountTable.selectAll().where { LedgerAccountTable.id eq accountId }.singleOrNull()
            }
        val inactiveAccountIds =
            referencedAccountIds.filter { accountRows[it] == null || accountRows[it]?.get(LedgerAccountTable.active) == false }
        if (inactiveAccountIds.isNotEmpty()) {
            logger.warn {
                "OpenItemPostingBridge: settlement $settlementId, referenced LedgerAccount(s) $inactiveAccountIds missing/inactive -- no journal entry booked."
            }
            return OpenItemPostingOutcome.Failed("ledger_account_inactive")
        }

        val (debitAccountId, creditAccountId) =
            when (direction) {
                OpenItemDirection.PAYABLE -> ledgerAccountId to bankAccountId
                OpenItemDirection.RECEIVABLE -> bankAccountId to ledgerAccountId
            }

        return postEntry(
            debitAccountId = debitAccountId,
            creditAccountId = creditAccountId,
            amount = amount,
            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
            on = settledOn,
            description = "Zahlung offener Posten · $counterpartyName".take(JOURNAL_ENTRY_DESCRIPTION_MAX_LENGTH),
            voucherReference = reference ?: "OI-SETTLE-$settlementId",
            actorMemberId = actorMemberId,
            actorRole = actorRole,
            failureContext = "settlement $settlementId ($direction)",
        )
    }

    fun postNetting(
        nettingId: Uuid,
        counterpartyDisplayName: String,
        amount: BigDecimal,
        on: LocalDate,
        actorMemberId: Uuid,
        actorRole: AccountRole,
    ): OpenItemPostingOutcome {
        val settingsRow =
            OrganizationSettingsTable
                .selectAll()
                .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                .singleOrNull()
        val payablesAccountId =
            settingsRow?.get(OrganizationSettingsTable.payablesAccountId)
                ?: return OpenItemPostingOutcome.Failed("payables_account_not_configured")
        val receivablesAccountId =
            settingsRow?.get(OrganizationSettingsTable.receivablesAccountId)
                ?: return OpenItemPostingOutcome.Failed("receivables_account_not_configured")

        val referencedAccountIds = listOf(payablesAccountId, receivablesAccountId).distinct()
        val accountRows =
            referencedAccountIds.associateWith { accountId ->
                LedgerAccountTable.selectAll().where { LedgerAccountTable.id eq accountId }.singleOrNull()
            }
        val inactiveAccountIds =
            referencedAccountIds.filter { accountRows[it] == null || accountRows[it]?.get(LedgerAccountTable.active) == false }
        if (inactiveAccountIds.isNotEmpty()) {
            logger.warn {
                "OpenItemPostingBridge: netting $nettingId, referenced LedgerAccount(s) $inactiveAccountIds missing/inactive -- no journal entry booked."
            }
            return OpenItemPostingOutcome.Failed("ledger_account_inactive")
        }

        return postEntry(
            debitAccountId = payablesAccountId,
            creditAccountId = receivablesAccountId,
            amount = amount,
            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
            on = on,
            description = "Verrechnung offener Posten · $counterpartyDisplayName".take(JOURNAL_ENTRY_DESCRIPTION_MAX_LENGTH),
            voucherReference = "OI-NETTING-$nettingId",
            actorMemberId = actorMemberId,
            actorRole = actorRole,
            failureContext = "netting $nettingId",
        )
    }

    /**
     * Mahngebuehr (Debitor) -- Soll `receivables_account_id` / Haben the item's own
     * `contra_account_id`. Same configuration-degradation posture as [postItemCreation]: a
     * missing `receivables_account_id` mapping degrades rather than throws (the notice itself is
     * still recorded, just without a fee booking -- see
     * `network.lapis.cloud.server.openitem.dunning.ReceivableDunningEngine` KDoc).
     */
    fun postDunningFee(
        noticeId: Uuid,
        counterpartyName: String,
        feeAmount: BigDecimal,
        contraAccountId: Uuid,
        on: LocalDate,
        actorMemberId: Uuid,
        actorRole: AccountRole,
    ): OpenItemPostingOutcome {
        val settingsRow =
            OrganizationSettingsTable
                .selectAll()
                .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                .singleOrNull()
        val receivablesAccountId =
            settingsRow?.get(OrganizationSettingsTable.receivablesAccountId)
                ?: return OpenItemPostingOutcome.Failed("receivables_account_not_configured")

        val referencedAccountIds = listOf(receivablesAccountId, contraAccountId).distinct()
        val accountRows =
            referencedAccountIds.associateWith { accountId ->
                LedgerAccountTable.selectAll().where { LedgerAccountTable.id eq accountId }.singleOrNull()
            }
        val inactiveAccountIds =
            referencedAccountIds.filter { accountRows[it] == null || accountRows[it]?.get(LedgerAccountTable.active) == false }
        if (inactiveAccountIds.isNotEmpty()) {
            logger.warn {
                "OpenItemPostingBridge: dunning fee $noticeId, referenced LedgerAccount(s) $inactiveAccountIds missing/inactive -- no journal entry booked."
            }
            return OpenItemPostingOutcome.Failed("ledger_account_inactive")
        }

        return postEntry(
            debitAccountId = receivablesAccountId,
            creditAccountId = contraAccountId,
            amount = feeAmount,
            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
            on = on,
            description = "Mahngebuehr · $counterpartyName".take(JOURNAL_ENTRY_DESCRIPTION_MAX_LENGTH),
            voucherReference = "OI-DUNNING-$noticeId",
            actorMemberId = actorMemberId,
            actorRole = actorRole,
            failureContext = "dunning fee $noticeId",
        )
    }

    /**
     * Exact reversal of [originalJournalEntryId]'s own postings (every [PostingTable] row for that
     * entry, DEBIT/CREDIT swapped, same ledger account/amount/sphere) as its own, brand-new
     * [JournalEntryStatus.POSTED] entry -- GoBD forbids ever deleting/altering the original.
     * Swapping every side of an already-balanced entry yields another balanced entry by
     * construction (Sum(new debit) = Sum(old credit) = Sum(old debit) = Sum(new credit)), so this
     * never degrades for a balance reason; a missing original (data-integrity defect, should be
     * unreachable) is a [ConflictException], not a degrading case.
     */
    fun postReversal(
        originalJournalEntryId: Uuid,
        description: String,
        reason: String,
        on: LocalDate,
        actorMemberId: Uuid,
        actorRole: AccountRole,
    ): OpenItemPostingOutcome {
        val originalPostings = PostingTable.selectAll().where { PostingTable.journalEntryId eq originalJournalEntryId }.toList()
        if (originalPostings.isEmpty()) {
            throw ConflictException("OpenItemPostingBridge.postReversal: original journal entry $originalJournalEntryId has no postings")
        }

        val journalEntryId = Uuid.random()
        val fullDescription = "$description ($reason)".take(JOURNAL_ENTRY_DESCRIPTION_MAX_LENGTH)
        JournalEntryTable.insert {
            it[id] = journalEntryId
            it[JournalEntryTable.entryDate] = on
            it[JournalEntryTable.description] = fullDescription
            it[JournalEntryTable.voucherReference] = "REVERSAL-$originalJournalEntryId"
            it[createdBy] = actorMemberId
            it[status] = JournalEntryStatus.POSTED
            it[postedAt] = DbClock.nowLocalDateTime()
            it[createdAt] = DbClock.nowLocalDateTime()
            it[JournalEntryTable.donorMemberId] = null
            it[JournalEntryTable.externalDonorId] = null
            it[JournalEntryTable.donorCategory] = null
        }

        val postingSnapshots = mutableListOf<PostingSnapshot>()
        originalPostings.forEach { original ->
            val swappedSide = if (original[PostingTable.side] == PostingSide.DEBIT) PostingSide.CREDIT else PostingSide.DEBIT
            val ledgerAccountId = original[PostingTable.ledgerAccountId]
            val amount = original[PostingTable.amount]
            val sphere = original[PostingTable.sphere]
            PostingTable.insert {
                it[id] = Uuid.random()
                it[PostingTable.journalEntryId] = journalEntryId
                it[PostingTable.ledgerAccountId] = ledgerAccountId
                it[PostingTable.side] = swappedSide
                it[PostingTable.amount] = amount
                it[PostingTable.sphere] = sphere
                it[costCenterId] = null
            }
            postingSnapshots +=
                PostingSnapshot(
                    ledgerAccountId = ledgerAccountId.toString(),
                    side = swappedSide,
                    amount = amount,
                    sphere = sphere,
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
                        description = fullDescription,
                        voucherReference = "REVERSAL-$originalJournalEntryId",
                        status = JournalEntryStatus.POSTED,
                        postedAt = DbClock.nowLocalDateTime(),
                        createdBy = actorMemberId.toString(),
                        donorMemberId = null,
                        externalDonorId = null,
                        donorCategory = null,
                        postings = postingSnapshots,
                    ),
                ),
        )

        return OpenItemPostingOutcome.Posted(journalEntryId)
    }

    private fun degraded(
        reason: String,
        itemId: Uuid,
    ): OpenItemPostingOutcome {
        logger.warn { "OpenItemPostingBridge: item $itemId, $reason -- no journal entry was booked." }
        return OpenItemPostingOutcome.Failed(reason)
    }

    private fun postEntry(
        debitAccountId: Uuid,
        creditAccountId: Uuid,
        amount: BigDecimal,
        sphere: GemeinnuetzigkeitSphere,
        on: LocalDate,
        description: String,
        voucherReference: String,
        actorMemberId: Uuid,
        actorRole: AccountRole,
        failureContext: String,
    ): OpenItemPostingOutcome {
        // Welle V1.4.22 Audit-Nachtrag (MINOR-d): debit == credit is a balanced entry that moves
        // nothing and silently closes whatever it was booked for -- exactly the staging finding
        // (settling against the receivables account). That call path is now rejected before it gets
        // here, and `updateOrganizationSettings` no longer accepts the configurations that could
        // produce it, so this is a tripwire for a code/data defect: same tier as a failing
        // [JournalEntryBalance.validateBalanced] (ConflictException, rolls the caller back), NOT a
        // degrading `Failed(reason)` -- there is no configuration a treasurer could fix in response.
        if (debitAccountId == creditAccountId) {
            throw ConflictException(
                "OpenItemPostingBridge.postEntry: debit and credit account are the same LedgerAccount " +
                    "$debitAccountId ($failureContext) -- refusing to book an entry that moves nothing",
            )
        }
        val postingInputs =
            listOf(
                PostingInput(ledgerAccountId = debitAccountId.toString(), side = PostingSide.DEBIT, amount = amount, sphere = sphere),
                PostingInput(ledgerAccountId = creditAccountId.toString(), side = PostingSide.CREDIT, amount = amount, sphere = sphere),
            )
        val balance = JournalEntryBalance.validateBalanced(postingInputs)
        if (!balance.balanced) throw ConflictException(balance.reason ?: "Journal entry not balanced ($failureContext)")

        val cashAccountIds = CashRegisterGuard.loadCashRegisterAccountIds(listOf(debitAccountId, creditAccountId))
        try {
            CashRegisterGuard.requireVoucherForCashPostings(voucherReference = voucherReference, cashAccountIds = cashAccountIds)
            CashRegisterGuard.requireNonNegativeCashBalances(postings = postingInputs, cashAccountIds = cashAccountIds)
        } catch (e: ConflictException) {
            val reason =
                if (e.message.contains(
                        "voucherReference",
                        ignoreCase = true,
                    )
                ) {
                    "cash_voucher_required"
                } else {
                    "cash_register_balance_insufficient"
                }
            logger.warn { "OpenItemPostingBridge: $failureContext, cash-register guard rejected the booking ($reason): ${e.message}" }
            return OpenItemPostingOutcome.Failed(reason)
        }

        val journalEntryId = Uuid.random()
        val now = DbClock.nowLocalDateTime()
        JournalEntryTable.insert {
            it[id] = journalEntryId
            it[JournalEntryTable.entryDate] = on
            it[JournalEntryTable.description] = description
            it[JournalEntryTable.voucherReference] = voucherReference
            it[createdBy] = actorMemberId
            it[status] = JournalEntryStatus.POSTED
            it[postedAt] = now
            it[createdAt] = now
            it[JournalEntryTable.donorMemberId] = null
            it[JournalEntryTable.externalDonorId] = null
            it[JournalEntryTable.donorCategory] = null
        }

        val postingSnapshots = mutableListOf<PostingSnapshot>()

        fun insertPosting(
            ledgerAccountId: Uuid,
            side: PostingSide,
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
        insertPosting(debitAccountId, PostingSide.DEBIT)
        insertPosting(creditAccountId, PostingSide.CREDIT)

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
                        entryDate = on,
                        description = description,
                        voucherReference = voucherReference,
                        status = JournalEntryStatus.POSTED,
                        postedAt = now,
                        createdBy = actorMemberId.toString(),
                        donorMemberId = null,
                        externalDonorId = null,
                        donorCategory = null,
                        postings = postingSnapshots,
                    ),
                ),
        )

        return OpenItemPostingOutcome.Posted(journalEntryId)
    }
}
