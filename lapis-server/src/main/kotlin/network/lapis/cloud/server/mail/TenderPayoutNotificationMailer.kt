package network.lapis.cloud.server.mail

import kotlinx.datetime.LocalDateTime
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * V1.9.330 -- receipt to the WINNER after a committed tender payout. Carries no name of the paying member and no ledger id.
 * [tenderTitle] is raw, the template sanitises it for the body and never puts it into the subject.
 */
data class TenderPayoutPaidNotification(
    val tenderId: Uuid,
    val winnerEmail: String,
    val tenderTitle: String,
    val amount: BigDecimal,
    val executedAt: LocalDateTime,
)

/** V1.9.331 -- notice to the board after a tender payout ended in PAYOUT_FAILED. No amount, no winner data. */
data class TenderPayoutFailedNotification(
    val tenderId: Uuid,
    /** Raw; the template sanitises it for the body and never puts it into the subject. */
    val tenderTitle: String,
    /** Already normalised, deduplicated and capped. */
    val recipientEmails: List<String>,
)

/** Fire-and-forget, never throws. Called only AFTER the payout transaction committed. */
interface TenderPayoutNotificationMailer {
    fun sendPaidForWinner(notification: TenderPayoutPaidNotification)

    fun sendFailedForBoard(notification: TenderPayoutFailedNotification)
}

object NoOpTenderPayoutNotificationMailer : TenderPayoutNotificationMailer {
    override fun sendPaidForWinner(notification: TenderPayoutPaidNotification) = Unit

    override fun sendFailedForBoard(notification: TenderPayoutFailedNotification) = Unit
}
