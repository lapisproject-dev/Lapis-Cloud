package network.lapis.cloud.server.mail

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.DeliveryStatus

/**
 * Welle V1.4.9 "Admin-Passwort-Reset" -- abstraction over "tell this member their password was
 * just reset by an administrator". Same swap-seam shape [PasswordResetMailer] already establishes
 * for a different outbound need. **Never carries the password itself** -- see
 * [MailTemplates.passwordResetByAdmin] KDoc.
 */
interface AdminPasswordResetNotificationMailer {
    /**
     * Hands the notification for [email] off for delivery. **Fire-and-forget**, same contract as
     * [PasswordResetMailer.send] -- returns once accepted by [MailDispatcher], not once actually
     * delivered. Never throws for a transport failure (see [MailDispatcher] KDoc).
     */
    fun send(
        email: String,
        occurredAt: LocalDateTime,
    ): DeliveryStatus
}
