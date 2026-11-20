package network.lapis.cloud.server.carpool

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import network.lapis.cloud.server.db.DbClock
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.hours

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.9.12 "Mitfahrzentrale" -- 1:1
 * `network.lapis.cloud.server.social.PostDraftRetentionPoller`s Muster: immer aktiv (kein
 * Feature-Flag, keine externen Secrets), `start()`/`stop()` idempotent, [tick] exception-safe.
 */
internal class CarpoolRetentionPoller(
    private val intervalHours: Long = 24,
) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job =
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                while (isActive) {
                    tick()
                    delay(intervalHours.hours)
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Three independent steps, each exception-safe on its own: a failure of one never blocks the next. */
    fun tick() {
        val now = DbClock.nowLocalDateTime()
        runCatching { CarpoolRetention.deleteDueRows(now) }
            .onSuccess { deleted -> if (deleted > 0) logger.info { "carpool posting retention: deleted $deleted row(s)" } }
            .onFailure { e -> logger.error(e) { "carpool posting retention tick failed" } }
        // V1.9.313: expired tombstones go (a partner no longer needs them), then opted-in trips of authors who left are withdrawn.
        runCatching { drain { transaction { CarpoolFederationStore.deleteExpiredTombstones(now = now, limit = BATCH) } } }
            .onSuccess { deleted -> if (deleted > 0) logger.info { "carpool tombstones: deleted $deleted expired row(s)" } }
            .onFailure { e -> logger.error(e) { "carpool tombstone cleanup tick failed" } }
        // V1.9.314: copies of hints received from partners and their local blocks go when they expire.
        runCatching { drain { transaction { CarpoolReceivedStore.deleteExpired(now = now, limit = BATCH) } } }
            .onSuccess { deleted ->
                if (deleted > 0) {
                    CarpoolPullCounters.expiredDeleted.addAndGet(deleted.toLong())
                    logger.info { "carpool received hints: deleted $deleted expired row(s)" }
                }
            }.onFailure { e -> logger.error(e) { "carpool received hint cleanup tick failed" } }
        runCatching {
            transaction {
                CarpoolWithdrawals.sweepInactiveAuthors(now = now, ids = CarpoolWithdrawalKey.current(), limit = SWEEP_LIMIT)
            }
        }.onFailure { e -> logger.error(e) { "carpool withdrawal sweep tick failed" } }
    }

    /** Calls [batch] (each call is its own transaction, at most [BATCH] rows per table) until a call deletes less than a full batch. */
    private fun drain(batch: () -> Int): Int {
        var total = 0
        repeat(MAX_ROUNDS) {
            val n = batch()
            total += n
            if (n < BATCH) return total
        }
        return total
    }

    private companion object {
        const val SWEEP_LIMIT = 5000
        const val BATCH = 2000
        const val MAX_ROUNDS = 50
    }
}
