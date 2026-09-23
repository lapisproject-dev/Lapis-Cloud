-- V1.7.2 sub-wave 2a "Keycloak als externe Benutzerverwaltung -- UI (Server-Seite)" -- adds the two
-- new OidcLoginEventType literals for an ADMIN-initiated manual account link/unlink
-- (KeycloakLinkService.linkMember/unlinkMember), reusing the same oidc_guest_login_event forensic
-- audit trail V46 already extended for the automatic Keycloak RP login events -- see
-- OidcLoginEventType KDoc and V46__keycloak_login_event_types.sql for the "why reuse, not a new
-- table" reasoning. The baseline column is VARCHAR(27), too narrow for
-- 'KEYCLOAK_LINK_MANUAL_REMOVED' (29 characters, the longest literal now) -- widened to VARCHAR(29)
-- below, together with OidcGuestLoginEventTable.kt's matching `enumerationByName(..., 29)`.
ALTER TABLE oidc_guest_login_event DROP CONSTRAINT IF EXISTS chk_oidc_guest_login_event_event_type;
ALTER TABLE oidc_guest_login_event ALTER COLUMN event_type TYPE VARCHAR(29);
ALTER TABLE oidc_guest_login_event ADD CONSTRAINT chk_oidc_guest_login_event_event_type
    CHECK (event_type IN (
        'RP_LOGIN_SUCCESS', 'RP_LOGIN_FAILED', 'ISSUER_TOKEN_ISSUED', 'ISSUER_TOKEN_ISSUE_FAILED',
        'BACKCHANNEL_LOGOUT_RECEIVED', 'BACKCHANNEL_LOGOUT_SENT',
        'KEYCLOAK_LOGIN_SUCCESS', 'KEYCLOAK_LOGIN_FAILED', 'KEYCLOAK_LINK_CREATED', 'KEYCLOAK_LINK_MISS',
        'KEYCLOAK_LINK_MANUAL', 'KEYCLOAK_LINK_MANUAL_REMOVED'
    ));
