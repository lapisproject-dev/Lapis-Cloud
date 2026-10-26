package network.lapis.cloud.server.dashboard

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import network.lapis.cloud.server.db.withSavepoint
import network.lapis.cloud.shared.domain.DASHBOARD_COUNT_CAP
import network.lapis.cloud.shared.domain.DashboardModuleKeys
import network.lapis.cloud.shared.domain.DashboardReasonKeys
import network.lapis.cloud.shared.domain.DashboardSignalDto
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private val logger = KotlinLogging.logger {}

/**
 * V1.9.224 -- runs the sources in ONE transaction, each in its own savepoint, so one failing source costs only its own signals. On
 * PostgreSQL a failed statement aborts the whole transaction (`25P02`); without the savepoint every following source would fail too.
 * The log line carries the module key and the exception class, never a member id or a message.
 */
internal object DashboardSignalCollector {
    private val PARAM_VALUE = Regex("""^(\d{1,6}|\d{4}-\d{2}-\d{2}(T[\d:.]+Z?)?)$""")
    private val DEADLINE_VALUE = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$""")
    private val UUID_VALUE = Regex("""^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$""")

    fun collect(
        ctx: SignalContext,
        sources: List<DashboardSignalSource>,
    ): List<DashboardSignalDto> {
        val collected =
            transaction {
                sources.flatMapIndexed { index, source ->
                    try {
                        withSavepoint(name = "dashboard_signal_$index") { source.collect(ctx) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn { "dashboard signal source failed: ${source.moduleKey} (${e::class.simpleName})" }
                        emptyList()
                    }
                }
            }
        return sanitizeAndSort(collected)
    }

    /** Defence in depth against free text and bad shapes: a signal that does not fit the contract is dropped, not repaired. */
    fun sanitizeAndSort(signals: List<DashboardSignalDto>): List<DashboardSignalDto> =
        signals
            .mapNotNull { sanitize(it) }
            .sortedWith(
                compareByDescending<DashboardSignalDto> { it.priority }
                    .thenBy(nullsLast()) { it.deadline }
                    .thenBy { it.moduleKey }
                    .thenBy { it.reasonKey },
            )

    private fun sanitize(signal: DashboardSignalDto): DashboardSignalDto? {
        val valid =
            signal.count >= 1 &&
                signal.moduleKey in DashboardModuleKeys.ALL &&
                signal.reasonKey in DashboardReasonKeys.ALL &&
                signal.params.values.all { PARAM_VALUE.matches(it) } &&
                (signal.deadline == null || DEADLINE_VALUE.matches(signal.deadline!!)) &&
                (signal.objectId == null || (signal.count == 1 && UUID_VALUE.matches(signal.objectId!!)))
        if (!valid) {
            logger.warn { "dashboard signal dropped: ${signal.moduleKey}/${signal.reasonKey} (contract violation)" }
            return null
        }
        return signal.copy(count = signal.count.coerceAtMost(DASHBOARD_COUNT_CAP))
    }
}
