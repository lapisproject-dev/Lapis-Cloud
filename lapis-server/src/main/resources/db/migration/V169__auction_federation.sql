-- V169 (V1.9.322, Fiat wave (c) "Federation visibility for auctions"): the schema foundation to show a Euro auction to partner servers as a
-- PREVIEW HINT (title, price, end, pseudonymous id -- never seller, bidder, offer or payment data; the buyer bids on the ORIGIN server).
-- Additive and repeatable (ADD COLUMN IF NOT EXISTS / CREATE TABLE IF NOT EXISTS / DROP CONSTRAINT IF EXISTS before ADD), H2 (MODE=PostgreSQL) and
-- PostgreSQL. No backfill: every switch defaults to OFF/FALSE, so a deployed instance behaves exactly as before. V1..V168 are untouched (a changed
-- checksum would break Flyway on every instance that already ran them).
--
-- Writer monopolies (pinned by AuctionFederationTripwireTest once the store exists):
--   auction.federation_opt_in/_at, organization_settings.auction_federation_*, auction_teaser_snapshot, auction_teaser_tombstone: AuctionFederationStore
--   auction_received_hint, auction_received_tombstone: AuctionReceivedStore
--   partner_server.auction_*: PartnerServerStore
-- Rollback danger: dropping these columns after a hint was published cannot recall it from a partner; a rollback must first withdraw (tombstone).
-- Not portable on purpose: no regex CHECKs, no partial indexes (H2 and PostgreSQL both must accept this file).
-- auction.federation_opt_in has NO CHECK against the currency: the "Euro only" rule lives in code (mayConsentToAuctionFederation) and is pinned by a
-- tripwire, because whether LTR auctions may ever be federated is an open product question.

ALTER TABLE auction ADD COLUMN IF NOT EXISTS federation_opt_in BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE auction ADD COLUMN IF NOT EXISTS federation_opt_in_at TIMESTAMP NULL;

ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS auction_federation_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS auction_federation_hop_limit SMALLINT NOT NULL DEFAULT 1;
ALTER TABLE organization_settings DROP CONSTRAINT IF EXISTS chk_org_auction_federation_hop_limit;
ALTER TABLE organization_settings ADD CONSTRAINT chk_org_auction_federation_hop_limit CHECK (auction_federation_hop_limit BETWEEN 1 AND 2);

ALTER TABLE partner_server ADD COLUMN IF NOT EXISTS auction_sharing BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE partner_server ADD COLUMN IF NOT EXISTS auction_feed_served_at TIMESTAMP;
ALTER TABLE partner_server ADD COLUMN IF NOT EXISTS auction_feed_served_count BIGINT NOT NULL DEFAULT 0;
ALTER TABLE partner_server ADD COLUMN IF NOT EXISTS auction_feed_last_items INTEGER;
ALTER TABLE partner_server ADD COLUMN IF NOT EXISTS auction_pull_at TIMESTAMP;
ALTER TABLE partner_server ADD COLUMN IF NOT EXISTS auction_pull_ok_at TIMESTAMP;
ALTER TABLE partner_server ADD COLUMN IF NOT EXISTS auction_pull_next_at TIMESTAMP;
ALTER TABLE partner_server ADD COLUMN IF NOT EXISTS auction_pull_failures INT NOT NULL DEFAULT 0;
ALTER TABLE partner_server ADD COLUMN IF NOT EXISTS auction_pull_status VARCHAR(32);
ALTER TABLE partner_server ADD COLUMN IF NOT EXISTS auction_pull_error VARCHAR(64);
ALTER TABLE partner_server ADD COLUMN IF NOT EXISTS auction_pull_etag VARCHAR(200);
ALTER TABLE partner_server ADD COLUMN IF NOT EXISTS auction_pull_last_accepted INT;
ALTER TABLE partner_server ADD COLUMN IF NOT EXISTS auction_pull_last_rejected INT;

-- The last published state of a shared auction: the revision counter and the price the feed showed last (throttled to one revision per 30 minutes).
-- Excluded from the organization backup (it describes what THIS server told its partners; a restore resets it).
CREATE TABLE IF NOT EXISTS auction_teaser_snapshot (
    auction_id     UUID         NOT NULL PRIMARY KEY,
    teaser_id      VARCHAR(64)  NOT NULL,
    revision       BIGINT       NOT NULL,
    price_kind     VARCHAR(8)   NOT NULL,
    price_minor    BIGINT       NOT NULL,
    price_as_of    TIMESTAMP    NOT NULL,
    content_sha256 CHAR(64)     NOT NULL,
    updated_at     TIMESTAMP    NOT NULL
);
ALTER TABLE auction_teaser_snapshot DROP CONSTRAINT IF EXISTS fk_auction_teaser_snapshot_auction;
ALTER TABLE auction_teaser_snapshot ADD CONSTRAINT fk_auction_teaser_snapshot_auction FOREIGN KEY (auction_id) REFERENCES auction (id);
ALTER TABLE auction_teaser_snapshot DROP CONSTRAINT IF EXISTS uq_auction_teaser_snapshot_teaser;
ALTER TABLE auction_teaser_snapshot ADD CONSTRAINT uq_auction_teaser_snapshot_teaser UNIQUE (teaser_id);
ALTER TABLE auction_teaser_snapshot DROP CONSTRAINT IF EXISTS chk_auction_teaser_snapshot_id_len;
ALTER TABLE auction_teaser_snapshot ADD CONSTRAINT chk_auction_teaser_snapshot_id_len CHECK (CHAR_LENGTH(teaser_id) = 64);
ALTER TABLE auction_teaser_snapshot DROP CONSTRAINT IF EXISTS chk_auction_teaser_snapshot_revision;
ALTER TABLE auction_teaser_snapshot ADD CONSTRAINT chk_auction_teaser_snapshot_revision CHECK (revision >= 1);
ALTER TABLE auction_teaser_snapshot DROP CONSTRAINT IF EXISTS chk_auction_teaser_snapshot_price;
ALTER TABLE auction_teaser_snapshot ADD CONSTRAINT chk_auction_teaser_snapshot_price CHECK (price_minor > 0);

-- The tombstone of a withdrawn hint (own withdrawal or a passed-on one). Stays in the backup, like carpool_teaser_tombstone: it protects a
-- withdrawal across a restore.
CREATE TABLE IF NOT EXISTS auction_teaser_tombstone (
    id                        UUID         NOT NULL PRIMARY KEY,
    teaser_id                 VARCHAR(64)  NOT NULL,
    partner_id                UUID         NULL,
    created_at                TIMESTAMP    NOT NULL,
    expires_at                TIMESTAMP    NOT NULL,
    relay_origin_host         VARCHAR(253),
    relay_reporter_partner_id UUID
);
ALTER TABLE auction_teaser_tombstone DROP CONSTRAINT IF EXISTS fk_auction_teaser_tombstone_partner;
ALTER TABLE auction_teaser_tombstone ADD CONSTRAINT fk_auction_teaser_tombstone_partner
    FOREIGN KEY (partner_id) REFERENCES partner_server (id) ON DELETE CASCADE;
ALTER TABLE auction_teaser_tombstone DROP CONSTRAINT IF EXISTS chk_auction_teaser_tombstone_id_len;
ALTER TABLE auction_teaser_tombstone ADD CONSTRAINT chk_auction_teaser_tombstone_id_len CHECK (CHAR_LENGTH(teaser_id) = 64);
ALTER TABLE auction_teaser_tombstone DROP CONSTRAINT IF EXISTS chk_auction_teaser_tombstone_ttl;
ALTER TABLE auction_teaser_tombstone ADD CONSTRAINT chk_auction_teaser_tombstone_ttl CHECK (expires_at > created_at);
CREATE INDEX IF NOT EXISTS idx_auction_teaser_tombstone_teaser ON auction_teaser_tombstone (teaser_id);
CREATE INDEX IF NOT EXISTS idx_auction_teaser_tombstone_expires ON auction_teaser_tombstone (expires_at);
CREATE INDEX IF NOT EXISTS idx_auction_teaser_tombstone_partner ON auction_teaser_tombstone (partner_id);
CREATE INDEX IF NOT EXISTS idx_auction_teaser_tombstone_relay_reporter ON auction_teaser_tombstone (relay_reporter_partner_id);

-- A hint of ANOTHER server: title, price, end and the pseudonymous id of its origin -- no member, no payment, no contact. Excluded from the backup.
CREATE TABLE IF NOT EXISTS auction_received_hint (
    id                UUID          NOT NULL PRIMARY KEY,
    teaser_id         VARCHAR(64)   NOT NULL,
    origin_host       VARCHAR(253)  NOT NULL,
    hops              SMALLINT      NOT NULL,
    max_hops          SMALLINT      NOT NULL,
    via_hosts         VARCHAR(800)  NULL,
    source_partner_id UUID          NOT NULL,
    revision          BIGINT        NOT NULL,
    title             VARCHAR(80)   NOT NULL,
    currency          VARCHAR(3)    NOT NULL,
    price_kind        VARCHAR(8)    NOT NULL,
    price_minor       BIGINT        NOT NULL,
    buy_now_minor     BIGINT        NULL,
    offers_possible   BOOLEAN       NOT NULL,
    ends_at           TIMESTAMP     NOT NULL,
    price_as_of       TIMESTAMP     NOT NULL,
    origin_signature  VARCHAR(1024) NOT NULL,
    received_at       TIMESTAMP     NOT NULL,
    last_seen_at      TIMESTAMP     NOT NULL,
    expires_at        TIMESTAMP     NOT NULL
);
ALTER TABLE auction_received_hint DROP CONSTRAINT IF EXISTS fk_auction_received_hint_source;
ALTER TABLE auction_received_hint ADD CONSTRAINT fk_auction_received_hint_source
    FOREIGN KEY (source_partner_id) REFERENCES partner_server (id) ON DELETE CASCADE;
ALTER TABLE auction_received_hint DROP CONSTRAINT IF EXISTS uq_auction_received_hint_origin_teaser;
ALTER TABLE auction_received_hint ADD CONSTRAINT uq_auction_received_hint_origin_teaser UNIQUE (origin_host, teaser_id);
ALTER TABLE auction_received_hint DROP CONSTRAINT IF EXISTS chk_auction_received_hint_id_len;
ALTER TABLE auction_received_hint ADD CONSTRAINT chk_auction_received_hint_id_len CHECK (CHAR_LENGTH(teaser_id) = 64);
ALTER TABLE auction_received_hint DROP CONSTRAINT IF EXISTS chk_auction_received_hint_hops;
ALTER TABLE auction_received_hint ADD CONSTRAINT chk_auction_received_hint_hops CHECK (hops BETWEEN 1 AND 2);
ALTER TABLE auction_received_hint DROP CONSTRAINT IF EXISTS chk_auction_received_hint_max_hops;
ALTER TABLE auction_received_hint ADD CONSTRAINT chk_auction_received_hint_max_hops CHECK (max_hops BETWEEN 1 AND 2);
ALTER TABLE auction_received_hint DROP CONSTRAINT IF EXISTS chk_auction_received_hint_hops_le_max;
ALTER TABLE auction_received_hint ADD CONSTRAINT chk_auction_received_hint_hops_le_max CHECK (hops <= max_hops);
ALTER TABLE auction_received_hint DROP CONSTRAINT IF EXISTS chk_auction_received_hint_revision;
ALTER TABLE auction_received_hint ADD CONSTRAINT chk_auction_received_hint_revision CHECK (revision >= 1);
ALTER TABLE auction_received_hint DROP CONSTRAINT IF EXISTS chk_auction_received_hint_price;
ALTER TABLE auction_received_hint ADD CONSTRAINT chk_auction_received_hint_price CHECK (price_minor > 0);
ALTER TABLE auction_received_hint DROP CONSTRAINT IF EXISTS chk_auction_received_hint_buy_now;
ALTER TABLE auction_received_hint ADD CONSTRAINT chk_auction_received_hint_buy_now CHECK (buy_now_minor IS NULL OR buy_now_minor > price_minor);
ALTER TABLE auction_received_hint DROP CONSTRAINT IF EXISTS chk_auction_received_hint_ttl;
ALTER TABLE auction_received_hint ADD CONSTRAINT chk_auction_received_hint_ttl CHECK (expires_at > received_at);
CREATE INDEX IF NOT EXISTS idx_auction_received_hint_source ON auction_received_hint (source_partner_id);
CREATE INDEX IF NOT EXISTS idx_auction_received_hint_expires ON auction_received_hint (expires_at);
CREATE INDEX IF NOT EXISTS idx_auction_received_hint_teaser ON auction_received_hint (teaser_id);

-- A LOCAL block against re-importing a withdrawn hint (kind WITHDRAWN) or one an administrator hid (kind HIDDEN). Never sent anywhere. Excluded from the backup.
CREATE TABLE IF NOT EXISTS auction_received_tombstone (
    id                     UUID         NOT NULL PRIMARY KEY,
    teaser_id              VARCHAR(64)  NOT NULL,
    origin_host            VARCHAR(253) NOT NULL,
    kind                   VARCHAR(16)  NOT NULL,
    reported_by_partner_id UUID         NULL,
    created_at             TIMESTAMP    NOT NULL,
    expires_at             TIMESTAMP    NOT NULL
);
ALTER TABLE auction_received_tombstone DROP CONSTRAINT IF EXISTS fk_auction_received_tombstone_reporter;
ALTER TABLE auction_received_tombstone ADD CONSTRAINT fk_auction_received_tombstone_reporter
    FOREIGN KEY (reported_by_partner_id) REFERENCES partner_server (id) ON DELETE SET NULL;
ALTER TABLE auction_received_tombstone DROP CONSTRAINT IF EXISTS uq_auction_received_tombstone_origin_teaser;
ALTER TABLE auction_received_tombstone ADD CONSTRAINT uq_auction_received_tombstone_origin_teaser UNIQUE (origin_host, teaser_id);
ALTER TABLE auction_received_tombstone DROP CONSTRAINT IF EXISTS chk_auction_received_tombstone_id_len;
ALTER TABLE auction_received_tombstone ADD CONSTRAINT chk_auction_received_tombstone_id_len CHECK (CHAR_LENGTH(teaser_id) = 64);
ALTER TABLE auction_received_tombstone DROP CONSTRAINT IF EXISTS chk_auction_received_tombstone_kind;
ALTER TABLE auction_received_tombstone ADD CONSTRAINT chk_auction_received_tombstone_kind CHECK (kind IN ('WITHDRAWN', 'HIDDEN'));
ALTER TABLE auction_received_tombstone DROP CONSTRAINT IF EXISTS chk_auction_received_tombstone_ttl;
ALTER TABLE auction_received_tombstone ADD CONSTRAINT chk_auction_received_tombstone_ttl CHECK (expires_at > created_at);
CREATE INDEX IF NOT EXISTS idx_auction_received_tombstone_expires ON auction_received_tombstone (expires_at);

-- audit_log_entry.entity_type: V166's list copied verbatim plus AUCTION_FEDERATION_FEED (23 chars, fits VARCHAR(29)).
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
                                'AUCTION_FEDERATION_FEED'
        ));
