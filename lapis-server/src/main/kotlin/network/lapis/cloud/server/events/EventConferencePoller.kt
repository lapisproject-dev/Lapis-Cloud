package network.lapis.cloud.server.events

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import network.lapis.cloud.server.time.ServerClock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// Privacy rule of this file: logs carry numbers and exception classes only -- never an id, a name or a link.
private val logger = KotlinLogging.logger {}

/**
 * Welle V1.9.113 -- ends the Lapis video conferences of events once their window (event end + 60 minutes) is over, and the ones a
 * moderator already ended through `endRoom`. Runs once at start and then every [intervalSeconds] (same shape as
 * `EncounterSpacePoller`); started only when conferencing is configured. All work is delegated to
 * [EventConferenceProvisioner.endExpired], which closes a room through the shared `ConferenceRoomCloser` -- there is no second teardown.
 * One failing sweep never stops the loop. At most 100 conferences per sweep.
 */
class EventConferencePoller internal constructor(
    private val provisioner: EventConferenceProvisioner,
    private val intervalSeconds: Long = 60,
    private val clock: () -> Instant = { ServerClock.nowInstant() },
) {
    private var job: Job? = null

    /** Idempotent. Runs one [tick] immediately, then every [intervalSeconds]. */
    fun start() {
        if (job?.isActive == true) return
        job =
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                while (isActive) {
                    tick()
                    delay(intervalSeconds.seconds)
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** One sweep. Never throws; returns the number of conferences it ended. */
    suspend fun tick(): Int =
        try {
            val ended = provisioner.endExpired(clock())
            if (ended > 0) logger.info { "event conference poller: $ended conference(s) ended" }
            ended
        } catch (e: Exception) {
            logger.error { "event conference poller: the sweep failed (${e::class.simpleName})" }
            0
        }
}
