-- V1.9.225 "Rechenschaftsbericht (PartG), R1": position catalogue, report sheets and values. See 76-partg-report.kuml.kts and
-- docs/features/partg-report.adoc.
-- Purely additive and repeatable: CREATE TABLE IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT, no partial index, no ON
-- DELETE CASCADE, H2 MODE=PostgreSQL- and PostgreSQL-compatible. V120 is released (0.41.0) and migrated on every instance, so it is NOT edited.
-- While this wave is unreleased V121 may still be corrected in place on the feature branch; after the release every correction is a NEW migration.
--
--   * partg_report_position: the legally fixed catalogue (section 24 PartG), seeded below with fixed UUIDs. 35 LEAF positions are typed in,
--     7 SUM positions are always computed (never stored). The id exists for the audit log (AuditLogRecorder needs a UUID entity id); the code
--     is the natural key. org_hint is the organisation's own hint (<= 500 characters), the only mutable column.
--   * partg_report_sheet: one header per (fiscal year, unit). Carries the revision (optimistic locking) and is the audit entity. unit_key is
--     'FEDERAL', 'STATE:<chapter uuid>' or 'SUB:<chapter uuid>'; chk_partg_sheet_unit_consistency keeps it equal to unit_level and
--     regional_chapter_id. The UUID text is lower case on H2 and PostgreSQL (pinned by PartgReportMigrationTest on both lanes).
--   * partg_report_value: one typed-in or imported amount (cents, >= 0) per sheet and LEAF position. A missing row means "not entered", a row
--     with 0 means "entered as zero". That a value may point only to a LEAF position cannot be expressed in SQL without a trigger; the service
--     enforces it and PartgReportSaveScenarios pins it (same approach as V59).
--   * audit_log_entry.entity_type: widened by PARTG_REPORT_SHEET and PARTG_REPORT_POSITION (VARCHAR(29) is unchanged). The list is V119's.

CREATE TABLE IF NOT EXISTS partg_report_position (
    id                  UUID          NOT NULL PRIMARY KEY,
    code                VARCHAR(16)   NOT NULL,
    section             VARCHAR(9)    NOT NULL,
    kind                VARCHAR(4)    NOT NULL,
    parent_code         VARCHAR(16)   NULL,
    sort_order          INTEGER       NOT NULL,
    number_label        VARCHAR(16)   NOT NULL,
    label_de            VARCHAR(200)  NOT NULL,
    legal_ref           VARCHAR(80)   NOT NULL,
    org_hint            VARCHAR(500)  NULL,
    org_hint_updated_at TIMESTAMP     NULL,
    org_hint_updated_by UUID          NULL REFERENCES member (id)
);

-- a UNIQUE constraint, not a unique index: H2 accepts only a constraint as the target of the foreign keys on the natural key `code`
ALTER TABLE partg_report_position DROP CONSTRAINT IF EXISTS uq_partg_report_position_code;
ALTER TABLE partg_report_position ADD CONSTRAINT uq_partg_report_position_code UNIQUE (code);
CREATE UNIQUE INDEX IF NOT EXISTS uq_partg_report_position_sort_order ON partg_report_position (sort_order);

ALTER TABLE partg_report_position DROP CONSTRAINT IF EXISTS chk_partg_position_section;
ALTER TABLE partg_report_position ADD CONSTRAINT chk_partg_position_section CHECK (section IN ('INCOME', 'EXPENSE', 'ASSET', 'LIABILITY'));

ALTER TABLE partg_report_position DROP CONSTRAINT IF EXISTS chk_partg_position_kind;
ALTER TABLE partg_report_position ADD CONSTRAINT chk_partg_position_kind CHECK (kind IN ('LEAF', 'SUM'));

ALTER TABLE partg_report_position DROP CONSTRAINT IF EXISTS fk_partg_position_parent;
ALTER TABLE partg_report_position ADD CONSTRAINT fk_partg_position_parent FOREIGN KEY (parent_code) REFERENCES partg_report_position (code);

CREATE INDEX IF NOT EXISTS ix_partg_report_position_org_hint_updated_by ON partg_report_position (org_hint_updated_by);

CREATE TABLE IF NOT EXISTS partg_report_sheet (
    id                  UUID         NOT NULL PRIMARY KEY,
    fiscal_year         INTEGER      NOT NULL,
    unit_level          VARCHAR(11)  NOT NULL,
    regional_chapter_id UUID         NULL REFERENCES regional_chapter (id),
    unit_key            VARCHAR(48)  NOT NULL,
    revision            BIGINT       NOT NULL DEFAULT 0,
    updated_at          TIMESTAMP    NOT NULL,
    updated_by          UUID         NOT NULL REFERENCES member (id)
);

ALTER TABLE partg_report_sheet DROP CONSTRAINT IF EXISTS chk_partg_sheet_year;
ALTER TABLE partg_report_sheet ADD CONSTRAINT chk_partg_sheet_year CHECK (fiscal_year BETWEEN 2000 AND 2100);

ALTER TABLE partg_report_sheet DROP CONSTRAINT IF EXISTS chk_partg_sheet_unit_level;
ALTER TABLE partg_report_sheet ADD CONSTRAINT chk_partg_sheet_unit_level CHECK (unit_level IN ('FEDERAL', 'STATE', 'SUBORDINATE'));

ALTER TABLE partg_report_sheet DROP CONSTRAINT IF EXISTS chk_partg_sheet_revision;
ALTER TABLE partg_report_sheet ADD CONSTRAINT chk_partg_sheet_revision CHECK (revision >= 0);

ALTER TABLE partg_report_sheet DROP CONSTRAINT IF EXISTS chk_partg_sheet_unit_consistency;
ALTER TABLE partg_report_sheet ADD CONSTRAINT chk_partg_sheet_unit_consistency CHECK (
    (unit_level = 'FEDERAL' AND regional_chapter_id IS NULL AND unit_key = 'FEDERAL')
    OR (unit_level = 'STATE' AND regional_chapter_id IS NOT NULL AND unit_key = 'STATE:' || CAST(regional_chapter_id AS VARCHAR(36)))
    OR (unit_level = 'SUBORDINATE' AND regional_chapter_id IS NOT NULL AND unit_key = 'SUB:' || CAST(regional_chapter_id AS VARCHAR(36)))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_partg_report_sheet_year_unit ON partg_report_sheet (fiscal_year, unit_key);
CREATE INDEX IF NOT EXISTS ix_partg_report_sheet_regional_chapter ON partg_report_sheet (regional_chapter_id);
CREATE INDEX IF NOT EXISTS ix_partg_report_sheet_updated_by ON partg_report_sheet (updated_by);

CREATE TABLE IF NOT EXISTS partg_report_value (
    id            UUID        NOT NULL PRIMARY KEY,
    sheet_id      UUID        NOT NULL REFERENCES partg_report_sheet (id),
    position_code VARCHAR(16) NOT NULL REFERENCES partg_report_position (code),
    amount_cents  BIGINT      NOT NULL,
    value_source  VARCHAR(6)  NOT NULL,
    updated_at    TIMESTAMP   NOT NULL,
    updated_by    UUID        NOT NULL REFERENCES member (id)
);

ALTER TABLE partg_report_value DROP CONSTRAINT IF EXISTS chk_partg_value_amount;
ALTER TABLE partg_report_value ADD CONSTRAINT chk_partg_value_amount CHECK (amount_cents BETWEEN 0 AND 99999999999999);

ALTER TABLE partg_report_value DROP CONSTRAINT IF EXISTS chk_partg_value_source;
ALTER TABLE partg_report_value ADD CONSTRAINT chk_partg_value_source CHECK (value_source IN ('MANUAL', 'IMPORT'));

CREATE UNIQUE INDEX IF NOT EXISTS uq_partg_report_value_sheet_position ON partg_report_value (sheet_id, position_code);
CREATE INDEX IF NOT EXISTS ix_partg_report_value_sheet ON partg_report_value (sheet_id);
CREATE INDEX IF NOT EXISTS ix_partg_report_value_position ON partg_report_value (position_code);
CREATE INDEX IF NOT EXISTS ix_partg_report_value_updated_by ON partg_report_value (updated_by);

-- audit_log_entry.entity_type: PARTG_REPORT_SHEET (18 chars) and PARTG_REPORT_POSITION (21 chars) fit VARCHAR(29). The list is V119's, copied
-- verbatim, plus the two new literals.
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
                                'MEMBERSHIP_TIER', 'POLL', 'ENCOUNTER_SPACE', 'EVENT', 'EVENT_IMPORT',
                                'COMMITTEE_MEMBERSHIP', 'ARTICLE_IMPORT', 'ARTICLE_BULK_APPROVED',
                                'ARTICLE_IMPORT_IMAGES_APPLIED', 'MAILING_LIST', 'MAILING_MESSAGE', 'COMMITTEE',
                                'TRAVEL_EXPENSE_SEPA_BATCH', 'CARPOOL_PARTICIPATION', 'VIDEO_OVERLAY', 'AGREEMENT_TEXT', 'PULPIT_RECORDING',
                                'EVENT_CAPTURE', 'TRAVEL_EXPENSE_DOCUMENT', 'TENDER', 'EVENT_REGISTRATION_EXPORT', 'ATTACHED_IMAGE',
                                'PARTG_REPORT_SHEET', 'PARTG_REPORT_POSITION'
        ));

-- Seed: the 42 positions in the order of the official layout (fixed ids). parent_code is set in a second step because the self reference
-- needs the parent row to exist already (children are listed before their sum row).
INSERT INTO partg_report_position (id, code, section, kind, sort_order, number_label, label_de, legal_ref) VALUES
    ('7a6e0000-0000-4000-8000-000000000001', 'E01', 'INCOME', 'LEAF', 1, '1', 'Mitgliedsbeiträge und ähnliche regelmäßige Beiträge', '§ 24 Abs. 4 PartG'),
    ('7a6e0000-0000-4000-8000-000000000002', 'E02', 'INCOME', 'LEAF', 2, '2', 'Mandatsträgerbeiträge und ähnliche regelmäßige Beiträge', '§ 24 Abs. 4 PartG'),
    ('7a6e0000-0000-4000-8000-000000000003', 'E03', 'INCOME', 'LEAF', 3, '3', 'Spenden von natürlichen Personen', '§ 24 Abs. 4 PartG'),
    ('7a6e0000-0000-4000-8000-000000000004', 'E04', 'INCOME', 'LEAF', 4, '4', 'Spenden von juristischen Personen', '§ 24 Abs. 4 PartG'),
    ('7a6e0000-0000-4000-8000-000000000005', 'E05', 'INCOME', 'LEAF', 5, '5', 'Einnahmen aus Unternehmenstätigkeit', '§ 24 Abs. 4 PartG'),
    ('7a6e0000-0000-4000-8000-000000000006', 'E05A', 'INCOME', 'LEAF', 6, '5a', 'Einnahmen aus Beteiligungen', '§ 24 Abs. 4 PartG'),
    ('7a6e0000-0000-4000-8000-000000000007', 'E06', 'INCOME', 'LEAF', 7, '6', 'Einnahmen aus sonstigem Vermögen', '§ 24 Abs. 4 PartG'),
    ('7a6e0000-0000-4000-8000-000000000008', 'E07', 'INCOME', 'LEAF', 8, '7', 'Einnahmen aus Veranstaltungen, Vertrieb von Druckschriften und Veröffentlichungen und sonstiger mit Einnahmen verbundener Tätigkeit', '§ 24 Abs. 4 PartG'),
    ('7a6e0000-0000-4000-8000-000000000009', 'E08', 'INCOME', 'LEAF', 9, '8', 'Staatliche Mittel', '§ 24 Abs. 4 PartG'),
    ('7a6e0000-0000-4000-8000-000000000010', 'E09', 'INCOME', 'LEAF', 10, '9', 'Sonstige Einnahmen', '§ 24 Abs. 4 PartG'),
    ('7a6e0000-0000-4000-8000-000000000011', 'E10', 'INCOME', 'LEAF', 11, '10', 'Zuschüsse von Gliederungen', '§ 24 Abs. 4 PartG'),
    ('7a6e0000-0000-4000-8000-000000000012', 'E11', 'INCOME', 'SUM', 12, '11', 'Gesamteinnahmen', '§ 24 Abs. 4 PartG'),
    ('7a6e0000-0000-4000-8000-000000000013', 'A01', 'EXPENSE', 'LEAF', 13, '1', 'Ausgaben für Personal', '§ 24 Abs. 5 PartG'),
    ('7a6e0000-0000-4000-8000-000000000014', 'A02A', 'EXPENSE', 'LEAF', 14, '2 a)', 'Ausgaben des laufenden Geschäftsbetriebs', '§ 24 Abs. 5 PartG'),
    ('7a6e0000-0000-4000-8000-000000000015', 'A02B', 'EXPENSE', 'LEAF', 15, '2 b)', 'Ausgaben für allgemeine politische Arbeit', '§ 24 Abs. 5 PartG'),
    ('7a6e0000-0000-4000-8000-000000000016', 'A02C', 'EXPENSE', 'LEAF', 16, '2 c)', 'Ausgaben für Wahlkämpfe', '§ 24 Abs. 5 PartG'),
    ('7a6e0000-0000-4000-8000-000000000017', 'A02D', 'EXPENSE', 'LEAF', 17, '2 d)', 'Ausgaben für die Vermögensverwaltung einschließlich sich ergebender Zinsen', '§ 24 Abs. 5 PartG'),
    ('7a6e0000-0000-4000-8000-000000000018', 'A02E', 'EXPENSE', 'LEAF', 18, '2 e)', 'Sonstige Zinsen', '§ 24 Abs. 5 PartG'),
    ('7a6e0000-0000-4000-8000-000000000019', 'A02F', 'EXPENSE', 'LEAF', 19, '2 f)', 'Ausgaben aus Unternehmenstätigkeit', '§ 24 Abs. 5 PartG'),
    ('7a6e0000-0000-4000-8000-000000000020', 'A02G', 'EXPENSE', 'LEAF', 20, '2 g)', 'Sonstige Ausgaben', '§ 24 Abs. 5 PartG'),
    ('7a6e0000-0000-4000-8000-000000000021', 'A02', 'EXPENSE', 'SUM', 21, '2', 'Ausgaben für Sachaufwand insgesamt', '§ 24 Abs. 5 PartG'),
    ('7a6e0000-0000-4000-8000-000000000022', 'A03', 'EXPENSE', 'LEAF', 22, '3', 'Zuschüsse an Gliederungen', '§ 24 Abs. 5 PartG'),
    ('7a6e0000-0000-4000-8000-000000000023', 'A04', 'EXPENSE', 'SUM', 23, '4', 'Gesamtausgaben', '§ 24 Abs. 5 PartG'),
    ('7a6e0000-0000-4000-8000-000000000024', 'RES', 'EXPENSE', 'SUM', 24, '', 'Überschuss bzw. Defizit', '§ 24 Abs. 5 PartG'),
    ('7a6e0000-0000-4000-8000-000000000025', 'BP01', 'ASSET', 'LEAF', 25, 'A1', 'Haus- und Grundvermögen', '§ 24 Abs. 6 PartG'),
    ('7a6e0000-0000-4000-8000-000000000026', 'BP02', 'ASSET', 'LEAF', 26, 'A2', 'Geschäftsstellenausstattung', '§ 24 Abs. 6 PartG'),
    ('7a6e0000-0000-4000-8000-000000000027', 'BP03', 'ASSET', 'LEAF', 27, 'A3', 'Beteiligungen an Unternehmen', '§ 24 Abs. 6 PartG'),
    ('7a6e0000-0000-4000-8000-000000000028', 'BP04', 'ASSET', 'LEAF', 28, 'A4', 'Sonstige Finanzanlagen', '§ 24 Abs. 6 PartG'),
    ('7a6e0000-0000-4000-8000-000000000029', 'BP05', 'ASSET', 'LEAF', 29, 'A5', 'Forderungen an Gliederungen', '§ 24 Abs. 6 PartG'),
    ('7a6e0000-0000-4000-8000-000000000030', 'BP06', 'ASSET', 'LEAF', 30, 'A6', 'Forderungen aus der staatlichen Teilfinanzierung', '§ 24 Abs. 6 PartG'),
    ('7a6e0000-0000-4000-8000-000000000031', 'BP07', 'ASSET', 'LEAF', 31, 'A7', 'Geldbestände', '§ 24 Abs. 6 PartG'),
    ('7a6e0000-0000-4000-8000-000000000032', 'BP08', 'ASSET', 'LEAF', 32, 'A8', 'Sonstige Vermögensgegenstände', '§ 24 Abs. 6 PartG'),
    ('7a6e0000-0000-4000-8000-000000000033', 'BP-SUM', 'ASSET', 'SUM', 33, 'A', 'Summe der Besitzposten', '§ 24 Abs. 6 PartG'),
    ('7a6e0000-0000-4000-8000-000000000034', 'SP01', 'LIABILITY', 'LEAF', 34, 'S1', 'Rückstellungen für Pensionsverpflichtungen', '§ 24 Abs. 6 PartG'),
    ('7a6e0000-0000-4000-8000-000000000035', 'SP02', 'LIABILITY', 'LEAF', 35, 'S2', 'Sonstige Rückstellungen', '§ 24 Abs. 6 PartG'),
    ('7a6e0000-0000-4000-8000-000000000036', 'SP03', 'LIABILITY', 'LEAF', 36, 'S3', 'Rückzahlungsverpflichtungen aus der staatlichen Teilfinanzierung', '§ 24 Abs. 6 PartG'),
    ('7a6e0000-0000-4000-8000-000000000037', 'SP04', 'LIABILITY', 'LEAF', 37, 'S4', 'Verbindlichkeiten gegenüber Gebietsverbänden', '§ 24 Abs. 6 PartG'),
    ('7a6e0000-0000-4000-8000-000000000038', 'SP05', 'LIABILITY', 'LEAF', 38, 'S5', 'Verbindlichkeiten gegenüber Kreditinstituten', '§ 24 Abs. 6 PartG'),
    ('7a6e0000-0000-4000-8000-000000000039', 'SP06', 'LIABILITY', 'LEAF', 39, 'S6', 'Verbindlichkeiten gegenüber sonstigen Darlehensgebern', '§ 24 Abs. 6 PartG'),
    ('7a6e0000-0000-4000-8000-000000000040', 'SP07', 'LIABILITY', 'LEAF', 40, 'S7', 'Sonstige Verbindlichkeiten', '§ 24 Abs. 6 PartG'),
    ('7a6e0000-0000-4000-8000-000000000041', 'SP-SUM', 'LIABILITY', 'SUM', 41, 'S', 'Summe der Schuldposten', '§ 24 Abs. 6 PartG'),
    ('7a6e0000-0000-4000-8000-000000000042', 'NET', 'LIABILITY', 'SUM', 42, '', 'Reinvermögen (Besitzposten abzüglich Schuldposten)', '§ 24 Abs. 6 PartG');

UPDATE partg_report_position SET parent_code = 'E11' WHERE code IN ('E01', 'E02', 'E03', 'E04', 'E05', 'E05A', 'E06', 'E07', 'E08', 'E09', 'E10');
UPDATE partg_report_position SET parent_code = 'A04' WHERE code IN ('A01', 'A02', 'A03');
UPDATE partg_report_position SET parent_code = 'A02' WHERE code IN ('A02A', 'A02B', 'A02C', 'A02D', 'A02E', 'A02F', 'A02G');
UPDATE partg_report_position SET parent_code = 'BP-SUM' WHERE code IN ('BP01', 'BP02', 'BP03', 'BP04', 'BP05', 'BP06', 'BP07', 'BP08');
UPDATE partg_report_position SET parent_code = 'SP-SUM' WHERE code IN ('SP01', 'SP02', 'SP03', 'SP04', 'SP05', 'SP06', 'SP07');
