package network.lapis.cloud.server.openitem.dunning

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.OpenItemTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.shared.domain.OpenItemDirection
import network.lapis.cloud.shared.domain.OpenItemStatusSets
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Default page size for the keyset-paginated `ORDER BY due_date ASC, id ASC LIMIT ...` scan over
 * DUNNABLE receivables -- same bounded-page reasoning
 * [network.lapis.cloud.server.payment.dunning.DunningPoller]'s own `PHASE_B_QUERY_BATCH_SIZE` KDoc
 * documents.
 */
private const val PHASE_B_QUERY_BATCH_SIZE = 500

/**
 * Welle V1.4.15 -- application-scoped poller for the receivable-dunning domain, structurally
 * independent from [network.lapis.cloud.server.payment.dunning.DunningPoller] (see
 * [ReceivableDunningService] KDoc). Deliberately much thinner than its member-contribution sibling
 * -- no PDF generation phase, no postal-dispatch phase, a single scan-and-issue pass per tick.
 * Modelled structurally after [network.lapis.cloud.server.payment.sepa.SepaBatchPoller]: ONE
 * coroutine (`SupervisorJob() + Dispatchers.IO`), `while (isActive) { tick(); delay(interval) }`,
 * [tick] public and exception-safe at two levels, [start]/[stop] idempotent, NO in-memory state
 * (every tick re-queries its candidates fresh -- restart reconciliation).
 */
class ReceivableDunningPoller(
    private val config: ReceivableDunningConfig,
    private val clock: () -> LocalDateTime = { DbClock.nowLocalDateTime() },
    private val phaseBQueryBatchSize: Int = PHASE_B_QUERY_BATCH_SIZE,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    /** Idempotent -- a second call while already running is a no-op. */
    fun start() {
        if (loopJob != null) return
        loopJob =
            scope.launch {
                while (isActive) {
                    tick()
                    delay(config.pollIntervalSeconds.seconds)
                }
            }
    }

    /** Cancels the poll loop -- for tests/graceful shutdown. */
    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    /**
     * One poll pass. A complete no-op if `organization_settings.receivable_dunning_enabled` is
     * `false` -- independent, second gate on top of [ReceivableDunningConfig.pollerEnabled] (same
     * "sandbox-first" discipline [network.lapis.cloud.server.payment.dunning.DunningPoller]
     * already establishes). Exception-safe at two levels (whole tick + each row individually) so
     * one broken row never stops the others.
     */
    fun tick() {
        try {
            val enabled =
                transaction {
                    OrganizationSettingsTable
                        .selectAll()
                        .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                        .single()[OrganizationSettingsTable.receivableDunningEnabled]
                }
            if (!enabled) return

            val asOf = clock().date
            var afterDueDate: LocalDate? = null
            var afterId: Uuid? = null
            var processed = 0
            while (processed < config.maxNoticesPerTick) {
                val page =
                    transaction {
                        var condition =
                            (OpenItemTable.direction eq OpenItemDirection.RECEIVABLE) and
                                (OpenItemTable.status inList OpenItemStatusSets.SETTLEABLE.toList()) and
                                (OpenItemTable.creationJournalEntryId.isNotNull())
                        val cursorDueDate = afterDueDate
                        val cursorId = afterId
                        if (cursorDueDate != null && cursorId != null) {
                            condition =
                                condition and (
                                    (OpenItemTable.dueDate greater cursorDueDate) or
                                        ((OpenItemTable.dueDate eq cursorDueDate) and (OpenItemTable.id greater cursorId))
                                )
                        }
                        OpenItemTable
                            .selectAll()
                            .where { condition }
                            .orderBy(OpenItemTable.dueDate to SortOrder.ASC, OpenItemTable.id to SortOrder.ASC)
                            .limit(phaseBQueryBatchSize)
                            .map { it[OpenItemTable.id] to it[OpenItemTable.dueDate] }
                    }
                if (page.isEmpty()) break
                page.forEach { (itemId, dueDate) ->
                    if (processed >= config.maxNoticesPerTick) return@forEach
                    processTickItem(itemId = itemId, asOf = asOf)
                    processed++
                    afterId = itemId
                    afterDueDate = dueDate
                }
                if (page.size < phaseBQueryBatchSize) break
            }
        } catch (e: Exception) {
            logger.error(e) { "ReceivableDunningPoller.tick failed" }
        }
    }

    private fun processTickItem(
        itemId: Uuid,
        asOf: LocalDate,
    ) {
        try {
            transaction {
                ReceivableDunningEngine.issueNextLevel(
                    itemId = itemId,
                    asOf = asOf,
                    respectGraceDays = true,
                    actorMemberId = null,
                    actorRole = null,
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "ReceivableDunningPoller: failed to process OpenItem $itemId" }
        }
    }
}
