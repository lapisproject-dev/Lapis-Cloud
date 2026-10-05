package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Welle V1.9.57 "Admin-Peer-Schutz" -- the actions that one account may take against ANOTHER account and that the peer
 * protection matrix (`network.lapis.cloud.server.security.PeerPolicy`) decides on. The matrix lives on the server only;
 * the client learns the outcome per target through `IPrivilegedActionService.getPeerActionDecisions`, it never
 * re-derives it.
 *
 * - [TEMP_PASSWORD]: an administrator sets a temporary password.
 * - [RESET_MAIL]: an administrator triggers a password-reset mail.
 * - [DEMOTE]: the target loses the ADMIN role.
 * - [PROMOTE_TO_ADMIN]: the target becomes ADMIN (role change, access grant, direct creation).
 * - [SUSPEND]: the target's status becomes login-blocked.
 * - [NON_BLOCKING_STATUS]: a status change without a login-blocking effect.
 * - [ERASE]: GDPR erasure (request or execution).
 * - [LINK_IDENTITY]: manual identity-provider link.
 * - [EMAIL_OVERRIDE]: the emergency e-mail address change (path C of V1.9.56).
 * - [READ_PROTECTED_DATA]: read address and beneficial-owner data.
 * - [WRITE_PROTECTED_DATA]: write address and beneficial-owner data.
 */
@Serializable
enum class PeerAction {
    TEMP_PASSWORD,
    RESET_MAIL,
    DEMOTE,
    PROMOTE_TO_ADMIN,
    SUSPEND,
    NON_BLOCKING_STATUS,
    ERASE,
    LINK_IDENTITY,
    EMAIL_OVERRIDE,
    READ_PROTECTED_DATA,
    WRITE_PROTECTED_DATA,
}

/** Welle V1.9.57 -- why [PeerDecisionKind.DENY] was decided. */
@Serializable
enum class PeerDenyReason {
    NOT_PERMITTED,
    SELF_TARGET,
    TARGET_IS_ADMIN,
    PROTECTED_TARGET,
    NO_SECOND_ADMIN,
    MAIL_UNAVAILABLE,
}

/** Welle V1.9.57 -- the shape of a peer-protection decision, as the client sees it. */
@Serializable
enum class PeerDecisionKind { ALLOW, MASK, REQUIRES_APPROVAL, DENY }

/**
 * Welle V1.9.57 -- the decision of the peer-protection matrix for ONE action against ONE target, for the UI only
 * (the server re-decides under lock on every call). [notifiesTarget] is true when the action is allowed and the target
 * gets a mail about it; [eligibleApprovers] is the number of administrators who could approve a request right now.
 */
@Serializable
data class PeerActionDecisionDto(
    val action: PeerAction,
    val kind: PeerDecisionKind,
    val denyReason: PeerDenyReason? = null,
    val notifiesTarget: Boolean = false,
    val eligibleApprovers: Int = 0,
)

/** Welle V1.9.57 -- wrapper, Kilua RPC cannot return a bare list of a nullable type. */
@Serializable
data class PeerActionDecisionsDto(
    val memberId: String,
    val decisions: List<PeerActionDecisionDto>,
)

/** Welle V1.9.57 -- the three actions that need the approval of a second administrator. */
@Serializable
enum class PrivilegedActionKind { TEMP_PASSWORD, DEMOTE, SUSPEND }

/**
 * Welle V1.9.57 -- lifecycle of a `privileged_action_request` row. [PENDING] and [APPROVED_WAITING] are open.
 * [INVALIDATED] (the facts changed: target role, requester no longer ADMIN, target anonymized) is deliberately distinct
 * from [REJECTED] (a person said no) so the audit trail stays unambiguous.
 */
@Serializable
enum class PrivilegedActionStatus {
    PENDING,
    APPROVED_WAITING,
    EXECUTED,
    REJECTED,
    WITHDRAWN,
    VETOED,
    EXPIRED,
    INVALIDATED,
    ;

    val isOpen: Boolean get() = this == PENDING || this == APPROVED_WAITING
}

/**
 * Welle V1.9.57 -- one request as an administrator sees it. Never a token, never an e-mail address. [reason] is the
 * requester's justification (the approver needs it to decide).
 */
@Serializable
data class PrivilegedActionRequestDto(
    val id: String,
    val action: PrivilegedActionKind,
    val actorMemberId: String,
    val actorDisplayName: String,
    val targetMemberId: String,
    val targetDisplayName: String,
    val requestedRole: AccountRole? = null,
    val requestedStatus: MemberStatus? = null,
    val reason: String,
    val status: PrivilegedActionStatus,
    val createdAt: LocalDateTime,
    val expiresAt: LocalDateTime,
    /** Temporary password only: from this moment the requester may generate the password. */
    val notBefore: LocalDateTime? = null,
    /** Temporary password only: until this moment the requester may generate the password. */
    val executeUntil: LocalDateTime? = null,
)

/** Welle V1.9.57 -- the "Ausstehende Freigaben" card: what waits for the caller, and what the caller asked for. */
@Serializable
data class PrivilegedActionOverviewDto(
    val awaitingMyApproval: List<PrivilegedActionRequestDto> = emptyList(),
    val requestedByMe: List<PrivilegedActionRequestDto> = emptyList(),
)

/** Welle V1.9.57 -- one event in the audit trail of the peer protection. Carries NEVER a reason, token or address. */
@Serializable
enum class PeerAuditEvent {
    REQUESTED,
    APPROVED,
    REJECTED,
    WITHDRAWN,
    VETOED,
    EXECUTED,
    EXPIRED,
    INVALIDATED,
    DENIED,
    NOTIFIED_PROMOTION,
}

/**
 * Welle V1.9.57 -- audit facts of the admin peer protection. **No reason, no token, no hash, no address**: the audit
 * log is hash-chained and not erasable; the reason lives only in the erasable `privileged_action_request` row,
 * [requestId] links to it. [operatorConsole] marks an action taken through the operator console (no signed-in actor).
 */
@Serializable
data class PeerActionAuditFacts(
    val event: PeerAuditEvent,
    val action: PeerAction? = null,
    val requestId: String? = null,
    val targetRole: AccountRole? = null,
    val approverId: String? = null,
    val denyReason: PeerDenyReason? = null,
    val operatorConsole: Boolean = false,
)

/** Welle V1.9.57 -- result of `executeTemporaryPassword`: shown ONCE, never stored. */
@Serializable
data class PrivilegedPasswordResultDto(
    val generatedPassword: String,
    val revokedSessionCount: Int,
    val memberNotified: MailDeliveryState,
)
