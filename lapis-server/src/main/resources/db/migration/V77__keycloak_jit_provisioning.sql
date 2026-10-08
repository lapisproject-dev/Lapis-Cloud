-- Welle V1.9.73 "Keycloak: Just-in-time-Anlage und Profil-Abgleich" -- additive, no new table or column.
--
-- 1. member_status_history.source gains KEYCLOAK_JIT (a member created on the first Keycloak login). A runtime row, so it carries
--    recorded_at like LIVE / IMPORT / SEED (chk_member_status_history_recorded).
-- 2. member_email_change.kind gains IDP_SYNC (an address taken over from the identity provider at login, applied at once).
--
-- DROP CONSTRAINT IF EXISTS + ADD CONSTRAINT: valid on H2 (MODE=PostgreSQL) and PostgreSQL alike. V1..V76 stay untouched.

ALTER TABLE member_status_history DROP CONSTRAINT IF EXISTS chk_member_status_history_source;
ALTER TABLE member_status_history ADD CONSTRAINT chk_member_status_history_source
    CHECK (source IN ('LIVE', 'IMPORT', 'SEED', 'KEYCLOAK_JIT', 'BACKFILL_AUDIT', 'BACKFILL_RECORD', 'BACKFILL_ASSUMED'));

ALTER TABLE member_status_history DROP CONSTRAINT IF EXISTS chk_member_status_history_recorded;
ALTER TABLE member_status_history ADD CONSTRAINT chk_member_status_history_recorded CHECK (
    (source IN ('BACKFILL_AUDIT', 'BACKFILL_RECORD', 'BACKFILL_ASSUMED') AND recorded_at IS NULL)
    OR
    (source IN ('LIVE', 'IMPORT', 'SEED', 'KEYCLOAK_JIT') AND recorded_at IS NOT NULL)
);

ALTER TABLE member_email_change DROP CONSTRAINT IF EXISTS chk_member_email_change_kind;
ALTER TABLE member_email_change ADD CONSTRAINT chk_member_email_change_kind
    CHECK (kind IN ('SELF', 'PROPOSAL', 'PROPOSAL_NO_ACCOUNT', 'ADMIN_OVERRIDE', 'IDP_SYNC'));
