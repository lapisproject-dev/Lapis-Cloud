-- Welle V1.9.61 -- Begegnungsraum (Welle B1, Server und Datenschutz). V1..V72 untouched. Purely additive, H2 MODE=PostgreSQL- and
-- Postgres-compatible (CREATE ... IF NOT EXISTS, ADD COLUMN IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT, no DO
-- block, no partial or expression indexes, no gen_random_uuid()/RANDOM_UUID()).
--
-- OPERATOR NOTE: V1__baseline.sql's still-unnamed inline CHECK on audit_log_entry.entity_type is widened IN PLACE by
-- 'ENCOUNTER_SPACE' (the exact precedent V59/V60/V65 document: on H2 -- tests, dev -- that unnamed constraint cannot be dropped by
-- name and governs every fresh database, while on PostgreSQL it is dropped by V6 and replaced by the named constraint below).
-- Flyway therefore sees a checksum mismatch for the already-applied V1 on an existing instance: run
-- `./gradlew :lapis-server:flywayRepair` BEFORE deploying this version on PdV, ELB and Staging, and take a backup first.
--
-- An encounter space ("Begegnungsraum") is a permanent room configuration (title, theme, who may enter) whose SESSIONS are ordinary
-- conference_room rows carrying encounter_space_id. A session is "open" while its conference_room row has ended_at IS NULL; at most one
-- session per space is open at a time, enforced by a row lock on encounter_space (no opened_at / current_room_id column on purpose: that
-- would need a circular foreign key, and the opening time and opener are conference_room.created_at / created_by_member_id).
--
-- Art. 9 GDPR (religious belief can be inferred from attending a church service): NO lasting trace of who attended may remain.
--   * encounter_space_role holds the OFFICE configuration only (who is pulpit / steward) -- never the congregation.
--   * encounter_consent_acknowledgment is the accountability proof of a non-member's explicit consent (Art. 7(1)). It deliberately has NO
--     room / space reference and NO timestamp (only a date), so it cannot be turned into an attendance record.
--   * conference_participation rows of an encounter session are DELETED when the member leaves and when the session ends (never kept with
--     left_at); conference_guest_consent_acknowledgment is never written for an encounter session.
--   * The audit log only receives configuration changes and open/close with the office holder as actor, never entering/leaving/removing.

CREATE TABLE IF NOT EXISTS encounter_space (
    id                    UUID           NOT NULL PRIMARY KEY,
    title                 VARCHAR(200)   NOT NULL,
    description           VARCHAR(1000)  NOT NULL,
    theme_key             VARCHAR(16)    NOT NULL,
    mode                  VARCHAR(16)    NOT NULL,
    guest_policy          VARCHAR(18)    NOT NULL,
    max_participants      INT            NULL,
    closed_notice         VARCHAR(200)   NULL,
    created_at            TIMESTAMP      NOT NULL,
    created_by_member_id  UUID           NOT NULL,
    updated_at            TIMESTAMP      NULL,
    archived_at           TIMESTAMP      NULL
);

ALTER TABLE encounter_space DROP CONSTRAINT IF EXISTS fk_encounter_space_created_by;
ALTER TABLE encounter_space ADD CONSTRAINT fk_encounter_space_created_by
    FOREIGN KEY (created_by_member_id) REFERENCES member(id);

ALTER TABLE encounter_space DROP CONSTRAINT IF EXISTS chk_encounter_space_theme;
ALTER TABLE encounter_space ADD CONSTRAINT chk_encounter_space_theme CHECK (theme_key IN ('CHURCH'));

ALTER TABLE encounter_space DROP CONSTRAINT IF EXISTS chk_encounter_space_mode;
ALTER TABLE encounter_space ADD CONSTRAINT chk_encounter_space_mode CHECK (mode IN ('SERVICE'));

ALTER TABLE encounter_space DROP CONSTRAINT IF EXISTS chk_encounter_space_guest_policy;
ALTER TABLE encounter_space ADD CONSTRAINT chk_encounter_space_guest_policy
    CHECK (guest_policy IN ('MEMBERS_ONLY', 'MEMBERS_AND_GUESTS'));

-- The explicit IS NULL on the first branch (a CHECK that evaluates to UNKNOWN passes).
ALTER TABLE encounter_space DROP CONSTRAINT IF EXISTS chk_encounter_space_max_participants;
ALTER TABLE encounter_space ADD CONSTRAINT chk_encounter_space_max_participants
    CHECK (max_participants IS NULL OR max_participants >= 2);

CREATE INDEX IF NOT EXISTS idx_encounter_space_archived_at ON encounter_space (archived_at);

CREATE TABLE IF NOT EXISTS encounter_space_role (
    space_id   UUID         NOT NULL,
    member_id  UUID         NOT NULL,
    role       VARCHAR(8)   NOT NULL,
    CONSTRAINT pk_encounter_space_role PRIMARY KEY (space_id, member_id)
);

ALTER TABLE encounter_space_role DROP CONSTRAINT IF EXISTS fk_encounter_space_role_space;
ALTER TABLE encounter_space_role ADD CONSTRAINT fk_encounter_space_role_space
    FOREIGN KEY (space_id) REFERENCES encounter_space(id);

ALTER TABLE encounter_space_role DROP CONSTRAINT IF EXISTS fk_encounter_space_role_member;
ALTER TABLE encounter_space_role ADD CONSTRAINT fk_encounter_space_role_member
    FOREIGN KEY (member_id) REFERENCES member(id);

ALTER TABLE encounter_space_role DROP CONSTRAINT IF EXISTS chk_encounter_space_role_role;
ALTER TABLE encounter_space_role ADD CONSTRAINT chk_encounter_space_role_role CHECK (role IN ('PULPIT', 'STEWARD'));

CREATE INDEX IF NOT EXISTS idx_encounter_space_role_member ON encounter_space_role (member_id);

-- Accountability proof of a non-member's explicit consent (Art. 9(2)(a), Art. 7(1) GDPR). One row per (member, disclaimer version); no
-- room/space reference and no timestamp -- only the DATE -- so the table cannot reveal WHICH service was attended or WHEN.
CREATE TABLE IF NOT EXISTS encounter_consent_acknowledgment (
    member_id        UUID         NOT NULL,
    consent_version  VARCHAR(50)  NOT NULL,
    consent_sha256   VARCHAR(64)  NOT NULL,
    acknowledged_on  DATE         NOT NULL,
    CONSTRAINT pk_encounter_consent_ack PRIMARY KEY (member_id, consent_version)
);

ALTER TABLE encounter_consent_acknowledgment DROP CONSTRAINT IF EXISTS fk_encounter_consent_ack_member;
ALTER TABLE encounter_consent_acknowledgment ADD CONSTRAINT fk_encounter_consent_ack_member
    FOREIGN KEY (member_id) REFERENCES member(id);

-- A conference_room row of an encounter session points at its space; NULL for every ordinary conference room (all existing rows).
ALTER TABLE conference_room ADD COLUMN IF NOT EXISTS encounter_space_id UUID NULL;

ALTER TABLE conference_room DROP CONSTRAINT IF EXISTS fk_conference_room_encounter_space;
ALTER TABLE conference_room ADD CONSTRAINT fk_conference_room_encounter_space
    FOREIGN KEY (encounter_space_id) REFERENCES encounter_space(id);

CREATE INDEX IF NOT EXISTS idx_conference_room_encounter_space ON conference_room (encounter_space_id);

-- One new AuditEntityType literal (ENCOUNTER_SPACE), appended LAST -- same DROP/ADD dance on the NAMED
-- chk_audit_log_entry_entity_type constraint every prior wave's migration establishes (list copied from V65__polls.sql, plus
-- 'ENCOUNTER_SPACE').
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
                                'MEMBERSHIP_TIER', 'POLL', 'ENCOUNTER_SPACE'
        ));
