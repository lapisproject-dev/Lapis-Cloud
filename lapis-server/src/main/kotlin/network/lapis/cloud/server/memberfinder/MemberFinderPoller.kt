package network.lapis.cloud.server.memberfinder

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.rpc.MemberFinderSettingsStore
import network.lapis.cloud.server.time.ServerClock
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.hours

// Privacy rule of this file: the logs name nothing about a person, a region or a number.
private val logger = KotlinLogging.logger {}

/**
 * V1.9.144 -- hourly housekeeping of the member finder (`CarpoolRetentionPoller` shape): `start()`/`stop()` are idempotent and [tick] never throws.
 * With the instance switch OFF a tick returns at once (the switch-off already deleted everything).
 */
internal class MemberFinderPoller(
    private val intervalHours: Long = 1,
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

    fun tick(now: LocalDateTime = ServerClock.now()) {
        runCatching {
            transaction {
                // The global finder lock first (decision E9), so the housekeeping never interleaves with a request or a switch-off.
                val settings = MemberFinderSettingsStore.lockAndLoad()
                if (settings.enabled) MemberFinderRetention.run(now)
            }
        }.onFailure { e -> logger.error(e) { "member finder housekeeping tick failed" } }
    }
}
