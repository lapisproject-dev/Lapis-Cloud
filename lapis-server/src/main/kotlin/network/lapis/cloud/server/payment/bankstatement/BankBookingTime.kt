package network.lapis.cloud.server.payment.bankstatement

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import network.lapis.cloud.server.time.OrganizationTimeZone

/**
 * Time values written by the bank-statement booking paths, derived from the bank's booking date
 * (`bank_statement_line.booking_date`, class D) -- the day the money reached the account -- instead of the moment
 * the treasurer assigned the line. See `docs/architecture/bank-statement-import.adoc`.
 */
internal object BankBookingTime {
    /** Class B: the bank booking date at 00:00 organization wall-clock (bridge paidAt, contribution.paid_at, webhook occurredAt). */
    fun wallClockOf(bookingDate: LocalDate): LocalDateTime = LocalDateTime(bookingDate, LocalTime(0, 0))

    /** Class A: `payment_transaction.received_at` of a bank-statement payment (start of the booking day in the organization zone, as UTC). */
    fun receivedAtOf(bookingDate: LocalDate): LocalDateTime = OrganizationTimeZone.systemStartOfDay(bookingDate)
}
