-- V1.9.290 "Rechenschaftsbericht (PartG), R5": consolidation of the whole party over its units -- the intra-party transfers (matching table)
-- and the manual combination of large-donor rows of several units to one person. See 76-partg-report.kuml.kts and
-- docs/features/partg-report.adoc. V1..V144 untouched (V144 is already on master, so R5 gets its own migration).
-- Purely additive and repeatable: IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT, no partial index, no ON DELETE
-- CASCADE (the writers delete child rows explicitly), H2 MODE=PostgreSQL- and PostgreSQL-compatible, NO backfill. While this wave is
-- unreleased V145 may still be corrected in place on the feature branch; after the release every correction is a NEW migration.
--
--   * partg_consolidation_sheet: one header per year (lock + revision fence, same pattern as partg_report_notes_sheet). The installation has
--     one organisation, so there is no organization_id (like every other partg_* table).
--   * partg_intra_transfer: one pair (year, kind, payer unit, payee unit) = the yearly sum of ONE relationship. SUBSIDY = payer's A03 against
--     payee's E10; BALANCE = payer's BP05 (claim) against payee's SP04 (liability). Each side's amount is optional; a pair with only one side
--     is shown as "not matched" and is NOT eliminated. Amounts: 1..99999999999999 cents, like partg_report_value.
--   * partg_donor_combination / _row: a manual group of large-donor rows (statement B) of DIFFERENT units that are one person. Stores ids
--     only, never a name or an address. One row belongs to at most one group (unique large_donor_id).
--
-- NOT expressible in SQL (checked in the service, in the shared PartgConsolidationRules): the year of the donor rows equals the year of the
-- header, the rows of one group come from different units, 2..20 rows per group, at most 1000 pairs and 500 groups per year.
-- DSGVO: organisation data; created_by / updated_by are "changed by you" data. The combination rows point to personal data of THIRD PARTIES
-- (via partg_report_large_donor) but hold none themselves. The free-text note of a pair may contain personal data: never logged, never audited.

CREATE TABLE IF NOT EXISTS partg_consolidation_sheet (
    id           UUID       NOT NULL PRIMARY KEY,
    fiscal_year  INTEGER    NOT NULL,
    revision     BIGINT     NOT NULL DEFAULT 0,
    updated_at   TIMESTAMP  NOT NULL,
    updated_by   UUID       NOT NULL REFERENCES member (id)
);

ALTER TABLE partg_consolidation_sheet DROP CONSTRAINT IF EXISTS chk_partg_consolidation_sheet_year;
ALTER TABLE partg_consolidation_sheet ADD CONSTRAINT chk_partg_consolidation_sheet_year CHECK (fiscal_year BETWEEN 2000 AND 2100);
ALTER TABLE partg_consolidation_sheet DROP CONSTRAINT IF EXISTS chk_partg_consolidation_sheet_revision;
ALTER TABLE partg_consolidation_sheet ADD CONSTRAINT chk_partg_consolidation_sheet_revision CHECK (revision >= 0);

CREATE UNIQUE INDEX IF NOT EXISTS uq_partg_consolidation_sheet_year ON partg_consolidation_sheet (fiscal_year);
CREATE INDEX IF NOT EXISTS ix_partg_consolidation_sheet_updated_by ON partg_consolidation_sheet (updated_by);

CREATE TABLE IF NOT EXISTS partg_intra_transfer (
    id                    UUID          NOT NULL PRIMARY KEY,
    consolidation_id      UUID          NOT NULL REFERENCES partg_consolidation_sheet (id),
    transfer_kind         VARCHAR(7)    NOT NULL,
    payer_level           VARCHAR(11)   NOT NULL,
    payer_chapter_id      UUID          NULL REFERENCES regional_chapter (id),
    payer_unit_key        VARCHAR(48)   NOT NULL,
    payee_level           VARCHAR(11)   NOT NULL,
    payee_chapter_id      UUID          NULL REFERENCES regional_chapter (id),
    payee_unit_key        VARCHAR(48)   NOT NULL,
    payer_amount_cents    BIGINT        NULL,
    payee_amount_cents    BIGINT        NULL,
    note                  VARCHAR(200)  NULL,
    created_at            TIMESTAMP     NOT NULL,
    created_by            UUID          NOT NULL REFERENCES member (id),
    updated_at            TIMESTAMP     NOT NULL,
    updated_by            UUID          NOT NULL REFERENCES member (id)
);

ALTER TABLE partg_intra_transfer DROP CONSTRAINT IF EXISTS chk_partg_intra_transfer_kind;
ALTER TABLE partg_intra_transfer ADD CONSTRAINT chk_partg_intra_transfer_kind CHECK (transfer_kind IN ('SUBSIDY', 'BALANCE'));
ALTER TABLE partg_intra_transfer DROP CONSTRAINT IF EXISTS chk_partg_intra_transfer_payer_unit;
ALTER TABLE partg_intra_transfer ADD CONSTRAINT chk_partg_intra_transfer_payer_unit CHECK (
    (payer_level = 'FEDERAL' AND payer_chapter_id IS NULL AND payer_unit_key = 'FEDERAL')
    OR (payer_level = 'STATE' AND payer_chapter_id IS NOT NULL AND payer_unit_key = 'STATE:' || CAST(payer_chapter_id AS VARCHAR(36)))
    OR (payer_level = 'SUBORDINATE' AND payer_chapter_id IS NOT NULL AND payer_unit_key = 'SUB:' || CAST(payer_chapter_id AS VARCHAR(36)))
);
ALTER TABLE partg_intra_transfer DROP CONSTRAINT IF EXISTS chk_partg_intra_transfer_payee_unit;
ALTER TABLE partg_intra_transfer ADD CONSTRAINT chk_partg_intra_transfer_payee_unit CHECK (
    (payee_level = 'FEDERAL' AND payee_chapter_id IS NULL AND payee_unit_key = 'FEDERAL')
    OR (payee_level = 'STATE' AND payee_chapter_id IS NOT NULL AND payee_unit_key = 'STATE:' || CAST(payee_chapter_id AS VARCHAR(36)))
    OR (payee_level = 'SUBORDINATE' AND payee_chapter_id IS NOT NULL AND payee_unit_key = 'SUB:' || CAST(payee_chapter_id AS VARCHAR(36)))
);
ALTER TABLE partg_intra_transfer DROP CONSTRAINT IF EXISTS chk_partg_intra_transfer_distinct;
ALTER TABLE partg_intra_transfer ADD CONSTRAINT chk_partg_intra_transfer_distinct CHECK (payer_unit_key <> payee_unit_key);
ALTER TABLE partg_intra_transfer DROP CONSTRAINT IF EXISTS chk_partg_intra_transfer_payer_amount;
ALTER TABLE partg_intra_transfer ADD CONSTRAINT chk_partg_intra_transfer_payer_amount CHECK (
    payer_amount_cents IS NULL OR payer_amount_cents BETWEEN 1 AND 99999999999999
);
ALTER TABLE partg_intra_transfer DROP CONSTRAINT IF EXISTS chk_partg_intra_transfer_payee_amount;
ALTER TABLE partg_intra_transfer ADD CONSTRAINT chk_partg_intra_transfer_payee_amount CHECK (
    payee_amount_cents IS NULL OR payee_amount_cents BETWEEN 1 AND 99999999999999
);
ALTER TABLE partg_intra_transfer DROP CONSTRAINT IF EXISTS chk_partg_intra_transfer_any_amount;
ALTER TABLE partg_intra_transfer ADD CONSTRAINT chk_partg_intra_transfer_any_amount CHECK (
    payer_amount_cents IS NOT NULL OR payee_amount_cents IS NOT NULL
);
ALTER TABLE partg_intra_transfer DROP CONSTRAINT IF EXISTS chk_partg_intra_transfer_note;
ALTER TABLE partg_intra_transfer ADD CONSTRAINT chk_partg_intra_transfer_note CHECK (
    note IS NULL OR CHAR_LENGTH(note) BETWEEN 1 AND 200
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_partg_intra_transfer_pair
    ON partg_intra_transfer (consolidation_id, transfer_kind, payer_unit_key, payee_unit_key);
CREATE INDEX IF NOT EXISTS ix_partg_intra_transfer_consolidation ON partg_intra_transfer (consolidation_id);
CREATE INDEX IF NOT EXISTS ix_partg_intra_transfer_payer_chapter ON partg_intra_transfer (payer_chapter_id);
CREATE INDEX IF NOT EXISTS ix_partg_intra_transfer_payee_chapter ON partg_intra_transfer (payee_chapter_id);
CREATE INDEX IF NOT EXISTS ix_partg_intra_transfer_created_by ON partg_intra_transfer (created_by);
CREATE INDEX IF NOT EXISTS ix_partg_intra_transfer_updated_by ON partg_intra_transfer (updated_by);

CREATE TABLE IF NOT EXISTS partg_donor_combination (
    id                UUID       NOT NULL PRIMARY KEY,
    consolidation_id  UUID       NOT NULL REFERENCES partg_consolidation_sheet (id),
    created_at        TIMESTAMP  NOT NULL,
    created_by        UUID       NOT NULL REFERENCES member (id)
);

CREATE INDEX IF NOT EXISTS ix_partg_donor_combination_consolidation ON partg_donor_combination (consolidation_id);
CREATE INDEX IF NOT EXISTS ix_partg_donor_combination_created_by ON partg_donor_combination (created_by);

CREATE TABLE IF NOT EXISTS partg_donor_combination_row (
    id                UUID  NOT NULL PRIMARY KEY,
    combination_id    UUID  NOT NULL REFERENCES partg_donor_combination (id),
    large_donor_id    UUID  NOT NULL REFERENCES partg_report_large_donor (id),
    CONSTRAINT uq_partg_donor_combination_row_donor UNIQUE (large_donor_id)
);

CREATE INDEX IF NOT EXISTS ix_partg_donor_combination_row_combination ON partg_donor_combination_row (combination_id);

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
                                'PARTG_INTRA_TRANSFER', 'PARTG_DONOR_COMBINATION'
        ));
