package network.lapis.cloud.server.bulletin

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.gallery.AttachedImageStorage
import kotlin.time.Duration.Companion.hours

private val logger = KotlinLogging.logger {}

/**
 * V1.9.297 -- same shape as `CarpoolRetentionPoller`: always on (no flag, no secrets), `start()`/`stop()` idempotent, [tick] never throws. Each tick
 * marks passed notices `EXPIRED` and deletes the due ones for good; the picture FILES are deleted after the database transaction committed. Logs
 * numbers only.
 */
internal class NoticeRetentionPoller(
    private val storage: AttachedImageStorage,
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
        val now = DbClock.nowLocalDateTime()
        runCatching { NoticeRetention.expireDue(now) }
            .onSuccess { expired -> if (expired > 0) logger.info { "notice retention: marked $expired notice(s) as expired" } }
            .onFailure { e -> logger.error(e) { "notice retention (expire) tick failed" } }
        runCatching {
            val keys = NoticeRetention.deleteDue(now)
            storage.deleteAfterCommit(keys)
            keys.size
        }.onSuccess { files -> if (files > 0) logger.info { "notice retention: deleted $files picture file(s)" } }
            .onFailure { e -> logger.error(e) { "notice retention (delete) tick failed" } }
    }
}
