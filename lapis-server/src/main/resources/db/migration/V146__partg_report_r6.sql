-- V1.9.291 "Rechenschaftsbericht (PartG), R6": kept draft versions of the report, the Finanzvorstand's assurance, the internal "final" mark and the
-- submission deadline. See 76-partg-report.kuml.kts and docs/features/partg-report.adoc. V1..V145 untouched (V145 is already on master, so R6 gets
-- its own migration; changing V145 in place would break its Flyway checksum on every database that already ran it).
-- Purely additive and repeatable: IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT, no partial index, no ON DELETE CASCADE
-- (the deletion routine removes the declaration before the version), H2 MODE=PostgreSQL- and PostgreSQL-compatible, NO backfill. While this wave is
-- unreleased V146 may still be corrected in place on the feature branch; after the release every correction is a NEW migration.
--
--   * partg_report_version_counter: the lock row and the number source per (year, scope). The row is NEVER deleted, so a version number is never
--     reused, not even after the deletion routine removed old versions.
--   * partg_report_version: ONE kept version. content_json is the computed report document (canonical JSON), content_hash its SHA-256 (hex, lower
--     case). The content columns are never updated; the ONLY column that ever changes is the pair finalized_at/finalized_by (set once).
--     The content holds the names and addresses of statements B and C (PERSONAL DATA OF THIRD PARTIES): has_third_party_data says so.
--   * partg_report_declaration: the assurance of the Finanzvorstand on one version (once per version): the template code and the checksum seen.
--   * partg_report_deadline: the confirmed submission deadline of a report year. A missing row means "proposal, not confirmed".
--
-- NOT expressible in SQL (checked in the service / shared PartgVersionRules): at most 200 versions per (year, scope), the scope key syntax
-- (ALL or a unit key), the size cap (also as a CHECK below), whole-party-only declaration and finalisation, the deadline range check relative to the
-- year (also as a CHECK below), the content being valid JSON of the known format.
-- DSGVO: counter, declaration and deadline are organisation data; frozen_by/finalized_by/declared_by/confirmed_by/updated_by are "changed by you"
-- data. partg_report_version is registered as personal data of third parties (copy of statements B and C), see PersonalDataRegistry.

CREATE TABLE IF NOT EXISTS partg_report_version_counter (
    id           UUID         NOT NULL PRIMARY KEY,
    fiscal_year  INTEGER      NOT NULL,
    scope_key    VARCHAR(48)  NOT NULL,
    last_number  INTEGER      NOT NULL DEFAULT 0,
    updated_at   TIMESTAMP    NOT NULL,
    updated_by   UUID         NOT NULL REFERENCES member (id)
);

ALTER TABLE partg_report_version_counter DROP CONSTRAINT IF EXISTS chk_partg_report_version_counter_year;
ALTER TABLE partg_report_version_counter ADD CONSTRAINT chk_partg_report_version_counter_year CHECK (fiscal_year BETWEEN 2000 AND 2100);
ALTER TABLE partg_report_version_counter DROP CONSTRAINT IF EXISTS chk_partg_report_version_counter_number;
ALTER TABLE partg_report_version_counter ADD CONSTRAINT chk_partg_report_version_counter_number CHECK (last_number >= 0);

CREATE UNIQUE INDEX IF NOT EXISTS uq_partg_report_version_counter_scope ON partg_report_version_counter (fiscal_year, scope_key);
CREATE INDEX IF NOT EXISTS ix_partg_report_version_counter_updated_by ON partg_report_version_counter (updated_by);

CREATE TABLE IF NOT EXISTS partg_report_version (
    id                    UUID          NOT NULL PRIMARY KEY,
    counter_id            UUID          NOT NULL REFERENCES partg_report_version_counter (id),
    fiscal_year           INTEGER       NOT NULL,
    scope_key             VARCHAR(48)   NOT NULL,
    version_number        INTEGER       NOT NULL,
    format_version        INTEGER       NOT NULL,
    content_json          TEXT          NOT NULL,
    content_hash          VARCHAR(64)   NOT NULL,
    byte_size             INTEGER       NOT NULL,
    has_third_party_data  BOOLEAN       NOT NULL,
    comment               VARCHAR(500)  NULL,
    frozen_at             TIMESTAMP     NOT NULL,
    frozen_by             UUID          NOT NULL REFERENCES member (id),
    finalized_at          TIMESTAMP     NULL,
    finalized_by          UUID          NULL REFERENCES member (id)
);

ALTER TABLE partg_report_version DROP CONSTRAINT IF EXISTS chk_partg_report_version_year;
ALTER TABLE partg_report_version ADD CONSTRAINT chk_partg_report_version_year CHECK (fiscal_year BETWEEN 2000 AND 2100);
ALTER TABLE partg_report_version DROP CONSTRAINT IF EXISTS chk_partg_report_version_number;
ALTER TABLE partg_report_version ADD CONSTRAINT chk_partg_report_version_number CHECK (version_number >= 1);
ALTER TABLE partg_report_version DROP CONSTRAINT IF EXISTS chk_partg_report_version_format;
ALTER TABLE partg_report_version ADD CONSTRAINT chk_partg_report_version_format CHECK (format_version >= 1);
ALTER TABLE partg_report_version DROP CONSTRAINT IF EXISTS chk_partg_report_version_hash;
ALTER TABLE partg_report_version ADD CONSTRAINT chk_partg_report_version_hash CHECK (CHAR_LENGTH(content_hash) = 64);
ALTER TABLE partg_report_version DROP CONSTRAINT IF EXISTS chk_partg_report_version_size;
ALTER TABLE partg_report_version ADD CONSTRAINT chk_partg_report_version_size CHECK (byte_size BETWEEN 1 AND 4194304);
ALTER TABLE partg_report_version DROP CONSTRAINT IF EXISTS chk_partg_report_version_comment;
ALTER TABLE partg_report_version ADD CONSTRAINT chk_partg_report_version_comment CHECK (
    comment IS NULL OR CHAR_LENGTH(comment) BETWEEN 1 AND 500
);
ALTER TABLE partg_report_version DROP CONSTRAINT IF EXISTS chk_partg_report_version_final_pair;
ALTER TABLE partg_report_version ADD CONSTRAINT chk_partg_report_version_final_pair CHECK (
    (finalized_at IS NULL AND finalized_by IS NULL) OR (finalized_at IS NOT NULL AND finalized_by IS NOT NULL)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_partg_report_version_number ON partg_report_version (fiscal_year, scope_key, version_number);
CREATE INDEX IF NOT EXISTS ix_partg_report_version_counter ON partg_report_version (counter_id);
CREATE INDEX IF NOT EXISTS ix_partg_report_version_frozen_by ON partg_report_version (frozen_by);
CREATE INDEX IF NOT EXISTS ix_partg_report_version_finalized_by ON partg_report_version (finalized_by);

CREATE TABLE IF NOT EXISTS partg_report_declaration (
    id             UUID         NOT NULL PRIMARY KEY,
    version_id     UUID         NOT NULL REFERENCES partg_report_version (id),
    template_code  VARCHAR(32)  NOT NULL,
    content_hash   VARCHAR(64)  NOT NULL,
    declared_at    TIMESTAMP    NOT NULL,
    declared_by    UUID         NOT NULL REFERENCES member (id)
);

ALTER TABLE partg_report_declaration DROP CONSTRAINT IF EXISTS chk_partg_report_declaration_hash;
ALTER TABLE partg_report_declaration ADD CONSTRAINT chk_partg_report_declaration_hash CHECK (CHAR_LENGTH(content_hash) = 64);

CREATE UNIQUE INDEX IF NOT EXISTS uq_partg_report_declaration_version ON partg_report_declaration (version_id);
CREATE INDEX IF NOT EXISTS ix_partg_report_declaration_declared_by ON partg_report_declaration (declared_by);

CREATE TABLE IF NOT EXISTS partg_report_deadline (
    id            UUID       NOT NULL PRIMARY KEY,
    fiscal_year   INTEGER    NOT NULL,
    deadline      DATE       NOT NULL,
    confirmed_at  TIMESTAMP  NOT NULL,
    confirmed_by  UUID       NOT NULL REFERENCES member (id)
);

ALTER TABLE partg_report_deadline DROP CONSTRAINT IF EXISTS chk_partg_report_deadline_year;
ALTER TABLE partg_report_deadline ADD CONSTRAINT chk_partg_report_deadline_year CHECK (fiscal_year BETWEEN 2000 AND 2100);
ALTER TABLE partg_report_deadline DROP CONSTRAINT IF EXISTS chk_partg_report_deadline_range;
ALTER TABLE partg_report_deadline ADD CONSTRAINT chk_partg_report_deadline_range CHECK (
    CAST(EXTRACT(YEAR FROM deadline) AS INTEGER) BETWEEN fiscal_year + 1 AND fiscal_year + 3
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_partg_report_deadline_year ON partg_report_deadline (fiscal_year);
CREATE INDEX IF NOT EXISTS ix_partg_report_deadline_confirmed_by ON partg_report_deadline (confirmed_by);

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
                                'PARTG_ACCOUNT_MAPPING', 'PARTG_UNIT_MODE', 'REGIONAL_CHAPTER_TREASURER',
                                'PARTG_INTRA_TRANSFER', 'PARTG_DONOR_COMBINATION',
                                'PARTG_REPORT_VERSION', 'PARTG_REPORT_DECLARATION', 'PARTG_REPORT_DEADLINE'
        ));
