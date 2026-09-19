package network.lapis.cloud.server.openitem.dunning

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.OpenItemSettlementTable
import network.lapis.cloud.server.db.generated.OpenItemTable
import network.lapis.cloud.server.db.generated.ReceivableDunningLevelTable
import network.lapis.cloud.server.db.generated.ReceivableDunningNoticeTable
import network.lapis.cloud.server.rpc.OpenItemMath
import network.lapis.cloud.server.rpc.OpenItemPostingBridge
import network.lapis.cloud.server.rpc.OpenItemPostingOutcome
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.OpenItemDirection
import network.lapis.cloud.shared.domain.OpenItemStatusSets
import network.lapis.cloud.shared.domain.ReceivableDunningNoticeSnapshot
import network.lapis.cloud.shared.domain.ReceivableDunningNoticeStatus
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** Always `1` in this wave -- see class KDoc "No real dunning cycles". */
private const val SINGLE_CYCLE_NUMBER = 1

/**
 * Welle V1.4.15 -- the shared "determine and issue the next dunning level for one receivable open
 * item" core, used by BOTH `ReceivableDunningService.issueReceivableDunningNotice` (manual
 * override) and [ReceivableDunningPoller] (automatic) -- same "one issuance path, two callers"
 * idiom `network.lapis.cloud.server.payment.dunning.DunningIssuance` already establishes for the
 * pre-existing member-contribution dunning domain. **Structurally independent from
 * `DunningIssuance`** -- no shared code, no shared tables, see `ReceivableDunningService` KDoc for
 * why.
 *
 * **No real dunning cycles** (unlike `DunningIssuance`, which restarts a cycle after a lapsed
 * mandate): a `receivable_dunning_notice.cycle_number` always inserts as `1` -- an
 * `OpenItemDirection.RECEIVABLE` open item is paid once and closed, it is never a recurring
 * subscription that could need a second dunning cycle. `uq_rdn_slot` (`open_item_id,
 * cycle_number, level_number`) still fully guards against re-issuing the same level twice.
 *
 * **Dunning fee, deliberately scope-cut**: [issueNextLevel] books the fee (if any) as its OWN
 * journal entry (Soll `receivables_account_id` / Haben the item's `contra_account_id`), but does
 * **NOT** add it to the open item's own `amount`/`openAmount` -- the fee is a separate accounting
 * fact recorded on the notice (`fee_amount`/`fee_journal_entry_id`), not a retroactive change to
 * the frozen original invoice amount. See `docs/architecture/open-items.adoc` "Deferred/out of
 * scope".
 */
internal object ReceivableDunningEngine {
    sealed interface IssueOutcome {
        data class Issued(
            val notice: ResultRow,
        ) : IssueOutcome

        /** No active level is due yet (or none configured) -- a legitimate no-op, not an error. */
        data object NothingDue : IssueOutcome
    }

    /**
     * Determines the next not-yet-recorded active [ReceivableDunningLevelTable] level for
     * [itemId] and inserts an ISSUED [ReceivableDunningNoticeTable] row for it, booking the fee
     * (if any). [respectGraceDays] `true` (poller) waits for `dueDate + graceDays <= asOf`;
     * `false` (manual override) issues immediately regardless of grace period. Caller must already
     * hold whatever lock it needs on the `open_item` row -- this function only reads it.
     */
    fun issueNextLevel(
        itemId: Uuid,
        asOf: LocalDate,
        respectGraceDays: Boolean,
        actorMemberId: Uuid?,
        actorRole: AccountRole?,
    ): IssueOutcome {
        val itemRow = OpenItemTable.selectAll().where { OpenItemTable.id eq itemId }.single()
        require(itemRow[OpenItemTable.direction] == OpenItemDirection.RECEIVABLE) { "OpenItem $itemId is not RECEIVABLE" }
        if (itemRow[OpenItemTable.status] !in OpenItemStatusSets.SETTLEABLE) return IssueOutcome.NothingDue

        val nextLevel = findNextLevel(itemId) ?: return IssueOutcome.NothingDue
        if (respectGraceDays) {
            val dueOn = itemRow[OpenItemTable.dueDate].plus(nextLevel[ReceivableDunningLevelTable.graceDays], DateTimeUnit.DAY)
            if (asOf < dueOn) return IssueOutcome.NothingDue
        }

        val activeSettlements =
            OpenItemSettlementTable
                .selectAll()
                .where { (OpenItemSettlementTable.openItemId eq itemId) and (OpenItemSettlementTable.reversedAt.isNull()) }
                .map { it[OpenItemSettlementTable.amount] }
        val openAmount = OpenItemMath.openAmount(amount = itemRow[OpenItemTable.amount], activeSettlementAmounts = activeSettlements)
        val feeAmount = nextLevel[ReceivableDunningLevelTable.feeAmount]

        val noticeId = Uuid.random()
        val now = DbClock.nowLocalDateTime()
        var feeJournalEntryId: Uuid? = null
        if (feeAmount != null && feeAmount > BigDecimal.ZERO) {
            val outcome =
                OpenItemPostingBridge.postDunningFee(
                    noticeId = noticeId,
                    counterpartyName = itemRow[OpenItemTable.counterpartyName],
                    feeAmount = feeAmount,
                    contraAccountId = itemRow[OpenItemTable.contraAccountId],
                    on = asOf,
                    actorMemberId = actorMemberId ?: itemRow[OpenItemTable.createdByMemberId],
                    actorRole = actorRole ?: AccountRole.ADMIN,
                )
            when (outcome) {
                is OpenItemPostingOutcome.Posted -> feeJournalEntryId = outcome.journalEntryId
                is OpenItemPostingOutcome.Failed ->
                    logger.warn {
                        "ReceivableDunningEngine: dunning fee for item $itemId level ${nextLevel[ReceivableDunningLevelTable.levelNumber]} could not be booked (${outcome.reason}) -- notice is still recorded."
                    }
            }
        }

        ReceivableDunningNoticeTable.insert {
            it[id] = noticeId
            it[openItemId] = itemId
            it[receivableDunningLevelId] = nextLevel[ReceivableDunningLevelTable.id]
            it[cycleNumber] = SINGLE_CYCLE_NUMBER
            it[levelNumber] = nextLevel[ReceivableDunningLevelTable.levelNumber]
            it[levelName] = nextLevel[ReceivableDunningLevelTable.name]
            it[ReceivableDunningNoticeTable.feeAmount] = feeAmount
            it[amountDue] = openAmount + (feeAmount ?: BigDecimal.ZERO)
            it[status] = ReceivableDunningNoticeStatus.ISSUED
            it[issuedAt] = now
            it[respondBy] = asOf.plus(nextLevel[ReceivableDunningLevelTable.responseDays], DateTimeUnit.DAY)
            it[ReceivableDunningNoticeTable.feeJournalEntryId] = feeJournalEntryId
            it[createdByMemberId] = actorMemberId
        }

        recordAudit(
            noticeId = noticeId,
            itemId = itemId,
            levelNumber = nextLevel[ReceivableDunningLevelTable.levelNumber],
            status = ReceivableDunningNoticeStatus.ISSUED,
            feeJournalEntryId = feeJournalEntryId,
            actorMemberId = actorMemberId,
            actorRole = actorRole,
        )

        val notice = ReceivableDunningNoticeTable.selectAll().where { ReceivableDunningNoticeTable.id eq noticeId }.single()
        return IssueOutcome.Issued(notice)
    }

    /** Records a SKIPPED slot for the next due level, without issuing/booking anything. */
    fun skipNextLevel(
        itemId: Uuid,
        asOf: LocalDate,
        reason: String,
        actorMemberId: Uuid,
        actorRole: AccountRole,
    ): ResultRow {
        val nextLevel = findNextLevel(itemId) ?: throw ConflictException("OpenItem $itemId has no further active dunning level to skip")
        val noticeId = Uuid.random()
        val now = DbClock.nowLocalDateTime()
        ReceivableDunningNoticeTable.insert {
            it[id] = noticeId
            it[openItemId] = itemId
            it[receivableDunningLevelId] = nextLevel[ReceivableDunningLevelTable.id]
            it[cycleNumber] = SINGLE_CYCLE_NUMBER
            it[levelNumber] = nextLevel[ReceivableDunningLevelTable.levelNumber]
            it[levelName] = nextLevel[ReceivableDunningLevelTable.name]
            it[feeAmount] = null
            it[amountDue] = BigDecimal.ZERO
            it[status] = ReceivableDunningNoticeStatus.SKIPPED
            it[issuedAt] = now
            it[respondBy] = asOf
            it[createdByMemberId] = actorMemberId
            it[cancellationReason] = reason
        }
        recordAudit(
            noticeId = noticeId,
            itemId = itemId,
            levelNumber = nextLevel[ReceivableDunningLevelTable.levelNumber],
            status = ReceivableDunningNoticeStatus.SKIPPED,
            feeJournalEntryId = null,
            actorMemberId = actorMemberId,
            actorRole = actorRole,
        )
        return ReceivableDunningNoticeTable.selectAll().where { ReceivableDunningNoticeTable.id eq noticeId }.single()
    }

    /** The first active level with `level_number` beyond every level already recorded for [itemId], if any. */
    private fun findNextLevel(itemId: Uuid): ResultRow? {
        val highestRecorded =
            ReceivableDunningNoticeTable
                .selectAll()
                .where { ReceivableDunningNoticeTable.openItemId eq itemId }
                .orderBy(ReceivableDunningNoticeTable.levelNumber to SortOrder.DESC)
                .limit(1)
                .singleOrNull()
                ?.get(ReceivableDunningNoticeTable.levelNumber)
                ?: 0
        return nextActiveLevelAbove(highestRecorded)
    }

    /**
     * The ONE place the "which level comes next" rule is written in SQL -- shared by [findNextLevel]
     * (single item, used by the two issuance paths) and mirrored in Kotlin by [loadDunningProgress]
     * (whole page, read-only). Both must stay in agreement; the server round-trip test
     * `OpenItemDunningProgressTest` pins exactly that (it reads `nextDunningLevelNumber` off the DTO
     * and then issues, asserting the issued level IS the announced one).
     */
    private fun nextActiveLevelAbove(highestRecorded: Int): ResultRow? =
        ReceivableDunningLevelTable
            .selectAll()
            .where { (ReceivableDunningLevelTable.active eq true) and (ReceivableDunningLevelTable.levelNumber greater highestRecorded) }
            .orderBy(ReceivableDunningLevelTable.levelNumber to SortOrder.ASC)
            .limit(1)
            .singleOrNull()

    /**
     * Escalation state of ONE receivable open item, exactly as `OpenItemDto`'s three dunning fields
     * need it (`highestDunningLevelNumber`/`nextDunningLevelNumber`/`nextDunningLevelDueOn`).
     *
     * [highestIssuedLevelNumber] counts **only `ISSUED`** notices: a `SKIPPED` slot is the explicit
     * record that this level was deliberately NOT dunned, and a `CANCELLED` one was withdrawn --
     * showing either as "Mahnstufe N" in the list would claim a dunning notice that does not exist.
     * It is reported for every RECEIVABLE item regardless of status, because it is a historical fact
     * (a settled invoice that was dunned twice was dunned twice).
     *
     * [nextLevelNumber]/[nextLevelDueOn] describe a FUTURE action and are therefore `null` unless
     * the item is still dunnable (`OpenItemStatusSets.SETTLEABLE`) -- exactly the gate
     * [issueNextLevel] itself applies. Unlike [highestIssuedLevelNumber] the "what has already been
     * recorded" basis here is **every** notice slot (any status, `SKIPPED`/`CANCELLED` included),
     * because `uq_rdn_slot` blocks re-using an occupied `(open_item_id, cycle_number, level_number)`
     * slot regardless of its status -- so this is the level [issueNextLevel] would really issue.
     */
    data class DunningProgress(
        val highestIssuedLevelNumber: Int?,
        val nextLevelNumber: Int?,
        val nextLevelDueOn: LocalDate?,
    )

    /**
     * Batch-loads [DunningProgress] for a whole page of `open_item` rows: **exactly two queries**
     * regardless of how many rows are passed (one over `receivable_dunning_notice` for all ids at
     * once, one over the item-independent `receivable_dunning_level` ladder), so
     * `OpenItemService.listOpenItems` stays free of the N+1 it would otherwise get for its up-to-200
     * rows -- same batching contract `loadActiveSettlements`/`loadAccountInfo` already establish.
     *
     * PAYABLE rows are dropped up front and are simply absent from the result map (the caller maps a
     * missing entry to three `null` fields): a creditor item is never dunned by this domain
     * ([issueNextLevel] refuses it structurally).
     */
    fun loadDunningProgress(rows: List<ResultRow>): Map<Uuid, DunningProgress> {
        val receivableRows = rows.filter { it[OpenItemTable.direction] == OpenItemDirection.RECEIVABLE }
        if (receivableRows.isEmpty()) return emptyMap()
        val ids = receivableRows.map { it[OpenItemTable.id] }.distinct()
        val noticeRows =
            ReceivableDunningNoticeTable
                .selectAll()
                .where { ReceivableDunningNoticeTable.openItemId inList ids }
                .toList()
        val highestRecordedByItem =
            noticeRows
                .groupBy { it[ReceivableDunningNoticeTable.openItemId] }
                .mapValues { (_, rs) -> rs.maxOf { it[ReceivableDunningNoticeTable.levelNumber] } }
        val highestIssuedByItem =
            noticeRows
                .filter { it[ReceivableDunningNoticeTable.status] == ReceivableDunningNoticeStatus.ISSUED }
                .groupBy { it[ReceivableDunningNoticeTable.openItemId] }
                .mapValues { (_, rs) -> rs.maxOf { it[ReceivableDunningNoticeTable.levelNumber] } }
        // levelNumber ASC, so `firstOrNull { it.first > highestRecorded }` is the same pick
        // `nextActiveLevelAbove` makes in SQL.
        val activeLevelLadder =
            ReceivableDunningLevelTable
                .selectAll()
                .where { ReceivableDunningLevelTable.active eq true }
                .orderBy(ReceivableDunningLevelTable.levelNumber to SortOrder.ASC)
                .map { it[ReceivableDunningLevelTable.levelNumber] to it[ReceivableDunningLevelTable.graceDays] }
        return receivableRows.associate { row ->
            val id = row[OpenItemTable.id]
            val dunnable = row[OpenItemTable.status] in OpenItemStatusSets.SETTLEABLE
            val nextLevel =
                if (dunnable) activeLevelLadder.firstOrNull { (levelNumber, _) -> levelNumber > (highestRecordedByItem[id] ?: 0) } else null
            id to
                DunningProgress(
                    highestIssuedLevelNumber = highestIssuedByItem[id],
                    nextLevelNumber = nextLevel?.first,
                    nextLevelDueOn = nextLevel?.let { (_, graceDays) -> row[OpenItemTable.dueDate].plus(graceDays, DateTimeUnit.DAY) },
                )
        }
    }

    private fun recordAudit(
        noticeId: Uuid,
        itemId: Uuid,
        levelNumber: Int,
        status: ReceivableDunningNoticeStatus,
        feeJournalEntryId: Uuid?,
        actorMemberId: Uuid?,
        actorRole: AccountRole?,
    ) {
        AuditLogRecorder.record(
            actorMemberId = actorMemberId,
            actorRole = actorRole,
            entityType = AuditEntityType.RECEIVABLE_DUNNING_NOTICE,
            entityId = noticeId,
            action = if (status == ReceivableDunningNoticeStatus.ISSUED) AuditAction.CREATE else AuditAction.UPDATE,
            before = null,
            after =
                Json.encodeToString(
                    ReceivableDunningNoticeSnapshot.serializer(),
                    ReceivableDunningNoticeSnapshot(
                        noticeId = noticeId.toString(),
                        openItemId = itemId.toString(),
                        levelNumber = levelNumber,
                        status = status,
                        feeJournalEntryId = feeJournalEntryId?.toString(),
                    ),
                ),
        )
    }
}
