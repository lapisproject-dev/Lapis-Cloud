-- V1.2.11 "Einmaliger CSV-Mitglieder-Import (PdV)" -- see MemberCsvImport (bootstrap package) for
-- the operator-run CLI this migration exists to support, and network.lapis.cloud.shared.domain
-- .MemberStatus KDoc for the two new literals' full rationale.
--
-- Idempotent by construction (IF NOT EXISTS / dual-DROP-then-ADD), same discipline as V2-V9.
--
-- Aufteilungsregel: both changes here are modifications to an EXISTING table (member) -- they are
-- repeated idempotently here AND in-place in V1__baseline.sql, so a fresh DB (every test run) and
-- the already-migrated pdv2/ELB instances reach the same end state. No genuinely new table is
-- introduced by this wave, so unlike V4-V9 there is nothing that lives ONLY here.
--
-- WHY V10 AND NOT AN EXTENSION OF V9: V8__sepa_mandates.sql and V9__dunning.sql are merged and
-- rolled out on pdv2 (>= V1.2.6); their checksums are consumed. An in-place edit of either would
-- fail flyway migrate there hard (validateOnMigrate = true, DatabaseConfig.kt).
--
-- OPERATOR NOTE: this migration edits V1__baseline.sql in place (again) -- run `flyway repair`
-- BEFORE the next deploy on BOTH pdv2 AND the ELB instance (two co-located production instances
-- since V1.2.6). Deploy the new server version BEFORE running MemberCsvImport -- see that class's
-- KDoc "Ablauf für den Operator" for why the ordering matters (a pre-deploy import would write
-- DONOR/DECEASED rows the still-running OLD server process's MemberStatus enum cannot deserialize).

-- ---------------------------------------------------------------------------
-- member.status CHECK widening: DONOR/DECEASED join the existing six literals.
--
-- Dual-DROP like V3/V4/V6/V7/V8/V9: pdv2 carries the explicitly named chk_member_status (since
-- V3), a genuinely old instance might still carry the Postgres-auto-generated member_status_check.
-- Exactly one of the two DROPs matches per environment. VERIFY WITH `\d member` ON pdv2/ELB BEFORE
-- DEPLOY.
-- ---------------------------------------------------------------------------
ALTER TABLE member DROP CONSTRAINT IF EXISTS member_status_check;
ALTER TABLE member DROP CONSTRAINT IF EXISTS chk_member_status;
ALTER TABLE member ADD CONSTRAINT chk_member_status
    CHECK (status IN ('APPLICATION', 'ACTIVE', 'GUEST', 'WITHDRAWN', 'REJECTED', 'FRIEND', 'DONOR', 'DECEASED'));

-- ---------------------------------------------------------------------------
-- member.external_reference: the source-CRM's own person number. NULL for organically created
-- members. No UNIQUE constraint -- a partial unique index scoped to non-NULL values is not
-- expressible under H2's MODE=PostgreSQL test dialect (same limitation V9__dunning.sql's
-- uq_dunning_notice_slot and SepaMandateTable already document); uniqueness is enforced
-- procedurally by MemberCsvImport's own idempotency check instead. No UPDATE rows needed here
-- (unlike V3) -- there are no existing values to backfill.
-- ---------------------------------------------------------------------------
ALTER TABLE member ADD COLUMN IF NOT EXISTS external_reference VARCHAR(50) NULL;
CREATE INDEX IF NOT EXISTS idx_member_external_reference ON member (external_reference);
