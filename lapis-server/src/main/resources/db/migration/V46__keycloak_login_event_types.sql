-- Welle V1.7.1b review fix: reuses the existing `oidc_guest_login_event` forensic audit trail (see
-- V1__baseline.sql "oidc_guest_login_event" and OidcLoginAuditRecorder KDoc) for Keycloak RP login
-- events instead of inventing a parallel logging mechanism -- see OidcLoginEventType KDoc for the
-- four new literals (KEYCLOAK_LOGIN_SUCCESS, KEYCLOAK_LOGIN_FAILED, KEYCLOAK_LINK_CREATED,
-- KEYCLOAK_LINK_MISS). The column's `CHECK (event_type IN (...))` constraint from the baseline only
-- allowed the original six OIDC-guest-federation literals -- extended here, VARCHAR(27) already
-- fits every new literal ('KEYCLOAK_LOGIN_SUCCESS' is 22 characters, the longest of the four).
ALTER TABLE oidc_guest_login_event DROP CONSTRAINT IF EXISTS oidc_guest_login_event_event_type_check;
ALTER TABLE oidc_guest_login_event DROP CONSTRAINT IF EXISTS chk_oidc_guest_login_event_event_type;
ALTER TABLE oidc_guest_login_event ADD CONSTRAINT chk_oidc_guest_login_event_event_type
    CHECK (event_type IN (
        'RP_LOGIN_SUCCESS', 'RP_LOGIN_FAILED', 'ISSUER_TOKEN_ISSUED', 'ISSUER_TOKEN_ISSUE_FAILED',
        'BACKCHANNEL_LOGOUT_RECEIVED', 'BACKCHANNEL_LOGOUT_SENT',
        'KEYCLOAK_LOGIN_SUCCESS', 'KEYCLOAK_LOGIN_FAILED', 'KEYCLOAK_LINK_CREATED', 'KEYCLOAK_LINK_MISS'
    ));
