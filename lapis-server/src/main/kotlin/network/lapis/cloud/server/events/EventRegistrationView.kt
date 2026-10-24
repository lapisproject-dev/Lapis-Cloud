package network.lapis.cloud.server.events

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.shared.domain.EventRegistrationPaymentState
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import org.jetbrains.exposed.v1.core.ResultRow
import java.math.BigDecimal

/**
 * V1.9.204 -- how one registration row presents itself in the overview table and the CSV export. Shared so both always agree.
 * Must run inside a transaction (the paid check reads the checkout sessions).
 */
internal object EventRegistrationView {
    /** A `PENDING_PAYMENT` whose hold has lapsed shows (and counts) as `EXPIRED` -- the same cut-off as [EventStore.countOccupied]. */
    fun effectiveStatus(
        row: ResultRow,
        now: LocalDateTime,
    ): EventRegistrationStatus {
        val raw = row[EventRegistrationTable.status]
        val hold = row[EventRegistrationTable.holdExpiresAt]
        return if (raw == EventRegistrationStatus.PENDING_PAYMENT && (hold == null || hold <= now)) EventRegistrationStatus.EXPIRED else raw
    }

    fun paymentState(row: ResultRow): EventRegistrationPaymentState =
        when {
            row[EventRegistrationTable.feeAmount].compareTo(BigDecimal.ZERO) == 0 -> EventRegistrationPaymentState.FREE
            row[EventRegistrationTable.refundMarkedAt] != null -> EventRegistrationPaymentState.REFUNDED
            EventRefunds.paidAmount(row[EventRegistrationTable.id]) != null -> EventRegistrationPaymentState.PAID
            row[EventRegistrationTable.status] == EventRegistrationStatus.PENDING_PAYMENT -> EventRegistrationPaymentState.OPEN
            else -> EventRegistrationPaymentState.UNPAID
        }
}
