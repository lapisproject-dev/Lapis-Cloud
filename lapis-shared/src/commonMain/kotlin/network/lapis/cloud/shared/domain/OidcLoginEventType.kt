package network.lapis.cloud.shared.domain

import kotlinx.serialization.Serializable

/**
 * V0.8.2 OIDC-Gastzugang-Federation -- individual-MEMBER identity federation (a guest of "home
 * server A" logging into "visited server B" using their home-server identity via OpenID Connect
 * Authorization Code + PKCE), a completely SEPARATE mechanism from V0.8.1's server-to-server
 * CONTENT federation (`FederationRelationshipDirection`/`FederationRelationshipStatus` in
 * `Federation.kt`). See `network.lapis.cloud.server.routes.OidcRoutes` KDoc for the full protocol
 * rationale and `25-oidc-guest-federation.kuml.kts` for the persisted shape.
 *
 * [OidcLoginEventType] backs `oidc_guest_login_event` -- a forensic, non-hash-chained login/logout
 * audit trail (NOT `audit_log_entry`; `AuditEntityType`'s literal set is deliberately bounded to
 * GoBD financial/legal scope, the same reasoning V0.8.1's `federation_inbox_delivery_log` already
 * established for its own forensic log).
 */
@Serializable
enum class OidcLoginEventType {
    RP_LOGIN_SUCCESS,
    RP_LOGIN_FAILED,
    ISSUER_TOKEN_ISSUED,
    ISSUER_TOKEN_ISSUE_FAILED,
    BACKCHANNEL_LOGOUT_RECEIVED,
    BACKCHANNEL_LOGOUT_SENT,

    /**
     * V1.7.1b "Keycloak als externe Benutzerverwaltung" -- reuses this same forensic audit trail
     * (`oidc_guest_login_event`) rather than inventing a parallel logging mechanism, even though
     * the Keycloak RP flow ([network.lapis.cloud.server.routes.KeycloakAuthRoutes]) is a
     * structurally different trust model from the V0.8.2 OIDC guest flow above (see
     * [network.lapis.cloud.server.keycloak.KeycloakAccountLinker] KDoc). The four literals below
     * were anticipated in the original V1.7.1b plan and are wired in by `KeycloakAuthRoutes`'s
     * callback handler.
     */
    KEYCLOAK_LOGIN_SUCCESS,
    KEYCLOAK_LOGIN_FAILED,
    KEYCLOAK_LINK_CREATED,
    KEYCLOAK_LINK_MISS,

    /**
     * V1.7.2 sub-wave 2a "Keycloak als externe Benutzerverwaltung -- UI (Server-Seite)" -- an
     * ADMIN manually links a member via
     * [network.lapis.cloud.shared.rpc.IKeycloakLinkService.linkMember] (as opposed to
     * [KEYCLOAK_LINK_CREATED], which is the AUTOMATIC email-match link a Keycloak login itself
     * creates -- see [network.lapis.cloud.server.keycloak.KeycloakAccountLinker] KDoc). Distinct
     * from [KEYCLOAK_LINK_CREATED] so an operator can tell the two apart in the audit trail: an
     * automatic link is routine; a manual one is an admin action worth its own signal.
     */
    KEYCLOAK_LINK_MANUAL,

    /**
     * V1.7.2 sub-wave 2a -- the admin-initiated counterpart to [KEYCLOAK_LINK_MANUAL]:
     * [network.lapis.cloud.shared.rpc.IKeycloakLinkService.unlinkMember] removed an existing
     * `keycloak_account_link` row. There is no automatic-unlink equivalent (nothing in this
     * codebase ever removes a link on its own), so this literal is always admin-initiated.
     */
    KEYCLOAK_LINK_MANUAL_REMOVED,
}
