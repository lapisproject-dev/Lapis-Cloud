package network.lapis.cloud.server.mail

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.DeliveryStatus

/**
 * Welle V1.4.14 Wave 2 "FinTS/HBCI-Live-Kontoabruf" -- abstraction over "tell the BOARD/ADMIN that
 * a bank account's FinTS live retrieval needs re-authentication". Same swap-seam shape
 * [AdminPasswordResetNotificationMailer] already establishes for a different outbound need.
 * **Never carries a PIN, a user id, or a raw bank message** -- see
 * `network.lapis.cloud.server.payment.fints.FinTsPoller` KDoc "Benachrichtigung nur beim Übergang".
 */
interface FinTsReauthNotificationMailer {
    /**
     * Hands the notification off for delivery to every [recipients] address. **Fire-and-forget**,
     * same contract as [AdminPasswordResetNotificationMailer.send] -- returns once accepted by
     * [MailDispatcher], not once actually delivered. Never throws for a transport failure.
     */
    fun send(
        recipients: List<String>,
        accountLabel: String,
        ibanMasked: String,
        occurredAt: LocalDateTime,
    ): DeliveryStatus
}
