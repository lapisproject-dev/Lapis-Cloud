package network.lapis.cloud.server.mail.budget

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.MailBudgetLockTable
import network.lapis.cloud.server.db.generated.MailSendSlotTable
import network.lapis.cloud.server.db.relaxSessionTimeouts
import network.lapis.cloud.server.mail.MailBudgetConfig
import network.lapis.cloud.server.mail.minusDuration
import network.lapis.cloud.server.mail.plusDuration
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.9.81 -- the database part of the hourly send budget: slot reservation under a row lock, plus the global bulk pause.
 *
 * **Reservation** ([reserve]) runs in its OWN short transaction, serialised by `SELECT ... FOR UPDATE` on the singleton row of
 * `mail_budget_lock` (id = 1): count the slots in the window, decide ([MailBudget.decide]), insert one slot on `Allowed`. No SMTP, no
 * `runBlocking`, no `delay` ever happens inside it (Exposed retries a transaction block on any `SQLException`, so the block is
 * idempotent and made single-attempt). It never blocks the caller for long: the transaction-local timeouts are
 * [STATEMENT_TIMEOUT_MS]/[IDLE_TX_TIMEOUT_MS] and the lock wait is [LOCK_TIMEOUT_MS]; a lock timeout or any other database error
 * counts as `WaitUntil(now + RECHECK)` -- the budget errs on the side of NOT sending. See `docs/architecture/row-locks.adoc`.
 *
 * With [config] `null` (no `LAPIS_MAIL_MAX_PER_HOUR`) there is no throttling at all: no slot, no lock, `Allowed`. The bulk pause is
 * read and written regardless -- a provider that answers 4xx must slow bulk sends down even without a configured budget.
 *
 * **Clock**: callers pass `now` from `DbClock`. Several server instances sharing one database share one budget and therefore need
 * synchronised clocks (NTP); the budget is counted per database, not per mailbox.
 */
open class MailBudgetStore(
    private val config: MailBudgetConfig.Enabled?,
) {
    val enabled: Boolean get() = config != null

    val maxPerHour: Int? get() = config?.maxPerHour
    val reservePerHour: Int? get() = config?.reservePerHour
    val bulkPerHour: Int? get() = config?.bulkPerHour

    /** Result of [reserveSlot]: [slotId] is the inserted slot when [decision] is `Allowed` and a budget is configured, else `null`. */
    data class Reservation(
        val decision: BudgetDecision,
        val slotId: Uuid?,
    )

    /** Reserves one slot of [lane] if the budget allows (see class KDoc). Never throws (except cancellation). */
    fun reserve(
        lane: MailLane,
        now: LocalDateTime,
    ): BudgetDecision = reserveSlot(lane = lane, now = now).decision

    /** Like [reserve], but also returns the inserted slot's id so a caller that loses its claim can [release] exactly that slot. */
    fun reserveSlot(
        lane: MailLane,
        now: LocalDateTime,
    ): Reservation {
        val cfg = config ?: return Reservation(decision = BudgetDecision.Allowed, slotId = null)
        return try {
            transaction {
                maxAttempts = 1
                relaxSessionTimeouts(
                    statementTimeoutMs = STATEMENT_TIMEOUT_MS,
                    idleInTransactionTimeoutMs = IDLE_TX_TIMEOUT_MS,
                    lockTimeoutMs = LOCK_TIMEOUT_MS,
                )
                // row-lock: FOR UPDATE (singleton mail_budget_lock; serialises slot reservation, no member row involved)
                MailBudgetLockTable
                    .selectAll()
                    .where { MailBudgetLockTable.id eq SINGLETON_ID }
                    .forUpdate()
                    .single()
                val decision = decideLocked(cfg = cfg, lane = lane, now = now)
                var slotId: Uuid? = null
                if (decision is BudgetDecision.Allowed) {
                    val newId = Uuid.random()
                    MailSendSlotTable.insert {
                        it[id] = newId
                        it[reservedAt] = now
                        it[MailSendSlotTable.lane] = lane.name
                    }
                    slotId = newId
                }
                Reservation(decision = decision, slotId = slotId)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            logger.warn { "Mail budget reservation failed (${e::class.simpleName}); treating as exhausted" }
            Reservation(decision = BudgetDecision.WaitUntil(now.plusDuration(MailBudget.RECHECK)), slotId = null)
        }
    }

    /** Display variant of [reserve]: same decision, but no insert and no lock. */
    fun peek(
        lane: MailLane,
        now: LocalDateTime,
    ): BudgetDecision {
        val cfg = config ?: return BudgetDecision.Allowed
        return transaction { decideLocked(cfg = cfg, lane = lane, now = now) }
    }

    private fun decideLocked(
        cfg: MailBudgetConfig.Enabled,
        lane: MailLane,
        now: LocalDateTime,
    ): BudgetDecision {
        val cutoff = now.minusDuration(MailBudget.WINDOW)
        val used = countAfter(cutoff)
        val limit = MailBudget.limitFor(lane = lane, max = cfg.maxPerHour, reserve = cfg.reservePerHour)
        val release =
            if (used >= limit) {
                MailSendSlotTable
                    .selectAll()
                    .where { MailSendSlotTable.reservedAt greater cutoff }
                    .orderBy(MailSendSlotTable.reservedAt, SortOrder.ASC)
                    .limit(1)
                    .offset((used - limit).toLong())
                    .singleOrNull()
                    ?.get(MailSendSlotTable.reservedAt)
            } else {
                null
            }
        return MailBudget.decide(
            lane = lane,
            usedInWindow = used,
            max = cfg.maxPerHour,
            reserve = cfg.reservePerHour,
            nthOldestForRelease = release,
            now = now,
        )
    }

    private fun countAfter(cutoff: LocalDateTime): Int =
        MailSendSlotTable
            .selectAll()
            .where { MailSendSlotTable.reservedAt greater cutoff }
            .count()
            .toInt()

    /** Slots in the current window (display). 0 without a budget. */
    fun usedInWindow(now: LocalDateTime): Int {
        if (config == null) return 0
        return transaction { countAfter(now.minusDuration(MailBudget.WINDOW)) }
    }

    /** Gives a slot back after a lost claim (best effort -- an unreleased slot is merely conservative). */
    fun release(slotId: Uuid?) {
        if (config == null || slotId == null) return
        runCatching { transaction { MailSendSlotTable.deleteWhere { MailSendSlotTable.id eq slotId } } }
    }

    open fun bulkPausedUntil(): LocalDateTime? =
        transaction {
            MailBudgetLockTable
                .selectAll()
                .where { MailBudgetLockTable.id eq SINGLETON_ID }
                .singleOrNull()
                ?.get(MailBudgetLockTable.bulkPausedUntil)
        }

    /** Extends the global bulk pause to [until]; never shortens an existing, later pause. */
    fun pauseBulk(until: LocalDateTime) {
        transaction {
            MailBudgetLockTable.update({
                (MailBudgetLockTable.id eq SINGLETON_ID) and
                    (MailBudgetLockTable.bulkPausedUntil.isNull() or (MailBudgetLockTable.bulkPausedUntil less until))
            }) {
                it[bulkPausedUntil] = until
            }
        }
    }

    fun purgeSlotsOlderThan(cutoff: LocalDateTime): Int = transaction { MailSendSlotTable.deleteWhere { reservedAt less cutoff } }

    companion object {
        const val SINGLETON_ID: Short = 1
        const val STATEMENT_TIMEOUT_MS = 5_000L
        const val IDLE_TX_TIMEOUT_MS = 5_000L
        const val LOCK_TIMEOUT_MS = 2_000L

        /** Slots older than this can never matter again (window 1 h). */
        val SLOT_RETENTION = 2.hours
    }
}
