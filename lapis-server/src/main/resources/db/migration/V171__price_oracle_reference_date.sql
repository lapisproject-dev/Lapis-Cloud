-- V171 (V1.9.324) -- Gold price at a reference date for donation conversions.
--
-- (a) price_oracle_config.conversion_rate_basis: the switch (CURRENT = unchanged behaviour, default).
-- (b) price_oracle_snapshot_source: the raw source values behind each stored snapshot, written from now on only (older snapshots have none).
-- (c) price_oracle_reference_rate: a manually confirmed rate for a (anchor, currency, effective date). Immutable by code (no update, no delete).
-- (d) price_oracle_conversion: how the rate of a conversion was found (basis, reference date, effective date, fallback reason, snapshot or manual rate).
-- (e) audit_log_entry.entity_type: V169's list copied verbatim plus PRICE_ORACLE_REFERENCE_RATE (27 chars) and PRICE_ORACLE_CONVERSION (23 chars); both fit VARCHAR(29).
--
-- A backup taken after this migration cannot be restored onto a server that is still before V171.

ALTER TABLE price_oracle_config ADD COLUMN conversion_rate_basis VARCHAR(14) DEFAULT 'CURRENT' NOT NULL;
ALTER TABLE price_oracle_config ADD CONSTRAINT chk_price_oracle_config_conversion_rate_basis
    CHECK (conversion_rate_basis IN ('CURRENT', 'REFERENCE_DATE'));

CREATE TABLE price_oracle_snapshot_source (
    id UUID NOT NULL PRIMARY KEY,
    snapshot_id UUID NOT NULL,
    source_id VARCHAR(64) NOT NULL,
    raw_price DECIMAL(38, 18) NOT NULL,
    observed_at TIMESTAMP NOT NULL,
    included BOOLEAN NOT NULL,
    CONSTRAINT fk_pos_source_snapshot FOREIGN KEY (snapshot_id) REFERENCES price_oracle_snapshot (id) ON DELETE RESTRICT,
    CONSTRAINT uq_price_oracle_snapshot_source UNIQUE (snapshot_id, source_id),
    CONSTRAINT chk_pos_source_raw_price CHECK (raw_price > 0)
);

CREATE TABLE price_oracle_reference_rate (
    id UUID NOT NULL PRIMARY KEY,
    anchor_asset VARCHAR(11) NOT NULL,
    donation_currency VARCHAR(3) NOT NULL,
    effective_date DATE NOT NULL,
    price DECIMAL(38, 18) NOT NULL,
    evidence_text VARCHAR(2000) NOT NULL,
    confirmed_by UUID NOT NULL,
    confirmed_at TIMESTAMP NOT NULL,
    reference_snapshot_median DECIMAL(38, 18) NULL,
    deviation_acknowledged BOOLEAN DEFAULT FALSE NOT NULL,
    CONSTRAINT fk_por_rate_confirmed_by FOREIGN KEY (confirmed_by) REFERENCES member (id),
    CONSTRAINT uq_price_oracle_reference_rate UNIQUE (anchor_asset, donation_currency, effective_date),
    CONSTRAINT chk_por_rate_anchor CHECK (anchor_asset IN ('BITCOIN_BTC', 'GOLD_XAU', 'FIAT')),
    CONSTRAINT chk_por_rate_price CHECK (price > 0),
    CONSTRAINT chk_por_rate_evidence CHECK (CHAR_LENGTH(evidence_text) >= 20)
);

ALTER TABLE price_oracle_conversion ADD COLUMN rate_basis VARCHAR(8) DEFAULT 'CURRENT' NOT NULL;
ALTER TABLE price_oracle_conversion ADD COLUMN reference_date DATE NULL;
ALTER TABLE price_oracle_conversion ADD COLUMN effective_date DATE NULL;
ALTER TABLE price_oracle_conversion ADD COLUMN fallback_reason VARCHAR(7) NULL;
ALTER TABLE price_oracle_conversion ADD COLUMN snapshot_id UUID NULL;
ALTER TABLE price_oracle_conversion ADD COLUMN reference_rate_id UUID NULL;
ALTER TABLE price_oracle_conversion ADD COLUMN quorum_required INT NULL;
ALTER TABLE price_oracle_conversion ADD CONSTRAINT fk_poc_snapshot FOREIGN KEY (snapshot_id) REFERENCES price_oracle_snapshot (id);
ALTER TABLE price_oracle_conversion ADD CONSTRAINT fk_poc_reference_rate FOREIGN KEY (reference_rate_id) REFERENCES price_oracle_reference_rate (id);
ALTER TABLE price_oracle_conversion ADD CONSTRAINT chk_poc_rate_basis CHECK (rate_basis IN ('CURRENT', 'SNAPSHOT', 'MANUAL'));
ALTER TABLE price_oracle_conversion ADD CONSTRAINT chk_poc_fallback_reason CHECK (fallback_reason IS NULL OR fallback_reason IN ('NONE', 'WEEKEND', 'HOLIDAY'));
ALTER TABLE price_oracle_conversion ADD CONSTRAINT chk_poc_rate_consistency CHECK (
    (rate_basis = 'CURRENT' AND snapshot_id IS NULL AND reference_rate_id IS NULL AND reference_date IS NULL
        AND effective_date IS NULL AND fallback_reason IS NULL AND quorum_required IS NULL)
    OR (rate_basis = 'SNAPSHOT' AND snapshot_id IS NOT NULL AND reference_rate_id IS NULL AND reference_date IS NOT NULL
        AND effective_date IS NOT NULL AND fallback_reason IS NOT NULL AND quorum_required IS NOT NULL)
    OR (rate_basis = 'MANUAL' AND reference_rate_id IS NOT NULL AND snapshot_id IS NULL AND reference_date IS NOT NULL
        AND effective_date IS NOT NULL AND fallback_reason IS NOT NULL AND quorum_required IS NOT NULL)
);

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
                                'CARPOOL_FEDERATION_FEED',
                                'MEETING_REWARD_POLICY', 'MEETING_REWARD_SETTLEMENT',
                                'AUCTION',
                                'AUCTION_FEDERATION_FEED',
                                'PRICE_ORACLE_REFERENCE_RATE', 'PRICE_ORACLE_CONVERSION'));
