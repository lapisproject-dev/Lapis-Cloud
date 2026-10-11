-- Welle V1.9.105 -- article byline (author line shown with consent), imported-article flag, archive index and audit type ARTICLE_IMPORT.
-- V1..V84 untouched. Purely additive, H2 MODE=PostgreSQL- and Postgres-compatible (ADD COLUMN IF NOT EXISTS, CREATE INDEX IF NOT EXISTS,
-- DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT, no DO block).
--
-- A dedicated V85 rather than an edit of V82 in place: V82 is already part of a squashed master commit (V1.9.100) and applied in local
-- H2/dev databases, so changing it would break the Flyway checksum there (same reasoning as the V83 header).
--
--   * byline / byline_kind: the author line shown next to a published article. Both NULL = no byline (the default and the only state of
--     every pre-existing row). byline_kind: PERSONAL (the author's own name, needs consent), ORGANIZATION (an organization/editorial
--     label set by the board, never a personal name) or IMPORTED (taken over from the legacy website by the admin import).
--   * byline_consent_at: the moment the author consented (GDPR Art. 6(1)(a)). Set if and only if byline_kind = PERSONAL (CHECK).
--   * byline_set_by / byline_set_at: who last changed the byline row (also records who revoked). No consent is ever inferred from these.
--   * imported: TRUE only for rows written by the admin import (ArticleStore.insertImported). Such rows have no human author in Lapis:
--     author_id is the importing board member, who only executed the import. They are excluded from "my articles" and from Art. 15/17
--     authorship paths.
--   * idx_article_published_archive (status, published_at, id): stable archive paging for the embed archive feed.
--
-- The audit constraint follows the V83 pattern: the inline unnamed CHECK of V1 got the generated name CONSTRAINT_407 on H2, the NAMED
-- chk_audit_log_entry_entity_type is dropped and re-added with the list of V83 plus 'ARTICLE_IMPORT' (14 chars, fits VARCHAR(29)).

ALTER TABLE article ADD COLUMN IF NOT EXISTS byline VARCHAR(120) NULL;
ALTER TABLE article ADD COLUMN IF NOT EXISTS byline_kind VARCHAR(12) NULL;
ALTER TABLE article ADD COLUMN IF NOT EXISTS byline_consent_at TIMESTAMP NULL;
ALTER TABLE article ADD COLUMN IF NOT EXISTS byline_set_by UUID NULL;
ALTER TABLE article ADD COLUMN IF NOT EXISTS byline_set_at TIMESTAMP NULL;
ALTER TABLE article ADD COLUMN IF NOT EXISTS imported BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE article DROP CONSTRAINT IF EXISTS fk_article_byline_set_by;
ALTER TABLE article
    ADD CONSTRAINT fk_article_byline_set_by FOREIGN KEY (byline_set_by) REFERENCES member (id);

ALTER TABLE article DROP CONSTRAINT IF EXISTS chk_article_byline_kind;
ALTER TABLE article
    ADD CONSTRAINT chk_article_byline_kind
        CHECK (byline_kind IS NULL OR byline_kind IN ('PERSONAL', 'ORGANIZATION', 'IMPORTED'));

ALTER TABLE article DROP CONSTRAINT IF EXISTS chk_article_byline_pair;
ALTER TABLE article
    ADD CONSTRAINT chk_article_byline_pair
        CHECK ((byline IS NULL AND byline_kind IS NULL) OR (byline IS NOT NULL AND byline_kind IS NOT NULL));

ALTER TABLE article DROP CONSTRAINT IF EXISTS chk_article_byline_consent;
ALTER TABLE article
    ADD CONSTRAINT chk_article_byline_consent
        CHECK ((COALESCE(byline_kind, '') = 'PERSONAL' AND byline_consent_at IS NOT NULL)
            OR (COALESCE(byline_kind, '') <> 'PERSONAL' AND byline_consent_at IS NULL));

CREATE INDEX IF NOT EXISTS idx_article_published_archive ON article (status, published_at, id);

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
                                'COMMITTEE_MEMBERSHIP', 'ARTICLE_IMPORT'
        ));
