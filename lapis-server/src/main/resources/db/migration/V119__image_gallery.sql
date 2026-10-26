-- V1.9.222 "Gemeinsame Bildergalerie" (image gallery). See 75-image-gallery.kuml.kts and docs/features/image-gallery.adoc.
-- Purely additive and repeatable: CREATE TABLE IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT, no partial index, no ON DELETE
-- CASCADE, H2 MODE=PostgreSQL- and PostgreSQL-compatible. V118 is released (0.40.0) and migrated on every instance, so it is NOT edited.
--
--   * attached_image: one picture of a gallery. The owner is (owner_type, owner_id) WITHOUT a foreign key (one table serves several owner
--     tables); position is gap-free per owner and position 0 is the cover image; storage_key (server UUID, distinct from id) names the file.
--     mime_type is the OUTPUT type: a WebP upload is stored as JPEG (PNG when it has alpha), WebP itself is never stored.
--   * audit_log_entry.entity_type: widened by ATTACHED_IMAGE (14 chars, VARCHAR(29) is unchanged). SOCIAL_POST is NOT added to owner_type here:
--     the social wave extends the CHECK with its own migration.

CREATE TABLE IF NOT EXISTS attached_image (
    id          UUID         NOT NULL PRIMARY KEY,
    owner_type  VARCHAR(32)  NOT NULL,
    owner_id    UUID         NOT NULL,
    position    INTEGER      NOT NULL,
    caption     VARCHAR(200) NULL,
    alt_text    VARCHAR(200) NULL,
    storage_key UUID         NOT NULL,
    mime_type   VARCHAR(32)  NOT NULL,
    byte_size   BIGINT       NOT NULL,
    width       INTEGER      NOT NULL,
    height      INTEGER      NOT NULL,
    uploaded_by UUID         NOT NULL REFERENCES member (id),
    created_at  TIMESTAMP    NOT NULL
);

ALTER TABLE attached_image DROP CONSTRAINT IF EXISTS chk_attached_image_owner_type;
ALTER TABLE attached_image ADD CONSTRAINT chk_attached_image_owner_type CHECK (owner_type IN ('CROWDFUNDING_PROJECT', 'AUCTION_LISTING'));

ALTER TABLE attached_image DROP CONSTRAINT IF EXISTS chk_attached_image_mime;
ALTER TABLE attached_image ADD CONSTRAINT chk_attached_image_mime CHECK (mime_type IN ('image/jpeg', 'image/png'));

ALTER TABLE attached_image DROP CONSTRAINT IF EXISTS chk_attached_image_position;
ALTER TABLE attached_image ADD CONSTRAINT chk_attached_image_position CHECK (position >= 0 AND position < 2000);

ALTER TABLE attached_image DROP CONSTRAINT IF EXISTS chk_attached_image_size;
ALTER TABLE attached_image ADD CONSTRAINT chk_attached_image_size CHECK (byte_size > 0 AND width > 0 AND height > 0);

CREATE UNIQUE INDEX IF NOT EXISTS uq_attached_image_owner_position ON attached_image (owner_type, owner_id, position);
CREATE UNIQUE INDEX IF NOT EXISTS uq_attached_image_storage_key ON attached_image (storage_key);
CREATE INDEX IF NOT EXISTS ix_attached_image_uploaded_by ON attached_image (uploaded_by);

-- audit_log_entry.entity_type: ATTACHED_IMAGE (14 chars, fits VARCHAR(29)). The list is V117's, copied verbatim, plus the new literal.
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
                                'EVENT_CAPTURE', 'TRAVEL_EXPENSE_DOCUMENT', 'TENDER', 'EVENT_REGISTRATION_EXPORT', 'ATTACHED_IMAGE'
        ));
