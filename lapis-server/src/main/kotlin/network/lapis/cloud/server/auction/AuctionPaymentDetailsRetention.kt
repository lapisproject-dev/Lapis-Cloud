package network.lapis.cloud.server.auction

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AuctionPaymentDetailsTable
import network.lapis.cloud.server.db.generated.AuctionTable
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AuctionStatus
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * V1.9.320 -- when the sealed payment details of a Fiat auction are deleted for good:
 *
 * - `CLOSED_NO_SALE`: immediately (there is nobody to pay).
 * - `COMPLETED`: [COMPLETED_RETENTION_DAYS] after the LATER of the two confirmations.
 * - `AWAITING_PAYMENT`/`PAID` that never complete (the winner never pays or the goods never arrive): [UNFINISHED_RETENTION_DAYS] after the award.
 * - `OPEN`: never (the seller may still correct them).
 *
 * Pure decision in [isDue]; [purgeDue] applies it. Called by the poller and lazily by `getAuctionPaymentDetails`.
 */
internal object AuctionPaymentDetailsRetention {
    const val COMPLETED_RETENTION_DAYS = 30L
    const val UNFINISHED_RETENTION_DAYS = 180L
    private const val MAX_PER_RUN = 500

    fun isDue(
        status: AuctionStatus,
        settledAt: LocalDateTime?,
        paymentConfirmedAt: LocalDateTime?,
        deliveryConfirmedAt: LocalDateTime?,
        now: LocalDateTime,
    ): Boolean =
        when (status) {
            AuctionStatus.CLOSED_NO_SALE -> true
            AuctionStatus.COMPLETED -> {
                val last = listOfNotNull(paymentConfirmedAt, deliveryConfirmedAt).maxOrNull() ?: settledAt
                last != null && last <= minusDays(value = now, days = COMPLETED_RETENTION_DAYS)
            }
            AuctionStatus.AWAITING_PAYMENT, AuctionStatus.PAID ->
                settledAt != null && settledAt <= minusDays(value = now, days = UNFINISHED_RETENTION_DAYS)
            AuctionStatus.OPEN, AuctionStatus.SETTLED -> false
        }

    /** Must run inside a `transaction {}` of the caller. Returns the number of deleted rows. */
    fun purgeDueInTransaction(now: LocalDateTime): Int {
        val completedCutoff = minusDays(value = now, days = COMPLETED_RETENTION_DAYS)
        val unfinishedCutoff = minusDays(value = now, days = UNFINISHED_RETENTION_DAYS)
        // The due-condition lives in the WHERE (mirror of [isDue]); otherwise the LIMIT could keep returning the same 500 rows that are not due.
        val dueCondition: Op<Boolean> =
            (AuctionTable.status eq AuctionStatus.CLOSED_NO_SALE) or
                (
                    (AuctionTable.status eq AuctionStatus.COMPLETED) and
                        (AuctionTable.paymentConfirmedAt.isNull() or (AuctionTable.paymentConfirmedAt lessEq completedCutoff)) and
                        (AuctionTable.deliveryConfirmedAt.isNull() or (AuctionTable.deliveryConfirmedAt lessEq completedCutoff)) and
                        (
                            AuctionTable.paymentConfirmedAt.isNotNull() or AuctionTable.deliveryConfirmedAt.isNotNull() or
                                (AuctionTable.settledAt lessEq completedCutoff)
                        )
                ) or
                (
                    ((AuctionTable.status eq AuctionStatus.AWAITING_PAYMENT) or (AuctionTable.status eq AuctionStatus.PAID)) and
                        (AuctionTable.settledAt lessEq unfinishedCutoff)
                )
        val due =
            (AuctionPaymentDetailsTable innerJoin AuctionTable)
                .selectAll()
                .where { dueCondition }
                .limit(MAX_PER_RUN)
                .filter { row ->
                    isDue(
                        status = row[AuctionTable.status],
                        settledAt = row[AuctionTable.settledAt],
                        paymentConfirmedAt = row[AuctionTable.paymentConfirmedAt],
                        deliveryConfirmedAt = row[AuctionTable.deliveryConfirmedAt],
                        now = now,
                    )
                }.map { it[AuctionPaymentDetailsTable.auctionId] }
        due.forEach { id -> AuctionPaymentDetailsTable.deleteWhere { AuctionPaymentDetailsTable.auctionId eq id } }
        return due.size
    }

    /** True when the row of [auctionId] must go right now (used by the lazy purge). */
    fun isDueNow(
        auctionId: Uuid,
        now: LocalDateTime,
    ): Boolean {
        val row = AuctionTable.selectAll().where { AuctionTable.id eq auctionId }.singleOrNull() ?: return false
        return isDue(
            status = row[AuctionTable.status],
            settledAt = row[AuctionTable.settledAt],
            paymentConfirmedAt = row[AuctionTable.paymentConfirmedAt],
            deliveryConfirmedAt = row[AuctionTable.deliveryConfirmedAt],
            now = now,
        )
    }

    private fun minusDays(
        value: LocalDateTime,
        days: Long,
    ): LocalDateTime {
        val zone = ServerClock.zone
        return value.toInstant(zone).minus(days.days).toLocalDateTime(zone)
    }
}

/**
 * V1.9.320 -- same shape as `NoticeRetentionPoller`: always on (no flag), `start()`/`stop()` idempotent, [tick] never throws. Logs the number only,
 * never a value.
 */
internal class AuctionPaymentDetailsRetentionPoller(
    private val intervalHours: Long = 6,
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

    fun tick(now: LocalDateTime = DbClock.nowLocalDateTime()): Int =
        runCatching { transaction { AuctionPaymentDetailsRetention.purgeDueInTransaction(now) } }
            .onSuccess { n -> if (n > 0) logger.info { "auction payment details retention: deleted $n row(s)" } }
            .onFailure { e -> logger.error(e) { "auction payment details retention tick failed" } }
            .getOrDefault(0)
}
