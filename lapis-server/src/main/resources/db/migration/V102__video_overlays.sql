-- Welle V1.9.129 "Video-Overlays (Logos, Name, Rahmen) im eigenen Kamerabild" -- see 69-video-overlay.kuml.kts and
-- 11-organization-settings.kuml.kts. V1..V101 untouched.
-- Purely additive, H2 MODE=PostgreSQL- and Postgres-compatible (IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT,
-- no DO block, no partial index, no ON DELETE CASCADE).
--
-- A dedicated V102 rather than an edit of V101 in place: V101 (conference_room_reactions_enabled) is part of the released v0.35.0, so
-- changing it would break the Flyway checksum on deployed databases (same reasoning as the V95 header).
--
--   * organization_settings.video_overlays_offered: FALSE (default, every existing instance keeps today's behaviour) or TRUE (an ADMIN
--     offers the overlay panel to members). Set only through IOrganizationSettingsService.updateVideoOverlaysOffered.
--   * video_overlay_asset: the admin's library of logos and frames. One row per stored, re-encoded image; the file lives on disk under
--     `<root>/<id>.<format>`. NO member FK on purpose: who uploaded something is in the audit entry, the table holds no personal data.

ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS video_overlays_offered BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS video_overlay_asset (
    id           UUID         NOT NULL PRIMARY KEY,
    kind         VARCHAR(8)   NOT NULL,
    name         VARCHAR(60)  NOT NULL,
    format       VARCHAR(4)   NOT NULL,
    width        INTEGER      NOT NULL,
    height       INTEGER      NOT NULL,
    size_bytes   BIGINT       NOT NULL,
    sha256       VARCHAR(64)  NOT NULL,
    access_token VARCHAR(64)  NOT NULL,
    sort_order   INTEGER      NOT NULL,
    created_at   TIMESTAMP    NOT NULL
);

ALTER TABLE video_overlay_asset DROP CONSTRAINT IF EXISTS chk_video_overlay_asset_kind;
ALTER TABLE video_overlay_asset ADD CONSTRAINT chk_video_overlay_asset_kind CHECK (kind IN ('LOGO', 'FRAME'));

ALTER TABLE video_overlay_asset DROP CONSTRAINT IF EXISTS chk_video_overlay_asset_format;
ALTER TABLE video_overlay_asset ADD CONSTRAINT chk_video_overlay_asset_format CHECK (format IN ('png', 'jpg'));

ALTER TABLE video_overlay_asset DROP CONSTRAINT IF EXISTS chk_video_overlay_asset_name;
ALTER TABLE video_overlay_asset ADD CONSTRAINT chk_video_overlay_asset_name CHECK (CHAR_LENGTH(name) >= 1 AND CHAR_LENGTH(name) <= 60);

ALTER TABLE video_overlay_asset DROP CONSTRAINT IF EXISTS uq_video_overlay_asset_token;
ALTER TABLE video_overlay_asset ADD CONSTRAINT uq_video_overlay_asset_token UNIQUE (access_token);

CREATE INDEX IF NOT EXISTS idx_video_overlay_asset_sort ON video_overlay_asset (sort_order);

-- ── audit_log_entry.entity_type: widen the CHECK for VIDEO_OVERLAY (13 chars, fits VARCHAR(29)) ──
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
                                'COMMITTEE_MEMBERSHIP', 'ARTICLE_IMPORT', 'ARTICLE_BULK_APPROVED',
                                'ARTICLE_IMPORT_IMAGES_APPLIED', 'MAILING_LIST', 'MAILING_MESSAGE', 'COMMITTEE',
                                'TRAVEL_EXPENSE_SEPA_BATCH', 'CARPOOL_PARTICIPATION', 'VIDEO_OVERLAY'
        ));
