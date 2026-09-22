-- V1.2.12 "Mitgliederverwaltung: vollständige Bearbeitung + privilegiertes Roster" -- see
-- network.lapis.cloud.server.rpc.MemberService KDoc for the three privileged update RPCs this
-- migration supports, and network.lapis.cloud.shared.domain.AuditEntityType KDoc for the new
-- MEMBER audit-entity literal.
--
-- Idempotent by construction (IF NOT EXISTS / dual-DROP-then-ADD), same discipline as V2-V10.
--
-- Aufteilungsregel: both changes here are modifications to EXISTING tables (audit_log_entry,
-- member) -- they are repeated idempotently here AND in-place in V1__baseline.sql, so a fresh DB
-- (every test run) and the already-migrated pdv2/ELB instances reach the same end state. No
-- genuinely new table is introduced by this wave.
--
-- WHY V11 AND NOT AN EXTENSION OF V10: V10__member_donor_deceased_and_external_reference.sql is
-- rolled out on pdv2 -- the operator ran MemberCsvImport against it (407 rows actually written),
-- so its checksum is consumed. An in-place edit of V10 would fail `flyway migrate` there hard
-- (validateOnMigrate = true, DatabaseConfig.kt).
--
-- OPERATOR NOTE: this migration edits V1__baseline.sql in place (again) -- run `flyway repair`
-- BEFORE the next deploy on BOTH pdv2 AND the ELB instance (two co-located production instances
-- since V1.2.6).

-- ---------------------------------------------------------------------------
-- audit_log_entry.entity_type CHECK widening: MEMBER joins the existing thirteen literals.
--
-- Dual-DROP like V4/V6/V7/V8/V9: pdv2 carries the explicitly named
-- chk_audit_log_entry_entity_type (since V9), a genuinely old instance might still carry the
-- Postgres-auto-generated audit_log_entry_entity_type_check. Exactly one of the two DROPs matches
-- per environment. VERIFY WITH `\d audit_log_entry` ON pdv2/ELB BEFORE DEPLOY.
--
-- 'MEMBER' (6 chars) < CONFERENCE_STREAM_DESTINATION (29, the longest existing literal) -- no
-- column-width change needed (entity_type stays VARCHAR(29)).
-- ---------------------------------------------------------------------------
ALTER TABLE audit_log_entry DROP CONSTRAINT IF EXISTS audit_log_entry_entity_type_check;
ALTER TABLE audit_log_entry DROP CONSTRAINT IF EXISTS chk_audit_log_entry_entity_type;
ALTER TABLE audit_log_entry ADD CONSTRAINT chk_audit_log_entry_entity_type
    CHECK (entity_type IN (
        'JOURNAL_ENTRY', 'PARTY_DONATION_VERDICT', 'RESOLUTION', 'BOARD_MEMBERSHIP',
        'CONFERENCE_RECORDING', 'CONFERENCE_STREAM', 'CONFERENCE_STREAM_DESTINATION', 'CONFERENCE_ROOM',
        'SOCIAL_POST', 'ORGANIZATION_SETTINGS', 'SEPA_MANDATE', 'SEPA_DEBIT_BATCH', 'DUNNING_NOTICE',
        'MEMBER'
    ));

-- ---------------------------------------------------------------------------
-- Two indexes for the privileged roster view (network.lapis.cloud.shared.rpc.IMemberService
-- .listMembersForAdministration): ORDER BY display_name and the status-chip filter. The
-- leading-wildcard substring search deliberately does NOT use either index -- at 407 rows
-- irrelevant; an upgrade path (pg_trgm) would only matter at roughly 50k+ rows and is not part
-- of this wave.
--
-- No kUML model change needed for either -- non-unique indexes are NOT tracked in this repo's
-- kUML model (precedent: idx_member_external_reference, V10, carries no «Index» stereotype in
-- 00-foundation.kuml.kts) and SchemaDriftTest introspects only 'UNIQUE INDEX' rows.
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_member_display_name ON member (display_name);
CREATE INDEX IF NOT EXISTS idx_member_status ON member (status);
