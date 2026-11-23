-- Wave V1.9.320 "Auktionen in Euro mit Direktzahlung (Fiat W1)". See 21-auction.kuml.kts, 11-organization-settings.kuml.kts,
-- 14-audit-log.kuml.kts and docs/architecture/auction-fiat-and-federation.adoc.
-- V1..V165 untouched (V165 is on the pushed master in 0.55.0/0.56.0 and probably already applied on instances; changing any applied migration
-- in place would break its Flyway checksum -- so this is a NEW migration).
-- Purely additive and repeatable, H2 MODE=PostgreSQL- and PostgreSQL-compatible: DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT,
-- CREATE TABLE/INDEX IF NOT EXISTS, no DO block, no partial index, no ON DELETE CASCADE, NO data change on existing rows (every existing
-- auction keeps currency = 'LTR' through the column default).
--
-- DECISION: a non-LTR (Fiat) auction NEVER touches ltr_ledger_entry. Lapis only brokers the contract between seller and winner; the money moves
-- directly between them, outside the platform. So there is no new ledger literal and ltr_ledger_entry's CHECKs stay untouched.
--
--   * auction.status: VARCHAR(14) -> VARCHAR(16) ('AWAITING_PAYMENT' has 16 characters) and the CHECK is replaced by a NAMED one with the
--     three new literals AWAITING_PAYMENT, PAID, COMPLETED. The V1 CHECK is UNNAMED: in H2 its generated name is the UPPER-CASE
--     "CONSTRAINT_D88C" (measured on V1, deterministic because V1 is immutable; AuctionStatusMigrationTest pins that exactly one CHECK on
--     `status` exists afterwards), in PostgreSQL the auto name is auction_status_check. Same dual-DROP discipline as V155.
--   * auction: currency + minor-unit amount columns + payment/delivery confirmation stamps; starting_bid_ltr and auction_bid.max_bid_ltr become
--     nullable (a Fiat row carries the *_minor columns instead; chk_auction_amount_columns pins exactly one of the two shapes).
--   * auction_payment_details: the seller's payment details (holder, IBAN, link) as ONE SecretBox-sealed JSON blob (AES-GCM, aad = auction id),
--     in a SEPARATE table so that no AuctionDto/listing query can ever select it. Deleted by the retention poller.
--   * auction_fiat_acknowledgment: append-only record of who acknowledged which Fiat disclaimer version (OPERATOR = administrator switching the
--     instance flag on, SELLER = member before listing in Euro).
--   * organization_settings.auction_fiat_enabled: instance switch, OFF by default. NOT part of updateOrganizationSettings' write set.
--   * audit_log_entry.entity_type + AUCTION (the list is V164's, copied verbatim).
--
-- ROLLBACK HAZARD / BACKUPS: backups made before this migration are refused by the restore (the schema checksum differs), like after every
-- migration. Release 0.56.0 cannot read a Fiat auction (it expects a NOT NULL starting_bid_ltr): roll back only from a backup taken before V166.

ALTER TABLE auction DROP CONSTRAINT IF EXISTS "CONSTRAINT_D88C";
ALTER TABLE auction DROP CONSTRAINT IF EXISTS auction_status_check;
ALTER TABLE auction DROP CONSTRAINT IF EXISTS chk_auction_status;
ALTER TABLE auction ALTER COLUMN status SET DATA TYPE VARCHAR(16);
ALTER TABLE auction ADD CONSTRAINT chk_auction_status
    CHECK (status IN ('OPEN', 'SETTLED', 'CLOSED_NO_SALE', 'AWAITING_PAYMENT', 'PAID', 'COMPLETED'));

ALTER TABLE auction ADD COLUMN IF NOT EXISTS currency VARCHAR(3) NOT NULL DEFAULT 'LTR';
ALTER TABLE auction ADD COLUMN IF NOT EXISTS starting_price_minor BIGINT NULL;
ALTER TABLE auction ADD COLUMN IF NOT EXISTS buy_now_price_minor BIGINT NULL;
ALTER TABLE auction ADD COLUMN IF NOT EXISTS final_price_minor BIGINT NULL;
ALTER TABLE auction ADD COLUMN IF NOT EXISTS seller_disclaimer_version VARCHAR(50) NULL;
ALTER TABLE auction ADD COLUMN IF NOT EXISTS payment_confirmed_at TIMESTAMP NULL;
ALTER TABLE auction ADD COLUMN IF NOT EXISTS delivery_confirmed_at TIMESTAMP NULL;

ALTER TABLE auction ALTER COLUMN starting_bid_ltr DROP NOT NULL;
ALTER TABLE auction_bid ALTER COLUMN max_bid_ltr DROP NOT NULL;
ALTER TABLE auction_bid ADD COLUMN IF NOT EXISTS max_bid_minor BIGINT NULL;

ALTER TABLE auction DROP CONSTRAINT IF EXISTS chk_auction_currency_format;
ALTER TABLE auction ADD CONSTRAINT chk_auction_currency_format CHECK (CHAR_LENGTH(currency) = 3 AND currency = UPPER(currency));

ALTER TABLE auction DROP CONSTRAINT IF EXISTS chk_auction_amount_columns;
ALTER TABLE auction ADD CONSTRAINT chk_auction_amount_columns CHECK (
        (currency = 'LTR'
            AND starting_bid_ltr IS NOT NULL
            AND starting_price_minor IS NULL AND buy_now_price_minor IS NULL AND final_price_minor IS NULL
            AND seller_disclaimer_version IS NULL
            AND payment_confirmed_at IS NULL AND delivery_confirmed_at IS NULL)
        OR
        (currency <> 'LTR'
            AND starting_bid_ltr IS NULL AND buy_now_price_ltr IS NULL AND final_price_ltr IS NULL
            AND starting_price_minor IS NOT NULL
            AND seller_disclaimer_version IS NOT NULL));

ALTER TABLE auction DROP CONSTRAINT IF EXISTS chk_auction_status_by_currency;
ALTER TABLE auction ADD CONSTRAINT chk_auction_status_by_currency CHECK (
        (currency = 'LTR' AND status IN ('OPEN', 'SETTLED', 'CLOSED_NO_SALE'))
        OR (currency <> 'LTR' AND status IN ('OPEN', 'CLOSED_NO_SALE', 'AWAITING_PAYMENT', 'PAID', 'COMPLETED')));

ALTER TABLE auction DROP CONSTRAINT IF EXISTS chk_auction_minor_positive;
ALTER TABLE auction ADD CONSTRAINT chk_auction_minor_positive CHECK (
        (starting_price_minor IS NULL OR starting_price_minor > 0)
        AND (buy_now_price_minor IS NULL OR buy_now_price_minor > 0)
        AND (final_price_minor IS NULL OR final_price_minor > 0));

ALTER TABLE auction_bid DROP CONSTRAINT IF EXISTS chk_auction_bid_amount_columns;
ALTER TABLE auction_bid ADD CONSTRAINT chk_auction_bid_amount_columns CHECK (
        (max_bid_ltr IS NOT NULL AND max_bid_minor IS NULL)
        OR (max_bid_ltr IS NULL AND max_bid_minor IS NOT NULL AND max_bid_minor > 0));

CREATE TABLE IF NOT EXISTS auction_payment_details (
    auction_id          UUID          NOT NULL PRIMARY KEY REFERENCES auction (id),
    details_ciphertext  VARCHAR(2000) NOT NULL,
    has_iban            BOOLEAN       NOT NULL,
    has_payment_link    BOOLEAN       NOT NULL,
    updated_at          TIMESTAMP     NOT NULL,
    CHECK (has_iban OR has_payment_link)
);

CREATE TABLE IF NOT EXISTS auction_fiat_acknowledgment (
    id                         UUID         NOT NULL PRIMARY KEY,
    kind                       VARCHAR(8)   NOT NULL CHECK (kind IN ('OPERATOR', 'SELLER')),
    acknowledged_by_member_id  UUID         NOT NULL REFERENCES member (id),
    acknowledged_at            TIMESTAMP    NOT NULL,
    disclaimer_version         VARCHAR(50)  NOT NULL,
    disclaimer_sha256          VARCHAR(64)  NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_auction_fiat_ack_member_kind ON auction_fiat_acknowledgment (acknowledged_by_member_id, kind, disclaimer_version);

ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS auction_fiat_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- audit_log_entry.entity_type: the list is V164's, copied verbatim, plus the new literal AUCTION (7 chars, fits VARCHAR(29)).
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
                                'AUCTION'
        ));
