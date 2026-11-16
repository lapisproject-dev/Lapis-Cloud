-- Wave "administrator rights as an independent flag" -- V1..V154 untouched. H2 MODE=PostgreSQL- and Postgres-compatible (no DO block).
--
-- ADMIN is no longer a value of account.role: a member keeps a base role (MEMBER, BOARD, TREASURER) and may additionally hold the
-- administrator right (is_admin). admin_changed_at is the tenure clock of the admin peer protection (NULL = pre-existing admin, tenured).
--
-- account.role carries an INLINE, unnamed CHECK from V1. It is replaced by a NAMED one that no longer allows 'ADMIN':
--   * H2: the generated name is the UPPER-CASE "CONSTRAINT_B9D" (deterministic because V1 is immutable);
--     AccountAdminFlagMigrationTest pins that exactly one CHECK on `role` exists afterwards.
--   * PostgreSQL: the auto name is account_role_check.
--
-- ROLLBACK HAZARD: release 0.53.x cannot read an admin account after V155 (it expects role = 'ADMIN'). Roll back only from a backup taken
-- before V155. Backups taken before V155 are refused by the restore (schemaChecksum mismatch), by design.
ALTER TABLE account ADD COLUMN IF NOT EXISTS is_admin BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE account ADD COLUMN IF NOT EXISTS admin_changed_at TIMESTAMP NULL;

-- Backfill BEFORE the narrower CHECK. role_changed_at is deliberately kept: it stays the stamp of the base role, and a NULL
-- admin_changed_at keeps a seeded/legacy admin "tenured", exactly as before.
UPDATE account SET is_admin = TRUE, admin_changed_at = role_changed_at, role = 'MEMBER' WHERE role = 'ADMIN';

ALTER TABLE account DROP CONSTRAINT IF EXISTS "CONSTRAINT_B9D";
ALTER TABLE account DROP CONSTRAINT IF EXISTS account_role_check;
ALTER TABLE account DROP CONSTRAINT IF EXISTS chk_account_role;
ALTER TABLE account ADD CONSTRAINT chk_account_role CHECK (role IN ('MEMBER', 'BOARD', 'TREASURER'));
