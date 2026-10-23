-- V1.9.199: organisation-wide travel-expense documents (V114) + Markdown format of the contact hint.
-- Purely additive; V113 is untouched. Files live on disk like receipts/attachments (server-generated
-- storage_key), see TravelExpenseDocumentRoutes KDoc. mime_type is VARCHAR(100): the xlsx MIME type
-- is 65 characters long (V113's VARCHAR(64) must NOT be copied).
CREATE TABLE IF NOT EXISTS travel_expense_document (
    id                UUID         NOT NULL PRIMARY KEY,
    title             VARCHAR(120) NOT NULL,
    storage_key       VARCHAR(200) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    mime_type         VARCHAR(100) NOT NULL,
    size_bytes        BIGINT       NOT NULL,
    sha256            VARCHAR(64)  NOT NULL,
    uploaded_by       UUID         NULL REFERENCES member (id) ON DELETE SET NULL,
    uploaded_at       TIMESTAMP    NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_ted_storage_key ON travel_expense_document (storage_key);

ALTER TABLE travel_expense_document DROP CONSTRAINT IF EXISTS chk_ted_mime;
ALTER TABLE travel_expense_document ADD CONSTRAINT chk_ted_mime
    CHECK (mime_type IN (
        'application/pdf',
        'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        'application/vnd.ms-excel'));

ALTER TABLE travel_expense_document DROP CONSTRAINT IF EXISTS chk_ted_size;
ALTER TABLE travel_expense_document ADD CONSTRAINT chk_ted_size
    CHECK (size_bytes > 0 AND size_bytes <= 10485760);

ALTER TABLE travel_expense_document DROP CONSTRAINT IF EXISTS chk_ted_title;
ALTER TABLE travel_expense_document ADD CONSTRAINT chk_ted_title
    CHECK (CHAR_LENGTH(TRIM(title)) BETWEEN 1 AND 120);

-- Contact hint: 1000 -> 2000 characters, plus its text format (PLAIN = legacy, MARKDOWN = rendered).
ALTER TABLE organization_settings ALTER COLUMN travel_contact_hint SET DATA TYPE VARCHAR(2000);
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS travel_contact_hint_format VARCHAR(16) NOT NULL DEFAULT 'PLAIN';
ALTER TABLE organization_settings DROP CONSTRAINT IF EXISTS chk_os_hint_format;
ALTER TABLE organization_settings ADD CONSTRAINT chk_os_hint_format
    CHECK (travel_contact_hint_format IN ('PLAIN', 'MARKDOWN'));

-- audit_log_entry.entity_type: widen the CHECK for TRAVEL_EXPENSE_DOCUMENT (23 chars, fits VARCHAR(29)).
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
                                'EVENT_CAPTURE', 'TRAVEL_EXPENSE_DOCUMENT'
        ));
