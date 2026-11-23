package network.lapis.cloud.server.payment

import network.lapis.cloud.server.db.generated.PaymentTransactionTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Writes the back-reference `payment_transaction.journal_entry_id` for a payment whose journal entry was just posted.
 * Every path that books a payment (PSP webhook, manual assignment, bank-statement auto-post) must call this inside
 * the SAME transaction as the posting, otherwise the payment list shows the payment as "not booked".
 */
internal object PaymentJournalLink {
    /**
     * Sets the link inside the caller's transaction. Exactly one row (matching [paymentTransactionId] with no link yet)
     * must change, otherwise an [IllegalStateException] rolls the caller's transaction back. Only IDs are in the message.
     */
    fun link(
        paymentTransactionId: Uuid,
        journalEntryId: Uuid,
    ) {
        val updated =
            PaymentTransactionTable.update({
                (PaymentTransactionTable.id eq paymentTransactionId) and PaymentTransactionTable.journalEntryId.isNull()
            }) {
                it[PaymentTransactionTable.journalEntryId] = journalEntryId
            }
        check(updated == 1) { "payment_transaction $paymentTransactionId could not be linked to journal entry $journalEntryId" }
    }
}
