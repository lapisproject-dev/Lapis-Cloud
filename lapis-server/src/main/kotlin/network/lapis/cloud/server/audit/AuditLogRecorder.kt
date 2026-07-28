package network.lapis.cloud.server.audit
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AuditLogChainStateTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * GoBD-Revisionssicherheit (V0.5.3) write path -- the ONLY place any
 * [network.lapis.cloud.server.db.generated.AuditLogEntryTable] row is ever inserted anywhere in
 * this codebase. There is deliberately no update/delete function here or anywhere else touching
 * that table (see [network.lapis.cloud.server.rpc.AuditLogImmutabilityTest] for the source-text
 * scan that enforces this) -- append-only is the whole point of a GoBD audit trail.
 *
 * **Must always be called from inside the caller's already-open `transaction {}`** -- [record]
 * deliberately does NOT open its own `transaction {}` (same "transaction-free by contract" idiom
 * `network.lapis.cloud.server.rpc.ResolutionBook`/`BoardMembershipEvents` already establish), so
 * the audit row is written atomically with the fachlich mutation it accompanies: either both
 * commit together, or a later failure in the same transaction rolls both back together. This is
 * the deliberate guarantee this wave provides -- "no mutation without a successful audit entry,
 * and no audit entry without the mutation it describes actually having happened" -- see
 * `14-audit-log.kuml.kts`'s file header for why a genuinely rejected/rolled-back attempt (e.g. a
 * `PROHIBITED` §25 PartG verdict) therefore never produces an audit row at all, by construction,
 * not as an oversight.
 *
 * Concurrency / race-freedom: [record] takes a `SELECT ... FOR UPDATE` row lock on the single
 * [AuditLogChainStateTable] singleton row ([AUDIT_LOG_CHAIN_STATE_ID]) before reading
 * `lastSequenceNumber`/`lastEntryHash` -- this is what serializes concurrent writers across the
 * *entire* application (every audited mutation anywhere funnels through this one lock), giving
 * gapless `sequence_number` assignment and a race-free hash chain even for the very first
 * ("genesis") row (unlike locking "the last `audit_log_entry` row ORDER BY sequence_number DESC
 * LIMIT 1 FOR UPDATE", which locks nothing at all on an empty table). This is a deliberate,
 * accepted tradeoff: every audited write in the whole system serializes through one global lock,
 * which is a real throughput ceiling at large scale but is fine at Verein/Partei scale.
 *
 * **Deadlock-avoidance contract (must be followed by every call site):** [record] must always be
 * the LAST database operation of the caller's transaction that takes a row lock. If a caller took
 * some OTHER row lock (e.g. `AccountingService.lockCashRegisterAccounts`) it must do so BEFORE
 * calling [record], never after -- two transactions that lock the chain-state row and some other
 * row in opposite order can deadlock. Every current call site in this codebase (see
 * `AccountingService`/`GovernanceService`/`BoardMembershipService`) already satisfies this: each
 * calls [record] as the final step before returning.
 */
object AuditLogRecorder {
    /** Fixed sentinel id of the single [AuditLogChainStateTable] row, seeded directly in `V1__baseline.sql`. */
    val AUDIT_LOG_CHAIN_STATE_ID: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000000f3")

    /**
     * Appends one hash-chained [AuditLogEntryTable] row. See the class KDoc for the transaction/
     * locking contract every caller must follow. [before]/[after] are pre-serialized JSON strings
     * (e.g. `Json.encodeToString(JournalEntrySnapshot(...))`) -- this function does not know or
     * care about their structure, it only chains over them as opaque text.
     */
    fun record(
        actorMemberId: Uuid?,
        actorRole: AccountRole?,
        entityType: AuditEntityType,
        entityId: Uuid,
        action: AuditAction,
        before: String? = null,
        after: String? = null,
        occurredAt: LocalDateTime = nowLocalDateTime(),
    ) {
        check(TransactionManager.currentOrNull() != null) {
            "AuditLogRecorder.record must be called from inside an already-open transaction {} block"
        }
        val chainRow =
            AuditLogChainStateTable
                .selectAll()
                .where { AuditLogChainStateTable.id eq AUDIT_LOG_CHAIN_STATE_ID }
                .forUpdate()
                .singleOrNull()
                ?: error("AuditLogChainState row $AUDIT_LOG_CHAIN_STATE_ID not found -- baseline seed missing?")

        val nextSequenceNumber = chainRow[AuditLogChainStateTable.lastSequenceNumber] + 1
        val previousEntryHash = chainRow[AuditLogChainStateTable.lastEntryHash]

        val chainInput =
            AuditHashChain.ChainInput(
                sequenceNumber = nextSequenceNumber,
                occurredAt = occurredAt,
                actorMemberId = actorMemberId,
                actorRole = actorRole,
                entityType = entityType,
                entityId = entityId,
                action = action,
                beforeSnapshot = before,
                afterSnapshot = after,
                previousEntryHash = previousEntryHash,
            )
        val entryHash = AuditHashChain.computeHash(chainInput)

        AuditLogEntryTable.insert {
            it[id] = Uuid.random()
            it[sequenceNumber] = nextSequenceNumber
            it[AuditLogEntryTable.occurredAt] = occurredAt
            it[AuditLogEntryTable.actorMemberId] = actorMemberId
            it[AuditLogEntryTable.actorRole] = actorRole
            it[AuditLogEntryTable.entityType] = entityType
            it[AuditLogEntryTable.entityId] = entityId
            it[AuditLogEntryTable.action] = action
            it[beforeSnapshot] = before
            it[afterSnapshot] = after
            it[AuditLogEntryTable.entryHash] = entryHash
            it[AuditLogEntryTable.previousEntryHash] = previousEntryHash
        }
        AuditLogChainStateTable.update({ AuditLogChainStateTable.id eq AUDIT_LOG_CHAIN_STATE_ID }) {
            it[lastSequenceNumber] = nextSequenceNumber
            it[lastEntryHash] = entryHash
        }
    }

    private fun nowLocalDateTime(): LocalDateTime = DbClock.nowLocalDateTime()
}
