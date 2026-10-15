-- Welle V1.9.134 "Vertragstexte je Instanz, versioniert" -- see 70-agreement-texts.kuml.kts. V1..V103 untouched.
-- Purely additive, H2 MODE=PostgreSQL- and Postgres-compatible (IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT,
-- no DO block, no partial index, no ON DELETE CASCADE).
--
-- A dedicated V104 rather than an edit of V103: V103 is already on origin/master and may be migrated on a running instance, so changing it
-- would break the Flyway checksum there.
--
-- Every instance has its own database, so there is NO organization_id column ("per instance" means "per database").
--
--   * agreement_text_version: APPEND-ONLY. One row per published version of the membership agreement or the FRIEND terms. The application
--     only ever INSERTs (tripwire AgreementTextStoreTripwireTest); the current text is the row with the highest sequence_no of its kind. No
--     UPDATE/DELETE trigger: H2 would need Java classes for it and a backup restore must be able to write rows.
--     sha256 = SHA-256 over "<version_label>\n<body>" (UTF-8, lower-case hex) -- the same formula as the built-in placeholders.
--   * agreement_text_draft: at most one working copy per kind (PRIMARY KEY kind). Its id is the stable audit entity id of the draft.
--   * audit_log_entry.entity_type: widened by AGREEMENT_TEXT.

CREATE TABLE IF NOT EXISTS agreement_text_version (
    id               UUID         NOT NULL PRIMARY KEY,
    kind             VARCHAR(10)  NOT NULL,
    sequence_no      INTEGER      NOT NULL,
    version_label    VARCHAR(50)  NOT NULL,
    valid_from       DATE         NOT NULL,
    body             TEXT         NOT NULL,
    sha256           VARCHAR(64)  NOT NULL,
    char_count       INTEGER      NOT NULL,
    author_member_id UUID         NOT NULL,
    published_at     TIMESTAMP    NOT NULL
);

ALTER TABLE agreement_text_version DROP CONSTRAINT IF EXISTS chk_agreement_text_version_kind;
ALTER TABLE agreement_text_version ADD CONSTRAINT chk_agreement_text_version_kind CHECK (kind IN ('MEMBERSHIP', 'FRIEND'));

ALTER TABLE agreement_text_version DROP CONSTRAINT IF EXISTS chk_agreement_text_version_seq;
ALTER TABLE agreement_text_version ADD CONSTRAINT chk_agreement_text_version_seq CHECK (sequence_no >= 1);

ALTER TABLE agreement_text_version DROP CONSTRAINT IF EXISTS chk_agreement_text_version_chars;
ALTER TABLE agreement_text_version ADD CONSTRAINT chk_agreement_text_version_chars CHECK (char_count >= 1 AND char_count <= 60000);

ALTER TABLE agreement_text_version DROP CONSTRAINT IF EXISTS chk_agreement_text_version_sha;
ALTER TABLE agreement_text_version ADD CONSTRAINT chk_agreement_text_version_sha CHECK (CHAR_LENGTH(sha256) = 64);

ALTER TABLE agreement_text_version DROP CONSTRAINT IF EXISTS uq_agreement_text_version_label;
ALTER TABLE agreement_text_version ADD CONSTRAINT uq_agreement_text_version_label UNIQUE (kind, version_label);

ALTER TABLE agreement_text_version DROP CONSTRAINT IF EXISTS uq_agreement_text_version_seq;
ALTER TABLE agreement_text_version ADD CONSTRAINT uq_agreement_text_version_seq UNIQUE (kind, sequence_no);

ALTER TABLE agreement_text_version DROP CONSTRAINT IF EXISTS fk_agreement_text_version_author;
ALTER TABLE agreement_text_version ADD CONSTRAINT fk_agreement_text_version_author FOREIGN KEY (author_member_id) REFERENCES member (id);

CREATE INDEX IF NOT EXISTS idx_agreement_text_version_kind_seq ON agreement_text_version (kind, sequence_no);

CREATE TABLE IF NOT EXISTS agreement_text_draft (
    kind          VARCHAR(10) NOT NULL PRIMARY KEY,
    id            UUID        NOT NULL,
    version_label VARCHAR(50) NOT NULL,
    valid_from    DATE        NOT NULL,
    body          TEXT        NOT NULL,
    sha256        VARCHAR(64) NOT NULL,
    char_count    INTEGER     NOT NULL,
    updated_by    UUID        NOT NULL,
    updated_at    TIMESTAMP   NOT NULL
);

ALTER TABLE agreement_text_draft DROP CONSTRAINT IF EXISTS chk_agreement_text_draft_kind;
ALTER TABLE agreement_text_draft ADD CONSTRAINT chk_agreement_text_draft_kind CHECK (kind IN ('MEMBERSHIP', 'FRIEND'));

ALTER TABLE agreement_text_draft DROP CONSTRAINT IF EXISTS uq_agreement_text_draft_id;
ALTER TABLE agreement_text_draft ADD CONSTRAINT uq_agreement_text_draft_id UNIQUE (id);

ALTER TABLE agreement_text_draft DROP CONSTRAINT IF EXISTS fk_agreement_text_draft_updated_by;
ALTER TABLE agreement_text_draft ADD CONSTRAINT fk_agreement_text_draft_updated_by FOREIGN KEY (updated_by) REFERENCES member (id);

-- ── audit_log_entry.entity_type: widen the CHECK for AGREEMENT_TEXT (14 chars, fits VARCHAR(29)) ──
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
                                'TRAVEL_EXPENSE_SEPA_BATCH', 'CARPOOL_PARTICIPATION', 'VIDEO_OVERLAY', 'AGREEMENT_TEXT'
        ));
