package network.lapis.cloud.server.economy.oracle

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.DonationTable
import network.lapis.cloud.server.security.CurrentMember
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import kotlin.uuid.Uuid

/** One row of `donation`. [id] equals [paymentTransactionId] for rows written by the V174 backfill; nobody may rely on that. */
internal data class DonationRow(
    val id: Uuid,
    val paymentTransactionId: Uuid,
    val memberId: Uuid,
    val amount: BigDecimal,
    val currency: String,
    val bookingDate: LocalDate,
)

/**
 * V1.9.328 -- the ONLY writer of [DonationTable] (insert only: a donation is never updated and never deleted; the organisation restore is the one exception
 * and copies rows verbatim). Lock order everywhere is payment first, donation second: [lockedForPayment] and [findOrCreateLocked] expect the caller to
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
     * The donation of [paymentId] (row-locked) and whether this call created it. The caller holds the payment's row lock, so no dialect specific
     * MERGE is needed; if a race got through anyway, `uq_donation_payment_transaction` rejects the second insert.
     */
    fun findOrCreateLocked(
        paymentId: Uuid,
        memberId: Uuid,
        amount: BigDecimal,
        currency: String,
        bookingDate: LocalDate,
        actor: CurrentMember,
        now: LocalDateTime,
    ): Pair<DonationRow, Boolean> {
        lockedForPayment(paymentId)?.let { return it to false }
        val donationId = Uuid.random()
        DonationTable.insert {
            it[id] = donationId
            it[paymentTransactionId] = paymentId
            it[DonationTable.memberId] = memberId
            it[DonationTable.amount] = amount
            it[DonationTable.currency] = currency.uppercase()
            it[DonationTable.bookingDate] = bookingDate
            it[createdAt] = now
            it[createdBy] = actor.memberId
        }
        return DonationRow(
            id = donationId,
            paymentTransactionId = paymentId,
            memberId = memberId,
            amount = amount,
            currency = currency.uppercase(),
            bookingDate = bookingDate,
        ) to true
    }

    /** payment id -> donation id for those of [paymentIds] that already have a donation row. */
    fun donationIdsByPayment(paymentIds: Collection<Uuid>): Map<Uuid, Uuid> {
        if (paymentIds.isEmpty()) return emptyMap()
        return DonationTable
            .selectAll()
            .where { DonationTable.paymentTransactionId inList paymentIds }
            .associate { it[DonationTable.paymentTransactionId] to it[DonationTable.id] }
    }

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
