-- Welle V1.9.143 "Kanzel-Aufzeichnung im Begegnungsraum" -- see 71-pulpit-recording.kuml.kts. V1..V105 untouched.
-- Purely additive, H2 MODE=PostgreSQL- and Postgres-compatible (IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT,
-- no DO block, no partial index, no ON DELETE CASCADE).
--
-- A dedicated V106 rather than an edit of V104/V105: both are already on origin/master and migrated on running instances, so changing either
-- would break the Flyway checksum there.
--
--   * organization_settings: the instance switch (default OFF), the version of the operator notice the admin acknowledged when switching it
--     on, and whether the pulpit has to confirm before a recording starts (default TRUE). All three are NOT part of the generic
--     updateOrganizationSettings write-set.
--   * encounter_space.pulpit_recording_allowed: the per-room option (default OFF).
--   * agreement_text_version / agreement_text_draft: two further kinds REC_ROOM (8 chars) and REC_PULPIT (10 chars); VARCHAR(10) is unchanged.
--   * pulpit_recording_document: marks a document as a pulpit recording. It carries NO member column -- only the space and the moment --
--     so the table itself is no personal-data trail. Foreign keys without cascade: the application deletes explicitly (hard delete).
--   * audit_log_entry.entity_type: widened by PULPIT_RECORDING (16 chars, fits VARCHAR(29)).

ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS pulpit_recording_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS pulpit_recording_notice_version VARCHAR(16) NULL;
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS pulpit_recording_require_confirmation BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE encounter_space ADD COLUMN IF NOT EXISTS pulpit_recording_allowed BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE agreement_text_version DROP CONSTRAINT IF EXISTS chk_agreement_text_version_kind;
ALTER TABLE agreement_text_version ADD CONSTRAINT chk_agreement_text_version_kind
    CHECK (kind IN ('MEMBERSHIP', 'FRIEND', 'REC_ROOM', 'REC_PULPIT'));

ALTER TABLE agreement_text_draft DROP CONSTRAINT IF EXISTS chk_agreement_text_draft_kind;
ALTER TABLE agreement_text_draft ADD CONSTRAINT chk_agreement_text_draft_kind
    CHECK (kind IN ('MEMBERSHIP', 'FRIEND', 'REC_ROOM', 'REC_PULPIT'));

CREATE TABLE IF NOT EXISTS pulpit_recording_document (
    document_id UUID      NOT NULL PRIMARY KEY,
    space_id    UUID      NOT NULL,
    recorded_at TIMESTAMP NOT NULL
);

ALTER TABLE pulpit_recording_document DROP CONSTRAINT IF EXISTS fk_pulpit_recording_document_document;
ALTER TABLE pulpit_recording_document ADD CONSTRAINT fk_pulpit_recording_document_document FOREIGN KEY (document_id) REFERENCES document (id);

ALTER TABLE pulpit_recording_document DROP CONSTRAINT IF EXISTS fk_pulpit_recording_document_space;
ALTER TABLE pulpit_recording_document ADD CONSTRAINT fk_pulpit_recording_document_space FOREIGN KEY (space_id) REFERENCES encounter_space (id);

CREATE INDEX IF NOT EXISTS idx_pulpit_recording_document_space ON pulpit_recording_document (space_id);

-- ── audit_log_entry.entity_type: widen the CHECK for PULPIT_RECORDING ──
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
                                'TRAVEL_EXPENSE_SEPA_BATCH', 'CARPOOL_PARTICIPATION', 'VIDEO_OVERLAY', 'AGREEMENT_TEXT', 'PULPIT_RECORDING'
        ));
