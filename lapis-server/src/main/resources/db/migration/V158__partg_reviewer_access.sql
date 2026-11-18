-- V1.9.310 "Rechenschaftsbericht (PartG), R6b": read access for the auditor (Pruefer) to KEPT versions of the report. See 76-partg-report.kuml.kts and
-- docs/features/partg-report.adoc. V1..V157 untouched (V157 is a different feature, already on master). Purely additive and repeatable, H2
-- MODE=PostgreSQL- and PostgreSQL-compatible: CREATE ... IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT, no partial index, no
-- ON DELETE CASCADE, NO backfill. While this wave is unreleased V158 may still be corrected in place on the feature branch; after the release every
-- correction is a NEW migration.
--
--   * partg_reviewer_invitation: one invitation of the board for an auditor WITHOUT an account. Only HASHES are stored: the SHA-256 of the link token
--     (256 bit random) and the SHA-256 of the current 6-digit code (bound to the invitation id). The E-mail address is personal data of a third party;
--     it is set to NULL 90 days after the invitation ended and by the board's erasure routine.
--   * partg_reviewer_invitation_version: the scope of an invitation, a fixed set of kept versions (whole-party versions only, checked by the service).
--     No ON DELETE CASCADE: the deletion routine of old versions detaches a version here first.
--   * partg_reviewer_session: a server-side session of the auditor (hash of the cookie token, idle and absolute expiry).
--   * partg_reviewer_event: the usage list the board sees (kind, version id, format, time). No content, no IP, no actor.
--
-- ROLLBACK HAZARD: 0.55.x and every build without V158 reject a database with V158 applied (Flyway validation). Roll back only by restoring the
-- backup taken before V158. Backups taken before V158 are rejected on restore by the schema checksum (BackupManifest.schemaChecksum), by design.

CREATE TABLE IF NOT EXISTS partg_reviewer_invitation (
    id                  UUID          NOT NULL PRIMARY KEY,
    report_year         INTEGER       NOT NULL,
    invitee_email       VARCHAR(254)  NULL,
    link_token_hash     VARCHAR(64)   NOT NULL,
    code_hash           VARCHAR(64)   NULL,
    code_expires_at     TIMESTAMP     NULL,
    code_attempts       INTEGER       NOT NULL DEFAULT 0,
    codes_burned        INTEGER       NOT NULL DEFAULT 0,
    codes_sent_total    INTEGER       NOT NULL DEFAULT 0,
    codes_window_start  TIMESTAMP     NULL,
    codes_window_count  INTEGER       NOT NULL DEFAULT 0,
    locked_at           TIMESTAMP     NULL,
    expires_at          TIMESTAMP     NOT NULL,
    revoked_at          TIMESTAMP     NULL,
    revoked_by          UUID          NULL REFERENCES member (id),
    end_reason          VARCHAR(14)   NULL,
    created_by          UUID          NOT NULL REFERENCES member (id),
    created_at          TIMESTAMP     NOT NULL,
    last_used_at        TIMESTAMP     NULL
);

ALTER TABLE partg_reviewer_invitation DROP CONSTRAINT IF EXISTS chk_partg_reviewer_invitation_year;
ALTER TABLE partg_reviewer_invitation ADD CONSTRAINT chk_partg_reviewer_invitation_year CHECK (report_year BETWEEN 2000 AND 2100);
ALTER TABLE partg_reviewer_invitation DROP CONSTRAINT IF EXISTS chk_partg_reviewer_invitation_token_hash;
ALTER TABLE partg_reviewer_invitation ADD CONSTRAINT chk_partg_reviewer_invitation_token_hash CHECK (CHAR_LENGTH(link_token_hash) = 64);
ALTER TABLE partg_reviewer_invitation DROP CONSTRAINT IF EXISTS chk_partg_reviewer_invitation_code_hash;
ALTER TABLE partg_reviewer_invitation ADD CONSTRAINT chk_partg_reviewer_invitation_code_hash CHECK (
    (code_hash IS NULL AND code_expires_at IS NULL) OR (code_hash IS NOT NULL AND code_expires_at IS NOT NULL AND CHAR_LENGTH(code_hash) = 64)
);
ALTER TABLE partg_reviewer_invitation DROP CONSTRAINT IF EXISTS chk_partg_reviewer_invitation_attempts;
ALTER TABLE partg_reviewer_invitation ADD CONSTRAINT chk_partg_reviewer_invitation_attempts CHECK (code_attempts BETWEEN 0 AND 5);
ALTER TABLE partg_reviewer_invitation DROP CONSTRAINT IF EXISTS chk_partg_reviewer_invitation_burned;
ALTER TABLE partg_reviewer_invitation ADD CONSTRAINT chk_partg_reviewer_invitation_burned CHECK (codes_burned BETWEEN 0 AND 3);
ALTER TABLE partg_reviewer_invitation DROP CONSTRAINT IF EXISTS chk_partg_reviewer_invitation_sent_total;
ALTER TABLE partg_reviewer_invitation ADD CONSTRAINT chk_partg_reviewer_invitation_sent_total CHECK (codes_sent_total BETWEEN 0 AND 10);
ALTER TABLE partg_reviewer_invitation DROP CONSTRAINT IF EXISTS chk_partg_reviewer_invitation_window_count;
ALTER TABLE partg_reviewer_invitation ADD CONSTRAINT chk_partg_reviewer_invitation_window_count CHECK (codes_window_count BETWEEN 0 AND 3);
ALTER TABLE partg_reviewer_invitation DROP CONSTRAINT IF EXISTS chk_partg_reviewer_invitation_expiry;
ALTER TABLE partg_reviewer_invitation ADD CONSTRAINT chk_partg_reviewer_invitation_expiry CHECK (expires_at > created_at);
ALTER TABLE partg_reviewer_invitation DROP CONSTRAINT IF EXISTS chk_partg_reviewer_invitation_end_reason;
ALTER TABLE partg_reviewer_invitation ADD CONSTRAINT chk_partg_reviewer_invitation_end_reason CHECK (
    (revoked_at IS NULL AND end_reason IS NULL) OR (revoked_at IS NOT NULL AND end_reason IS NOT NULL AND end_reason IN ('REVOKED', 'SCOPE_DELETED', 'ERASED'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_partg_reviewer_invitation_token ON partg_reviewer_invitation (link_token_hash);
CREATE INDEX IF NOT EXISTS ix_partg_reviewer_invitation_year ON partg_reviewer_invitation (report_year);
CREATE INDEX IF NOT EXISTS ix_partg_reviewer_invitation_created_by ON partg_reviewer_invitation (created_by);
CREATE INDEX IF NOT EXISTS ix_partg_reviewer_invitation_revoked_by ON partg_reviewer_invitation (revoked_by);

CREATE TABLE IF NOT EXISTS partg_reviewer_invitation_version (
    invitation_id  UUID  NOT NULL,
    version_id     UUID  NOT NULL
);

ALTER TABLE partg_reviewer_invitation_version DROP CONSTRAINT IF EXISTS pk_partg_reviewer_invitation_version;
ALTER TABLE partg_reviewer_invitation_version ADD CONSTRAINT pk_partg_reviewer_invitation_version PRIMARY KEY (invitation_id, version_id);
ALTER TABLE partg_reviewer_invitation_version DROP CONSTRAINT IF EXISTS fk_partg_reviewer_invitation_version_invitation;
ALTER TABLE partg_reviewer_invitation_version ADD CONSTRAINT fk_partg_reviewer_invitation_version_invitation
    FOREIGN KEY (invitation_id) REFERENCES partg_reviewer_invitation (id);
ALTER TABLE partg_reviewer_invitation_version DROP CONSTRAINT IF EXISTS fk_partg_reviewer_invitation_version_version;
ALTER TABLE partg_reviewer_invitation_version ADD CONSTRAINT fk_partg_reviewer_invitation_version_version
    FOREIGN KEY (version_id) REFERENCES partg_report_version (id);
CREATE INDEX IF NOT EXISTS ix_partg_reviewer_invitation_version_version ON partg_reviewer_invitation_version (version_id);

CREATE TABLE IF NOT EXISTS partg_reviewer_session (
    id                    UUID         NOT NULL PRIMARY KEY,
    token_hash            VARCHAR(64)  NOT NULL,
    invitation_id         UUID         NOT NULL REFERENCES partg_reviewer_invitation (id),
    created_at            TIMESTAMP    NOT NULL,
    last_seen_at          TIMESTAMP    NOT NULL,
    idle_expires_at       TIMESTAMP    NOT NULL,
    absolute_expires_at   TIMESTAMP    NOT NULL
);

ALTER TABLE partg_reviewer_session DROP CONSTRAINT IF EXISTS chk_partg_reviewer_session_token_hash;
ALTER TABLE partg_reviewer_session ADD CONSTRAINT chk_partg_reviewer_session_token_hash CHECK (CHAR_LENGTH(token_hash) = 64);
CREATE UNIQUE INDEX IF NOT EXISTS uq_partg_reviewer_session_token ON partg_reviewer_session (token_hash);
CREATE INDEX IF NOT EXISTS ix_partg_reviewer_session_invitation ON partg_reviewer_session (invitation_id);

CREATE TABLE IF NOT EXISTS partg_reviewer_event (
    id             UUID         NOT NULL PRIMARY KEY,
    invitation_id  UUID         NOT NULL REFERENCES partg_reviewer_invitation (id),
    kind           VARCHAR(20)  NOT NULL,
    version_id     UUID         NULL,
    format         VARCHAR(4)   NULL,
    occurred_at    TIMESTAMP    NOT NULL
);

ALTER TABLE partg_reviewer_event DROP CONSTRAINT IF EXISTS chk_partg_reviewer_event_kind;
ALTER TABLE partg_reviewer_event ADD CONSTRAINT chk_partg_reviewer_event_kind CHECK (kind IN (
    'INVITATION_CREATED', 'INVITATION_REVOKED', 'CODE_SENT', 'CODE_FAILED', 'INVITATION_LOCKED', 'SESSION_OPENED', 'SESSION_ENDED',
    'DOWNLOAD', 'DOCUMENT_VIEWED', 'COMPARISON_VIEWED'
));
ALTER TABLE partg_reviewer_event DROP CONSTRAINT IF EXISTS chk_partg_reviewer_event_format;
ALTER TABLE partg_reviewer_event ADD CONSTRAINT chk_partg_reviewer_event_format CHECK (format IS NULL OR format IN ('PDF', 'XLSX', 'JSON'));
CREATE INDEX IF NOT EXISTS ix_partg_reviewer_event_invitation ON partg_reviewer_event (invitation_id, occurred_at);

-- audit_log_entry.entity_type + PARTG_REVIEWER_ACCESS (21 chars, fits VARCHAR(29)); the list is V153's, copied verbatim, plus the new literal.
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
                                'PARTG_REVIEWER_ACCESS'
        ));
