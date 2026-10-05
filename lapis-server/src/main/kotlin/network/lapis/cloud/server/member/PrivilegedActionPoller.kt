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
 * Welle V1.9.57 -- expires overdue privileged-action requests (PENDING past the approval window, APPROVED_WAITING past the
 * execute window) and purges old resolved rows (see [PrivilegedActionService.runDue]). Same shape as [EmailChangePoller]:
 * always on (no feature flag, no external secret), `start()`/`stop()` idempotent, [tick] exception-safe. Every due row is
 * handled in its own transaction under the member and account locks and an open-status guard, so a repeated or concurrent run
 * is idempotent. The service additionally finishes a lapsed request lazily whenever it is touched, so nothing depends on this
 * timer for correctness.
 */
internal class PrivilegedActionPoller(
    private val service: PrivilegedActionService,
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
            .onSuccess { touched -> if (touched > 0) logger.info { "privileged action poller: $touched request(s) processed" } }
            .onFailure { e -> logger.error(e) { "privileged action poller tick failed" } }
    }
}
