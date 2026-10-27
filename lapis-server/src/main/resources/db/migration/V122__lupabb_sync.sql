-- V1.9.226 "LupaBB-Stammdatenabgleich": two columns on member, four tables, two widened CHECKs. See 77-lupabb-sync.kuml.kts and
-- docs/features/lupabb-sync.adoc.
-- Purely additive and repeatable: CREATE TABLE IF NOT EXISTS, ADD COLUMN IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT,
-- no partial index, no ON DELETE CASCADE, H2 MODE=PostgreSQL- and PostgreSQL-compatible. V121 is NOT edited.
-- While this wave is unreleased V122 may still be corrected in place on the feature branch; after the release every correction is a NEW migration.
--
--   * member.lupabb_person_number: the person number of the linked LupaBB record. A plain unique index is enough: H2 and PostgreSQL both allow
--     several NULLs. external_reference (CSV import) stays unchanged.
--   * member.lupabb_missing_since: the day the last sync did not find the member in LupaBB any more (a hint; nobody is deleted or deactivated).
--   * member_lupabb_profile: the LupaBB-only person data (names, title, company, telephone numbers, address supplement, post box).
--   * lupabb_sync_config: ONE row (id = 1): the sealed credentials plus the run guard (running_since / run_claim / last_fetch_started_at).
--   * lupabb_sync_run: the log of the applied runs.
--   * lupabb_sync_suppression: HMAC-SHA256 of the person number of every LupaBB person erased under Art. 17 GDPR.
--   * audit_log_entry.entity_type: widened by LUPABB_SYNC_RUN (15 chars) and LUPABB_SYNC_CONFIG (18 chars); VARCHAR(29) is unchanged. The list is
--     V121's, copied verbatim.
--   * member_status_history.source: widened by LUPABB_SYNC (a runtime row, so it carries recorded_at like LIVE / IMPORT / SEED / KEYCLOAK_JIT).

ALTER TABLE member ADD COLUMN IF NOT EXISTS lupabb_person_number BIGINT NULL;
ALTER TABLE member ADD COLUMN IF NOT EXISTS lupabb_missing_since DATE NULL;

ALTER TABLE member DROP CONSTRAINT IF EXISTS chk_lupabb_person_number_positive;
ALTER TABLE member ADD CONSTRAINT chk_lupabb_person_number_positive CHECK (lupabb_person_number IS NULL OR lupabb_person_number > 0);

CREATE UNIQUE INDEX IF NOT EXISTS uq_member_lupabb_person_number ON member (lupabb_person_number);

CREATE TABLE IF NOT EXISTS member_lupabb_profile (
    member_id           UUID         NOT NULL PRIMARY KEY REFERENCES member (id),
    given_name          VARCHAR(100) NULL,
    family_name         VARCHAR(100) NULL,
    title               VARCHAR(50)  NULL,
    name_affix          VARCHAR(50)  NULL,
    company             VARCHAR(200) NULL,
    person_type         VARCHAR(7)   NULL,
    phone               VARCHAR(50)  NULL,
    mobile              VARCHAR(50)  NULL,
    address_supplement  VARCHAR(200) NULL,
    po_box              VARCHAR(50)  NULL,
    last_synced_at      TIMESTAMP    NOT NULL,
    last_touched_fields VARCHAR(400) NULL
);

ALTER TABLE member_lupabb_profile DROP CONSTRAINT IF EXISTS chk_member_lupabb_profile_person_type;
ALTER TABLE member_lupabb_profile ADD CONSTRAINT chk_member_lupabb_profile_person_type CHECK (person_type IS NULL OR person_type IN ('NATURAL', 'LEGAL'));

CREATE TABLE IF NOT EXISTS lupabb_sync_config (
    id                     INTEGER     NOT NULL PRIMARY KEY,
    credentials_ciphertext TEXT        NOT NULL,
    api_key_transport      VARCHAR(6)  NOT NULL,
    set_at                 TIMESTAMP   NOT NULL,
    set_by                 UUID        NOT NULL REFERENCES member (id),
    running_since          TIMESTAMP   NULL,
    run_claim              UUID        NULL,
    last_fetch_started_at  TIMESTAMP   NULL
);

ALTER TABLE lupabb_sync_config DROP CONSTRAINT IF EXISTS chk_lupabb_sync_config_singleton;
ALTER TABLE lupabb_sync_config ADD CONSTRAINT chk_lupabb_sync_config_singleton CHECK (id = 1);

ALTER TABLE lupabb_sync_config DROP CONSTRAINT IF EXISTS chk_lupabb_sync_config_transport;
ALTER TABLE lupabb_sync_config ADD CONSTRAINT chk_lupabb_sync_config_transport CHECK (api_key_transport IN ('HEADER', 'QUERY'));

CREATE INDEX IF NOT EXISTS ix_lupabb_sync_config_set_by ON lupabb_sync_config (set_by);

CREATE TABLE IF NOT EXISTS lupabb_sync_run (
    id               UUID        NOT NULL PRIMARY KEY,
    started_by       UUID        NOT NULL REFERENCES member (id),
    started_at       TIMESTAMP   NOT NULL,
    finished_at      TIMESTAMP   NOT NULL,
    created_count    INTEGER     NOT NULL,
    updated_count    INTEGER     NOT NULL,
    unchanged_count  INTEGER     NOT NULL,
    skipped_count    INTEGER     NOT NULL,
    ambiguous_count  INTEGER     NOT NULL,
    error_count      INTEGER     NOT NULL,
    missing_count    INTEGER     NOT NULL,
    data_sha256      VARCHAR(64) NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_lupabb_sync_run_finished ON lupabb_sync_run (finished_at, id);
CREATE INDEX IF NOT EXISTS ix_lupabb_sync_run_started_by ON lupabb_sync_run (started_by);

CREATE TABLE IF NOT EXISTS lupabb_sync_suppression (
    person_number_hmac VARCHAR(64) NOT NULL PRIMARY KEY,
    created_at         TIMESTAMP   NOT NULL
);

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
                                'TRAVEL_EXPENSE_SEPA_BATCH', 'CARPOOL_PARTICIPATION', 'VIDEO_OVERLAY', 'AGREEMENT_TEXT', 'PULPIT_RECORDING',
                                'EVENT_CAPTURE', 'TRAVEL_EXPENSE_DOCUMENT', 'TENDER', 'EVENT_REGISTRATION_EXPORT', 'ATTACHED_IMAGE',
                                'PARTG_REPORT_SHEET', 'PARTG_REPORT_POSITION', 'LUPABB_SYNC_RUN', 'LUPABB_SYNC_CONFIG'
        ));

ALTER TABLE member_status_history DROP CONSTRAINT IF EXISTS chk_member_status_history_source;
ALTER TABLE member_status_history ADD CONSTRAINT chk_member_status_history_source
    CHECK (source IN ('LIVE', 'IMPORT', 'SEED', 'KEYCLOAK_JIT', 'LUPABB_SYNC', 'BACKFILL_AUDIT', 'BACKFILL_RECORD', 'BACKFILL_ASSUMED'));

ALTER TABLE member_status_history DROP CONSTRAINT IF EXISTS chk_member_status_history_recorded;
ALTER TABLE member_status_history ADD CONSTRAINT chk_member_status_history_recorded CHECK (
    (source IN ('BACKFILL_AUDIT', 'BACKFILL_RECORD', 'BACKFILL_ASSUMED') AND recorded_at IS NULL)
    OR
    (source IN ('LIVE', 'IMPORT', 'SEED', 'KEYCLOAK_JIT', 'LUPABB_SYNC') AND recorded_at IS NOT NULL)
);
