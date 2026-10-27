package network.lapis.cloud.server.mail.newsletter

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import network.lapis.cloud.server.db.DbClock
import java.io.File
import kotlin.time.Duration.Companion.hours

private val logger = KotlinLogging.logger {}

/** Same lifecycle shape as `CarpoolRetentionPoller`: always on, idempotent `start()`/`stop()`, exception-safe [tick]. */
internal class MailingTrackingRetentionPoller(
    private val intervalHours: Long = 24,
    /** V1.9.232 -- where attachment files live; `null` skips the attachment retention (tracking retention is unaffected). */
    private val attachmentStorageRoot: File? = null,
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
        attachmentStorageRoot?.let { root ->
            runCatching {
                var total = 0
                var batch: Int
                do {
                    batch =
                        MailingAttachmentRetention.purgeDue(now = DbClock.nowLocalDateTime(), storageRoot = root, batchSize = BATCH_SIZE)
                    total += batch
                } while (batch >= BATCH_SIZE && total < MAX_PER_TICK)
                total
            }.onSuccess { purged -> if (purged > 0) logger.info { "mailing attachment retention: removed $purged file(s)" } }
                .onFailure { e -> logger.error(e) { "mailing attachment retention tick failed" } }
        }
        runCatching {
            var total = 0
            var batch: Int
            do {
                batch = MailingTrackingRetention.purgeDue(now = DbClock.nowLocalDateTime())
                total += batch
            } while (batch >= BATCH_SIZE && total < MAX_PER_TICK)
            total
        }.onSuccess { purged -> if (purged > 0) logger.info { "mailing tracking retention: cleaned $purged delivery row(s)" } }
            .onFailure { e -> logger.error(e) { "mailing tracking retention tick failed" } }
    }

    private companion object {
        const val BATCH_SIZE = 200
        const val MAX_PER_TICK = 20_000
    }
}
