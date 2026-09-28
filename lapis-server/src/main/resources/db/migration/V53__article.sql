-- V1.4.34 Nachrichten-/Artikel-Modul mit redaktionellem Workflow.
--
-- `slug` is NULL until an article is first approved (`ArticleService.approveArticle` assigns it
-- exactly once, then never changes it again -- see `ArticlePolicy.slugFor`/`EventPolicy.slugFor`).
-- `cover_image_id` deliberately has NO foreign key -- same convention as `event.cover_image_id`
-- (see `EventTable.kt`): it is an opaque pointer into the article-cover storage directory, not a
-- row in another table.
CREATE TABLE article
(
    id               UUID PRIMARY KEY,
    slug             VARCHAR(120) UNIQUE,
    title            VARCHAR(140)  NOT NULL,
    excerpt          VARCHAR(300)  NOT NULL DEFAULT '',
    body             TEXT          NOT NULL DEFAULT '',
    cover_image_id   UUID,
    author_id        UUID          NOT NULL REFERENCES member (id),
    status           VARCHAR(10)   NOT NULL,
    submitted_at     TIMESTAMP,
    reviewed_by       UUID REFERENCES member (id),
    reviewed_at      TIMESTAMP,
    rejection_reason VARCHAR(1000),
    published_at     TIMESTAMP,
    created_at       TIMESTAMP     NOT NULL,
    updated_at       TIMESTAMP     NOT NULL
);

CREATE INDEX idx_article_status_published_at ON article (status, published_at DESC);
CREATE INDEX idx_article_author_updated_at ON article (author_id, updated_at DESC);

-- AuditEntityType-CHECK-Verbreiterung -- same append-only pattern V11/V13/V14/V15/V20/V25/V26/V51
-- already established. Full literal list taken from V51__document_folder_access_level.sql (the
-- most recent migration touching this constraint as of this wave), with 'ARTICLE' appended last.
ALTER TABLE audit_log_entry
    DROP CONSTRAINT IF EXISTS audit_log_entry_entity_type_check;
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
                                'DOCUMENT', 'DOCUMENT_FOLDER', 'ARTICLE'
            ));
