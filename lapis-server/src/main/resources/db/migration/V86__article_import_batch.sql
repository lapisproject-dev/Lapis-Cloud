-- Welle V1.9.106 -- article import follow-up: import batch id on article, audit types ARTICLE_BULK_APPROVED and ARTICLE_IMPORT_IMAGES_APPLIED.
-- V1..V85 untouched. Purely additive, H2 MODE=PostgreSQL- and Postgres-compatible (ADD COLUMN IF NOT EXISTS, CREATE INDEX IF NOT EXISTS,
-- DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT, no DO block).
--
-- A dedicated V86 rather than an edit of V85 in place: V85 is already part of the squashed master commit V1.9.105 and applied in local H2/dev
-- databases, so changing it would break the Flyway checksum there (same reasoning as the V85 header).
--
--   * article.import_batch_id: the id of the import run that created the row (one random id per committed import call). It lets the image
--     commit address exactly the articles of one run and nothing else. NULL for every pre-existing row and for every non-imported article
--     (CHECK: only imported rows may carry one).
--   * idx_article_imported_status_batch: backs the bulk approval and the image commit lookups.
--   * audit types: ARTICLE_BULK_APPROVED (21 chars) and ARTICLE_IMPORT_IMAGES_APPLIED (29 chars, fills VARCHAR(29) exactly).
--
-- The audit constraint follows the V85 pattern: the NAMED chk_audit_log_entry_entity_type is dropped and re-added with the V85 list plus the
-- two new literals (appended last).

ALTER TABLE article ADD COLUMN IF NOT EXISTS import_batch_id UUID NULL;

ALTER TABLE article DROP CONSTRAINT IF EXISTS chk_article_import_batch;
ALTER TABLE article
    ADD CONSTRAINT chk_article_import_batch
        CHECK (import_batch_id IS NULL OR imported = TRUE);

CREATE INDEX IF NOT EXISTS idx_article_imported_status_batch ON article (imported, status, import_batch_id);

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
                                'ARTICLE_IMPORT_IMAGES_APPLIED'
        ));
