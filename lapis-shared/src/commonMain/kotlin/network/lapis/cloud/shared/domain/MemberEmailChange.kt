package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Welle V1.9.56 "E-Mail-Änderung absichern" -- which path produced a change of a member's login address.
 *
 * - [SELF]: the owner, signed in, with the current password (effective immediately).
 * - [PROPOSAL]: board/admin proposal against a member who has a password -- takes effect only when the owner
 *   accepts WITH the password.
 * - [PROPOSAL_NO_ACCOUNT]: board/admin proposal against a member without a usable password (no login account, or the
 *   password is managed by the identity provider) -- takes effect after proof of ownership of the new address AND a
 *   72 hour warning period without rejection.
 * - [ADMIN_OVERRIDE]: the emergency path (ADMIN only, with a reason) -- same ownership + 72 hour rule.
 * - [IDP_SYNC]: Welle V1.9.73 -- the address was taken over from the identity provider (Keycloak) at login, applied at
 *   once. The proof is the verified ID token (`email_verified = true`); there are no tokens and no warning period, and
 *   the row is written already APPLIED (or CONFLICT), never PENDING. Opt-in (`LAPIS_KEYCLOAK_SYNC_PROFILE`).
 */
@Serializable
enum class EmailChangeKind { SELF, PROPOSAL, PROPOSAL_NO_ACCOUNT, ADMIN_OVERRIDE, IDP_SYNC }

/** Welle V1.9.56 -- lifecycle of a `member_email_change` row. Only PENDING is open. */
@Serializable
enum class EmailChangeStatus { PENDING, APPLIED, REVOKED, WITHDRAWN, EXPIRED, SUPERSEDED, CONFLICT }

/**
 * Welle V1.9.56 -- what the UI must know before offering an e-mail change. [mailDelivery] is the honest outbound-mail
 * truth (NOT_CONFIGURED disables every third-party path); [ownChangeAvailable] is false while the caller's login is
 * managed by the identity provider (Keycloak, non-ADMIN) or the caller has no password account.
 */
@Serializable
data class EmailChangeCapabilityDto(
    val mailDelivery: MailDeliveryState,
    val ownChangeAvailable: Boolean,
)

/** Welle V1.9.56 -- result of an immediate own change. [oldAddressNotified] says whether the info mail to the old address was handed to SMTP. */
@Serializable
data class OwnEmailChangeResultDto(
    val oldAddressNotified: MailDeliveryState,
)

/**
 * Welle V1.9.56 -- an open change as the OWNER sees it. [newEmail] is plaintext on purpose (the owner must recognise
 * the address) and appears in no other DTO. [requiresPassword] is true for [EmailChangeKind.PROPOSAL] (accept with the
 * password), false when the change takes effect through the link to the new address plus the warning period.
 */
@Serializable
data class OwnPendingEmailChangeDto(
    val changeId: String,
    val newEmail: String,
    val kind: EmailChangeKind,
    val expiresAt: LocalDateTime,
    val effectiveAt: LocalDateTime?,
    val requiresPassword: Boolean,
)

/**
 * Welle V1.9.56 -- wrapper of [OwnPendingEmailChangeDto]: Kilua RPC cannot return a nullable type, so "no open change" is
 * [pending] == null inside a DTO.
 */
@Serializable
data class OwnPendingEmailChangeStateDto(
    val pending: OwnPendingEmailChangeDto? = null,
)

/** Welle V1.9.56 -- wrapper of [EmailChangePendingDto] for the same reason as [OwnPendingEmailChangeStateDto]. */
@Serializable
data class PendingEmailChangeLookupDto(
    val pending: EmailChangePendingDto? = null,
)

/** Welle V1.9.56 -- an open change as an ADMINISTRATOR sees it: the new address only MASKED, never a token. */
@Serializable
data class EmailChangePendingDto(
    val changeId: String,
    val newEmailMasked: String,
    val kind: EmailChangeKind,
    val expiresAt: LocalDateTime,
    val effectiveAt: LocalDateTime?,
    val newEmailConfirmed: Boolean,
    /** `true` when the CALLER may withdraw this change: the initiator, or an ADMIN. */
    val withdrawable: Boolean = true,
)

/** Welle V1.9.56 -- one event in the audit trail of an address change. Carries NEVER an address. */
@Serializable
enum class EmailChangeAuditEvent {
    REQUESTED,
    NEW_ADDRESS_CONFIRMED,
    APPLIED,
    REVOKED,
    WITHDRAWN,
    EXPIRED,
    SUPERSEDED,
    CONFLICT,
}

/**
 * Welle V1.9.56 -- audit facts of an address change. **No address, not even masked or hashed**: the audit log is
 * hash-chained and not erasable, a hash of a mailbox address can be reversed by dictionary (Art. 17 GDPR). The
 * addresses live only in the erasable `member_email_change` row; [changeId] links to it. [linkActor] is set when the
 * event came from a link click (the person behind the link is unauthenticated), otherwise null.
 */
@Serializable
data class EmailChangeAuditFacts(
    val event: EmailChangeAuditEvent,
    val kind: EmailChangeKind,
    val changeId: String,
    val linkActor: EmailChangeLinkActor? = null,
)

/** Welle V1.9.56 -- which mailbox the unauthenticated person behind a link event controls. */
@Serializable
enum class EmailChangeLinkActor { OLD_ADDRESS, NEW_ADDRESS }
