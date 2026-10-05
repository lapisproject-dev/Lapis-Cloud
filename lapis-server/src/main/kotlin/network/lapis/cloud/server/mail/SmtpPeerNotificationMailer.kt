package network.lapis.cloud.server.mail

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.DeliveryStatus
import network.lapis.cloud.shared.domain.PrivilegedActionKind

/** Thin [PeerNotificationMailer] adapter over [MailDispatcher] -- see [PeerNotificationMailer] KDoc. */
class SmtpPeerNotificationMailer(
    private val dispatcher: MailDispatcher,
    private val branding: MailBranding,
) : PeerNotificationMailer {
    override fun sendRequestForTarget(
        email: String,
        rawVetoToken: String,
        actorName: String,
        notBefore: LocalDateTime?,
    ): DeliveryStatus =
        enqueue(
            to = email,
            mail =
                MailTemplates.peerRequestForTarget(
                    rawVetoToken = rawVetoToken,
                    actorName = actorName,
                    notBefore = notBefore,
                    branding = branding,
                ),
            purpose = "peer-request-target",
        )

    override fun sendApprovalNeeded(
        email: String,
        actorName: String,
        targetName: String,
        action: PrivilegedActionKind,
        expiresAt: LocalDateTime,
    ): DeliveryStatus =
        enqueue(
            to = email,
            mail =
                MailTemplates.peerApprovalNeeded(
                    actorName = actorName,
                    targetName = targetName,
                    action = action,
                    expiresAt = expiresAt,
                    branding = branding,
                ),
            purpose = "peer-approval-needed",
        )

    override fun sendExecutedForTarget(
        email: String,
        event: PeerExecutedEvent,
        actorName: String,
        occurredAt: LocalDateTime,
    ): DeliveryStatus =
        enqueue(
            to = email,
            mail = MailTemplates.peerExecutedForTarget(event = event, actorName = actorName, occurredAt = occurredAt, branding = branding),
            purpose = "peer-executed-target",
        )

    override fun sendResetMailTriggered(
        email: String,
        actorName: String,
        occurredAt: LocalDateTime,
    ): DeliveryStatus =
        enqueue(
            to = email,
            mail = MailTemplates.peerResetMailTriggered(actorName = actorName, occurredAt = occurredAt, branding = branding),
            purpose = "peer-reset-mail-triggered",
        )

    override fun sendProtectedDataChanged(
        email: String,
        actorName: String,
        occurredAt: LocalDateTime,
    ): DeliveryStatus =
        enqueue(
            to = email,
            mail = MailTemplates.peerProtectedDataChanged(actorName = actorName, occurredAt = occurredAt, branding = branding),
            purpose = "peer-protected-data-changed",
        )

    override fun sendNewAdministrator(
        email: String,
        newAdminName: String,
        actorName: String,
        occurredAt: LocalDateTime,
    ): DeliveryStatus =
        enqueue(
            to = email,
            mail =
                MailTemplates.peerNewAdministrator(
                    newAdminName = newAdminName,
                    actorName = actorName,
                    occurredAt = occurredAt,
                    branding = branding,
                ),
            purpose = "peer-new-administrator",
        )

    private fun enqueue(
        to: String,
        mail: MailTemplates.RenderedMail,
        purpose: String,
    ): DeliveryStatus {
        val accepted =
            dispatcher.enqueue(
                to = to,
                subject = mail.subject,
                plainTextBody = mail.plainText,
                htmlBody = mail.html,
                purpose = purpose,
            )
        return if (accepted) DeliveryStatus.SENT else DeliveryStatus.FAILED
    }
}
