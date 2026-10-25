-- Welle V1.9.207: last successful sign-in per account (admin member list column "Letzte Anmeldung").
-- V1..V117 untouched, no PII beyond timestamp + method enum, no audit. Additive and idempotent, no UPDATE/backfill:
-- accounts that signed in before V118 show "never" until their next sign-in.
-- last_login_at is UTC (Klasse A, same convention as role_changed_at).
ALTER TABLE account ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMP NULL;
ALTER TABLE account ADD COLUMN IF NOT EXISTS last_login_method VARCHAR(32) NULL;
ALTER TABLE account DROP CONSTRAINT IF EXISTS chk_account_last_login_method;
ALTER TABLE account ADD CONSTRAINT chk_account_last_login_method
    CHECK (last_login_method IS NULL OR last_login_method IN ('PASSWORD', 'KEYCLOAK', 'OIDC_FEDERATION'));
ALTER TABLE account DROP CONSTRAINT IF EXISTS chk_account_last_login_pair;
ALTER TABLE account ADD CONSTRAINT chk_account_last_login_pair
    CHECK ((last_login_at IS NULL AND last_login_method IS NULL) OR (last_login_at IS NOT NULL AND last_login_method IS NOT NULL));
