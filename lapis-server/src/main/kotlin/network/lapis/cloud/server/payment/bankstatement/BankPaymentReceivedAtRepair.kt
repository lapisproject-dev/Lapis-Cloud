package network.lapis.cloud.server.payment.bankstatement

import io.github.oshai.kotlinlogging.KotlinLogging
import network.lapis.cloud.server.db.generated.BankStatementLineTable
import network.lapis.cloud.server.db.generated.PaymentTransactionTable
import network.lapis.cloud.shared.domain.BankStatementLineStatus
import network.lapis.cloud.shared.domain.PaymentProvider
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

private val logger = KotlinLogging.logger {}

/**
 * Idempotent startup repair of legacy data: bank-statement payments booked before the booking-date fix carry the
 * moment of the assignment as `received_at`. The signature of that old behaviour is `received_at == reconciled_at`
 * (both came from the same `now`). This job sets `received_at` to the start of the line's booking day in the
 * organization zone, see [BankBookingTime.receivedAtOf].
 *
 * Only MANUAL payments whose `provider_event_id` is one of the three known bank-statement fingerprints of exactly one
 * POSTED line are touched; anything ambiguous is skipped. Journal entries are never changed. No audit entry is written:
 * there is no audit entity type for payment transactions, and the original value equals `reconciled_at`, so nothing is lost.
 * Only counters are logged. Runs once per start on the production entry point (never in `module()`); a second run
 * finds nothing to do.
 */
object BankPaymentReceivedAtRepair {
    private data class Candidate(
        val paymentId: kotlin.uuid.Uuid,
        val oldReceivedAt: kotlinx.datetime.LocalDateTime,
        val lineId: kotlin.uuid.Uuid,
        val fingerprint: String,
        val providerEventId: String,
        val bookingDate: kotlinx.datetime.LocalDate,
    )

    /** Returns the number of repaired payments. */
    fun run(): Int {
        val rows =
            transaction {
                PaymentTransactionTable
                    .join(BankStatementLineTable, JoinType.INNER, PaymentTransactionTable.id, BankStatementLineTable.paymentTransactionId)
                    .selectAll()
                    .where {
                        (PaymentTransactionTable.provider eq PaymentProvider.MANUAL) and
                            (BankStatementLineTable.status eq BankStatementLineStatus.POSTED)
                    }.mapNotNull { r ->
                        val received = r[PaymentTransactionTable.receivedAt]
                        if (received != r[PaymentTransactionTable.reconciledAt]) return@mapNotNull null
                        Candidate(
                            paymentId = r[PaymentTransactionTable.id],
                            oldReceivedAt = received,
                            lineId = r[BankStatementLineTable.id],
                            fingerprint = r[BankStatementLineTable.fingerprint],
                            providerEventId = r[PaymentTransactionTable.providerEventId],
                            bookingDate = r[BankStatementLineTable.bookingDate],
                        )
                    }
            }
        val unique = rows.groupBy { it.paymentId }
        var repaired = 0
        var skipped = 0
        for ((_, group) in unique) {
            val c = group.singleOrNull()
            val known =
                c != null &&
                    (
                        c.providerEventId == "manual-assign:${c.lineId}" ||
                            c.providerEventId == "manual-donation:${c.lineId}" ||
                            c.providerEventId == c.fingerprint
                    )
            if (c == null || !known) {
                skipped++
                continue
            }
            val target = BankBookingTime.receivedAtOf(c.bookingDate)
            if (target == c.oldReceivedAt) continue
            val updated =
                transaction {
                    PaymentTransactionTable.update({
                        (PaymentTransactionTable.id eq c.paymentId) and (PaymentTransactionTable.receivedAt eq c.oldReceivedAt)
                    }) {
                        it[receivedAt] = target
                    }
                }
            if (updated == 1) repaired++
        }
        if (repaired > 0 || skipped > 0) logger.info { "Bank payment received_at repair: repaired=$repaired skipped=$skipped" }
        return repaired
    }
}
