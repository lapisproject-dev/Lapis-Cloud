package network.lapis.cloud.server.economy.oracle

import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.generated.BankStatementLineTable
import network.lapis.cloud.shared.domain.BankStatementLineStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * V1.9.327 -- the link between a donation payment and its bank booking, used by the reference-date conversion. A payment counts only when it is
 * backed by EXACTLY ONE `bank_statement_line` in status POSTED (as in V168; a payment with several is ambiguous and left out), and the date is that line's
 * `booking_date` -- the exact calendar day of the statement, never derived from `payment_transaction.received_at` (which is a start-of-day value in the
 * ORGANIZATION zone and may have been corrected afterwards).
 * PayPal/Stripe/anonymous payments have no bank line: their donation has no booking day (V1.9.329) and the reference date is then always chosen manually,
 * with a justification. The candidates are read by [DonationStore.candidatesOf]. All functions expect an open transaction.
 */
internal object DonationPaymentLink {
    const val MAX_CANDIDATES = 50

    /** The booking day of [paymentId], or `null` when there is not exactly one POSTED line. */
    fun bookingDateOf(paymentId: Uuid): LocalDate? {
        val dates =
            BankStatementLineTable
                .selectAll()
                .where {
                    (BankStatementLineTable.paymentTransactionId eq paymentId) and
                        (BankStatementLineTable.status eq BankStatementLineStatus.POSTED)
                }.limit(2)
                .map { it[BankStatementLineTable.bookingDate] }
        return dates.singleOrNull()
    }
}
