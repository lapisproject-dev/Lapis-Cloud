package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.CrmContactTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.OpenItemNettingTable
import network.lapis.cloud.server.db.generated.OpenItemSettlementTable
import network.lapis.cloud.server.db.generated.OpenItemTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.ReceivableDunningNoticeTable
import network.lapis.cloud.server.openitem.dunning.ReceivableDunningEngine
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.CounterpartyDefaultsDto
import network.lapis.cloud.shared.domain.CounterpartyKey
import network.lapis.cloud.shared.domain.NettingCandidateDto
import network.lapis.cloud.shared.domain.NettingPreviewDto
import network.lapis.cloud.shared.domain.OpenItemAgingBucket
import network.lapis.cloud.shared.domain.OpenItemAgingBucketDto
import network.lapis.cloud.shared.domain.OpenItemDetailDto
import network.lapis.cloud.shared.domain.OpenItemDirection
import network.lapis.cloud.shared.domain.OpenItemDto
import network.lapis.cloud.shared.domain.OpenItemInput
import network.lapis.cloud.shared.domain.OpenItemNettingDto
import network.lapis.cloud.shared.domain.OpenItemNettingSnapshot
import network.lapis.cloud.shared.domain.OpenItemSettlementDto
import network.lapis.cloud.shared.domain.OpenItemSettlementKind
import network.lapis.cloud.shared.domain.OpenItemSnapshot
import network.lapis.cloud.shared.domain.OpenItemStatus
import network.lapis.cloud.shared.domain.OpenItemStatusSets
import network.lapis.cloud.shared.domain.OpenItemSummaryDto
import network.lapis.cloud.shared.domain.ReceivableDunningNoticeDto
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IOpenItemService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

private val OPEN_ITEM_READ_ROLES = arrayOf(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)
private val OPEN_ITEM_WRITE_ROLES = arrayOf(AccountRole.TREASURER, AccountRole.ADMIN)
private const val MAX_LIST_RESULTS = 200
private const val MAX_COUNTERPARTY_NAME_LENGTH = 200
private const val MAX_AMOUNT_SCALE = 2

/**
 * Security fix (MINOR, second review pass): every `amount` on this subledger lands in a
 * `numeric(12,2)` column (`open_item.amount`, `open_item_settlement.amount`,
 * `open_item_netting.amount`), so anything at or above 10^10 is a DB overflow -- an unhandled HTTP
 * 500 instead of a clean 400. Worse, `scale() > MAX_AMOUNT_SCALE` does NOT catch it: the client sends
 * `Decimal` as a JSON double, so "99999999999999999999,99" arrives as `BigDecimal("1.0E20")` whose
 * scale is **-19**, passing both the scale and the `<= ZERO` check. One billion is far above any
 * plausible Verein/Partei invoice and two full orders of magnitude below the column's own ceiling.
 * Mirrored (loosely, never as the security boundary) by `MAX_OPEN_ITEM_AMOUNT` in the client's
 * `OpenItemFormValidation.kt`.
 */
private val MAX_AMOUNT: BigDecimal = BigDecimal("1000000000.00")

/**
 * The ONE amount pre-check of this file -- positive, at most [MAX_AMOUNT_SCALE] fractional digits,
 * at most [MAX_AMOUNT]. `stripTrailingZeros()` first, so "12.3400" (scale 4, value exact to two
 * digits) is accepted while "12.345" is not, and so a negative-scale value like `1.0E20` cannot slip
 * past the scale check either (see [MAX_AMOUNT] KDoc).
 */
private fun requireValidAmount(
    amount: BigDecimal,
    fieldName: String = "amount",
) {
    if (amount <= BigDecimal.ZERO) throw BadRequestException("$fieldName must be positive")
    if (amount.stripTrailingZeros().scale() > MAX_AMOUNT_SCALE) {
        throw BadRequestException("$fieldName must have at most $MAX_AMOUNT_SCALE fractional digits")
    }
    if (amount > MAX_AMOUNT) throw BadRequestException("$fieldName must be at most ${MAX_AMOUNT.toPlainString()}")
}

/** Matches `reference VARCHAR(100)` in V35__open_items.sql. */
private const val MAX_REFERENCE_LENGTH = 100

/** Matches `note VARCHAR(1000)` in V35__open_items.sql. */
private const val MAX_NOTE_LENGTH = 1000

/** Matches every `*_reason`/`posting_error` VARCHAR(500) column in V35__open_items.sql. */
private const val MAX_REASON_LENGTH = 500

/**
 * Security fix (MINOR, first review pass): `reference`/`note`/`reason` previously had no
 * upper-bound length check (only `counterpartyName` did) -- an oversized value would hit the
 * DB's `VARCHAR(N)` constraint and surface as an unhandled 500 instead of a clean 400. Same
 * shape as [network.lapis.cloud.server.rpc.SocialNetworkService]'s `requireDecisionNoteLength`/
 * [network.lapis.cloud.server.rpc.MemberHonorService]'s inline length checks.
 */
private fun requireMaxLength(
    value: String?,
    max: Int,
    fieldName: String,
) {
    if (value != null && value.length > max) {
        throw BadRequestException("$fieldName must be at most $max characters")
    }
}

private const val AGING_DAYS_1_30_MAX = 30
private const val AGING_DAYS_31_90_MAX = 90

/** Practical cap for the full-table netting-candidate scan -- see [OpenItemService.findAllNettingCandidates] KDoc. */
private const val NETTING_CANDIDATE_SCAN_CAP = 5000
private val logger = KotlinLogging.logger {}

/**
 * Implements [IOpenItemService] -- Welle V1.4.15 "Kreditoren-/Debitorenbuchhaltung". Mirrors the
 * framework conventions [TravelExpenseService]/[network.lapis.cloud.server.payment.sepa.SepaService]
 * already establish:
 *
 * 1. Role gate BEFORE any lookup.
 * 2. Every write opens exactly ONE `transaction {}` and takes `SELECT ... FOR UPDATE` on the
 *    affected `open_item` row(s) FIRST.
 * 3. [AuditLogRecorder.record] is always the LAST lock-taking operation of that transaction.
 * 4. [NotFoundException] for a foreign/malformed id, [ConflictException] for a state violation.
 *
 * **`openAmount` is NEVER stored** -- see [OpenItemMath.openAmount] KDoc. `status` IS materialized
 * on every write (so `listOpenItems`/`getOpenItemSummary` can filter/sort in SQL), but it is never
 * itself the input to an arithmetic decision -- every settle/netting amount check re-derives
 * `openAmount` from `amount` and the live settlement rows.
 */
class OpenItemService(
    private val call: ApplicationCall,
) : IOpenItemService {
    override suspend fun getOpenItemSummary(direction: OpenItemDirection?): OpenItemSummaryDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*OPEN_ITEM_READ_ROLES)
        return transaction {
            val asOf = DbClock.nowLocalDateTime().date
            val payableBuckets = aggregateBuckets(direction = OpenItemDirection.PAYABLE, asOf = asOf)
            val receivableBuckets = aggregateBuckets(direction = OpenItemDirection.RECEIVABLE, asOf = asOf)
            val settingsRow =
                OrganizationSettingsTable
                    .selectAll()
                    .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                    .singleOrNull()
            val accountsConfigured =
                settingsRow?.get(OrganizationSettingsTable.receivablesAccountId) != null &&
                    settingsRow[OrganizationSettingsTable.payablesAccountId] != null
            val candidates = findAllNettingCandidates(asOf)
            OpenItemSummaryDto(
                asOf = asOf,
                payableBuckets = payableBuckets,
                receivableBuckets = receivableBuckets,
                payableOpenTotal = payableBuckets.fold(BigDecimal.ZERO) { acc, b -> acc + b.totalAmount },
                receivableOpenTotal = receivableBuckets.fold(BigDecimal.ZERO) { acc, b -> acc + b.totalAmount },
                nettingCandidateCounterpartyCount = candidates.size,
                accountsConfigured = accountsConfigured,
            )
        }
    }

    override suspend fun listOpenItems(
        direction: OpenItemDirection?,
        onlyOpen: Boolean,
        limit: Int,
        afterDueDate: LocalDate?,
        afterOpenItemId: String?,
    ): List<OpenItemDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*OPEN_ITEM_READ_ROLES)
        val effectiveLimit = limit.coerceIn(1, MAX_LIST_RESULTS)
        val afterId = afterOpenItemId?.toOpenItemUuid("afterOpenItemId")
        return transaction {
            val asOf = DbClock.nowLocalDateTime().date
            var condition: Op<Boolean> = Op.TRUE
            if (direction != null) condition = condition and (OpenItemTable.direction eq direction)
            if (onlyOpen) condition = condition and (OpenItemTable.status inList OpenItemStatusSets.SETTLEABLE.toList())
            if (afterDueDate != null && afterId != null) {
                condition =
                    condition and
                    (
                        (OpenItemTable.dueDate greater afterDueDate) or
                            ((OpenItemTable.dueDate eq afterDueDate) and (OpenItemTable.id greater afterId))
                    )
            }
            // Sort "daysOverdue DESC, dueDate ASC, id ASC" collapses to a plain "dueDate ASC, id
            // ASC" against a single shared asOf: every overdue row's dueDate < asOf, every not-due
            // row's dueDate >= asOf, so sorting purely by dueDate already interleaves them in
            // exactly the required order -- see OpenItemService KDoc / idx_oi_keyset.
            val rows =
                OpenItemTable
                    .selectAll()
                    .where { condition }
                    .orderBy(OpenItemTable.dueDate to SortOrder.ASC, OpenItemTable.id to SortOrder.ASC)
                    .limit(effectiveLimit)
                    .toList()
            val accountInfo = loadAccountInfo(rows.map { it[OpenItemTable.contraAccountId] })
            val settlementsByItem = loadActiveSettlements(rows.map { it[OpenItemTable.id] })
            // Batch-loaded exactly like the two lines above (two queries for the whole page, never
            // one per row) -- see ReceivableDunningEngine.loadDunningProgress KDoc.
            val dunningByItem = ReceivableDunningEngine.loadDunningProgress(rows)
            rows.map { row ->
                row.toOpenItemDto(
                    asOf = asOf,
                    accountInfo = accountInfo,
                    activeSettlements = settlementsByItem[row[OpenItemTable.id]].orEmpty(),
                    dunningProgress = dunningByItem[row[OpenItemTable.id]],
                )
            }
        }
    }

    override suspend fun getOpenItem(openItemId: String): List<OpenItemDetailDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*OPEN_ITEM_READ_ROLES)
        val id = openItemId.toOpenItemUuid("openItemId")
        return transaction {
            val row = OpenItemTable.selectAll().where { OpenItemTable.id eq id }.singleOrNull() ?: return@transaction emptyList()
            listOf(loadOpenItemDetail(row[OpenItemTable.id]))
        }
    }

    override suspend fun createOpenItem(input: OpenItemInput): OpenItemDetailDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*OPEN_ITEM_WRITE_ROLES)
        val counterpartyName = input.counterpartyName.trim()
        if (counterpartyName.isEmpty() || counterpartyName.length > MAX_COUNTERPARTY_NAME_LENGTH) {
            throw BadRequestException("counterpartyName must be 1..$MAX_COUNTERPARTY_NAME_LENGTH characters after trim")
        }
        requireValidAmount(amount = input.amount)
        if (input.dueDate < input.itemDate) throw BadRequestException("dueDate must not be before itemDate")
        requireMaxLength(value = input.reference, max = MAX_REFERENCE_LENGTH, fieldName = "reference")
        requireMaxLength(value = input.note, max = MAX_NOTE_LENGTH, fieldName = "note")
        val contraAccountId = input.contraAccountId.toOpenItemUuid("contraAccountId")
        val crmContactId = input.crmContactId?.toOpenItemUuid("crmContactId")

        return transaction {
            LedgerAccountTable.selectAll().where { LedgerAccountTable.id eq contraAccountId }.singleOrNull()
                ?: throw NotFoundException("LedgerAccount $contraAccountId (contraAccountId) not found")
            if (crmContactId != null) {
                CrmContactTable.selectAll().where { CrmContactTable.id eq crmContactId }.singleOrNull()
                    ?: throw NotFoundException("CrmContact $crmContactId not found")
            }

            val itemId = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            val counterpartyKey = CounterpartyKey.of(counterpartyName)

            OpenItemTable.insert {
                it[id] = itemId
                it[OpenItemTable.direction] = input.direction
                it[OpenItemTable.counterpartyName] = counterpartyName
                it[OpenItemTable.counterpartyKey] = counterpartyKey
                it[OpenItemTable.crmContactId] = crmContactId
                it[reference] = input.reference
                it[itemDate] = input.itemDate
                it[dueDate] = input.dueDate
                it[amount] = input.amount
                it[OpenItemTable.contraAccountId] = contraAccountId
                it[sphere] = input.sphere
                it[status] = OpenItemStatus.OPEN
                it[note] = input.note
                it[createdByMemberId] = current.memberId
                it[createdAt] = now
            }

            val outcome =
                OpenItemPostingBridge.postItemCreation(
                    itemId = itemId,
                    direction = input.direction,
                    counterpartyName = counterpartyName,
                    reference = input.reference,
                    amount = input.amount,
                    contraAccountId = contraAccountId,
                    sphere = input.sphere,
                    itemDate = input.itemDate,
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                )
            applyOpenItemPostingOutcome(itemId = itemId, outcome = outcome)

            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.OPEN_ITEM,
                entityId = itemId,
                action = AuditAction.CREATE,
                before = null,
                after =
                    Json.encodeToString(
                        OpenItemSnapshot.serializer(),
                        OpenItemSnapshot(
                            openItemId = itemId.toString(),
                            direction = input.direction,
                            counterpartyKey = counterpartyKey,
                            status = OpenItemStatus.OPEN,
                            amount = input.amount,
                            creationJournalEntryId = (outcome as? OpenItemPostingOutcome.Posted)?.journalEntryId?.toString(),
                            creationPostingError = (outcome as? OpenItemPostingOutcome.Failed)?.reason,
                        ),
                    ),
            )

            loadOpenItemDetail(itemId)
        }
    }

    override suspend fun updateOpenItemMetadata(
        openItemId: String,
        reference: String?,
        note: String?,
    ): OpenItemDetailDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*OPEN_ITEM_WRITE_ROLES)
        requireMaxLength(value = reference, max = MAX_REFERENCE_LENGTH, fieldName = "reference")
        requireMaxLength(value = note, max = MAX_NOTE_LENGTH, fieldName = "note")
        val id = openItemId.toOpenItemUuid("openItemId")
        return transaction {
            val row =
                OpenItemTable
                    .selectAll()
                    .where { OpenItemTable.id eq id }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("OpenItem $id not found")
            if (row[OpenItemTable.status] ==
                OpenItemStatus.CANCELLED
            ) {
                throw ConflictException("OpenItem $id is CANCELLED, metadata is immutable")
            }
            OpenItemTable.update({ OpenItemTable.id eq id }) {
                it[OpenItemTable.reference] = reference
                it[OpenItemTable.note] = note
            }
            // Security fix (MINOR, first review pass): every other write path in this file audits
            // itself -- this one previously did not, leaving a financial-subledger metadata
            // mutation untraceable (who changed the reference/note, and to what).
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.OPEN_ITEM,
                entityId = id,
                action = AuditAction.UPDATE,
                before = Json.encodeToString(OpenItemSnapshot.serializer(), row.toOpenItemSnapshot()),
                after =
                    Json.encodeToString(
                        OpenItemSnapshot.serializer(),
                        row.toOpenItemSnapshot().copy(reference = reference, note = note),
                    ),
            )
            loadOpenItemDetail(id)
        }
    }

    override suspend fun cancelOpenItem(
        openItemId: String,
        reason: String,
    ): OpenItemDetailDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*OPEN_ITEM_WRITE_ROLES)
        if (reason.isBlank()) throw BadRequestException("reason must not be blank")
        requireMaxLength(value = reason, max = MAX_REASON_LENGTH, fieldName = "reason")
        val id = openItemId.toOpenItemUuid("openItemId")
        return transaction {
            val row =
                OpenItemTable
                    .selectAll()
                    .where { OpenItemTable.id eq id }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("OpenItem $id not found")
            val status = row[OpenItemTable.status]
            if (status !in
                OpenItemStatusSets.SETTLEABLE
            ) {
                throw ConflictException("OpenItem $id is $status, only OPEN/PARTIALLY_SETTLED may be cancelled")
            }
            val activeSettlements = loadActiveSettlements(listOf(id))[id].orEmpty()
            if (activeSettlements.isNotEmpty()) {
                throw ConflictException(
                    "OpenItem $id has active settlements, cannot cancel -- reverse them first",
                )
            }

            var cancellationJournalEntryId: Uuid? = null
            val creationJournalEntryId = row[OpenItemTable.creationJournalEntryId]
            if (creationJournalEntryId != null) {
                val outcome =
                    OpenItemPostingBridge.postReversal(
                        originalJournalEntryId = creationJournalEntryId,
                        description = "Storno offener Posten",
                        reason = reason,
                        on = DbClock.nowLocalDateTime().date,
                        actorMemberId = current.memberId,
                        actorRole = current.role,
                    )
                cancellationJournalEntryId = (outcome as OpenItemPostingOutcome.Posted).journalEntryId
            }

            val now = DbClock.nowLocalDateTime()
            OpenItemTable.update({ OpenItemTable.id eq id }) {
                it[OpenItemTable.status] = OpenItemStatus.CANCELLED
                it[cancelledAt] = now
                it[cancelledByMemberId] = current.memberId
                it[cancellationReason] = reason
                it[OpenItemTable.cancellationJournalEntryId] = cancellationJournalEntryId
            }

            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.OPEN_ITEM,
                entityId = id,
                action = AuditAction.UPDATE,
                before = Json.encodeToString(OpenItemSnapshot.serializer(), row.toOpenItemSnapshot()),
                after =
                    Json.encodeToString(
                        OpenItemSnapshot.serializer(),
                        row.toOpenItemSnapshot().copy(status = OpenItemStatus.CANCELLED),
                    ),
            )
            loadOpenItemDetail(id)
        }
    }

    override suspend fun settleOpenItem(
        openItemId: String,
        amount: BigDecimal,
        settledOn: LocalDate,
        bankAccountId: String?,
    ): OpenItemDetailDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*OPEN_ITEM_WRITE_ROLES)
        requireValidAmount(amount = amount)
        val id = openItemId.toOpenItemUuid("openItemId")
        val explicitBankAccountId = bankAccountId?.toOpenItemUuid("bankAccountId")

        return transaction {
            val row =
                OpenItemTable
                    .selectAll()
                    .where { OpenItemTable.id eq id }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("OpenItem $id not found")
            val status = row[OpenItemTable.status]
            if (status !in OpenItemStatusSets.SETTLEABLE) throw ConflictException("OpenItem $id is $status, cannot be settled")
            val creationJournalEntryId =
                row[OpenItemTable.creationJournalEntryId]
                    ?: throw ConflictException("OpenItem $id is not booked yet -- call retryOpenItemPosting first")

            val activeSettlements = loadActiveSettlements(listOf(id))[id].orEmpty()
            val openAmount =
                OpenItemMath.openAmount(
                    amount = row[OpenItemTable.amount],
                    activeSettlementAmounts = activeSettlements.map { it.amount },
                )
            if (amount > openAmount) throw ConflictException("amount $amount exceeds openAmount $openAmount for OpenItem $id")

            val settingsRow =
                OrganizationSettingsTable
                    .selectAll()
                    .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                    .singleOrNull()
            val effectiveBankAccountId =
                explicitBankAccountId ?: settingsRow?.get(OrganizationSettingsTable.paymentBankAccountId)
                    ?: throw ConflictException("no bankAccountId given and organization_settings.payment_bank_account_id is not configured")

            val settlementId = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            OpenItemSettlementTable.insert {
                it[OpenItemSettlementTable.id] = settlementId
                it[OpenItemSettlementTable.openItemId] = id
                it[kind] = OpenItemSettlementKind.PAYMENT
                it[OpenItemSettlementTable.amount] = amount
                it[OpenItemSettlementTable.settledOn] = settledOn
                it[nettingId] = null
                it[createdByMemberId] = current.memberId
                it[createdAt] = now
            }

            val direction = row[OpenItemTable.direction]
            val outcome =
                OpenItemPostingBridge.postSettlement(
                    settlementId = settlementId,
                    direction = direction,
                    counterpartyName = row[OpenItemTable.counterpartyName],
                    reference = row[OpenItemTable.reference],
                    amount = amount,
                    bankAccountId = effectiveBankAccountId,
                    settledOn = settledOn,
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                )
            when (outcome) {
                is OpenItemPostingOutcome.Posted ->
                    OpenItemSettlementTable.update({ OpenItemSettlementTable.id eq settlementId }) {
                        it[journalEntryId] =
                            outcome.journalEntryId
                    }
                is OpenItemPostingOutcome.Failed ->
                    OpenItemSettlementTable.update({ OpenItemSettlementTable.id eq settlementId }) { it[postingError] = outcome.reason }
            }

            val newOpenAmount = openAmount - amount
            val newStatus = OpenItemMath.deriveStatus(amount = row[OpenItemTable.amount], openAmount = newOpenAmount, cancelled = false)
            OpenItemTable.update({ OpenItemTable.id eq id }) { it[OpenItemTable.status] = newStatus }

            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.OPEN_ITEM,
                entityId = id,
                action = AuditAction.UPDATE,
                before = Json.encodeToString(OpenItemSnapshot.serializer(), row.toOpenItemSnapshot()),
                after = Json.encodeToString(OpenItemSnapshot.serializer(), row.toOpenItemSnapshot().copy(status = newStatus)),
            )
            loadOpenItemDetail(id)
        }
    }

    override suspend fun reverseSettlement(
        settlementId: String,
        reason: String,
    ): OpenItemDetailDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*OPEN_ITEM_WRITE_ROLES)
        if (reason.isBlank()) throw BadRequestException("reason must not be blank")
        requireMaxLength(value = reason, max = MAX_REASON_LENGTH, fieldName = "reason")
        val id = settlementId.toOpenItemUuid("settlementId")
        return transaction {
            val settlementRow =
                OpenItemSettlementTable
                    .selectAll()
                    .where { OpenItemSettlementTable.id eq id }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("OpenItemSettlement $id not found")
            if (settlementRow[OpenItemSettlementTable.reversedAt] !=
                null
            ) {
                throw ConflictException("OpenItemSettlement $id is already reversed")
            }
            if (settlementRow[OpenItemSettlementTable.kind] != OpenItemSettlementKind.PAYMENT) {
                throw ConflictException("OpenItemSettlement $id is a NETTING settlement -- reverse via reverseNetting instead")
            }
            val journalEntryId =
                settlementRow[OpenItemSettlementTable.journalEntryId]
                    ?: throw ConflictException("OpenItemSettlement $id was never booked, nothing to reverse")
            val openItemId = settlementRow[OpenItemSettlementTable.openItemId]
            val itemRow =
                OpenItemTable
                    .selectAll()
                    .where { OpenItemTable.id eq openItemId }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("OpenItem $openItemId not found")

            val outcome =
                OpenItemPostingBridge.postReversal(
                    originalJournalEntryId = journalEntryId,
                    description = "Storno Zahlung offener Posten",
                    reason = reason,
                    on = DbClock.nowLocalDateTime().date,
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                )
            val reversalJournalEntryId = (outcome as OpenItemPostingOutcome.Posted).journalEntryId

            val now = DbClock.nowLocalDateTime()
            OpenItemSettlementTable.update({ OpenItemSettlementTable.id eq id }) {
                it[reversedAt] = now
                it[reversedByMemberId] = current.memberId
                it[reversalReason] = reason
                it[OpenItemSettlementTable.reversalJournalEntryId] = reversalJournalEntryId
            }

            val activeSettlements = loadActiveSettlements(listOf(openItemId))[openItemId].orEmpty()
            val newOpenAmount =
                OpenItemMath
                    .openAmount(
                        amount = itemRow[OpenItemTable.amount],
                        activeSettlementAmounts =
                            activeSettlements.map {
                                it.amount
                            },
                    )
            val newStatus =
                if (itemRow[OpenItemTable.status] == OpenItemStatus.CANCELLED) {
                    OpenItemStatus.CANCELLED
                } else {
                    OpenItemMath.deriveStatus(amount = itemRow[OpenItemTable.amount], openAmount = newOpenAmount, cancelled = false)
                }
            OpenItemTable.update({ OpenItemTable.id eq openItemId }) { it[status] = newStatus }

            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.OPEN_ITEM,
                entityId = openItemId,
                action = AuditAction.UPDATE,
                before = Json.encodeToString(OpenItemSnapshot.serializer(), itemRow.toOpenItemSnapshot()),
                after = Json.encodeToString(OpenItemSnapshot.serializer(), itemRow.toOpenItemSnapshot().copy(status = newStatus)),
            )
            loadOpenItemDetail(openItemId)
        }
    }

    override suspend fun retryOpenItemPosting(openItemId: String): OpenItemDetailDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*OPEN_ITEM_WRITE_ROLES)
        val id = openItemId.toOpenItemUuid("openItemId")
        return transaction {
            val row =
                OpenItemTable
                    .selectAll()
                    .where { OpenItemTable.id eq id }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("OpenItem $id not found")
            if (row[OpenItemTable.creationJournalEntryId] != null) return@transaction loadOpenItemDetail(id)
            // A cancelled/settled item never got (and must never subsequently get) a creation posting:
            // cancelOpenItem posts no reversal when creationJournalEntryId is null and cannot run twice,
            // so a late creation posting would leave a liability in the journal with no counter-entry.
            val currentStatus = row[OpenItemTable.status]
            if (currentStatus in OpenItemStatusSets.CLOSED) {
                throw ConflictException("OpenItem $id is $currentStatus, posting can no longer be retried")
            }
            val outcome =
                OpenItemPostingBridge.postItemCreation(
                    itemId = id,
                    direction = row[OpenItemTable.direction],
                    counterpartyName = row[OpenItemTable.counterpartyName],
                    reference = row[OpenItemTable.reference],
                    amount = row[OpenItemTable.amount],
                    contraAccountId = row[OpenItemTable.contraAccountId],
                    sphere = row[OpenItemTable.sphere],
                    itemDate = row[OpenItemTable.itemDate],
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                )
            applyOpenItemPostingOutcome(itemId = id, outcome = outcome)
            // Security fix (MINOR, first review pass): createOpenItem audits BOTH outcomes
            // (embedding creationPostingError specifically so two consecutive failed retries are
            // not byte-identical, see OpenItemSnapshot KDoc) -- this retry path previously only
            // audited the Posted branch, leaving a failed retry attempt (a real, actionable event
            // for a treasurer diagnosing a stuck posting) with no audit trail at all.
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.OPEN_ITEM,
                entityId = id,
                action = AuditAction.UPDATE,
                before = Json.encodeToString(OpenItemSnapshot.serializer(), row.toOpenItemSnapshot()),
                after =
                    Json.encodeToString(
                        OpenItemSnapshot.serializer(),
                        row.toOpenItemSnapshot().copy(
                            creationJournalEntryId = (outcome as? OpenItemPostingOutcome.Posted)?.journalEntryId?.toString(),
                            creationPostingError = (outcome as? OpenItemPostingOutcome.Failed)?.reason,
                        ),
                    ),
            )
            loadOpenItemDetail(id)
        }
    }

    override suspend fun retrySettlementPosting(settlementId: String): OpenItemDetailDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*OPEN_ITEM_WRITE_ROLES)
        val id = settlementId.toOpenItemUuid("settlementId")
        return transaction {
            val settlementRow =
                OpenItemSettlementTable
                    .selectAll()
                    .where { OpenItemSettlementTable.id eq id }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("OpenItemSettlement $id not found")
            val openItemId = settlementRow[OpenItemSettlementTable.openItemId]
            if (settlementRow[OpenItemSettlementTable.journalEntryId] != null) return@transaction loadOpenItemDetail(openItemId)
            val itemRow =
                OpenItemTable.selectAll().where { OpenItemTable.id eq openItemId }.singleOrNull()
                    ?: throw NotFoundException("OpenItem $openItemId not found")
            val settingsRow =
                OrganizationSettingsTable
                    .selectAll()
                    .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                    .singleOrNull()
            val bankAccountId =
                settingsRow?.get(OrganizationSettingsTable.paymentBankAccountId)
                    ?: throw ConflictException("organization_settings.payment_bank_account_id is not configured")
            val outcome =
                OpenItemPostingBridge.postSettlement(
                    settlementId = id,
                    direction = itemRow[OpenItemTable.direction],
                    counterpartyName = itemRow[OpenItemTable.counterpartyName],
                    reference = itemRow[OpenItemTable.reference],
                    amount = settlementRow[OpenItemSettlementTable.amount],
                    bankAccountId = bankAccountId,
                    settledOn = settlementRow[OpenItemSettlementTable.settledOn],
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                )
            when (outcome) {
                is OpenItemPostingOutcome.Posted ->
                    OpenItemSettlementTable.update({ OpenItemSettlementTable.id eq id }) {
                        it[journalEntryId] = outcome.journalEntryId
                        it[postingError] = null
                    }
                is OpenItemPostingOutcome.Failed ->
                    OpenItemSettlementTable.update({ OpenItemSettlementTable.id eq id }) { it[postingError] = outcome.reason }
            }
            loadOpenItemDetail(openItemId)
        }
    }

    // ── Netting ─────────────────────────────────────────────────────────────────────

    override suspend fun listNettingCandidates(limit: Int): List<NettingCandidateDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*OPEN_ITEM_READ_ROLES)
        val effectiveLimit = limit.coerceIn(1, MAX_LIST_RESULTS)
        return transaction {
            val asOf = DbClock.nowLocalDateTime().date
            findAllNettingCandidates(asOf).take(effectiveLimit)
        }
    }

    override suspend fun previewNetting(
        payableItemId: String,
        receivableItemId: String,
        amount: BigDecimal,
    ): List<NettingPreviewDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*OPEN_ITEM_WRITE_ROLES)
        val payableId = payableItemId.toOpenItemUuid("payableItemId")
        val receivableId = receivableItemId.toOpenItemUuid("receivableItemId")
        return transaction {
            val (payableRow, receivableRow) = loadNettingPair(payableId = payableId, receivableId = receivableId)
            val payableOpen =
                OpenItemMath.openAmount(
                    amount = payableRow[OpenItemTable.amount],
                    activeSettlementAmounts =
                        loadActiveSettlements(
                            listOf(payableId),
                        )[payableId].orEmpty().map {
                            it.amount
                        },
                )
            val receivableOpen =
                OpenItemMath.openAmount(
                    amount = receivableRow[OpenItemTable.amount],
                    activeSettlementAmounts =
                        loadActiveSettlements(
                            listOf(receivableId),
                        )[receivableId].orEmpty().map {
                            it.amount
                        },
                )
            requireNettableAmount(amount = amount, payableOpen = payableOpen, receivableOpen = receivableOpen)

            val settingsRow =
                OrganizationSettingsTable.selectAll().where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }.singleOrNull()
            val payablesAccountId = settingsRow?.get(OrganizationSettingsTable.payablesAccountId)
            val receivablesAccountId = settingsRow?.get(OrganizationSettingsTable.receivablesAccountId)
            val accountInfo = loadAccountInfo(listOfNotNull(payablesAccountId, receivablesAccountId))
            val debit = payablesAccountId?.let { accountInfo[it] }
            val credit = receivablesAccountId?.let { accountInfo[it] }

            listOf(
                NettingPreviewDto(
                    debitAccountNumber = debit?.first ?: "?",
                    debitAccountName = debit?.second ?: "(nicht konfiguriert)",
                    creditAccountNumber = credit?.first ?: "?",
                    creditAccountName = credit?.second ?: "(nicht konfiguriert)",
                    amount = amount,
                    payableOpenAmountAfter = payableOpen - amount,
                    receivableOpenAmountAfter = receivableOpen - amount,
                    payableStatusAfter =
                        OpenItemMath.deriveStatus(
                            amount = payableRow[OpenItemTable.amount],
                            openAmount = payableOpen - amount,
                            cancelled = false,
                        ),
                    receivableStatusAfter =
                        OpenItemMath.deriveStatus(
                            amount = receivableRow[OpenItemTable.amount],
                            openAmount = receivableOpen - amount,
                            cancelled = false,
                        ),
                ),
            )
        }
    }

    override suspend fun executeNetting(
        payableItemId: String,
        receivableItemId: String,
        amount: BigDecimal,
    ): OpenItemNettingDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*OPEN_ITEM_WRITE_ROLES)
        val payableId = payableItemId.toOpenItemUuid("payableItemId")
        val receivableId = receivableItemId.toOpenItemUuid("receivableItemId")
        return transaction {
            val (payableRow, receivableRow) = loadNettingPair(payableId = payableId, receivableId = receivableId)
            val payableOpen =
                OpenItemMath.openAmount(
                    amount = payableRow[OpenItemTable.amount],
                    activeSettlementAmounts =
                        loadActiveSettlements(
                            listOf(payableId),
                        )[payableId].orEmpty().map {
                            it.amount
                        },
                )
            val receivableOpen =
                OpenItemMath.openAmount(
                    amount = receivableRow[OpenItemTable.amount],
                    activeSettlementAmounts =
                        loadActiveSettlements(
                            listOf(receivableId),
                        )[receivableId].orEmpty().map {
                            it.amount
                        },
                )
            requireNettableAmount(amount = amount, payableOpen = payableOpen, receivableOpen = receivableOpen)

            val nettingId = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            val today = now.date
            val counterpartyKey = payableRow[OpenItemTable.counterpartyKey]
            val crmContactId = payableRow[OpenItemTable.crmContactId] ?: receivableRow[OpenItemTable.crmContactId]

            OpenItemNettingTable.insert {
                it[id] = nettingId
                it[OpenItemNettingTable.counterpartyKey] = counterpartyKey
                it[OpenItemNettingTable.crmContactId] = crmContactId
                it[OpenItemNettingTable.amount] = amount
                it[OpenItemNettingTable.payableItemId] = payableId
                it[OpenItemNettingTable.receivableItemId] = receivableId
                it[createdByMemberId] = current.memberId
                it[createdAt] = now
            }
            listOf(payableId, receivableId).forEach { itemId ->
                OpenItemSettlementTable.insert {
                    it[id] = Uuid.random()
                    it[openItemId] = itemId
                    it[kind] = OpenItemSettlementKind.NETTING
                    it[OpenItemSettlementTable.amount] = amount
                    it[settledOn] = today
                    it[OpenItemSettlementTable.nettingId] = nettingId
                    it[createdByMemberId] = current.memberId
                    it[createdAt] = now
                }
            }

            val outcome =
                OpenItemPostingBridge.postNetting(
                    nettingId = nettingId,
                    counterpartyDisplayName = payableRow[OpenItemTable.counterpartyName],
                    amount = amount,
                    on = today,
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                )
            // No retryNetting endpoint exists in this wave -- a Failed outcome rolls back the
            // whole netting attempt rather than leaving a half-booked netting/settlement pair
            // with no way to complete it later (see IOpenItemService KDoc -- deliberate, unlike
            // createOpenItem/settleOpenItem which DO have a retry path).
            if (outcome is OpenItemPostingOutcome.Failed) throw ConflictException("netting could not be booked: ${outcome.reason}")
            val journalEntryId = (outcome as OpenItemPostingOutcome.Posted).journalEntryId
            OpenItemNettingTable.update(
                { OpenItemNettingTable.id eq nettingId },
            ) { it[OpenItemNettingTable.journalEntryId] = journalEntryId }

            OpenItemTable.update({ OpenItemTable.id eq payableId }) {
                it[status] =
                    OpenItemMath.deriveStatus(
                        amount = payableRow[OpenItemTable.amount],
                        openAmount = payableOpen - amount,
                        cancelled = false,
                    )
            }
            OpenItemTable.update({ OpenItemTable.id eq receivableId }) {
                it[status] =
                    OpenItemMath.deriveStatus(
                        amount = receivableRow[OpenItemTable.amount],
                        openAmount = receivableOpen - amount,
                        cancelled = false,
                    )
            }

            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.OPEN_ITEM_NETTING,
                entityId = nettingId,
                action = AuditAction.CREATE,
                before = null,
                after =
                    Json.encodeToString(
                        OpenItemNettingSnapshot.serializer(),
                        OpenItemNettingSnapshot(
                            nettingId = nettingId.toString(),
                            payableItemId = payableId.toString(),
                            receivableItemId = receivableId.toString(),
                            amount = amount,
                            journalEntryId = journalEntryId.toString(),
                        ),
                    ),
            )

            loadNettingDto(nettingId)
        }
    }

    override suspend fun reverseNetting(
        nettingId: String,
        reason: String,
    ): OpenItemNettingDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*OPEN_ITEM_WRITE_ROLES)
        if (reason.isBlank()) throw BadRequestException("reason must not be blank")
        requireMaxLength(value = reason, max = MAX_REASON_LENGTH, fieldName = "reason")
        val id = nettingId.toOpenItemUuid("nettingId")
        return transaction {
            val nettingRow =
                OpenItemNettingTable
                    .selectAll()
                    .where { OpenItemNettingTable.id eq id }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("OpenItemNetting $id not found")
            if (nettingRow[OpenItemNettingTable.reversedAt] != null) throw ConflictException("OpenItemNetting $id is already reversed")
            val journalEntryId =
                nettingRow[OpenItemNettingTable.journalEntryId]
                    ?: throw ConflictException("OpenItemNetting $id was never booked, nothing to reverse")

            val outcome =
                OpenItemPostingBridge.postReversal(
                    originalJournalEntryId = journalEntryId,
                    description = "Storno Verrechnung",
                    reason = reason,
                    on = DbClock.nowLocalDateTime().date,
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                )
            val reversalJournalEntryId = (outcome as OpenItemPostingOutcome.Posted).journalEntryId

            val now = DbClock.nowLocalDateTime()
            OpenItemNettingTable.update({ OpenItemNettingTable.id eq id }) {
                it[reversedAt] = now
                it[reversedByMemberId] = current.memberId
                it[reversalReason] = reason
                it[OpenItemNettingTable.reversalJournalEntryId] = reversalJournalEntryId
            }
            OpenItemSettlementTable.update({ OpenItemSettlementTable.nettingId eq id }) {
                it[reversedAt] = now
                it[reversedByMemberId] = current.memberId
                it[reversalReason] = reason
                it[OpenItemSettlementTable.reversalJournalEntryId] = reversalJournalEntryId
            }

            val payableId = nettingRow[OpenItemNettingTable.payableItemId]
            val receivableId = nettingRow[OpenItemNettingTable.receivableItemId]
            // Stable lock order, same reasoning as loadNettingPair (S-3).
            listOf(payableId, receivableId).sortedBy { it.toString() }.forEach { itemId ->
                val itemRow =
                    OpenItemTable
                        .selectAll()
                        .where { OpenItemTable.id eq itemId }
                        .forUpdate()
                        .single()
                if (itemRow[OpenItemTable.status] != OpenItemStatus.CANCELLED) {
                    val active = loadActiveSettlements(listOf(itemId))[itemId].orEmpty()
                    val newOpenAmount =
                        OpenItemMath.openAmount(
                            amount = itemRow[OpenItemTable.amount],
                            activeSettlementAmounts = active.map { it.amount },
                        )
                    OpenItemTable.update({ OpenItemTable.id eq itemId }) {
                        it[status] =
                            OpenItemMath.deriveStatus(amount = itemRow[OpenItemTable.amount], openAmount = newOpenAmount, cancelled = false)
                    }
                }
            }

            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.OPEN_ITEM_NETTING,
                entityId = id,
                action = AuditAction.VOID,
                before =
                    Json.encodeToString(
                        OpenItemNettingSnapshot.serializer(),
                        OpenItemNettingSnapshot(
                            nettingId = id.toString(),
                            payableItemId = payableId.toString(),
                            receivableItemId = receivableId.toString(),
                            amount = nettingRow[OpenItemNettingTable.amount],
                            journalEntryId = journalEntryId.toString(),
                        ),
                    ),
                after = null,
            )
            loadNettingDto(id)
        }
    }

    override suspend fun getCounterpartyDefaults(
        counterpartyName: String,
        direction: OpenItemDirection,
    ): List<CounterpartyDefaultsDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*OPEN_ITEM_WRITE_ROLES)
        val key = CounterpartyKey.of(counterpartyName)
        return transaction {
            val row =
                OpenItemTable
                    .selectAll()
                    .where { (OpenItemTable.counterpartyKey eq key) and (OpenItemTable.direction eq direction) }
                    .orderBy(OpenItemTable.createdAt to SortOrder.DESC)
                    .limit(1)
                    .singleOrNull() ?: return@transaction emptyList()
            val accountId = row[OpenItemTable.contraAccountId]
            val info = loadAccountInfo(listOf(accountId))[accountId]
            listOf(
                CounterpartyDefaultsDto(
                    contraAccountId = accountId.toString(),
                    contraAccountNumber = info?.first ?: "",
                    contraAccountName = info?.second ?: "",
                    sphere = row[OpenItemTable.sphere],
                ),
            )
        }
    }

    // ── Internal helpers ───────────────────────────────────────────────────────────

    private fun loadNettingPair(
        payableId: Uuid,
        receivableId: Uuid,
    ): Pair<ResultRow, ResultRow> {
        // Stable lock order (ascending string id) to avoid a deadlock when two actors net the
        // same pair in opposite parameter order concurrently -- see OpenItemService KDoc S-3.
        val orderedIds = listOf(payableId, receivableId).sortedBy { it.toString() }
        val rowsById =
            orderedIds.associateWith { id ->
                OpenItemTable
                    .selectAll()
                    .where { OpenItemTable.id eq id }
                    .forUpdate()
                    .singleOrNull()
            }
        val payableRow = rowsById[payableId] ?: throw NotFoundException("OpenItem $payableId not found")
        val receivableRow = rowsById[receivableId] ?: throw NotFoundException("OpenItem $receivableId not found")
        if (payableRow[OpenItemTable.direction] != OpenItemDirection.PAYABLE) throw ConflictException("OpenItem $payableId is not PAYABLE")
        if (receivableRow[OpenItemTable.direction] !=
            OpenItemDirection.RECEIVABLE
        ) {
            throw ConflictException("OpenItem $receivableId is not RECEIVABLE")
        }
        if (payableRow[OpenItemTable.status] !in
            OpenItemStatusSets.SETTLEABLE
        ) {
            throw ConflictException("OpenItem $payableId is ${payableRow[OpenItemTable.status]}, not settleable")
        }
        if (receivableRow[OpenItemTable.status] !in
            OpenItemStatusSets.SETTLEABLE
        ) {
            throw ConflictException("OpenItem $receivableId is ${receivableRow[OpenItemTable.status]}, not settleable")
        }
        if (payableRow[OpenItemTable.creationJournalEntryId] == null) throw ConflictException("OpenItem $payableId is not booked yet")
        if (receivableRow[OpenItemTable.creationJournalEntryId] == null) throw ConflictException("OpenItem $receivableId is not booked yet")
        // Security fix (CRITICAL, first review pass): Aufrechnung/netting requires Gegenseitigkeit
        // (BGB §387) -- both items must actually belong to the SAME counterparty. Without this
        // check, any TREASURER/ADMIN could net an arbitrary payable against an arbitrary
        // unrelated receivable by calling this RPC directly (bypassing the UI's own
        // counterparty-matched candidate list in findAllNettingCandidates), silently
        // extinguishing a real debt to one party using an unrelated party's claim against the
        // org -- a balanced-looking journal entry that is not a real transaction. Same matching
        // rule as findAllNettingCandidates (line ~887): same non-null crmContactId, or the same
        // normalized counterpartyKey.
        val payableCrm = payableRow[OpenItemTable.crmContactId]
        val receivableCrm = receivableRow[OpenItemTable.crmContactId]
        val sameCounterparty =
            (payableCrm != null && payableCrm == receivableCrm) ||
                payableRow[OpenItemTable.counterpartyKey] == receivableRow[OpenItemTable.counterpartyKey]
        if (!sameCounterparty) {
            throw ConflictException(
                "OpenItem $payableId and $receivableId do not share a counterparty -- netting requires Gegenseitigkeit (BGB §387)",
            )
        }
        return payableRow to receivableRow
    }

    private fun requireNettableAmount(
        amount: BigDecimal,
        payableOpen: BigDecimal,
        receivableOpen: BigDecimal,
    ) {
        requireValidAmount(amount = amount)
        val max = minOf(payableOpen, receivableOpen)
        if (amount > max) throw ConflictException("amount $amount exceeds the nettable maximum $max")
    }

    /**
     * Full-table scan over every SETTLEABLE, already-booked open item -- fine at Verein/Partei
     * scale (same accepted tradeoff [network.lapis.cloud.server.audit.AuditLogRecorder]'s own
     * KDoc documents for its single global lock), capped at [NETTING_CANDIDATE_SCAN_CAP] rows per
     * side as a defensive bound.
     */
    private fun findAllNettingCandidates(asOf: LocalDate): List<NettingCandidateDto> {
        val payableRows =
            OpenItemTable
                .selectAll()
                .where {
                    (OpenItemTable.direction eq OpenItemDirection.PAYABLE) and
                        (OpenItemTable.status inList OpenItemStatusSets.SETTLEABLE.toList()) and
                        (OpenItemTable.creationJournalEntryId.isNotNull())
                }.limit(NETTING_CANDIDATE_SCAN_CAP)
                .toList()
        val receivableRows =
            OpenItemTable
                .selectAll()
                .where {
                    (OpenItemTable.direction eq OpenItemDirection.RECEIVABLE) and
                        (OpenItemTable.status inList OpenItemStatusSets.SETTLEABLE.toList()) and
                        (OpenItemTable.creationJournalEntryId.isNotNull())
                }.limit(NETTING_CANDIDATE_SCAN_CAP)
                .toList()
        if (payableRows.isEmpty() || receivableRows.isEmpty()) return emptyList()

        // Pairing FIRST, loading second (audit finding, second pass): matching only needs
        // `crmContactId`/`counterpartyKey`/`id`, all of which are already on the scanned rows. Loading
        // contra-account info, active settlements and the dunning progress for EVERY scanned row (up
        // to 2 * NETTING_CANDIDATE_SCAN_CAP) was wasted work for every row that never becomes a
        // candidate -- and the dunning progress in particular is only ever read for the receivable
        // half. They are now loaded for the paired rows alone.
        val pairs = mutableListOf<Pair<ResultRow, ResultRow>>()
        val usedReceivableIds = mutableSetOf<Uuid>()
        for (payable in payableRows) {
            val payableCrm = payable[OpenItemTable.crmContactId]
            val payableKey = payable[OpenItemTable.counterpartyKey]
            val match =
                receivableRows.firstOrNull { receivable ->
                    receivable[OpenItemTable.id] !in usedReceivableIds &&
                        (
                            (payableCrm != null && payableCrm == receivable[OpenItemTable.crmContactId]) ||
                                payableKey == receivable[OpenItemTable.counterpartyKey]
                        )
                } ?: continue
            usedReceivableIds += match[OpenItemTable.id]
            pairs += payable to match
        }
        if (pairs.isEmpty()) return emptyList()

        val pairedRows = pairs.flatMap { (payable, receivable) -> listOf(payable, receivable) }
        val accountInfo = loadAccountInfo(pairedRows.map { it[OpenItemTable.contraAccountId] })
        val settlementsByItem = loadActiveSettlements(pairedRows.map { it[OpenItemTable.id] })
        val dunningByItem = ReceivableDunningEngine.loadDunningProgress(pairedRows)

        fun ResultRow.dto() =
            toOpenItemDto(
                asOf = asOf,
                accountInfo = accountInfo,
                activeSettlements = settlementsByItem[this[OpenItemTable.id]].orEmpty(),
                dunningProgress = dunningByItem[this[OpenItemTable.id]],
            )

        return pairs.map { (payable, match) ->
            val payableCrm = payable[OpenItemTable.crmContactId]
            val matchedByCrm = payableCrm != null && payableCrm == match[OpenItemTable.crmContactId]
            val payableDto = payable.dto()
            val receivableDto = match.dto()
            NettingCandidateDto(
                counterpartyKey = payable[OpenItemTable.counterpartyKey],
                counterpartyDisplayName = payable[OpenItemTable.counterpartyName],
                crmContactId = (payableCrm ?: match[OpenItemTable.crmContactId])?.toString(),
                matchedByNameOnly = !matchedByCrm,
                payable = payableDto,
                receivable = receivableDto,
                maxNettableAmount = minOf(payableDto.openAmount, receivableDto.openAmount),
            )
        }
    }

    /**
     * Security fix (MAJOR, first review pass): this used to be a fully unbounded table scan --
     * unlike [listOpenItems] (keyset-paginated, [MAX_LIST_RESULTS]) or [findAllNettingCandidates]
     * (capped at [NETTING_CANDIDATE_SCAN_CAP]), it had no limit at all, running on every
     * accounting-dashboard summary fetch. Capped the same way, with a warning log so a truncated
     * (and therefore approximate) total is visible in operations rather than silently wrong.
     */
    private fun aggregateBuckets(
        direction: OpenItemDirection,
        asOf: LocalDate,
    ): List<OpenItemAgingBucketDto> {
        val rows =
            OpenItemTable
                .selectAll()
                .where { (OpenItemTable.direction eq direction) and (OpenItemTable.status inList OpenItemStatusSets.SETTLEABLE.toList()) }
                .limit(NETTING_CANDIDATE_SCAN_CAP)
                .toList()
        if (rows.size == NETTING_CANDIDATE_SCAN_CAP) {
            logger.warn {
                "OpenItemService.aggregateBuckets($direction): hit the $NETTING_CANDIDATE_SCAN_CAP-row scan cap -- totals may be understated"
            }
        }
        val ids = rows.map { it[OpenItemTable.id] }
        val settlementsByItem = loadActiveSettlements(ids)
        val byBucket = linkedMapOf<OpenItemAgingBucket, MutableList<BigDecimal>>()
        OpenItemAgingBucket.entries.forEach { byBucket[it] = mutableListOf() }
        rows.forEach { row ->
            val openAmount =
                OpenItemMath.openAmount(
                    amount = row[OpenItemTable.amount],
                    activeSettlementAmounts = settlementsByItem[row[OpenItemTable.id]].orEmpty().map { it.amount },
                )
            if (openAmount <= BigDecimal.ZERO) return@forEach
            val overdue = OpenItemMath.daysOverdue(dueDate = row[OpenItemTable.dueDate], asOf = asOf)
            byBucket.getValue(OpenItemMath.bucketOf(overdue)) += openAmount
        }
        return byBucket.map { (bucket, amounts) ->
            OpenItemAgingBucketDto(
                bucket = bucket,
                count = amounts.size,
                totalAmount =
                    amounts.fold(BigDecimal.ZERO) { a, b ->
                        a +
                            b
                    },
            )
        }
    }

    private fun loadNettingDto(nettingId: Uuid): OpenItemNettingDto {
        val row = OpenItemNettingTable.selectAll().where { OpenItemNettingTable.id eq nettingId }.single()
        return OpenItemNettingDto(
            id = row[OpenItemNettingTable.id].toString(),
            counterpartyKey = row[OpenItemNettingTable.counterpartyKey],
            amount = row[OpenItemNettingTable.amount],
            payableItemId = row[OpenItemNettingTable.payableItemId].toString(),
            receivableItemId = row[OpenItemNettingTable.receivableItemId].toString(),
            journalEntryId = row[OpenItemNettingTable.journalEntryId]?.toString(),
            postingError = row[OpenItemNettingTable.postingError],
            createdByMemberId = row[OpenItemNettingTable.createdByMemberId].toString(),
            createdAt = row[OpenItemNettingTable.createdAt],
            reversedAt = row[OpenItemNettingTable.reversedAt],
            reversalReason = row[OpenItemNettingTable.reversalReason],
        )
    }
}

internal data class ActiveSettlement(
    val amount: BigDecimal,
)

internal fun loadActiveSettlements(itemIds: List<Uuid>): Map<Uuid, List<ActiveSettlement>> {
    if (itemIds.isEmpty()) return emptyMap()
    return OpenItemSettlementTable
        .selectAll()
        .where { (OpenItemSettlementTable.openItemId inList itemIds) and (OpenItemSettlementTable.reversedAt.isNull()) }
        .toList()
        .groupBy({ it[OpenItemSettlementTable.openItemId] }, { ActiveSettlement(it[OpenItemSettlementTable.amount]) })
}

internal fun loadAccountInfo(accountIds: List<Uuid>): Map<Uuid, Pair<String, String>> {
    val distinctIds = accountIds.distinct()
    if (distinctIds.isEmpty()) return emptyMap()
    return LedgerAccountTable
        .selectAll()
        .where { LedgerAccountTable.id inList distinctIds }
        .associate { it[LedgerAccountTable.id] to (it[LedgerAccountTable.accountNumber] to it[LedgerAccountTable.name]) }
}

/**
 * [dunningProgress] is deliberately a REQUIRED parameter without a default: the three dunning fields
 * of [OpenItemDto] were shipped in V1.4.21 and left `null` at every call site because this mapper
 * simply never set them, which silently disabled the whole debtor-dunning section of the UI
 * (`OpenItemsScreen.renderDunningActions` always fell through to "Keine weitere Mahnstufe
 * verfügbar", `OpenItemAuthzUi.canSkipDunningLevel` was always `false`, the "Mahnstufe" column always
 * showed "–"). A default of `null` here would let the next call site reintroduce exactly that.
 * Callers get the value from
 * [network.lapis.cloud.server.openitem.dunning.ReceivableDunningEngine.loadDunningProgress], which
 * batch-loads a whole page in two queries.
 */
internal fun ResultRow.toOpenItemDto(
    asOf: LocalDate,
    accountInfo: Map<Uuid, Pair<String, String>>,
    activeSettlements: List<ActiveSettlement>,
    dunningProgress: ReceivableDunningEngine.DunningProgress?,
): OpenItemDto {
    val accountId = this[OpenItemTable.contraAccountId]
    val info = accountInfo[accountId]
    val openAmount =
        OpenItemMath.openAmount(
            amount = this[OpenItemTable.amount],
            activeSettlementAmounts = activeSettlements.map { it.amount },
        )
    val overdue = OpenItemMath.daysOverdue(dueDate = this[OpenItemTable.dueDate], asOf = asOf)
    return OpenItemDto(
        id = this[OpenItemTable.id].toString(),
        direction = this[OpenItemTable.direction],
        counterpartyName = this[OpenItemTable.counterpartyName],
        counterpartyKey = this[OpenItemTable.counterpartyKey],
        crmContactId = this[OpenItemTable.crmContactId]?.toString(),
        reference = this[OpenItemTable.reference],
        itemDate = this[OpenItemTable.itemDate],
        dueDate = this[OpenItemTable.dueDate],
        amount = this[OpenItemTable.amount],
        openAmount = openAmount,
        contraAccountId = accountId.toString(),
        contraAccountNumber = info?.first ?: "",
        contraAccountName = info?.second ?: "",
        sphere = this[OpenItemTable.sphere],
        status = this[OpenItemTable.status],
        note = this[OpenItemTable.note],
        daysOverdue = if (openAmount <= BigDecimal.ZERO) 0 else overdue,
        asOf = asOf,
        creationJournalEntryId = this[OpenItemTable.creationJournalEntryId]?.toString(),
        creationPostingError = this[OpenItemTable.creationPostingError],
        createdByMemberId = this[OpenItemTable.createdByMemberId].toString(),
        createdAt = this[OpenItemTable.createdAt],
        cancelledAt = this[OpenItemTable.cancelledAt],
        cancellationReason = this[OpenItemTable.cancellationReason],
        highestDunningLevelNumber = dunningProgress?.highestIssuedLevelNumber,
        nextDunningLevelNumber = dunningProgress?.nextLevelNumber,
        nextDunningLevelDueOn = dunningProgress?.nextLevelDueOn,
    )
}

internal fun ResultRow.toOpenItemSnapshot(): OpenItemSnapshot =
    OpenItemSnapshot(
        openItemId = this[OpenItemTable.id].toString(),
        direction = this[OpenItemTable.direction],
        counterpartyKey = this[OpenItemTable.counterpartyKey],
        status = this[OpenItemTable.status],
        amount = this[OpenItemTable.amount],
        creationJournalEntryId = this[OpenItemTable.creationJournalEntryId]?.toString(),
        creationPostingError = this[OpenItemTable.creationPostingError],
        reference = this[OpenItemTable.reference],
        note = this[OpenItemTable.note],
    )

/**
 * Assembles a complete [OpenItemDetailDto] for [itemId] -- shared between [OpenItemService] and
 * `network.lapis.cloud.server.openitem.dunning.ReceivableDunningService` (same module, different
 * package) so there is exactly one place that assembles this DTO shape. Does NOT itself open a
 * transaction or check roles -- callers must already be inside one and have already gated access.
 */
internal fun loadOpenItemDetail(itemId: Uuid): OpenItemDetailDto {
    val row = OpenItemTable.selectAll().where { OpenItemTable.id eq itemId }.single()
    val asOf = DbClock.nowLocalDateTime().date
    val accountInfo = loadAccountInfo(listOf(row[OpenItemTable.contraAccountId]))
    // Explicit ORDER BY: without it Postgres returns rows in plan-dependent order, and the client
    // relies on chronological order (oldest settlement first, newest last).
    val settlementRows =
        OpenItemSettlementTable
            .selectAll()
            .where { OpenItemSettlementTable.openItemId eq itemId }
            .orderBy(OpenItemSettlementTable.createdAt to SortOrder.ASC, OpenItemSettlementTable.id to SortOrder.ASC)
            .toList()
    val activeSettlements =
        settlementRows
            .filter {
                it[OpenItemSettlementTable.reversedAt] == null
            }.map { ActiveSettlement(it[OpenItemSettlementTable.amount]) }
    val item =
        row.toOpenItemDto(
            asOf = asOf,
            accountInfo = accountInfo,
            activeSettlements = activeSettlements,
            // Same mapper, same three fields as the list -- so detail and list can never disagree
            // about the next dunning level (the review finding this fix closes).
            dunningProgress = ReceivableDunningEngine.loadDunningProgress(listOf(row))[itemId],
        )
    val settlementDtos =
        settlementRows.map {
            OpenItemSettlementDto(
                id = it[OpenItemSettlementTable.id].toString(),
                openItemId = itemId.toString(),
                kind = it[OpenItemSettlementTable.kind],
                amount = it[OpenItemSettlementTable.amount],
                settledOn = it[OpenItemSettlementTable.settledOn],
                nettingId = it[OpenItemSettlementTable.nettingId]?.toString(),
                journalEntryId = it[OpenItemSettlementTable.journalEntryId]?.toString(),
                postingError = it[OpenItemSettlementTable.postingError],
                createdByMemberId = it[OpenItemSettlementTable.createdByMemberId].toString(),
                createdAt = it[OpenItemSettlementTable.createdAt],
                reversedAt = it[OpenItemSettlementTable.reversedAt],
                reversalReason = it[OpenItemSettlementTable.reversalReason],
            )
        }
    val noticeDtos =
        ReceivableDunningNoticeTable
            .selectAll()
            .where { ReceivableDunningNoticeTable.openItemId eq itemId }
            .orderBy(
                ReceivableDunningNoticeTable.cycleNumber to SortOrder.ASC,
                ReceivableDunningNoticeTable.levelNumber to SortOrder.ASC,
                ReceivableDunningNoticeTable.issuedAt to SortOrder.ASC,
                ReceivableDunningNoticeTable.id to SortOrder.ASC,
            ).map {
                ReceivableDunningNoticeDto(
                    id = it[ReceivableDunningNoticeTable.id].toString(),
                    openItemId = itemId.toString(),
                    receivableDunningLevelId = it[ReceivableDunningNoticeTable.receivableDunningLevelId].toString(),
                    cycleNumber = it[ReceivableDunningNoticeTable.cycleNumber],
                    levelNumber = it[ReceivableDunningNoticeTable.levelNumber],
                    levelName = it[ReceivableDunningNoticeTable.levelName],
                    feeAmount = it[ReceivableDunningNoticeTable.feeAmount],
                    amountDue = it[ReceivableDunningNoticeTable.amountDue],
                    status = it[ReceivableDunningNoticeTable.status],
                    issuedAt = it[ReceivableDunningNoticeTable.issuedAt],
                    respondBy = it[ReceivableDunningNoticeTable.respondBy],
                    documentId = it[ReceivableDunningNoticeTable.documentId]?.toString(),
                    feeJournalEntryId = it[ReceivableDunningNoticeTable.feeJournalEntryId]?.toString(),
                    createdByMemberId = it[ReceivableDunningNoticeTable.createdByMemberId]?.toString(),
                    cancelledAt = it[ReceivableDunningNoticeTable.cancelledAt],
                    cancellationReason = it[ReceivableDunningNoticeTable.cancellationReason],
                )
            }
    return OpenItemDetailDto(item = item, settlements = settlementDtos, dunningNotices = noticeDtos)
}

/**
 * Pure arithmetic, unit-testable without a database -- same "pure logic extracted to a sibling
 * object" idiom [JournalEntryBalance]/[CashRegisterGuard] already establish.
 */
internal object OpenItemMath {
    /** `amount - Σ(settlement amounts where reversedAt IS NULL)`. NEVER stored -- see OpenItemService KDoc. */
    fun openAmount(
        amount: BigDecimal,
        activeSettlementAmounts: List<BigDecimal>,
    ): BigDecimal = amount - activeSettlementAmounts.fold(BigDecimal.ZERO) { acc, a -> acc + a }

    fun deriveStatus(
        amount: BigDecimal,
        openAmount: BigDecimal,
        cancelled: Boolean,
    ): OpenItemStatus =
        when {
            cancelled -> OpenItemStatus.CANCELLED
            openAmount <= BigDecimal.ZERO -> OpenItemStatus.SETTLED
            openAmount.compareTo(amount) == 0 -> OpenItemStatus.OPEN
            else -> OpenItemStatus.PARTIALLY_SETTLED
        }

    /** `0` if `dueDate >= asOf`, otherwise the number of days from `dueDate` to `asOf`. */
    fun daysOverdue(
        dueDate: LocalDate,
        asOf: LocalDate,
    ): Int = if (asOf <= dueDate) 0 else dueDate.daysUntil(asOf)

    fun bucketOf(daysOverdue: Int): OpenItemAgingBucket =
        when {
            daysOverdue <= 0 -> OpenItemAgingBucket.NOT_DUE
            daysOverdue <= AGING_DAYS_1_30_MAX -> OpenItemAgingBucket.DAYS_1_30
            daysOverdue <= AGING_DAYS_31_90_MAX -> OpenItemAgingBucket.DAYS_31_90
            else -> OpenItemAgingBucket.OVER_90
        }
}

private fun String.toOpenItemUuid(role: String): Uuid =
    runCatching {
        Uuid.parse(this)
    }.getOrElse { throw NotFoundException("Invalid $role: $this") }

/**
 * Review MINOR fix (Welle V1.4.3.6 "Externe Rechnungsstellung für Veranstaltungen"): the
 * `OpenItemTable.update { creationJournalEntryId/creationPostingError }` step of a "just created a
 * new RECEIVABLE/PAYABLE open item, now record how [OpenItemPostingBridge.postItemCreation] went"
 * flow used to be duplicated verbatim between `OpenItemService.createOpenItem` and
 * `EventService.issueEventInvoice` (the latter is a deliberately thin bridge into this SAME
 * open-item bookkeeping, see that method's own KDoc). Lifted out of [OpenItemService] (where it was
 * a private instance method touching no instance state) to a top-level `internal` function so both
 * callers -- and any future one -- share ONE place this bookkeeping step is written. Deliberately
 * NOT extended to the settlement/cancellation/reversal/netting call sites elsewhere in
 * [OpenItemService] (`journalEntryId`/`postingError` on `OpenItemSettlementTable`/
 * `OpenItemNettingTable`, not `creationJournalEntryId`/`creationPostingError` on [OpenItemTable]) --
 * those are a structurally different table/column shape, not the same duplication.
 */
internal fun applyOpenItemPostingOutcome(
    itemId: Uuid,
    outcome: OpenItemPostingOutcome,
) {
    when (outcome) {
        is OpenItemPostingOutcome.Posted ->
            OpenItemTable.update({ OpenItemTable.id eq itemId }) {
                it[creationJournalEntryId] = outcome.journalEntryId
                it[creationPostingError] = null
            }
        is OpenItemPostingOutcome.Failed ->
            OpenItemTable.update({ OpenItemTable.id eq itemId }) { it[creationPostingError] = outcome.reason }
    }
}
