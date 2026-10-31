-- V1.9.244 "Partnerserver P1": a federated, public event feed (pull) between directly connected Lapis servers.
-- See 79-partner-servers.kuml.kts, 11-organization-settings.kuml.kts, 14-audit-log.kuml.kts, docs/features/partner-servers.adoc
-- and docs/architecture/partner-server-feed.adoc.
-- Purely additive and repeatable: IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT. V1 and V128 are NOT edited.
--   * organization_settings.partner_list_hidden: whether OUR partner list is left out of our feed (default FALSE).
--   * organization_settings.partner_server_id: the stable, public identity of THIS server in the feed. Created lazily by the code (no
--     database-side UUID function: H2 and PostgreSQL spell it differently), written by PartnerServerStore only.
--   * partner_server: one row per directly connected partner host. UNIQUE(host) and UNIQUE(remote_server_id): a server id is bound to
--     exactly one host, so a malicious partner cannot claim the id of another one (cache poisoning).
--   * partner_event: the cache of the partners' public events. UNIQUE(partner_server_id, remote_event_id) -- the PARTNER ROW, not the
--     claimed server id, scopes the event id. Deleted together with the partner (CASCADE). Excluded from the backup.
--   * partner_blocklist: host patterns (`host` or `*.host`) that are never fetched and never shown.
--   * audit_log_entry.entity_type: widened by PARTNER_SERVER (14 chars) and PARTNER_BLOCKLIST (17 chars), VARCHAR(29) is unchanged.
--     The list is V128's, copied verbatim.

ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS partner_list_hidden BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS partner_server_id UUID NULL;

CREATE TABLE IF NOT EXISTS partner_server (
    id UUID NOT NULL PRIMARY KEY,
    host VARCHAR(253) NOT NULL,
    display_name VARCHAR(100) NULL,
    remote_server_id UUID NULL,
    remote_name VARCHAR(100) NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL,
    last_fetch_at TIMESTAMP NULL,
    last_status VARCHAR(5) NOT NULL DEFAULT 'NEVER',
    last_error VARCHAR(200) NULL,
    event_count INTEGER NOT NULL DEFAULT 0,
    consecutive_failures INTEGER NOT NULL DEFAULT 0,
    next_fetch_at TIMESTAMP NOT NULL,
    etag VARCHAR(200) NULL,
    remote_partners_json VARCHAR(65536) NULL
);

ALTER TABLE partner_server DROP CONSTRAINT IF EXISTS uq_partner_server_host;
ALTER TABLE partner_server ADD CONSTRAINT uq_partner_server_host UNIQUE (host);
ALTER TABLE partner_server DROP CONSTRAINT IF EXISTS uq_partner_server_remote_id;
ALTER TABLE partner_server ADD CONSTRAINT uq_partner_server_remote_id UNIQUE (remote_server_id);
ALTER TABLE partner_server DROP CONSTRAINT IF EXISTS chk_partner_server_status;
ALTER TABLE partner_server ADD CONSTRAINT chk_partner_server_status CHECK (last_status IN ('OK', 'ERROR', 'NEVER'));
ALTER TABLE partner_server DROP CONSTRAINT IF EXISTS chk_partner_server_host_lower;
ALTER TABLE partner_server ADD CONSTRAINT chk_partner_server_host_lower CHECK (host = LOWER(host));
ALTER TABLE partner_server DROP CONSTRAINT IF EXISTS chk_partner_server_counters;
ALTER TABLE partner_server ADD CONSTRAINT chk_partner_server_counters CHECK (consecutive_failures >= 0 AND event_count >= 0);
CREATE INDEX IF NOT EXISTS idx_partner_server_due ON partner_server (active, next_fetch_at);

CREATE TABLE IF NOT EXISTS partner_event (
    id UUID NOT NULL PRIMARY KEY,
    partner_server_id UUID NOT NULL,
    remote_server_id UUID NOT NULL,
    remote_event_id VARCHAR(100) NOT NULL,
    title VARCHAR(200) NOT NULL,
    starts_at TIMESTAMP NOT NULL,
    ends_at TIMESTAMP NOT NULL,
    time_zone VARCHAR(64) NOT NULL,
    online BOOLEAN NOT NULL,
    place VARCHAR(200) NULL,
    summary VARCHAR(300) NULL,
    recurrence_sentence VARCHAR(200) NULL,
    url VARCHAR(500) NOT NULL,
    fetched_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL
);

ALTER TABLE partner_event DROP CONSTRAINT IF EXISTS fk_partner_event_server;
ALTER TABLE partner_event ADD CONSTRAINT fk_partner_event_server FOREIGN KEY (partner_server_id) REFERENCES partner_server (id) ON DELETE CASCADE;
ALTER TABLE partner_event DROP CONSTRAINT IF EXISTS uq_partner_event_remote;
ALTER TABLE partner_event ADD CONSTRAINT uq_partner_event_remote UNIQUE (partner_server_id, remote_event_id);
ALTER TABLE partner_event DROP CONSTRAINT IF EXISTS chk_partner_event_times;
ALTER TABLE partner_event ADD CONSTRAINT chk_partner_event_times CHECK (ends_at >= starts_at);
CREATE INDEX IF NOT EXISTS idx_partner_event_expires ON partner_event (expires_at);
CREATE INDEX IF NOT EXISTS idx_partner_event_start ON partner_event (starts_at, remote_event_id);

CREATE TABLE IF NOT EXISTS partner_blocklist (
    id UUID NOT NULL PRIMARY KEY,
    pattern VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

ALTER TABLE partner_blocklist DROP CONSTRAINT IF EXISTS uq_partner_blocklist_pattern;
ALTER TABLE partner_blocklist ADD CONSTRAINT uq_partner_blocklist_pattern UNIQUE (pattern);
ALTER TABLE partner_blocklist DROP CONSTRAINT IF EXISTS chk_partner_blocklist_lower;
ALTER TABLE partner_blocklist ADD CONSTRAINT chk_partner_blocklist_lower CHECK (pattern = LOWER(pattern));

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
                                'EVENT_CAPTURE', 'TRAVEL_EXPENSE_DOCUMENT', 'TENDER', 'EVENT_REGISTRATION_EXPORT', 'ATTACHED_IMAGE',
                                'PARTG_REPORT_SHEET', 'PARTG_REPORT_POSITION', 'LUPABB_SYNC_RUN', 'LUPABB_SYNC_CONFIG', 'MEETING_EVENT_LINK',
                                'MEETING_SESSION', 'DIRECT_MESSAGE_GROUP_SEND', 'PARTNER_SERVER', 'PARTNER_BLOCKLIST'
        ));
