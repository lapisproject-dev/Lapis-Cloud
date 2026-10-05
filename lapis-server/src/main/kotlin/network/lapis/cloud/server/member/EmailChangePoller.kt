package network.lapis.cloud.server.member

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import network.lapis.cloud.server.db.DbClock
import kotlin.time.Duration.Companion.minutes

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.9.56 -- drives the time-based part of the address-change lifecycle: expires overdue changes, applies
 * confirmed path B0/C changes once their 72 hour warning period has elapsed, purges old resolved rows (see
 * [EmailChangeService.runDue]). Same shape as `CarpoolRetentionPoller`: always on (no feature flag, no external secret),
 * `start()`/`stop()` idempotent, [tick] exception-safe. Every due row is handled in its own transaction under the member
 * lock and a `status = PENDING` guard, so a repeated or concurrent run is idempotent.
 */
internal class EmailChangePoller(
    private val service: EmailChangeService,
    private val intervalMinutes: Long = 15,
) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job =
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                while (isActive) {
                    tick()
                    delay(intervalMinutes.minutes)
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun tick() {
        runCatching { service.runDue(DbClock.nowLocalDateTime(TimeZone.UTC)) }
            .onSuccess { touched -> if (touched > 0) logger.info { "email change poller: $touched change(s) processed" } }
            .onFailure { e -> logger.error(e) { "email change poller tick failed" } }
    }
}
