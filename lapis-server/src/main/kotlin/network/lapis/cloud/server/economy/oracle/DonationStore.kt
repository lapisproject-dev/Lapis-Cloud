package network.lapis.cloud.server.economy.oracle

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.DonationTable
import network.lapis.cloud.server.db.generated.PaymentTransactionTable
import network.lapis.cloud.server.db.generated.PriceOracleConversionTable
import network.lapis.cloud.server.db.isUniqueViolation
import network.lapis.cloud.server.db.withSavepoint
import network.lapis.cloud.shared.domain.PaymentIntent
import network.lapis.cloud.shared.domain.PaymentProvider
import network.lapis.cloud.shared.domain.PaymentTransactionStatus
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.notExists
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import kotlin.uuid.Uuid

/** One row of `donation`. [id] equals [paymentTransactionId] for rows written by the V174 backfill; nobody may rely on that. */
internal data class DonationRow(
    val id: Uuid,
    val paymentTransactionId: Uuid,
    val memberId: Uuid?,
    val amount: BigDecimal,
    val currency: String,
    val bookingDate: LocalDate?,
)

/** One open donation of a member as a choice of the reference-date form. [bookingDate] is `null` when no bank line backs the donation. */
internal data class DonationCandidate(
    val paymentId: Uuid,
    val donationId: Uuid,
    val amount: BigDecimal,
    val currency: String,
    val bookingDate: LocalDate?,
    val provider: PaymentProvider,
    val receivedAt: LocalDateTime,
)

/**
 * V1.9.328 -- the ONLY writer of [DonationTable] (insert only: a donation is never updated and never deleted; the organisation restore is the one exception
 * and copies rows verbatim). Lock order everywhere is payment first, donation second: [lockedForPayment] and [ensureForPayment] expect the caller to
 * hold the `FOR UPDATE` lock of the payment row already, so two conversions of the same donation (entered by donation id or by payment id) queue on the
 * payment row and can never deadlock. All functions expect an open transaction.
 */
internal object DonationStore {
    /** The donation of [paymentId] with a row lock, or `null`. */
    fun lockedForPayment(paymentId: Uuid): DonationRow? =
        DonationTable
            .selectAll()
            .where { DonationTable.paymentTransactionId eq paymentId }
            .forUpdate()
            .singleOrNull()
            ?.toRow()

    /** The donation with [donationId], WITHOUT a lock -- only to find the payment that must be locked first. */
    fun peekById(donationId: Uuid): DonationRow? =
        DonationTable
            .selectAll()
            .where { DonationTable.id eq donationId }
            .singleOrNull()
            ?.toRow()

    /**
     * V1.9.329 -- THE way a donation comes into being: one row for every CAPTURED payment with intent DONATION and amount > 0 (bank, PayPal, Stripe,
     * anonymous). Returns the (row-locked) donation and whether this call created it, or `null` -- writing nothing -- when the payment is no such payment.
     * The caller holds the payment row (it inserted it, or locked it `FOR UPDATE`), so no dialect specific MERGE is needed; a race that got through anyway
     * is rejected by `uq_donation_payment_transaction` (only a unique violation counts, anything else is rethrown) and answered with the existing row.
     * A payment without journal entry but with a reconciliation note (amount mismatch, unpaid) yields `null` too (V175 (b)).
     * `booking_date` is the day of the ONE POSTED bank line, else `null`; `member_id` is the payment's member, `null` for an anonymous donation.
     */
    fun ensureForPayment(
        paymentId: Uuid,
        createdBy: Uuid?,
        now: LocalDateTime,
    ): Pair<DonationRow, Boolean>? {
        val payment =
            PaymentTransactionTable
                .selectAll()
                .where { PaymentTransactionTable.id eq paymentId }
                .singleOrNull() ?: return null
        val amount = payment[PaymentTransactionTable.amount]
        if (payment[PaymentTransactionTable.intent] != PaymentIntent.DONATION ||
            payment[PaymentTransactionTable.status] != PaymentTransactionStatus.CAPTURED ||
            amount.signum() <= 0
        ) {
            return null
        }
        // Same predicate as the V175 backfill (b): an unposted payment with a reconciliation note (amount mismatch, delayed method) holds the session
        // amount, not what arrived, so it must never become a donation -- neither up front nor lazily through a conversion.
        if (payment[PaymentTransactionTable.journalEntryId] == null && payment[PaymentTransactionTable.reconciliationNote] != null) {
            return null
        }
        lockedForPayment(paymentId)?.let { return it to false }
        val donationId = Uuid.random()
        val currency = payment[PaymentTransactionTable.currency].uppercase()
        val member = payment[PaymentTransactionTable.memberId]
        val booking = DonationPaymentLink.bookingDateOf(paymentId)
        // A savepoint keeps a lost race from poisoning the surrounding transaction (PostgreSQL aborts it on any error).
        val inserted =
            runCatching {
                withSavepoint(name = "donation_ensure") {
                    DonationTable.insert {
                        it[id] = donationId
                        it[paymentTransactionId] = paymentId
                        it[memberId] = member
                        it[DonationTable.amount] = amount
                        it[DonationTable.currency] = currency
                        it[bookingDate] = booking
                        it[createdAt] = now
                        it[DonationTable.createdBy] = createdBy
                    }
                }
            }
        if (inserted.isFailure) {
            val cause = inserted.exceptionOrNull()
            if (cause != null && cause.isUniqueViolation()) {
                return lockedForPayment(paymentId)?.let { it to false } ?: throw cause
            }
            throw cause ?: IllegalStateException("donation insert failed with no exception")
        }
        return DonationRow(
            id = donationId,
            paymentTransactionId = paymentId,
            memberId = member,
            amount = amount,
            currency = currency,
            bookingDate = booking,
        ) to true
    }

    /**
     * V1.9.329: the open donations of [memberId] in [currency] -- captured, with a donation row, no conversion yet (a conversion always carries both ids, V174
     * CHECK + composite FK, so either test covers both). Newest first by booking day (the day of the bank statement), else by the day the payment arrived;
     * at most [DonationPaymentLink.MAX_CANDIDATES]. The latest ones are read by arrival and the stable sort by that day happens in Kotlin (the SQL ordering
     * stays portable) -- the two orders differ only for a bank donation booked on another day than it arrived.
     * Known limit: the stale-booking-day filter runs AFTER the cap, so stale rows among the newest [DonationPaymentLink.MAX_CANDIDATES] donations can
     * hide older valid ones; only members with very many open donations are affected.
     */
    fun candidatesOf(
        memberId: Uuid,
        currency: String,
    ): List<DonationCandidate> =
        DonationTable
            .join(PaymentTransactionTable, JoinType.INNER, DonationTable.paymentTransactionId, PaymentTransactionTable.id)
            .selectAll()
            .where {
                (DonationTable.memberId eq memberId) and
                    (DonationTable.currency eq currency.uppercase()) and
                    (PaymentTransactionTable.status eq PaymentTransactionStatus.CAPTURED) and
                    (PaymentTransactionTable.intent eq PaymentIntent.DONATION) and
                    notExists(
                        PriceOracleConversionTable
                            .selectAll()
                            .where {
                                (PriceOracleConversionTable.donationId eq DonationTable.id) or
                                    (PriceOracleConversionTable.paymentTransactionId eq DonationTable.paymentTransactionId)
                            },
                    )
            }.orderBy(PaymentTransactionTable.receivedAt to SortOrder.DESC)
            .limit(DonationPaymentLink.MAX_CANDIDATES)
            .map {
                DonationCandidate(
                    paymentId = it[DonationTable.paymentTransactionId],
                    donationId = it[DonationTable.id],
                    amount = it[DonationTable.amount],
                    currency = it[DonationTable.currency],
                    bookingDate = it[DonationTable.bookingDate],
                    provider = it[PaymentTransactionTable.provider],
                    receivedAt = it[PaymentTransactionTable.receivedAt],
                )
            }.filter {
                // A stored booking day that no longer matches exactly one POSTED bank line (line reset, second line) would be rejected by
                // requireConvertibleDonation (DONATION_NOT_AVAILABLE), so the form must not offer it.
                it.bookingDate == null || DonationPaymentLink.bookingDateOf(it.paymentId) == it.bookingDate
            }.sortedByDescending { it.bookingDate ?: it.receivedAt.date }

    private fun ResultRow.toRow(): DonationRow =
        DonationRow(
            id = this[DonationTable.id],
            paymentTransactionId = this[DonationTable.paymentTransactionId],
            memberId = this[DonationTable.memberId],
            amount = this[DonationTable.amount],
            currency = this[DonationTable.currency],
            bookingDate = this[DonationTable.bookingDate],
        )
}
