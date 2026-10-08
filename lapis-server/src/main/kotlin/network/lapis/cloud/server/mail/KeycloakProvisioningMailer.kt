package network.lapis.cloud.server.mail

import kotlinx.datetime.LocalDateTime

/**
 * Welle V1.9.73 -- the two notices of the Keycloak just-in-time provisioning / profile sync. Both are fire-and-forget, sent
 * AFTER the commit, and neither carries a credential or the full new address (see the [MailTemplates] documentation).
 */
interface KeycloakProvisioningMailer {
    /** To an administrator: a member was created on the first Keycloak login. [newMemberName] is sanitized free text. */
    fun sendMemberProvisioned(
        email: String,
        newMemberName: String,
        occurredAt: LocalDateTime,
    )

    /** To the OLD address of a member whose address was taken over from the identity provider; the new address only masked. */
    fun sendEmailSyncedToOldAddress(
        email: String,
        maskedNewEmail: String,
        occurredAt: LocalDateTime,
    )
}

/** Sends nothing -- the default wherever outbound mail is not wired (tests, SMTP not configured). */
object NoOpKeycloakProvisioningMailer : KeycloakProvisioningMailer {
    override fun sendMemberProvisioned(
        email: String,
        newMemberName: String,
        occurredAt: LocalDateTime,
    ) = Unit

    override fun sendEmailSyncedToOldAddress(
        email: String,
        maskedNewEmail: String,
        occurredAt: LocalDateTime,
    ) = Unit
}

/** Thin [KeycloakProvisioningMailer] adapter over [MailDispatcher] -- same shape as [SmtpKeycloakLinkNotificationMailer]. */
class SmtpKeycloakProvisioningMailer(
    private val dispatcher: MailDispatcher,
    private val branding: MailBranding,
) : KeycloakProvisioningMailer {
    override fun sendMemberProvisioned(
        email: String,
        newMemberName: String,
        occurredAt: LocalDateTime,
    ) {
        val mail = MailTemplates.keycloakMemberProvisioned(newMemberName = newMemberName, occurredAt = occurredAt, branding = branding)
        dispatcher.enqueue(
            to = email,
            subject = mail.subject,
            plainTextBody = mail.plainText,
            htmlBody = mail.html,
            purpose = "keycloak-member-provisioned",
        )
    }

    override fun sendEmailSyncedToOldAddress(
        email: String,
        maskedNewEmail: String,
        occurredAt: LocalDateTime,
    ) {
        val mail = MailTemplates.keycloakEmailSyncedNotice(maskedNewEmail = maskedNewEmail, occurredAt = occurredAt, branding = branding)
        dispatcher.enqueue(
            to = email,
            subject = mail.subject,
            plainTextBody = mail.plainText,
            htmlBody = mail.html,
            purpose = "keycloak-email-synced",
        )
    }
}
