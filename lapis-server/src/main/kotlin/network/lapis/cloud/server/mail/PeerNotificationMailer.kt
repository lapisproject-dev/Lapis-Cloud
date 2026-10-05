package network.lapis.cloud.server.mail

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.DeliveryStatus
import network.lapis.cloud.shared.domain.PrivilegedActionKind

/** Welle V1.9.57 -- what was done to the TARGET's account, for [PeerNotificationMailer.sendExecutedForTarget]. */
enum class PeerExecutedEvent { TEMPORARY_PASSWORD_SET, ROLE_CHANGED, ACCESS_SUSPENDED, STATUS_CHANGED }

/**
 * Welle V1.9.57 "Admin-Peer-Schutz" -- the receipts of the admin peer protection. Every mail corresponds to one audited
 * state change (so there is deliberately no rate limiter, same precedent as [KeycloakLinkNotificationMailer]) and reads
 * like a receipt: what happened, who triggered it (display name), when, and one thing the recipient can do. Same swap-seam
 * shape as [EmailChangeMailer]; [SmtpPeerNotificationMailer] is the sole production implementation.
 *
 * Contract identical to [PasswordResetMailer.send] "Fire-and-forget". **Never logs a raw token.** Only
 * [sendRequestForTarget] carries a token (the one-time objection link), and only the TARGET ever receives it.
 */
interface PeerNotificationMailer {
    /** To the TARGET, temporary password only: a second administrator must approve; [rawVetoToken] is the objection link. */
    fun sendRequestForTarget(
        email: String,
        rawVetoToken: String,
        actorName: String,
        notBefore: LocalDateTime?,
    ): DeliveryStatus

    /** To an eligible approver: a request awaits their decision. No token. */
    fun sendApprovalNeeded(
        email: String,
        actorName: String,
        targetName: String,
        action: PrivilegedActionKind,
        expiresAt: LocalDateTime,
    ): DeliveryStatus

    /** To the TARGET: the action was carried out. */
    fun sendExecutedForTarget(
        email: String,
        event: PeerExecutedEvent,
        actorName: String,
        occurredAt: LocalDateTime,
    ): DeliveryStatus

    /** To the TARGET administrator: an administrator triggered a password-reset mail for the account. */
    fun sendResetMailTriggered(
        email: String,
        actorName: String,
        occurredAt: LocalDateTime,
    ): DeliveryStatus

    /** To the TARGET administrator: an administrator changed their address or beneficial-owner data. */
    fun sendProtectedDataChanged(
        email: String,
        actorName: String,
        occurredAt: LocalDateTime,
    ): DeliveryStatus

    /** To every OTHER administrator: a new administrator appeared. */
    fun sendNewAdministrator(
        email: String,
        newAdminName: String,
        actorName: String,
        occurredAt: LocalDateTime,
    ): DeliveryStatus
}

/** Silently drops every mail -- the default of the many test call sites that do not care about peer notices. */
object NoOpPeerNotificationMailer : PeerNotificationMailer {
    override fun sendRequestForTarget(
        email: String,
        rawVetoToken: String,
        actorName: String,
        notBefore: LocalDateTime?,
    ): DeliveryStatus = DeliveryStatus.SENT

    override fun sendApprovalNeeded(
        email: String,
        actorName: String,
        targetName: String,
        action: PrivilegedActionKind,
        expiresAt: LocalDateTime,
    ): DeliveryStatus = DeliveryStatus.SENT

    override fun sendExecutedForTarget(
        email: String,
        event: PeerExecutedEvent,
        actorName: String,
        occurredAt: LocalDateTime,
    ): DeliveryStatus = DeliveryStatus.SENT

    override fun sendResetMailTriggered(
        email: String,
        actorName: String,
        occurredAt: LocalDateTime,
    ): DeliveryStatus = DeliveryStatus.SENT

    override fun sendProtectedDataChanged(
        email: String,
        actorName: String,
        occurredAt: LocalDateTime,
    ): DeliveryStatus = DeliveryStatus.SENT

    override fun sendNewAdministrator(
        email: String,
        newAdminName: String,
        actorName: String,
        occurredAt: LocalDateTime,
    ): DeliveryStatus = DeliveryStatus.SENT
}
