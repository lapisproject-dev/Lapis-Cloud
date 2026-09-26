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
 *
 * [aiAssistantEnabled] (V1.6.1) tells the client whether the optional AI assistance layer is
 * operational on this server (`LAPIS_AI_ENABLED` + complete provider profile) so it can decide
 * whether the "Fragen zur Satzung" entry belongs in the navigation without an always-on probe
 * endpoint. Defaults to `false` -- feature off is the default everywhere, including every
 * pre-existing construction site.
 *
 * [keycloakMode] (V1.7.2 sub-wave 2a "Keycloak als externe Benutzerverwaltung -- UI") mirrors this
 * deployment's `keycloakConfig.enabled` -- lets an already-authenticated client decide, for
 * example, whether to offer a local "change password" action at all (a member whose login is
 * Keycloak-managed has no usable local password to change, see
 * [network.lapis.cloud.shared.rpc.IAuthService.changePassword] KDoc). Same idiom as
 * [aiAssistantEnabled]: defaults to `false` so every pre-existing construction site stays
 * source-compatible. The pre-login equivalent (before any session/RPC call exists) is the
 * `keycloakMode` field `network.lapis.cloud.server.branding.BrandingHtml.inject` writes into the
 * `id="lapis-brand"` payload -- deliberately two separate carriers for the SAME underlying
 * `keycloakConfig.enabled` value, one for each side of the "before vs. after login" boundary.
 *
 * [mcpEnabled]/[mcpWriteEnabled] (Welle V1.8.2b) mirror `McpConfig.isOperational`/
 * `.isWriteOperational` -- same "defaults to false, feature off everywhere pre-existing" idiom as
 * [aiAssistantEnabled]. [mcpEnabled] alone gates whether the client's "KI-Entwürfe" navigation
 * entry appears at all ([mcpWriteEnabled] `false` never hides it -- existing drafts stay reachable,
 * see `client.NavVisibility.showsAiDrafts` KDoc); [mcpWriteEnabled] additionally lets
 * `AiDraftsScreen` show an operator-disabled banner instead of silently offering a "create" action
 * that no longer exists anywhere for this session to trigger in the first place.
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
    val aiAssistantEnabled: Boolean = false,
    val keycloakMode: Boolean = false,
    val mcpEnabled: Boolean = false,
    val mcpWriteEnabled: Boolean = false,
)
