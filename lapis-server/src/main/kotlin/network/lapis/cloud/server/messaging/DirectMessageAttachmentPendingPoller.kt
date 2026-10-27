package network.lapis.cloud.server.messaging

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.time.ServerClock
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import kotlin.time.Duration.Companion.hours

private val logger = KotlinLogging.logger {}
private const val PURGE_BATCH_SIZE = 200
private const val MAX_BATCHES_PER_TICK = 50

/**
 * V1.9.233 -- deletes PENDING direct-message uploads (`message_id` NULL) that were never sent within
 * [DirectMessagePendingLimits.PENDING_TTL_HOURS] (24 h), rows first (batched), then the files. Runs HOURLY -- not on the 24 h cadence of
 * `MailingTrackingRetentionPoller`, which would let a pending file live up to 48 h: with this poller a pending file is gone after at
 * most about 25 h. Sent attachments are never touched. Same life-cycle pattern as `CarpoolRetentionPoller` (idempotent start/stop,
 * exception-safe [tick]); logs carry counts only.
 */
internal class DirectMessageAttachmentPendingPoller(
    private val storageRoot: File,
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

    /** One purge pass; returns the number of deleted pending uploads. Never throws. */
    fun tick(): Int =
        runCatching {
            val cutoff =
                (ServerClock.nowInstant() - DirectMessagePendingLimits.PENDING_TTL_HOURS.hours)
                    .toLocalDateTime(ServerClock.zone)
            var total = 0
            repeat(MAX_BATCHES_PER_TICK) {
                val keys = transaction { DirectMessageAttachmentStore.purgePendingDueInTx(cutoff = cutoff, batchSize = PURGE_BATCH_SIZE) }
                if (keys.isEmpty()) return@runCatching total
                DirectMessageAttachmentStore.deleteFiles(root = storageRoot, keys = keys)
                total += keys.size
            }
            total
        }.onSuccess { deleted ->
            if (deleted >
                0
            ) {
                logger.info { "direct message attachment retention: deleted $deleted pending upload(s)" }
            }
        }.onFailure { e -> logger.error(e) { "direct message attachment retention tick failed" } }
            .getOrDefault(0)
}
