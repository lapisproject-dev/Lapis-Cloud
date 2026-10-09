-- Welle V1.9.82 -- public events feed with full text, archive feed and admin import of past events. V1..V80 untouched. Purely additive, H2 MODE=PostgreSQL-
-- and Postgres-compatible (ADD COLUMN IF NOT EXISTS, CREATE INDEX IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT, no DO
-- block, no partial or expression indexes -- H2 cannot build the partial index the design sketched, a plain composite index serves the
-- archive query instead).
--
-- V1__baseline.sql stays UNTOUCHED (no flywayRepair needed), exactly like V80. It carries an inline, UNNAMED CHECK on
-- audit_log_entry.entity_type. On PostgreSQL that constraint was dropped by V6 and is replaced by the NAMED chk_audit_log_entry_entity_type
-- below. On H2 -- tests, dev -- the same unnamed constraint gets a generated UPPER-CASE name (CONSTRAINT_407, deterministic because V1 is
-- frozen; the quoted identifier matters, with DATABASE_TO_LOWER an unquoted one would be folded) and H2 enforces it independently of the
-- named one, so it must go too, or an INSERT with entity_type = 'EVENT' fails even after this migration. It is dropped BY THAT GENERATED
-- NAME; on PostgreSQL no constraint of that name exists and IF EXISTS makes the statement a no-op. EventFeedImportMigrationScenarios pins
-- the result (EVENT and EVENT_IMPORT accepted, an unknown type still rejected) on both databases, so a shifted name fails loudly.
--
--   * summary / cover_image_alt: short public texts for the events feed (teaser, alt text of the cover image).
--   * online_url_public: the online link is only shown on the public page and in the feed when an administrator explicitly ticked this.
--   * imported: TRUE for events created by the admin import of past events (EventImporter); never changeable through updateEvent.

ALTER TABLE event ADD COLUMN IF NOT EXISTS summary VARCHAR(300) NULL;
ALTER TABLE event ADD COLUMN IF NOT EXISTS cover_image_alt VARCHAR(500) NULL;
ALTER TABLE event ADD COLUMN IF NOT EXISTS online_url_public BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE event ADD COLUMN IF NOT EXISTS imported BOOLEAN NOT NULL DEFAULT FALSE;

-- Archive feed: WHERE status = 'PUBLISHED' AND visibility = 'PUBLIC' AND ends_at <= ? ORDER BY ends_at DESC, id DESC. Equality columns first,
-- then the range/sort column; the backward index scan serves DESC.
CREATE INDEX IF NOT EXISTS idx_event_public_archive ON event (status, visibility, ends_at, id);

-- Two new AuditEntityType literals (EVENT, EVENT_IMPORT), appended LAST -- same DROP/ADD dance on the NAMED
-- chk_audit_log_entry_entity_type constraint every prior wave's migration establishes (list copied from V73__encounter_space.sql, plus
-- 'EVENT', 'EVENT_IMPORT').
ALTER TABLE audit_log_entry
    DROP CONSTRAINT IF EXISTS "CONSTRAINT_407";
ALTER TABLE audit_log_entry
    DROP CONSTRAINT IF EXISTS chk_audit_log_entry_entity_type;
ALTER TABLE audit_log_entry
    ADD CONSTRAINT chk_audit_log_entry_entity_type
        CHECK (entity_type IN (
                                'JOURNAL_ENTRY', 'PARTY_DONATION_VERDICT', 'RESOLUTION', 'BOARD_MEMBERSHIP',
                                'CONFERENCE_RECORDING', 'CONFERENCE_STREAM', 'CONFERENCE_STREAM_DESTINATION',
                                'CONFERENCE_ROOM', 'SOCIAL_POST', 'ORGANIZATION_SETTINGS', 'SEPA_MANDATE',
                                'SEPA_DEBIT_BATCH', 'DUNNING_NOTICE', 'MEMBER', 'PAYMENT_TRANSACTION', 'API_KEY',
                                'WEBHOOK_ENDPOINT', 'BANK_STATEMENT_IMPORT', 'ACCOUNTING_EXPORT_CONNECTION',
                                'ACCOUNTING_EXPORT_RUN', 'ACCOUNTING_EXPORT_MAPPING', 'CONTRIBUTION_RELIEF_REQUEST',
                                'TRAVEL_EXPENSE_REPORT', 'VOLUNTEER_ALLOWANCE_PAYMENT', 'VOLUNTEER_DECLARATION',
                                'BANK_ACCOUNT', 'OPEN_ITEM', 'OPEN_ITEM_NETTING', 'RECEIVABLE_DUNNING_NOTICE',
                                'DOCUMENT', 'DOCUMENT_FOLDER', 'ARTICLE', 'REGIONAL_CHAPTER', 'REGIONAL_CHAPTER_OFFICER',
                                'MEMBERSHIP_TIER', 'POLL', 'ENCOUNTER_SPACE', 'EVENT', 'EVENT_IMPORT'
        ));
