-- V1.9.289 "Rechenschaftsbericht (PartG), R4a": accounts -> report positions, bookkeeping mode per unit and year, the chapter on the journal
-- entry and the chapter treasurer. See 10-accounting.kuml.kts, 58-regional-chapter.kuml.kts, 76-partg-report.kuml.kts and
-- docs/features/partg-report.adoc. V1..V143 untouched (V143 is already on master, so R4 gets its own migration).
-- Purely additive and repeatable: IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT, no partial index, no ON DELETE
-- CASCADE, H2 MODE=PostgreSQL- and PostgreSQL-compatible, NO backfill (every existing journal entry stays "federal", NULL).
-- While this wave is unreleased V144 may still be corrected in place on the feature branch; after the release every correction is a NEW
-- migration.
--
--   * journal_entry.regional_chapter_id: the reporting chapter of a booking, NULL = federal level. Set ONLY when the entry is inserted and
--     never changed afterwards (a posted entry is immutable; there is no RPC that updates a journal entry's chapter).
--   * regional_chapter_treasurer: the grant "chapter treasurer" (a plain member may book and read the journal of the chapters granted).
--     Unlike regional_chapter_officer a member may hold SEVERAL active grants, so the uniqueness guard is the pair (member, chapter):
--     active_key = member_id || ':' || regional_chapter_id while active, NULL once revoked (H2 has no partial index).
--   * partg_account_mapping: ledger account -> report position (n:1) or "deliberately not in the report". A missing row = not yet mapped.
--     That the target is a LEAF position of the matching section is enforced by the service (shared PartgLedgerRules), as with
--     partg_report_value.
--   * partg_report_unit_mode: bookkeeping mode A/B/C per unit and year. A missing row = C ("own cash outside Lapis"). SUB units are never
--     stored (always C).
--   * partg_report_value.value_source: LEDGER added (a value proposed from the bookings).
--   * audit_log_entry.entity_type: PARTG_ACCOUNT_MAPPING, PARTG_UNIT_MODE, REGIONAL_CHAPTER_TREASURER added. The list is V139's, verbatim.
--
-- NOT expressible in the ERM profile (SQL only): every CHECK below and the active_key consistency.
-- DSGVO: organisation data. updated_by / granted_by_member_id are "changed by you" data.

ALTER TABLE journal_entry ADD COLUMN IF NOT EXISTS regional_chapter_id UUID NULL;
ALTER TABLE journal_entry DROP CONSTRAINT IF EXISTS fk_journal_entry_regional_chapter;
ALTER TABLE journal_entry ADD CONSTRAINT fk_journal_entry_regional_chapter
    FOREIGN KEY (regional_chapter_id) REFERENCES regional_chapter (id);
CREATE INDEX IF NOT EXISTS ix_journal_entry_chapter_date ON journal_entry (regional_chapter_id, entry_date);

CREATE TABLE IF NOT EXISTS regional_chapter_treasurer (
    id                    UUID         NOT NULL PRIMARY KEY,
    member_id             UUID         NOT NULL REFERENCES member (id),
    regional_chapter_id   UUID         NOT NULL REFERENCES regional_chapter (id),
    granted_at            TIMESTAMP    NOT NULL,
    granted_by_member_id  UUID         NULL REFERENCES member (id),
    revoked_at            TIMESTAMP    NULL,
    active_key            VARCHAR(73)  NULL
);

ALTER TABLE regional_chapter_treasurer DROP CONSTRAINT IF EXISTS chk_regional_chapter_treasurer_active;
ALTER TABLE regional_chapter_treasurer ADD CONSTRAINT chk_regional_chapter_treasurer_active CHECK (
    (revoked_at IS NULL AND active_key IS NOT NULL
        AND active_key = CAST(member_id AS VARCHAR(36)) || ':' || CAST(regional_chapter_id AS VARCHAR(36)))
    OR (revoked_at IS NOT NULL AND active_key IS NULL)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_rct_active_key ON regional_chapter_treasurer (active_key);
CREATE INDEX IF NOT EXISTS ix_rct_member ON regional_chapter_treasurer (member_id);
CREATE INDEX IF NOT EXISTS ix_rct_chapter ON regional_chapter_treasurer (regional_chapter_id);
CREATE INDEX IF NOT EXISTS ix_rct_granted_by ON regional_chapter_treasurer (granted_by_member_id);

CREATE TABLE IF NOT EXISTS partg_account_mapping (
    id                UUID         NOT NULL PRIMARY KEY,
    ledger_account_id UUID         NOT NULL REFERENCES ledger_account (id),
    position_code     VARCHAR(16)  NULL REFERENCES partg_report_position (code),
    excluded          BOOLEAN      NOT NULL,
    updated_at        TIMESTAMP    NOT NULL,
    updated_by        UUID         NOT NULL REFERENCES member (id),
    CONSTRAINT uq_partg_account_mapping_account UNIQUE (ledger_account_id)
);

ALTER TABLE partg_account_mapping DROP CONSTRAINT IF EXISTS chk_partg_mapping_target;
ALTER TABLE partg_account_mapping ADD CONSTRAINT chk_partg_mapping_target CHECK (
    (excluded = TRUE AND position_code IS NULL) OR (excluded = FALSE AND position_code IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS ix_partg_account_mapping_position ON partg_account_mapping (position_code);
CREATE INDEX IF NOT EXISTS ix_partg_account_mapping_updated_by ON partg_account_mapping (updated_by);

CREATE TABLE IF NOT EXISTS partg_report_unit_mode (
    id                   UUID         NOT NULL PRIMARY KEY,
    fiscal_year          INTEGER      NOT NULL,
    unit_key             VARCHAR(48)  NOT NULL,
    regional_chapter_id  UUID         NULL REFERENCES regional_chapter (id),
    bookkeeping_mode     VARCHAR(1)   NOT NULL,
    updated_at           TIMESTAMP    NOT NULL,
    updated_by           UUID         NOT NULL REFERENCES member (id)
);

ALTER TABLE partg_report_unit_mode DROP CONSTRAINT IF EXISTS chk_partg_unit_mode_year;
ALTER TABLE partg_report_unit_mode ADD CONSTRAINT chk_partg_unit_mode_year CHECK (fiscal_year BETWEEN 2000 AND 2100);
ALTER TABLE partg_report_unit_mode DROP CONSTRAINT IF EXISTS chk_partg_unit_mode_mode;
ALTER TABLE partg_report_unit_mode ADD CONSTRAINT chk_partg_unit_mode_mode CHECK (bookkeeping_mode IN ('A', 'B', 'C'));
ALTER TABLE partg_report_unit_mode DROP CONSTRAINT IF EXISTS chk_partg_unit_mode_unit;
ALTER TABLE partg_report_unit_mode ADD CONSTRAINT chk_partg_unit_mode_unit CHECK (
    (regional_chapter_id IS NULL AND unit_key = 'FEDERAL')
    OR (regional_chapter_id IS NOT NULL AND unit_key = 'STATE:' || CAST(regional_chapter_id AS VARCHAR(36)))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_partg_report_unit_mode_year_unit ON partg_report_unit_mode (fiscal_year, unit_key);
CREATE INDEX IF NOT EXISTS ix_partg_report_unit_mode_chapter ON partg_report_unit_mode (regional_chapter_id);
CREATE INDEX IF NOT EXISTS ix_partg_report_unit_mode_updated_by ON partg_report_unit_mode (updated_by);

ALTER TABLE partg_report_value DROP CONSTRAINT IF EXISTS chk_partg_value_source;
ALTER TABLE partg_report_value ADD CONSTRAINT chk_partg_value_source CHECK (value_source IN ('MANUAL', 'IMPORT', 'LEDGER'));

ALTER TABLE audit_log_entry
    DROP CONSTRAINT IF EXISTS chk_audit_log_entry_entity_type;
ALTER TABLE audit_log_entry
    ADD CONSTRAINT chk_audit_log_entry_entity_type
        CHECK (entity_type IN ('JOURNAL_ENTRY', 'PARTY_DONATION_VERDICT', 'RESOLUTION', 'BOARD_MEMBERSHIP',
                                'CONFERENCE_RECORDING', 'CONFERENCE_STREAM', 'CONFERENCE_STREAM_DESTINATION',
                                'CONFERENCE_ROOM', 'SOCIAL_POST', 'ORGANIZATION_SETTINGS', 'SEPA_MANDATE',
                                'SEPA_DEBIT_BATCH', 'DUNNING_NOTICE', 'MEMBER', 'PAYMENT_TRANSACTION', 'API_KEY',
                                'WEBHOOK_ENDPOINT', 'BANK_STATEMENT_IMPORT', 'ACCOUNTING_EXPORT_CONNECTION',
                                'ACCOUNTING_EXPORT_RUN', 'ACCOUNTING_EXPORT_MAPPING', 'CONTRIBUTION_RELIEF_REQUEST',
                                'TRAVEL_EXPENSE_REPORT', 'VOLUNTEER_ALLOWANCE_PAYMENT', 'VOLUNTEER_DECLARATION',
                                'BANK_ACCOUNT', 'OPEN_ITEM', 'OPEN_ITEM_NETTING', 'RECEIVABLE_DUNNING_NOTICE',
                                'DOCUMENT', 'DOCUMENT_FOLDER', 'ARTICLE', 'REGIONAL_CHAPTER', 'REGIONAL_CHAPTER_OFFICER',
                                'MEMBERSHIP_TIER', 'POLL', 'ENCOUNTER_SPACE', 'EVENT', 'EVENT_IMPORT',
                                'COMMITTEE_MEMBERSHIP', 'ARTICLE_IMPORT', 'ARTICLE_BULK_APPROVED',
                                'ARTICLE_IMPORT_IMAGES_APPLIED', 'MAILING_LIST', 'MAILING_MESSAGE', 'COMMITTEE',
                                'TRAVEL_EXPENSE_SEPA_BATCH', 'CARPOOL_PARTICIPATION', 'VIDEO_OVERLAY', 'AGREEMENT_TEXT', 'PULPIT_RECORDING',
                                'EVENT_CAPTURE', 'TRAVEL_EXPENSE_DOCUMENT', 'TENDER', 'EVENT_REGISTRATION_EXPORT', 'ATTACHED_IMAGE',
                                'PARTG_REPORT_SHEET', 'PARTG_REPORT_POSITION', 'LUPABB_SYNC_RUN', 'LUPABB_SYNC_CONFIG', 'MEETING_EVENT_LINK',
                                'MEETING_SESSION', 'DIRECT_MESSAGE_GROUP_SEND', 'PARTNER_SERVER', 'PARTNER_BLOCKLIST',
                                'ACTIVISM_ACTION', 'ACTIVISM_CONTRIBUTION', 'ACTIVISM_REPORT', 'ACTIVISM_SETTINGS',
                                'PARTG_REPORT_EXPORT',
                                'PARTG_REPORT_ANNEX', 'PARTG_REPORT_NOTES',
                                'PARTG_ACCOUNT_MAPPING', 'PARTG_UNIT_MODE', 'REGIONAL_CHAPTER_TREASURER'
        ));
