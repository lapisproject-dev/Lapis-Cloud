-- Welle V1.7.1b "Keycloak als externe Benutzerverwaltung -- Server-Kern" -- adds the two tables
-- backing the optional, default-OFF Keycloak Relying-Party login path (see
-- network.lapis.cloud.server.keycloak.KeycloakConfig KDoc and the vault spec
-- "Keycloak Externe Benutzerverwaltung.md"). Purely additive and idempotent (IF NOT EXISTS
-- everywhere); no earlier migration is touched.
--
-- Deliberately NO new column on account/member: this is a SEPARATE linking concept from the
-- existing account.oidc_issuer/oidc_subject pair, which identifies a federated GUEST (this server
-- acting as Relying Party against a REMOTE Lapis Cloud home server, V0.8.2). keycloak_account_link
-- links a real, EXISTING local member to an external Keycloak identity -- it never creates a
-- member on its own (see KeycloakAccountLinker KDoc).

CREATE TABLE IF NOT EXISTS keycloak_account_link (
    id               UUID         NOT NULL PRIMARY KEY,
    member_id        UUID         NOT NULL,
    keycloak_issuer  VARCHAR(2048) NOT NULL,
    keycloak_subject VARCHAR(255) NOT NULL,
    linked_at        TIMESTAMP    NOT NULL,
    linked_by        UUID         NULL,
    last_login_at    TIMESTAMP    NULL
);
ALTER TABLE keycloak_account_link DROP CONSTRAINT IF EXISTS fk_keycloak_account_link_member;
ALTER TABLE keycloak_account_link ADD CONSTRAINT fk_keycloak_account_link_member
    FOREIGN KEY (member_id) REFERENCES member(id);
ALTER TABLE keycloak_account_link DROP CONSTRAINT IF EXISTS fk_keycloak_account_link_linked_by;
ALTER TABLE keycloak_account_link ADD CONSTRAINT fk_keycloak_account_link_linked_by
    FOREIGN KEY (linked_by) REFERENCES member(id);
-- A member has at most one Keycloak link.
CREATE UNIQUE INDEX IF NOT EXISTS uq_keycloak_account_link_member ON keycloak_account_link (member_id);
-- A given (issuer, subject) pair identifies exactly one local member.
CREATE UNIQUE INDEX IF NOT EXISTS uq_keycloak_account_link_issuer_subject
    ON keycloak_account_link (keycloak_issuer, keycloak_subject);

-- RP side pre-auth scratch state (PKCE/state/nonce) for one in-flight Keycloak login attempt --
-- NO member FK by design (resolved to a member only after successful verification+linking), same
-- shape as oidc_rp_login_attempt (25-oidc-guest-federation).
CREATE TABLE IF NOT EXISTS keycloak_login_attempt (
    id            UUID         NOT NULL PRIMARY KEY,
    state_hash    VARCHAR(64)  NOT NULL,
    code_verifier VARCHAR(128) NOT NULL,
    nonce         VARCHAR(255) NOT NULL,
    redirect_uri  VARCHAR(2048) NOT NULL,
    created_at    TIMESTAMP    NOT NULL,
    expires_at    TIMESTAMP    NOT NULL,
    consumed_at   TIMESTAMP    NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_keycloak_login_attempt_state_hash ON keycloak_login_attempt (state_hash);
