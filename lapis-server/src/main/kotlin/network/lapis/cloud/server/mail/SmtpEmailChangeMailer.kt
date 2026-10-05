package network.lapis.cloud.server.mail

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.DeliveryStatus
import network.lapis.cloud.shared.domain.EmailChangeKind

/** Thin [EmailChangeMailer] adapter over [MailDispatcher] -- see [EmailChangeMailer] KDoc. */
class SmtpEmailChangeMailer(
    private val dispatcher: MailDispatcher,
    private val branding: MailBranding,
) : EmailChangeMailer {
    override fun sendConfirmToNewAddress(
        email: String,
        rawToken: String,
        kind: EmailChangeKind,
        effectiveAt: LocalDateTime?,
    ): DeliveryStatus =
        enqueue(
            to = email,
            mail = MailTemplates.emailChangeConfirm(rawToken = rawToken, kind = kind, effectiveAt = effectiveAt, branding = branding),
            purpose = "email-change-confirm",
        )

    override fun sendWarningToOldAddress(
        email: String,
        rawRevokeToken: String,
        kind: EmailChangeKind,
        maskedNewEmail: String,
        effectiveAt: LocalDateTime?,
    ): DeliveryStatus =
        enqueue(
            to = email,
            mail =
                MailTemplates.emailChangeWarningOld(
                    rawRevokeToken = rawRevokeToken,
                    kind = kind,
                    maskedNewEmail = maskedNewEmail,
                    effectiveAt = effectiveAt,
                    branding = branding,
                ),
            purpose = "email-change-warning",
        )

    override fun sendSelfChangeInfo(
        email: String,
        maskedNewEmail: String,
    ): DeliveryStatus =
        enqueue(
            to = email,
            mail = MailTemplates.emailChangeSelfInfo(maskedNewEmail = maskedNewEmail, branding = branding),
            purpose = "email-change-self-info",
        )

    override fun sendAppliedInfo(
        email: String,
        maskedNewEmail: String,
    ): DeliveryStatus =
        enqueue(
            to = email,
            mail = MailTemplates.emailChangeAppliedInfo(maskedNewEmail = maskedNewEmail, branding = branding),
            purpose = "email-change-applied-info",
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
