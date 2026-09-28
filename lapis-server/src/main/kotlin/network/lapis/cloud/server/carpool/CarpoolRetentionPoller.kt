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
import kotlin.time.Duration.Companion.hours

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.9.12 "Mitfahrerzentrale" -- 1:1
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

    fun tick() {
        runCatching { CarpoolRetention.deleteDueRows(DbClock.nowLocalDateTime()) }
            .onSuccess { deleted -> if (deleted > 0) logger.info { "carpool posting retention: deleted $deleted row(s)" } }
            .onFailure { e -> logger.error(e) { "carpool posting retention tick failed" } }
    }
}
