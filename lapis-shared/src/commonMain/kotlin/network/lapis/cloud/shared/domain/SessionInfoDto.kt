package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * "Whoami" projection of the caller's currently resolved session (V0.7.1 Authentifizierung) --
 * see [network.lapis.cloud.shared.rpc.IAuthService.getSessionInfo]. Deliberately minimal: just
 * enough for a client to display "logged in as X (role)" and know when its session will expire --
 * NOT a full [MemberDto] (no email/address/beneficial-owner fields here; use
 * [network.lapis.cloud.shared.rpc.IMemberService.getCurrentMember] for that).
 *
 * [isGuest] and [homeserverUrl] (V0.8.4 Guest Badge) exist specifically so a client can render a
 * federated OIDC guest indicator -- not a step toward turning this into a full profile DTO.
 * [homeserverUrl] is `null` for a non-guest session and always non-null for a genuine guest
 * session (every `GUEST` member has a 1:1 row in
 * `network.lapis.cloud.server.db.generated.OidcGuestProfileTable`, populated at guest
 * login/refresh -- see `network.lapis.cloud.server.federation.OidcGuestMemberStore`).
 *
 * [status] (V0.11.0) is the caller's real [MemberStatus] -- added so client-side navigation gating
 * (hiding governance/accounting/LTR/documents/mailing entries for a [MemberStatus.FRIEND]) can be
 * driven from this one field rather than a new, independently-derived client-side boolean.
 * Defaults to [MemberStatus.ACTIVE] so every pre-existing construction site of this DTO stays
 * source-compatible without silently mis-describing a non-member session.
 */
@Serializable
data class SessionInfoDto(
    val memberId: String,
    val displayName: String,
    val role: AccountRole,
    val expiresAt: LocalDateTime,
    val isGuest: Boolean = false,
    val homeserverUrl: String? = null,
    val status: MemberStatus = MemberStatus.ACTIVE,
)
