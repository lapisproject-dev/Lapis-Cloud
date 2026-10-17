-- Welle V1.9.156 "Aufzeichnung und Live-Uebertragung in Veranstaltungsraeumen" -- see 73-event-capture.kuml.kts. V1..V109 untouched.
-- A dedicated V110 rather than an edit of V109: V109 is on origin/master and may be migrated on staging/forks (a changed file breaks the Flyway
-- checksum there). Purely additive, H2 MODE=PostgreSQL- and Postgres-compatible (ADD COLUMN IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before every
-- ADD CONSTRAINT, no DO block, no partial index, NO ON DELETE CASCADE: the application deletes explicitly).
--
--   * organization_settings: the instance switch (default OFF) and the version of the operator notice acknowledged when switching it on. Both are
--     NOT part of the generic updateOrganizationSettings write-set.
--   * event.capture_allowed: the release of ONE event (default OFF); frozen by the application while the room is active.
--   * agreement_text_version / agreement_text_draft: the kind EVENT_REC (9 chars); VARCHAR(10) is unchanged, only the two CHECKs grow.
--   * event_capture_acknowledgment: proof that a participant acknowledged version X of the consent for event Z on day Y. It carries NO name, NO room
--     and NO clock time (Art. 9 / no attendance trace): exactly one of member_id (a person with an account) or guest_key (SHA-256 hex of the LiveKit
--     identity of a person WITHOUT an account) is set.
--   * audit_log_entry.entity_type: widened by EVENT_CAPTURE (13 chars, fits VARCHAR(29)).

ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS event_capture_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS event_capture_notice_version VARCHAR(16) NULL;

ALTER TABLE event ADD COLUMN IF NOT EXISTS capture_allowed BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE agreement_text_version DROP CONSTRAINT IF EXISTS chk_agreement_text_version_kind;
ALTER TABLE agreement_text_version ADD CONSTRAINT chk_agreement_text_version_kind
    CHECK (kind IN ('MEMBERSHIP', 'FRIEND', 'REC_ROOM', 'REC_PULPIT', 'FINDER', 'ENC_CHURCH', 'ENC_ASSEM', 'EVENT_REC'));

ALTER TABLE agreement_text_draft DROP CONSTRAINT IF EXISTS chk_agreement_text_draft_kind;
ALTER TABLE agreement_text_draft ADD CONSTRAINT chk_agreement_text_draft_kind
    CHECK (kind IN ('MEMBERSHIP', 'FRIEND', 'REC_ROOM', 'REC_PULPIT', 'FINDER', 'ENC_CHURCH', 'ENC_ASSEM', 'EVENT_REC'));

CREATE TABLE IF NOT EXISTS event_capture_acknowledgment (
    id              UUID        NOT NULL PRIMARY KEY,
    event_id        UUID        NOT NULL,
    member_id       UUID        NULL,
    guest_key       VARCHAR(64) NULL,
    consent_version VARCHAR(50) NOT NULL,
    consent_sha256  VARCHAR(64) NOT NULL,
    acknowledged_on DATE        NOT NULL
);

ALTER TABLE event_capture_acknowledgment DROP CONSTRAINT IF EXISTS chk_event_capture_ack_subject;
ALTER TABLE event_capture_acknowledgment ADD CONSTRAINT chk_event_capture_ack_subject
    CHECK ((member_id IS NULL AND guest_key IS NOT NULL) OR (member_id IS NOT NULL AND guest_key IS NULL));

ALTER TABLE event_capture_acknowledgment DROP CONSTRAINT IF EXISTS fk_event_capture_ack_event;
ALTER TABLE event_capture_acknowledgment ADD CONSTRAINT fk_event_capture_ack_event FOREIGN KEY (event_id) REFERENCES event (id);

ALTER TABLE event_capture_acknowledgment DROP CONSTRAINT IF EXISTS fk_event_capture_ack_member;
ALTER TABLE event_capture_acknowledgment ADD CONSTRAINT fk_event_capture_ack_member FOREIGN KEY (member_id) REFERENCES member (id);

ALTER TABLE event_capture_acknowledgment DROP CONSTRAINT IF EXISTS uq_event_capture_ack_member;
ALTER TABLE event_capture_acknowledgment ADD CONSTRAINT uq_event_capture_ack_member UNIQUE (event_id, member_id, consent_version);

ALTER TABLE event_capture_acknowledgment DROP CONSTRAINT IF EXISTS uq_event_capture_ack_guest;
ALTER TABLE event_capture_acknowledgment ADD CONSTRAINT uq_event_capture_ack_guest UNIQUE (event_id, guest_key, consent_version);

CREATE INDEX IF NOT EXISTS idx_event_capture_ack_event ON event_capture_acknowledgment (event_id);

-- ── audit_log_entry.entity_type: widen the CHECK for EVENT_CAPTURE ──
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
                                'EVENT_CAPTURE'
        ));
