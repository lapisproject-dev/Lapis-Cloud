-- V1.9.312 "Mitfahrzentrale-Foederation" W2 (origin side of the teaser feed): the pinned signing key of a partner, the counters of the feed and the
-- audit literal. See docs/architecture/carpool-federation.adoc. V159 is NOT edited (it is already on origin/master and may have run on a database).
-- Additive and repeatable (ADD COLUMN IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT), H2 MODE=PostgreSQL- and
-- PostgreSQL-compatible, NO backfill: every existing partner ends up with no pinned key (the feed refuses it) and zero counters.
-- While this wave is unreleased V160 may still be corrected in place on the feature branch; after the release every correction is a NEW migration.
--
--   * partner_server.signing_key_id / signing_public_key_pem / signing_key_pinned_at: the public key a DIRECT partner announces in its public
--     partner feed (field `signingKey`), pinned by the P1 poll over its TLS fetch. The carpool teaser feed verifies request signatures against
--     THIS key only (no key is ever fetched while a request is being answered). Written ONLY by PartnerServerStore.
--   * partner_server.carpool_feed_served_at / carpool_feed_served_count / carpool_feed_last_items: how often and when the partner fetched the
--     teaser feed (200 and 304) and how many items the last 200 held. No content. Written ONLY by PartnerServerStore.
--
-- ROLLBACK HAZARD: builds without V160 reject a database with V160 applied (Flyway validation). Roll back only by restoring the backup taken before
-- V160. Backups taken before V160 are rejected on restore by the schema checksum (BackupManifest.schemaChecksum), by design.

ALTER TABLE partner_server ADD COLUMN IF NOT EXISTS signing_key_id VARCHAR(512);
ALTER TABLE partner_server ADD COLUMN IF NOT EXISTS signing_public_key_pem VARCHAR(4096);
ALTER TABLE partner_server ADD COLUMN IF NOT EXISTS signing_key_pinned_at TIMESTAMP;
ALTER TABLE partner_server ADD COLUMN IF NOT EXISTS carpool_feed_served_at TIMESTAMP;
ALTER TABLE partner_server ADD COLUMN IF NOT EXISTS carpool_feed_served_count BIGINT NOT NULL DEFAULT 0;
ALTER TABLE partner_server ADD COLUMN IF NOT EXISTS carpool_feed_last_items INTEGER;

-- Upgrade order: a partner on the new version may have been polled by an old server, which stored the new ETag but ignored `signingKey`. A later
-- If-None-Match poll would then get a 304 and never pin a key. Clearing every ETag forces one full fetch (200) per partner, which pins the key.
UPDATE partner_server SET etag = NULL;

-- audit_log_entry.entity_type + CARPOOL_FEDERATION_FEED (23 chars, fits VARCHAR(29)); the list is V159's, copied verbatim, plus the new literal.
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
                                'PARTG_REPORT_VERSION', 'PARTG_REPORT_DECLARATION', 'PARTG_REPORT_DEADLINE',
                                'PARTG_LARGE_DONOR_PROPOSAL',
                                'BULLETIN_NOTICE',
                                'CONFERENCE_ROOM_POLL',
                                'PARTG_REVIEWER_ACCESS',
                                'CARPOOL_FEDERATION_OPT_IN',
                                'CARPOOL_FEDERATION_FEED'
        ));
