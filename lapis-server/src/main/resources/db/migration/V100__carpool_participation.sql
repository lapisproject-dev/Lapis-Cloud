-- Welle V1.9.125 "Mitfahrten in der Mitfahrerzentrale" -- passengers on carpool offers. V1..V99 untouched.
-- Purely additive, idempotent, H2 MODE=PostgreSQL- and Postgres-compatible (CREATE TABLE/INDEX IF NOT EXISTS, DROP CONSTRAINT IF EXISTS
-- before every ADD CONSTRAINT, no DO block, no partial unique index) -- same discipline as V58.
--
-- A dedicated V100 rather than an edit of V99 (a foreign wave after v0.33.0 that may already run on staging -> Flyway checksum) or of V58
-- (released).
--
--   * carpool_participation: ONE row per (posting, person). A new request after DECLINED/WITHDRAWN revives the same row (never a second one).
--       initiator DRIVER    = the driver invited the person; the person confirms/declines.
--       initiator PASSENGER = the person asked to ride along; the driver confirms/declines.
--     decided_at is NULL exactly while the status is OPEN (basis of the 7-day display rule and the 24-hour re-request lock).
--     posting_id is ON DELETE CASCADE (a deleted/expired posting takes its participations with it); person_id has no cascade: members are
--     anonymised, not deleted -- the GDPR erasure deletes the rows explicitly.
--   * carpool_posting.meeting_note: meeting point and time, visible only to the driver and to a CONFIRMED passenger.
--   * audit_log_entry.entity_type: the CHECK is widened for CARPOOL_PARTICIPATION (21 chars, fits VARCHAR(29)).

CREATE TABLE IF NOT EXISTS carpool_participation (
    id          UUID         NOT NULL PRIMARY KEY,
    posting_id  UUID         NOT NULL,
    person_id   UUID         NOT NULL,
    initiator   VARCHAR(16)  NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL,
    decided_at  TIMESTAMP    NULL
);

ALTER TABLE carpool_participation DROP CONSTRAINT IF EXISTS fk_carpool_participation_posting;
ALTER TABLE carpool_participation ADD CONSTRAINT fk_carpool_participation_posting
    FOREIGN KEY (posting_id) REFERENCES carpool_posting(id) ON DELETE CASCADE;

ALTER TABLE carpool_participation DROP CONSTRAINT IF EXISTS fk_carpool_participation_person;
ALTER TABLE carpool_participation ADD CONSTRAINT fk_carpool_participation_person
    FOREIGN KEY (person_id) REFERENCES member(id);

ALTER TABLE carpool_participation DROP CONSTRAINT IF EXISTS chk_carpool_participation_initiator;
ALTER TABLE carpool_participation ADD CONSTRAINT chk_carpool_participation_initiator
    CHECK (initiator IN ('DRIVER', 'PASSENGER'));

ALTER TABLE carpool_participation DROP CONSTRAINT IF EXISTS chk_carpool_participation_status;
ALTER TABLE carpool_participation ADD CONSTRAINT chk_carpool_participation_status
    CHECK (status IN ('OPEN', 'CONFIRMED', 'DECLINED', 'WITHDRAWN'));

ALTER TABLE carpool_participation DROP CONSTRAINT IF EXISTS chk_carpool_participation_decided;
ALTER TABLE carpool_participation ADD CONSTRAINT chk_carpool_participation_decided
    CHECK ((status = 'OPEN' AND decided_at IS NULL) OR (status <> 'OPEN' AND decided_at IS NOT NULL));

CREATE UNIQUE INDEX IF NOT EXISTS uq_carpool_participation_posting_person ON carpool_participation(posting_id, person_id);
CREATE INDEX IF NOT EXISTS idx_carpool_participation_person_status ON carpool_participation(person_id, status);

ALTER TABLE carpool_posting ADD COLUMN IF NOT EXISTS meeting_note VARCHAR(200) NULL;

-- ── audit_log_entry.entity_type: widen the CHECK for CARPOOL_PARTICIPATION ──
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
                                'TRAVEL_EXPENSE_SEPA_BATCH', 'CARPOOL_PARTICIPATION'
        ));
