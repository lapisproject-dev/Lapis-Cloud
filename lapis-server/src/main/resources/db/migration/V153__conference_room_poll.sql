-- "room-polls" wave A (V1.9.304): non-binding polls inside a video conference. Started and ended by the room's moderators
-- (ConferenceModeratorAuthority), answered once by the people present, counted separately for MEMBERS (ACTIVE, incl. sponsoring members without a
-- voting right) and GUESTS_AND_FRIENDS (GUEST, FRIEND, DONOR) and for everybody together; see docs/architecture/room-polls.adoc.
-- V1..V152 untouched (V152 is a different feature, already on master). Purely additive and repeatable, H2 MODE=PostgreSQL- and
-- PostgreSQL-compatible: CREATE ... IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT, no partial index, no backfill.
--
-- Anonymity model: WHO answered (conference_room_poll_voted, personal data, deleted when the poll ends) and WHAT was answered
-- (conference_room_poll_answer, random id, NO member id, NO time, NO sequence) are two tables that are never joined. conference_room_poll itself
-- stores NO actor (the actor of start and end is in the audit log). "At most one open poll per room" is the UNIQUE index on open_room_id, a
-- NULL-able copy of room_id that is set only while the poll is OPEN (NULL is distinct in H2 and PostgreSQL, so closed polls never collide).
-- snapshot_json / result_json hold the ALREADY SUPPRESSED public result (whole percent shares, no weight sums, no group size below 5).
--
-- ROLLBACK HAZARD: 0.53.x and every build without V153 reject a database with V153 applied (Flyway validation). Roll back only by restoring the
-- backup taken before V153. Backups taken before V153 are rejected on restore by the schema checksum (BackupManifest.schemaChecksum), by design.

CREATE TABLE IF NOT EXISTS conference_room_poll (
    id                    UUID           NOT NULL PRIMARY KEY,
    room_id               UUID           NOT NULL,
    open_room_id          UUID           NULL,
    question              VARCHAR(300)   NOT NULL,
    counting_mode         VARCHAR(10)    NOT NULL,
    status                VARCHAR(6)     NOT NULL,
    started_at            TIMESTAMP      NOT NULL,
    closed_at             TIMESTAMP      NULL,
    close_reason          VARCHAR(10)    NULL,
    state_version         BIGINT         NOT NULL,
    snapshot_answer_count INT            NOT NULL,
    snapshot_json         VARCHAR(8000)  NULL,
    result_json           VARCHAR(8000)  NULL
);

ALTER TABLE conference_room_poll DROP CONSTRAINT IF EXISTS fk_conference_room_poll_room;
ALTER TABLE conference_room_poll ADD CONSTRAINT fk_conference_room_poll_room FOREIGN KEY (room_id) REFERENCES conference_room(id);

ALTER TABLE conference_room_poll DROP CONSTRAINT IF EXISTS chk_conference_room_poll_mode;
ALTER TABLE conference_room_poll ADD CONSTRAINT chk_conference_room_poll_mode CHECK (counting_mode IN ('HEAD_COUNT', 'WEIGHTED', 'BOTH'));

ALTER TABLE conference_room_poll DROP CONSTRAINT IF EXISTS chk_conference_room_poll_status;
ALTER TABLE conference_room_poll ADD CONSTRAINT chk_conference_room_poll_status CHECK (status IN ('OPEN', 'CLOSED'));

ALTER TABLE conference_room_poll DROP CONSTRAINT IF EXISTS chk_conference_room_poll_reason;
ALTER TABLE conference_room_poll ADD CONSTRAINT chk_conference_room_poll_reason
    CHECK (close_reason IS NULL OR close_reason IN ('MANUAL', 'ROOM_ENDED', 'TIMEOUT'));

ALTER TABLE conference_room_poll DROP CONSTRAINT IF EXISTS chk_conference_room_poll_lifecycle;
ALTER TABLE conference_room_poll ADD CONSTRAINT chk_conference_room_poll_lifecycle
    CHECK ((status = 'OPEN' AND open_room_id IS NOT NULL AND open_room_id = room_id AND closed_at IS NULL AND close_reason IS NULL)
        OR (status = 'CLOSED' AND open_room_id IS NULL AND closed_at IS NOT NULL AND close_reason IS NOT NULL));

ALTER TABLE conference_room_poll DROP CONSTRAINT IF EXISTS chk_conference_room_poll_question;
ALTER TABLE conference_room_poll ADD CONSTRAINT chk_conference_room_poll_question CHECK (CHAR_LENGTH(TRIM(question)) BETWEEN 3 AND 300);

ALTER TABLE conference_room_poll DROP CONSTRAINT IF EXISTS chk_conference_room_poll_snapshot;
ALTER TABLE conference_room_poll ADD CONSTRAINT chk_conference_room_poll_snapshot
    CHECK (snapshot_answer_count >= 0 AND MOD(snapshot_answer_count, 5) = 0);

ALTER TABLE conference_room_poll DROP CONSTRAINT IF EXISTS chk_conference_room_poll_version;
ALTER TABLE conference_room_poll ADD CONSTRAINT chk_conference_room_poll_version CHECK (state_version >= 1);

CREATE UNIQUE INDEX IF NOT EXISTS uq_conference_room_poll_open_room ON conference_room_poll (open_room_id);
CREATE INDEX IF NOT EXISTS idx_conference_room_poll_room_started ON conference_room_poll (room_id, started_at);

CREATE TABLE IF NOT EXISTS conference_room_poll_option (
    id        UUID         NOT NULL PRIMARY KEY,
    poll_id   UUID         NOT NULL,
    position  INT          NOT NULL,
    text      VARCHAR(80)  NOT NULL
);

ALTER TABLE conference_room_poll_option DROP CONSTRAINT IF EXISTS fk_conference_room_poll_option_poll;
ALTER TABLE conference_room_poll_option ADD CONSTRAINT fk_conference_room_poll_option_poll FOREIGN KEY (poll_id) REFERENCES conference_room_poll(id);
ALTER TABLE conference_room_poll_option DROP CONSTRAINT IF EXISTS chk_conference_room_poll_option_position;
ALTER TABLE conference_room_poll_option ADD CONSTRAINT chk_conference_room_poll_option_position CHECK (position BETWEEN 0 AND 9);
CREATE UNIQUE INDEX IF NOT EXISTS uq_conference_room_poll_option_position ON conference_room_poll_option (poll_id, position);

-- WHO answered: personal data. No time column, no sequence. Rows are deleted when the poll ends and by the data-subject erasure.
CREATE TABLE IF NOT EXISTS conference_room_poll_voted (
    id         UUID  NOT NULL PRIMARY KEY,
    poll_id    UUID  NOT NULL,
    member_id  UUID  NOT NULL
);

ALTER TABLE conference_room_poll_voted DROP CONSTRAINT IF EXISTS fk_conference_room_poll_voted_poll;
ALTER TABLE conference_room_poll_voted ADD CONSTRAINT fk_conference_room_poll_voted_poll FOREIGN KEY (poll_id) REFERENCES conference_room_poll(id);
ALTER TABLE conference_room_poll_voted DROP CONSTRAINT IF EXISTS fk_conference_room_poll_voted_member;
ALTER TABLE conference_room_poll_voted ADD CONSTRAINT fk_conference_room_poll_voted_member FOREIGN KEY (member_id) REFERENCES member(id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_conference_room_poll_voted ON conference_room_poll_voted (poll_id, member_id);

-- WHAT was answered. NO member id, NO time, random UUIDv4 id. Guests and friends carry no LTR weight (the CHECK states it at database level).
CREATE TABLE IF NOT EXISTS conference_room_poll_answer (
    id           UUID           NOT NULL PRIMARY KEY,
    poll_id      UUID           NOT NULL,
    option_id    UUID           NOT NULL,
    voter_group  VARCHAR(18)    NOT NULL,
    weight_ltr   DECIMAL(18,2)  NOT NULL
);

ALTER TABLE conference_room_poll_answer DROP CONSTRAINT IF EXISTS fk_conference_room_poll_answer_poll;
ALTER TABLE conference_room_poll_answer ADD CONSTRAINT fk_conference_room_poll_answer_poll FOREIGN KEY (poll_id) REFERENCES conference_room_poll(id);
ALTER TABLE conference_room_poll_answer DROP CONSTRAINT IF EXISTS fk_conference_room_poll_answer_option;
ALTER TABLE conference_room_poll_answer ADD CONSTRAINT fk_conference_room_poll_answer_option FOREIGN KEY (option_id) REFERENCES conference_room_poll_option(id);
ALTER TABLE conference_room_poll_answer DROP CONSTRAINT IF EXISTS chk_conference_room_poll_answer_group;
ALTER TABLE conference_room_poll_answer ADD CONSTRAINT chk_conference_room_poll_answer_group CHECK (voter_group IN ('MEMBERS', 'GUESTS_AND_FRIENDS'));
ALTER TABLE conference_room_poll_answer DROP CONSTRAINT IF EXISTS chk_conference_room_poll_answer_weight;
ALTER TABLE conference_room_poll_answer ADD CONSTRAINT chk_conference_room_poll_answer_weight
    CHECK (weight_ltr >= 0 AND (voter_group = 'MEMBERS' OR weight_ltr = 0));
CREATE INDEX IF NOT EXISTS idx_conference_room_poll_answer_poll ON conference_room_poll_answer (poll_id);

-- audit_log_entry.entity_type + CONFERENCE_ROOM_POLL (20 chars, fits VARCHAR(29)); the list is V149's, copied verbatim, plus the new literal.
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
                                'CONFERENCE_ROOM_POLL'
        ));
