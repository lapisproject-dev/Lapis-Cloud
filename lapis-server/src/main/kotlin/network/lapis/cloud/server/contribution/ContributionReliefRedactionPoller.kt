package network.lapis.cloud.server.contribution

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
 * Welle V1.4.10 "Beitragsvergünstigungen" -- dünner, immer aktiver Poller (kein Feature-Flag,
 * keine externen Secrets nötig) nach dem Muster von `network.lapis.cloud.server.webhook
 * .WebhookDeliveryPoller`: EINE Coroutine, `while (isActive) { tick(); delay(interval) }`,
 * `start()`/`stop()` idempotent. [tick] ist `public` und exception-sicher (ein einzelner
 * fehlschlagender Lauf beendet die Coroutine nicht) -- Tests rufen ausschließlich
 * [ContributionReliefRedaction.redactDueReasonTexts] direkt auf, nie diesen Poller (keine
 * Timing-Flakiness).
 */
class ContributionReliefRedactionPoller(
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
        runCatching { ContributionReliefRedaction.redactDueReasonTexts(DbClock.nowLocalDateTime()) }
            .onSuccess { count -> if (count > 0) logger.info { "contribution relief redaction: redacted $count reason_text value(s)" } }
            .onFailure { e -> logger.error(e) { "contribution relief redaction tick failed" } }
    }
}
