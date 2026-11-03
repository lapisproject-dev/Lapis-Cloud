-- V1.9.269 "Rechenschaftsbericht (PartG), R3": separate statements A (deductions) and B (large donors), the member count E, the notes with
-- the holdings. See 76-partg-report.kuml.kts and docs/features/partg-report.adoc. V1..V138 untouched.
-- Purely additive and repeatable: CREATE TABLE IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT, no partial index, no ON
-- DELETE CASCADE, H2 MODE=PostgreSQL- and PostgreSQL-compatible. V121 and V136 are released (0.46.0 contains V136..V138) and are NOT edited.
-- While this wave is unreleased V139 may still be corrected in place on the feature branch; after the release every correction is a NEW migration.
--
--   * partg_report_annex: one header per (fiscal year, unit) for the deductions (A) and the member count (E). Carries the revision (optimistic
--     locking) and is the audit entity PARTG_REPORT_ANNEX. A NULL figure means "not entered", 0 means "entered as zero".
--   * partg_report_large_donor: statement B, persons with MORE than 10,000.00 EUR in a year to ONE unit. chk_partg_donor_amount keeps the data
--     minimisation in the database as well. At most 500 rows per annex: not expressible without a trigger, the service checks it under the lock
--     of the annex row (PartgAnnexWriter).
--   * partg_report_notes_sheet / partg_report_note / partg_report_holding: the notes of the organisation per year (five fixed blocks, the
--     holdings as structured rows, at most 50, checked by the service).
--
-- NOT expressible in the ERM profile (SQL only): every CHECK below (year, unit consistency, revision, amount ranges, enum literals, the rule
-- that the two relief fields exist only in the RELIEF_28 block) and the row limits.
-- DSGVO: partg_report_large_donor holds PERSONAL DATA OF THIRD PARTIES (name, address, yearly sum); it has no member foreign key and is
-- registered in PersonalDataRegistry.knownUncoveredSubjectRoots. updated_by / created_by of all tables are "changed by you" data.
--
-- audit_log_entry.entity_type: PARTG_REPORT_ANNEX and PARTG_REPORT_NOTES (18 chars each) fit VARCHAR(29). The list is V136's, copied verbatim,
-- plus the two new literals.

CREATE TABLE IF NOT EXISTS partg_report_annex (
    id                              UUID         NOT NULL PRIMARY KEY,
    fiscal_year                     INTEGER      NOT NULL,
    unit_level                      VARCHAR(11)  NOT NULL,
    regional_chapter_id             UUID         NULL REFERENCES regional_chapter (id),
    unit_key                        VARCHAR(48)  NOT NULL,
    revision                        BIGINT       NOT NULL DEFAULT 0,
    deduction_unassignable_cents    BIGINT       NULL,
    deduction_cash_over_1000_cents  BIGINT       NULL,
    deduction_over_3300_cents       BIGINT       NULL,
    deduction_wrongly_reported_cents BIGINT NULL,
    member_count                    INTEGER      NULL,
    updated_at                      TIMESTAMP    NOT NULL,
    updated_by                      UUID         NOT NULL REFERENCES member (id)
);

ALTER TABLE partg_report_annex DROP CONSTRAINT IF EXISTS chk_partg_annex_year;
ALTER TABLE partg_report_annex ADD CONSTRAINT chk_partg_annex_year CHECK (fiscal_year BETWEEN 2000 AND 2100);

ALTER TABLE partg_report_annex DROP CONSTRAINT IF EXISTS chk_partg_annex_unit_level;
ALTER TABLE partg_report_annex ADD CONSTRAINT chk_partg_annex_unit_level CHECK (unit_level IN ('FEDERAL', 'STATE', 'SUBORDINATE'));

ALTER TABLE partg_report_annex DROP CONSTRAINT IF EXISTS chk_partg_annex_unit_consistency;
ALTER TABLE partg_report_annex ADD CONSTRAINT chk_partg_annex_unit_consistency CHECK (
    (unit_level = 'FEDERAL' AND regional_chapter_id IS NULL AND unit_key = 'FEDERAL')
    OR (unit_level = 'STATE' AND regional_chapter_id IS NOT NULL AND unit_key = 'STATE:' || CAST(regional_chapter_id AS VARCHAR(36)))
    OR (unit_level = 'SUBORDINATE' AND regional_chapter_id IS NOT NULL AND unit_key = 'SUB:' || CAST(regional_chapter_id AS VARCHAR(36)))
);

ALTER TABLE partg_report_annex DROP CONSTRAINT IF EXISTS chk_partg_annex_revision;
ALTER TABLE partg_report_annex ADD CONSTRAINT chk_partg_annex_revision CHECK (revision >= 0);

ALTER TABLE partg_report_annex DROP CONSTRAINT IF EXISTS chk_partg_annex_deduction_unassignable;
ALTER TABLE partg_report_annex ADD CONSTRAINT chk_partg_annex_deduction_unassignable
    CHECK (deduction_unassignable_cents IS NULL OR deduction_unassignable_cents BETWEEN 0 AND 99999999999999);
ALTER TABLE partg_report_annex DROP CONSTRAINT IF EXISTS chk_partg_annex_deduction_cash;
ALTER TABLE partg_report_annex ADD CONSTRAINT chk_partg_annex_deduction_cash
    CHECK (deduction_cash_over_1000_cents IS NULL OR deduction_cash_over_1000_cents BETWEEN 0 AND 99999999999999);
ALTER TABLE partg_report_annex DROP CONSTRAINT IF EXISTS chk_partg_annex_deduction_over_3300;
ALTER TABLE partg_report_annex ADD CONSTRAINT chk_partg_annex_deduction_over_3300
    CHECK (deduction_over_3300_cents IS NULL OR deduction_over_3300_cents BETWEEN 0 AND 99999999999999);
ALTER TABLE partg_report_annex DROP CONSTRAINT IF EXISTS chk_partg_annex_deduction_wrong;
ALTER TABLE partg_report_annex ADD CONSTRAINT chk_partg_annex_deduction_wrong
    CHECK (deduction_wrongly_reported_cents IS NULL OR deduction_wrongly_reported_cents BETWEEN 0 AND 99999999999999);

ALTER TABLE partg_report_annex DROP CONSTRAINT IF EXISTS chk_partg_annex_member_count;
ALTER TABLE partg_report_annex ADD CONSTRAINT chk_partg_annex_member_count CHECK (member_count IS NULL OR member_count BETWEEN 0 AND 10000000);

CREATE UNIQUE INDEX IF NOT EXISTS uq_partg_report_annex_year_unit ON partg_report_annex (fiscal_year, unit_key);
CREATE INDEX IF NOT EXISTS ix_partg_report_annex_regional_chapter ON partg_report_annex (regional_chapter_id);
CREATE INDEX IF NOT EXISTS ix_partg_report_annex_updated_by ON partg_report_annex (updated_by);

CREATE TABLE IF NOT EXISTS partg_report_large_donor (
    id                 UUID          NOT NULL PRIMARY KEY,
    annex_id           UUID          NOT NULL REFERENCES partg_report_annex (id),
    person_kind        VARCHAR(7)    NOT NULL,
    name               VARCHAR(200)  NOT NULL,
    street             VARCHAR(200)  NULL,
    postal_code        VARCHAR(10)   NULL,
    city               VARCHAR(100)  NULL,
    country            VARCHAR(2)    NOT NULL DEFAULT 'DE',
    contribution_kind  VARCHAR(8)    NOT NULL,
    amount_cents       BIGINT        NOT NULL,
    created_at         TIMESTAMP     NOT NULL,
    created_by         UUID          NOT NULL REFERENCES member (id),
    updated_at         TIMESTAMP     NOT NULL,
    updated_by         UUID          NOT NULL REFERENCES member (id)
);

ALTER TABLE partg_report_large_donor DROP CONSTRAINT IF EXISTS chk_partg_donor_amount;
ALTER TABLE partg_report_large_donor ADD CONSTRAINT chk_partg_donor_amount CHECK (amount_cents > 1000000 AND amount_cents <= 99999999999999);
ALTER TABLE partg_report_large_donor DROP CONSTRAINT IF EXISTS chk_partg_donor_person_kind;
ALTER TABLE partg_report_large_donor ADD CONSTRAINT chk_partg_donor_person_kind CHECK (person_kind IN ('NATURAL', 'LEGAL'));
ALTER TABLE partg_report_large_donor DROP CONSTRAINT IF EXISTS chk_partg_donor_contribution_kind;
ALTER TABLE partg_report_large_donor ADD CONSTRAINT chk_partg_donor_contribution_kind CHECK (contribution_kind IN ('DONATION', 'FEE', 'BOTH'));
ALTER TABLE partg_report_large_donor DROP CONSTRAINT IF EXISTS chk_partg_donor_name_len;
ALTER TABLE partg_report_large_donor ADD CONSTRAINT chk_partg_donor_name_len CHECK (CHAR_LENGTH(name) BETWEEN 1 AND 200);
ALTER TABLE partg_report_large_donor DROP CONSTRAINT IF EXISTS chk_partg_donor_country;
ALTER TABLE partg_report_large_donor ADD CONSTRAINT chk_partg_donor_country CHECK (CHAR_LENGTH(country) = 2);

CREATE INDEX IF NOT EXISTS ix_partg_report_large_donor_annex ON partg_report_large_donor (annex_id);
CREATE INDEX IF NOT EXISTS ix_partg_report_large_donor_created_by ON partg_report_large_donor (created_by);
CREATE INDEX IF NOT EXISTS ix_partg_report_large_donor_updated_by ON partg_report_large_donor (updated_by);

CREATE TABLE IF NOT EXISTS partg_report_notes_sheet (
    id          UUID       NOT NULL PRIMARY KEY,
    fiscal_year INTEGER    NOT NULL,
    revision    BIGINT     NOT NULL DEFAULT 0,
    updated_at  TIMESTAMP  NOT NULL,
    updated_by  UUID       NOT NULL REFERENCES member (id)
);

ALTER TABLE partg_report_notes_sheet DROP CONSTRAINT IF EXISTS chk_partg_notes_sheet_year;
ALTER TABLE partg_report_notes_sheet ADD CONSTRAINT chk_partg_notes_sheet_year CHECK (fiscal_year BETWEEN 2000 AND 2100);
ALTER TABLE partg_report_notes_sheet DROP CONSTRAINT IF EXISTS chk_partg_notes_sheet_revision;
ALTER TABLE partg_report_notes_sheet ADD CONSTRAINT chk_partg_notes_sheet_revision CHECK (revision >= 0);

CREATE UNIQUE INDEX IF NOT EXISTS uq_partg_report_notes_sheet_year ON partg_report_notes_sheet (fiscal_year);
CREATE INDEX IF NOT EXISTS ix_partg_report_notes_sheet_updated_by ON partg_report_notes_sheet (updated_by);

CREATE TABLE IF NOT EXISTS partg_report_note (
    id             UUID            NOT NULL PRIMARY KEY,
    notes_sheet_id UUID            NOT NULL REFERENCES partg_report_notes_sheet (id),
    block_code     VARCHAR(21)     NOT NULL,
    included       BOOLEAN         NOT NULL DEFAULT FALSE,
    body           VARCHAR(4000)   NULL,
    relief_abs1    VARCHAR(11)     NULL,
    relief_abs3    VARCHAR(11)     NULL,
    updated_at     TIMESTAMP       NOT NULL,
    updated_by     UUID            NOT NULL REFERENCES member (id)
);

ALTER TABLE partg_report_note DROP CONSTRAINT IF EXISTS chk_partg_note_block;
ALTER TABLE partg_report_note ADD CONSTRAINT chk_partg_note_block
    CHECK (block_code IN ('GENERAL_ACCOUNTING', 'RELIEF_28', 'HOLDINGS', 'MEDIA_PRODUCTS', 'REAL_ESTATE_VALUATION'));
ALTER TABLE partg_report_note DROP CONSTRAINT IF EXISTS chk_partg_note_relief_abs1;
ALTER TABLE partg_report_note ADD CONSTRAINT chk_partg_note_relief_abs1 CHECK (relief_abs1 IS NULL OR relief_abs1 IN ('YES', 'NO', 'UNSPECIFIED'));
ALTER TABLE partg_report_note DROP CONSTRAINT IF EXISTS chk_partg_note_relief_abs3;
ALTER TABLE partg_report_note ADD CONSTRAINT chk_partg_note_relief_abs3 CHECK (relief_abs3 IS NULL OR relief_abs3 IN ('YES', 'NO', 'UNSPECIFIED'));
ALTER TABLE partg_report_note DROP CONSTRAINT IF EXISTS chk_partg_note_relief_only_block;
ALTER TABLE partg_report_note ADD CONSTRAINT chk_partg_note_relief_only_block
    CHECK (block_code = 'RELIEF_28' OR (relief_abs1 IS NULL AND relief_abs3 IS NULL));

CREATE UNIQUE INDEX IF NOT EXISTS uq_partg_report_note_sheet_block ON partg_report_note (notes_sheet_id, block_code);
CREATE INDEX IF NOT EXISTS ix_partg_report_note_updated_by ON partg_report_note (updated_by);

CREATE TABLE IF NOT EXISTS partg_report_holding (
    id                 UUID          NOT NULL PRIMARY KEY,
    notes_sheet_id     UUID          NOT NULL REFERENCES partg_report_notes_sheet (id),
    sort_order         INTEGER       NOT NULL,
    name               VARCHAR(200)  NOT NULL,
    seat               VARCHAR(100)  NULL,
    share_basis_points INTEGER       NULL,
    capital_cents      BIGINT        NULL,
    result_cents       BIGINT        NULL,
    updated_at         TIMESTAMP     NOT NULL,
    updated_by         UUID          NOT NULL REFERENCES member (id)
);

ALTER TABLE partg_report_holding DROP CONSTRAINT IF EXISTS chk_partg_holding_name_len;
ALTER TABLE partg_report_holding ADD CONSTRAINT chk_partg_holding_name_len CHECK (CHAR_LENGTH(name) BETWEEN 1 AND 200);
ALTER TABLE partg_report_holding DROP CONSTRAINT IF EXISTS chk_partg_holding_share;
ALTER TABLE partg_report_holding ADD CONSTRAINT chk_partg_holding_share
    CHECK (share_basis_points IS NULL OR share_basis_points BETWEEN 0 AND 10000);
ALTER TABLE partg_report_holding DROP CONSTRAINT IF EXISTS chk_partg_holding_capital;
ALTER TABLE partg_report_holding ADD CONSTRAINT chk_partg_holding_capital
    CHECK (capital_cents IS NULL OR capital_cents BETWEEN 0 AND 99999999999999);
ALTER TABLE partg_report_holding DROP CONSTRAINT IF EXISTS chk_partg_holding_result;
ALTER TABLE partg_report_holding ADD CONSTRAINT chk_partg_holding_result
    CHECK (result_cents IS NULL OR result_cents BETWEEN -99999999999999 AND 99999999999999);

CREATE UNIQUE INDEX IF NOT EXISTS uq_partg_report_holding_sheet_order ON partg_report_holding (notes_sheet_id, sort_order);
CREATE INDEX IF NOT EXISTS ix_partg_report_holding_updated_by ON partg_report_holding (updated_by);

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
                                'PARTG_REPORT_ANNEX', 'PARTG_REPORT_NOTES'
        ));
