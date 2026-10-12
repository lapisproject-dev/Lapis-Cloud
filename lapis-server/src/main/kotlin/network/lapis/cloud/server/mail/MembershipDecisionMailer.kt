package network.lapis.cloud.server.mail

/** V1.9.118 -- which rejection mail a decision sends: a plain rejection, or the FRIEND fallback that keeps the friend access. */
enum class RejectionVariant {
    /** The applicant ends as `REJECTED`: no login link. */
    REJECTED,

    /** A former friend falls back to `FRIEND`: the mail says so and links to the login. */
    FRIEND,
}

/**
 * V1.9.118 -- tells an applicant the outcome of their membership application. Same swap-seam shape as
 * [KeycloakLinkNotificationMailer]. Neither mail ever contains the rejection reason.
 */
interface MembershipDecisionMailer {
    /** False for [NoOpMembershipDecisionMailer]: SMTP delivery is not configured. */
    val deliveryConfigured: Boolean

    /** @return true if the mail was handed to the outbox, false if it was refused (budget, discarded). */
    fun sendApproved(
        email: String,
        displayName: String,
        language: String,
    ): Boolean

    /** @return as [sendApproved]. */
    fun sendRejected(
        email: String,
        displayName: String,
        variant: RejectionVariant,
        language: String,
    ): Boolean
}

/** Used when SMTP is not configured: nothing is sent. */
object NoOpMembershipDecisionMailer : MembershipDecisionMailer {
    override val deliveryConfigured: Boolean = false

    override fun sendApproved(
        email: String,
        displayName: String,
        language: String,
    ): Boolean = false

    override fun sendRejected(
        email: String,
        displayName: String,
        variant: RejectionVariant,
        language: String,
    ): Boolean = false
}

/** Thin [MembershipDecisionMailer] adapter over [MailDispatcher]. */
class SmtpMembershipDecisionMailer(
    private val dispatcher: MailDispatcher,
    private val branding: MailBranding,
) : MembershipDecisionMailer {
    override val deliveryConfigured: Boolean = true

    override fun sendApproved(
        email: String,
        displayName: String,
        language: String,
    ): Boolean {
        val mail = MailTemplates.membershipApproved(displayName = displayName, language = language, branding = branding)
        return dispatcher.enqueue(
            to = email,
            subject = mail.subject,
            plainTextBody = mail.plainText,
            htmlBody = mail.html,
            purpose = "membership-approved",
        )
    }

    override fun sendRejected(
        email: String,
        displayName: String,
        variant: RejectionVariant,
        language: String,
    ): Boolean {
        val mail =
            MailTemplates.membershipRejected(displayName = displayName, variant = variant, language = language, branding = branding)
        return dispatcher.enqueue(
            to = email,
            subject = mail.subject,
            plainTextBody = mail.plainText,
            htmlBody = mail.html,
            purpose = "membership-rejected",
        )
    }
}
