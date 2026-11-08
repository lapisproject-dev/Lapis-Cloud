-- V1.9.293 "Rechenschaftsbericht (PartG), R5b": proposal for statement B (large donors) from the bookings. See 76-partg-report.kuml.kts and
-- docs/features/partg-report.adoc. V1..V146 untouched (V146 is already on master, so R5b gets its own migration; changing V146 in place would break
-- its Flyway checksum on every database that already ran it).
-- Purely additive and repeatable: ADD COLUMN IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT, one NORMAL unique index (no partial
-- index: in H2 and PostgreSQL several NULL values are distinct in a unique index, so rows without a key stay allowed), NO backfill, no new table.
--
--   * partg_report_large_donor.source_donor_key: the pseudonymous donor key `M:<member uuid>` or `X:<external donor uuid>` of the booking donor a row
--     was taken from or linked to. NULL = entered by hand and never linked. 38 characters: two of prefix plus a 36 character UUID.
--   * partg_report_large_donor.source_applied_at: when the row was last written FROM the bookings (created or amount replaced). The link of an
--     existing row sets only the key, not this column. A row changed by hand later has updated_at > source_applied_at ("changed afterwards").
--
-- NOT expressible in SQL (checked in the service / shared PartgDonorProposalRules): the proposal itself, the recomputation under the lock, the
-- 500 rows per unit, the allowed actions per status.
-- DSGVO: the key is a pseudonymous REFERENCE to master data that already exists (member / external donor). R5b creates NO new collection of
-- personal data and records nothing below the threshold. The audit entry holds counters only.

ALTER TABLE partg_report_large_donor ADD COLUMN IF NOT EXISTS source_donor_key VARCHAR(80) NULL;
ALTER TABLE partg_report_large_donor ADD COLUMN IF NOT EXISTS source_applied_at TIMESTAMP NULL;

ALTER TABLE partg_report_large_donor DROP CONSTRAINT IF EXISTS chk_partg_donor_source_key;
ALTER TABLE partg_report_large_donor ADD CONSTRAINT chk_partg_donor_source_key CHECK (
    source_donor_key IS NULL OR (CHAR_LENGTH(source_donor_key) = 38 AND (source_donor_key LIKE 'M:%' OR source_donor_key LIKE 'X:%'))
);
ALTER TABLE partg_report_large_donor DROP CONSTRAINT IF EXISTS chk_partg_donor_source_applied;
ALTER TABLE partg_report_large_donor ADD CONSTRAINT chk_partg_donor_source_applied CHECK (
    source_applied_at IS NULL OR source_donor_key IS NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_partg_report_large_donor_source ON partg_report_large_donor (annex_id, source_donor_key);

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
                                'PARTG_LARGE_DONOR_PROPOSAL'
        ));
