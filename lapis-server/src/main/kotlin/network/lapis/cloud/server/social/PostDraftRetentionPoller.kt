package network.lapis.cloud.server.social

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
 * Welle V1.8.2b (MINOR-3) -- thin, always-active poller (no feature flag, no external secrets),
 * 1:1 [network.lapis.cloud.server.contribution.ContributionReliefRedactionPoller]'s own pattern:
 * one coroutine, `while (isActive) { tick(); delay(interval) }`, `start()`/`stop()` idempotent,
 * [tick] `public` and exception-safe (a single failing run never kills the coroutine). Tests call
 * [PostDraftRetention.deleteDueRows] directly, never this poller, for the same "no timing
 * flakiness" reason that class' own KDoc gives.
 *
 * **Always started, independent of `McpConfig`** -- deliberately NOT gated behind
 * `mcpConfig.isOperational`/`isWriteOperational`: existing drafts keep existing (and keep needing
 * cleanup) even after an operator switches MCP itself off, see `PostDraftRetention` class KDoc.
 */
internal class PostDraftRetentionPoller(
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
        runCatching { PostDraftRetention.deleteDueRows(DbClock.nowLocalDateTime()) }
            .onSuccess { (discarded, released) ->
                if (discarded > 0 || released > 0) {
                    logger.info { "mcp post draft retention: deleted $discarded discarded, $released released draft row(s)" }
                }
            }.onFailure { e -> logger.error(e) { "mcp post draft retention tick failed" } }
    }
}
