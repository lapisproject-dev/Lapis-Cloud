package network.lapis.cloud.server.economy.oracle

import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.generated.BankStatementLineTable
import network.lapis.cloud.server.db.generated.PaymentTransactionTable
import network.lapis.cloud.server.db.generated.PriceOracleConversionTable
import network.lapis.cloud.shared.domain.BankStatementLineStatus
import network.lapis.cloud.shared.domain.PaymentIntent
import network.lapis.cloud.shared.domain.PaymentTransactionStatus
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.notExists
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import kotlin.uuid.Uuid

/** One captured bank donation of a member that no conversion has used yet. [bookingDate] is the calendar day of the bank statement line. */
internal data class BankedDonation(
    val id: Uuid,
    val amount: BigDecimal,
    val currency: String,
    val bookingDate: LocalDate,
    /** V1.9.328: the donation row, if one exists already (backfilled by V174 or created by an earlier, rolled-forward step); `null` otherwise. */
    val donationId: Uuid? = null,
)

/**
 * V1.9.327 -- the link between a donation payment and its bank booking, used by the reference-date conversion. A payment counts only when it is
 * backed by EXACTLY ONE `bank_statement_line` in status POSTED (as in V168; a payment with several is ambiguous and left out), and the date is that line's
 * `booking_date` -- the exact calendar day of the statement, never derived from `payment_transaction.received_at` (which is a start-of-day value in the
 * ORGANIZATION zone and may have been corrected afterwards). PayPal/Stripe payments have no bank line and are never candidates. All functions expect an
 * open transaction.
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

    /** Captured donations of [memberId] in [currency] with exactly one POSTED bank line and no conversion yet (a conversion always carries payment_transaction_id, V174 CHECK + composite FK, so the payment test covers the donation too), newest first, at most [MAX_CANDIDATES]. */
    fun candidatesOf(
        memberId: Uuid,
        currency: String,
    ): List<BankedDonation> {
        val other = BankStatementLineTable.alias("other_line")
        val joined =
            PaymentTransactionTable.join(
                BankStatementLineTable,
                JoinType.INNER,
                onColumn = PaymentTransactionTable.id,
                otherColumn = BankStatementLineTable.paymentTransactionId,
                additionalConstraint = { BankStatementLineTable.status eq BankStatementLineStatus.POSTED },
            )
        val rows =
            joined
                .selectAll()
                .where {
                    (PaymentTransactionTable.memberId eq memberId) and
                        (PaymentTransactionTable.intent eq PaymentIntent.DONATION) and
                        (PaymentTransactionTable.status eq PaymentTransactionStatus.CAPTURED) and
                        (PaymentTransactionTable.currency eq currency.uppercase()) and
                        notExists(
                            PriceOracleConversionTable
                                .selectAll()
                                .where { PriceOracleConversionTable.paymentTransactionId eq PaymentTransactionTable.id },
                        ) and
                        notExists(
                            other
                                .selectAll()
                                .where {
                                    (other[BankStatementLineTable.paymentTransactionId] eq PaymentTransactionTable.id) and
                                        (other[BankStatementLineTable.status] eq BankStatementLineStatus.POSTED) and
                                        (other[BankStatementLineTable.id] neq BankStatementLineTable.id)
                                },
                        )
                }.orderBy(PaymentTransactionTable.receivedAt to SortOrder.DESC)
                .limit(MAX_CANDIDATES)
                .toList()
        val donationIds = DonationStore.donationIdsByPayment(rows.map { it[PaymentTransactionTable.id] })
        return rows.map {
            BankedDonation(
                id = it[PaymentTransactionTable.id],
                amount = it[PaymentTransactionTable.amount],
                currency = it[PaymentTransactionTable.currency],
                bookingDate = it[BankStatementLineTable.bookingDate],
                donationId = donationIds[it[PaymentTransactionTable.id]],
            )
        }
    }
}
