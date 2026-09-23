-- V1.7.1b security-audit fixes (MAJOR 1 + MAJOR 2a) -- see
-- network.lapis.cloud.server.routes.KeycloakAuthRoutes KDoc and
-- network.lapis.cloud.server.db.generated.KeycloakLoginAttemptTable KDoc "browserBindingHash".
--
-- MAJOR 1 (login CSRF / session fixation via unbound `state`): a `keycloak_login_attempt` row
-- used to carry no link back to the specific browser that started it at `/start` -- an attacker
-- could capture their own `/callback?code=...&state=...` URL and hand it to a victim, whose
-- browser would then redeem it into the ATTACKER's session. `browser_binding_hash` stores a hash
-- of a fresh, HttpOnly cookie value minted on `/start` and required to match on `/callback`.
-- Nullable (not NOT NULL): a transient in-flight row from immediately before this migration has no
-- value here yet -- it simply fails the new binding check on its next callback, which is the
-- correct fail-closed behavior for a scratch table with a 10-minute TTL, not a reason to make an
-- ADD COLUMN unsafe against a Postgres instance that may have a few live rows at deploy time.
ALTER TABLE keycloak_login_attempt ADD COLUMN IF NOT EXISTS browser_binding_hash VARCHAR(64) NULL;

-- MAJOR 2a (unbounded, unauthenticated growth of keycloak_login_attempt): `/start` now
-- opportunistically deletes old rows (expired or long-consumed) on every call -- this index makes
-- that DELETE's WHERE clause on expires_at an index scan instead of a full table scan, which
-- matters precisely under the flood scenario the finding describes.
CREATE INDEX IF NOT EXISTS idx_keycloak_login_attempt_expires_at ON keycloak_login_attempt (expires_at);
