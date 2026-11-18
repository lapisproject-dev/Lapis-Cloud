package network.lapis.cloud.server.partg

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.mail.plusDuration
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.PartgReviewerRules
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

private val logger = KotlinLogging.logger {}

/**
 * V1.9.310 (R6b) -- once a day: deletes the auditor's sessions that are past their end and sets the E-mail address of every invitation to NULL that
 * ended more than 90 days ago (the address is personal data of a third party; the invitation row, its scope and the usage list stay as a pseudonymous
 * record, like the audit log). Same lifecycle shape as `MailingTrackingRetentionPoller`: always on, idempotent `start()`/`stop()`, exception-safe [tick].
 */
internal class PartgReviewerRetentionPoller(
    private val intervalHours: Long = 24,
    private val clock: () -> LocalDateTime = { ServerClock.now() },
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

    /** One pass; never throws. Returns (sessions removed, addresses erased). */
    fun tick(): Pair<Int, Int> =
        runCatching {
            val now = clock()
            transaction {
                val sessions = PartgReviewerStore.purgeExpiredSessions(now)
                val emails = PartgReviewerStore.nullEmailsEndedBefore(now.plusDuration((-PartgReviewerRules.EMAIL_RETENTION_DAYS).days))
                sessions to emails
            }
        }.onSuccess { (sessions, emails) ->
            if (sessions > 0 ||
                emails > 0
            ) {
                logger.info { "partg reviewer retention: $sessions session(s) removed, $emails address(es) erased" }
            }
        }.onFailure { logger.error { "partg reviewer retention tick failed: ${it::class.simpleName}" } }
            .getOrDefault(0 to 0)
}
