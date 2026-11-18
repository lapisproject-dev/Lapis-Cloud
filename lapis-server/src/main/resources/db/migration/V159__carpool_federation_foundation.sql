-- V1.9.311 "Mitfahrzentrale-Foederation" W1 (foundation): switches only, nothing is federated, no network traffic. See
-- docs/architecture/carpool-federation.adoc. V158 is NOT edited (it is a different feature and may already be rolled out). Additive and repeatable
-- (ADD COLUMN IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT), H2 MODE=PostgreSQL- and PostgreSQL-compatible, NO backfill:
-- every existing row ends up with the safe default (not shared / switched off).
-- While this wave is unreleased V159 may still be corrected in place on the feature branch; after the release every correction is a NEW migration.
--
--   * carpool_posting.federation_opt_in: the owner's per-trip consent to pass a short hint (date + two-digit postcode areas) to partner
--     organisations. Written ONLY by CarpoolFederationStore.
--   * organization_settings.carpool_federation_enabled / carpool_federation_hop_limit: the instance switch (default OFF) and how far a hint may travel
--     (1..3, default 2). Written ONLY by CarpoolFederationStore.
--   * partner_server.carpool_sharing: per DIRECT partner consent to exchange trips with it (default OFF). partner_discovered_server gets no flag.
--
-- ROLLBACK HAZARD: builds without V159 reject a database with V159 applied (Flyway validation). Roll back only by restoring the backup taken before
-- V159. Backups taken before V159 are rejected on restore by the schema checksum (BackupManifest.schemaChecksum), by design.

ALTER TABLE carpool_posting ADD COLUMN IF NOT EXISTS federation_opt_in BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS carpool_federation_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS carpool_federation_hop_limit SMALLINT NOT NULL DEFAULT 2;
ALTER TABLE organization_settings DROP CONSTRAINT IF EXISTS chk_org_carpool_federation_hop_limit;
ALTER TABLE organization_settings ADD CONSTRAINT chk_org_carpool_federation_hop_limit CHECK (carpool_federation_hop_limit BETWEEN 1 AND 3);

ALTER TABLE partner_server ADD COLUMN IF NOT EXISTS carpool_sharing BOOLEAN NOT NULL DEFAULT FALSE;

-- audit_log_entry.entity_type + CARPOOL_FEDERATION_OPT_IN (25 chars, fits VARCHAR(29)); the list is V158's, copied verbatim, plus the new literal.
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
                                'CARPOOL_FEDERATION_OPT_IN'
        ));
