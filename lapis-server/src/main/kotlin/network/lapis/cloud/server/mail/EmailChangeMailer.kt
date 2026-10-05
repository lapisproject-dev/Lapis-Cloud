package network.lapis.cloud.server.mail

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.DeliveryStatus
import network.lapis.cloud.shared.domain.EmailChangeKind

/**
 * Welle V1.9.56 "E-Mail-Änderung absichern" -- the four mails of the address-change lifecycle. Same swap-seam shape as
 * [FriendVerificationMailer]; [SmtpEmailChangeMailer] is the sole implementation, delegating to the shared
 * [MailDispatcher] (one transport, many thin adapters).
 *
 * Contract identical to [PasswordResetMailer.send] "Fire-and-forget": the call hands the message to the dispatcher and
 * returns, it never reports the real delivery outcome. **Never logs a raw token** (account-takeover-adjacent oracle).
 * The mail to the OLD address carries only the MASKED new address and no name of the initiator.
 */
interface EmailChangeMailer {
    /**
     * Link to the NEW address. [EmailChangeKind.PROPOSAL]: "accept with your password" (`#/confirm-email`); the two
     * other proposal kinds: "confirm that this address is yours" (`#/verify-new-email`) with the earliest effective time.
     */
    fun sendConfirmToNewAddress(
        email: String,
        rawToken: String,
        kind: EmailChangeKind,
        effectiveAt: LocalDateTime?,
    ): DeliveryStatus

    /** Warning with the reject link to the OLD address (`#/revoke-email-change`). [maskedNewEmail] is already masked. */
    fun sendWarningToOldAddress(
        email: String,
        rawRevokeToken: String,
        kind: EmailChangeKind,
        maskedNewEmail: String,
        effectiveAt: LocalDateTime?,
    ): DeliveryStatus

    /** Info to the OLD address after the owner changed the address themselves (path A). */
    fun sendSelfChangeInfo(
        email: String,
        maskedNewEmail: String,
    ): DeliveryStatus

    /** Info to the OLD address after a third-party change became effective (warning period elapsed). */
    fun sendAppliedInfo(
        email: String,
        maskedNewEmail: String,
    ): DeliveryStatus
}
