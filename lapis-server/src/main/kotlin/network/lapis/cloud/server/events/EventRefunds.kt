package network.lapis.cloud.server.events

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.PaymentCheckoutSessionTable
import network.lapis.cloud.server.db.generated.PaymentTransactionTable
import network.lapis.cloud.shared.domain.EventRefundDto
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventRegistrationStatusSets
import network.lapis.cloud.shared.domain.PaymentCheckoutSessionStatus
import network.lapis.cloud.shared.domain.PaymentIntent
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * V1.9.35 -- the "refund still open" view of event registrations. NO own `transaction {}`: every
 * caller opens the transaction (and so decides the audit scope).
 *
 * "Paid" is derived, never stored: `PspWebhookIngestion` completes the checkout session in the same
 * transaction that inserts the `payment_transaction`, so a COMPLETED `EVENT_FEE` session for a
 * registration means a real payment exists. "Refund marked" is the V66 `refund_marked_at` column.
 * Marking moves no money and posts no booking -- the refund itself is paid outside Lapis Cloud.
 * An invoice-paid fee (`issueEventInvoice`) has no checkout session and is NOT recognized here.
 */
internal object EventRefunds {
    const val MAX_OPEN_REFUNDS = 200

    private val completedEventFee =
        { regId: Uuid ->
            (PaymentCheckoutSessionTable.eventRegistrationId eq regId) and
                (PaymentCheckoutSessionTable.status eq PaymentCheckoutSessionStatus.COMPLETED) and
                (PaymentCheckoutSessionTable.intent eq PaymentIntent.EVENT_FEE)
        }

    /** COMPLETED `EVENT_FEE` sessions of [registrationId], oldest completion first. */
    fun completedSessions(registrationId: Uuid): List<ResultRow> =
        PaymentCheckoutSessionTable
            .selectAll()
            .where { completedEventFee(registrationId) }
            .orderBy(PaymentCheckoutSessionTable.completedAt to SortOrder.ASC)
            .toList()

    fun isPaid(registrationId: Uuid): Boolean =
        PaymentCheckoutSessionTable
            .select(PaymentCheckoutSessionTable.id)
            .where { completedEventFee(registrationId) }
            .limit(1)
            .firstOrNull() != null

    /** Sum of the COMPLETED sessions, or null if unpaid. */
    fun paidAmount(registrationId: Uuid): BigDecimal? =
        completedSessions(registrationId).takeIf { it.isNotEmpty() }?.fold(BigDecimal.ZERO) { acc, row ->
            acc + row[PaymentCheckoutSessionTable.amount]
        }

    /** The `payment_transaction.id` of the newest COMPLETED session's payment, if it exists. */
    fun latestPaymentTransactionId(registrationId: Uuid): Uuid? {
        val sessionIds = completedSessions(registrationId).map { it[PaymentCheckoutSessionTable.id] }
        if (sessionIds.isEmpty()) return null
        return PaymentTransactionTable
            .select(PaymentTransactionTable.id)
            .where { PaymentTransactionTable.checkoutSessionId inList sessionIds }
            .orderBy(PaymentTransactionTable.receivedAt to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.get(PaymentTransactionTable.id)
    }

    /**
     * Open refunds: status CANCELLED/EXPIRED, not marked, at least one COMPLETED EVENT_FEE session.
     * One join query (no N+1), oldest first, capped at [MAX_OPEN_REFUNDS].
     */
    fun listOpen(): List<EventRefundDto> {
        val rows =
            EventRegistrationTable
                .join(
                    PaymentCheckoutSessionTable,
                    JoinType.INNER,
                    EventRegistrationTable.id,
                    PaymentCheckoutSessionTable.eventRegistrationId,
                ).join(EventTable, JoinType.INNER, EventRegistrationTable.eventId, EventTable.id)
                .selectAll()
                .where {
                    (EventRegistrationTable.status inList EventRegistrationStatusSets.INACTIVE.toList()) and
                        EventRegistrationTable.refundMarkedAt.isNull() and
                        (PaymentCheckoutSessionTable.status eq PaymentCheckoutSessionStatus.COMPLETED) and
                        (PaymentCheckoutSessionTable.intent eq PaymentIntent.EVENT_FEE)
                }.toList()
        val grouped = rows.groupBy { it[EventRegistrationTable.id] }.values
        val memberIds = grouped.mapNotNull { it.first()[EventRegistrationTable.memberId] }
        val names = EventStore.memberInfoByIds(memberIds)
        return grouped
            .map { group ->
                val first = group.first()
                val memberId = first[EventRegistrationTable.memberId]
                EventRefundDto(
                    registrationId = first[EventRegistrationTable.id].toString(),
                    eventId = first[EventRegistrationTable.eventId].toString(),
                    eventTitle = first[EventTable.title],
                    eventStartsAt = first[EventTable.startsAt],
                    participantDisplayName = memberId?.let { names[it]?.displayName } ?: first[EventRegistrationTable.guestName].orEmpty(),
                    paidAmount = group.fold(BigDecimal.ZERO) { acc, r -> acc + r[PaymentCheckoutSessionTable.amount] },
                    paymentCount = group.size,
                    cancelledAt = first[EventRegistrationTable.cancelledAt],
                    status = first[EventRegistrationTable.status],
                    refundMarkedAt = null,
                ) to (first[EventRegistrationTable.cancelledAt] ?: first[EventRegistrationTable.registeredAt])
            }.sortedBy { it.second }
            .take(MAX_OPEN_REFUNDS)
            .map { it.first }
    }

    /**
     * Marks [registrationId] refunded. Locks the registration row (`FOR UPDATE`), re-checks the
     * open conditions, then a conditional `UPDATE ... WHERE refund_marked_at IS NULL`. Returns the
     * affected row count: 0 when not open (a concurrent second call therefore gets 0), else 1.
     */
    fun markRefunded(
        registrationId: Uuid,
        actor: Uuid,
        now: LocalDateTime,
    ): Int {
        val row =
            EventRegistrationTable
                .selectAll()
                .where { EventRegistrationTable.id eq registrationId }
                .forUpdate()
                .singleOrNull() ?: return 0
        val status: EventRegistrationStatus = row[EventRegistrationTable.status]
        if (status !in EventRegistrationStatusSets.INACTIVE || row[EventRegistrationTable.refundMarkedAt] != null) return 0
        if (!isPaid(registrationId)) return 0
        return EventRegistrationTable.update({
            (EventRegistrationTable.id eq registrationId) and EventRegistrationTable.refundMarkedAt.isNull()
        }) {
            it[refundMarkedAt] = now
            it[refundMarkedBy] = actor
        }
    }

    /** DTO of one (already marked or still open) registration; participant name resolved with one lookup. */
    fun toDto(registrationRow: ResultRow): EventRefundDto {
        val regId = registrationRow[EventRegistrationTable.id]
        val event = EventStore.getEventOrThrow(registrationRow[EventRegistrationTable.eventId])
        val memberId = registrationRow[EventRegistrationTable.memberId]
        val sessions = completedSessions(regId)
        return EventRefundDto(
            registrationId = regId.toString(),
            eventId = registrationRow[EventRegistrationTable.eventId].toString(),
            eventTitle = event[EventTable.title],
            eventStartsAt = event[EventTable.startsAt],
            participantDisplayName =
                memberId?.let { EventStore.memberDisplayNameOrNull(it) } ?: registrationRow[EventRegistrationTable.guestName].orEmpty(),
            paidAmount = sessions.fold(BigDecimal.ZERO) { acc, r -> acc + r[PaymentCheckoutSessionTable.amount] },
            paymentCount = sessions.size,
            cancelledAt = registrationRow[EventRegistrationTable.cancelledAt],
            status = registrationRow[EventRegistrationTable.status],
            refundMarkedAt = registrationRow[EventRegistrationTable.refundMarkedAt],
        )
    }
}
