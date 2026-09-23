package network.lapis.cloud.server.mail

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.DeliveryStatus

/**
 * V1.7.2 security-audit fix -- which administrative change to a member's Keycloak login link a
 * [KeycloakLinkNotificationMailer] notice reports.
 */
enum class KeycloakLinkChange {
    /** `IKeycloakLinkService.linkMember` -- an ADMIN manually attached a Keycloak identity. */
    LINKED,

    /** `IKeycloakLinkService.unlinkMember` -- an ADMIN removed the existing link. */
    UNLINKED,
}

/**
 * V1.7.2 security-audit fix -- "tell this member an administrator changed which Keycloak identity
 * can log in as them". A manual link is functionally the same class of action as Welle V1.4.9's
 * `IMemberService.setTemporaryPasswordForMember` (an ADMIN decides which credential opens this
 * member's account), so it gets the same transparency control that precedent established: a
 * password-/identifier-free security notice to the member's stored address, the one real-time
 * signal a member has that their account was administratively touched. Same swap-seam shape as
 * [AdminPasswordResetNotificationMailer]. **Never carries the Keycloak subject** -- the member
 * cannot act on it, and the mail must not become a second place that identifier is stored.
 */
interface KeycloakLinkNotificationMailer {
    /** Fire-and-forget, same contract as [AdminPasswordResetNotificationMailer.send]. */
    fun send(
        email: String,
        change: KeycloakLinkChange,
        occurredAt: LocalDateTime,
    ): DeliveryStatus
}

/** Thin [KeycloakLinkNotificationMailer] adapter over [MailDispatcher] -- mirror of [SmtpAdminPasswordResetNotificationMailer]. */
class SmtpKeycloakLinkNotificationMailer(
    private val dispatcher: MailDispatcher,
    private val branding: MailBranding,
) : KeycloakLinkNotificationMailer {
    override fun send(
        email: String,
        change: KeycloakLinkChange,
        occurredAt: LocalDateTime,
    ): DeliveryStatus {
        val mail = MailTemplates.keycloakLinkChangedByAdmin(change = change, occurredAt = occurredAt, branding = branding)
        dispatcher.enqueue(
            to = email,
            subject = mail.subject,
            plainTextBody = mail.plainText,
            htmlBody = mail.html,
            purpose = "keycloak-link-notice",
        )
        return DeliveryStatus.SENT
    }
}
