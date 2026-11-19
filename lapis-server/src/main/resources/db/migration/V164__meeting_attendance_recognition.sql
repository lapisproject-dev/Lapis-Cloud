-- V1.9.316 "LTR-Anerkennung fuer Sitzungsteilnahme" (attendance recognition). See 83-meeting-reward.kuml.kts, 08-ltr-balance.kuml.kts,
-- 14-audit-log.kuml.kts and docs/features/meeting-attendance-recognition.adoc.
-- V1..V163 untouched (V157..V163 are on the pushed master and probably already applied on staging/instances; changing any of them in place would
-- break its Flyway checksum -- so this is a NEW migration, not an extension of V163).
-- Purely additive and repeatable, H2 MODE=PostgreSQL- and PostgreSQL-compatible: DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT,
-- CREATE TABLE/INDEX IF NOT EXISTS, no partial index, no ON DELETE CASCADE, NO backfill.
--
-- DECISION: a recognition is an ISSUANCE by the organisation (like MINT and TENDER_PAYOUT), NOT a transfer: ONE credit row MEETING_REWARD per
-- recognised person (reference MEETING = meeting.id), no debit row, no organisation account, LTR only (never EUR).
--
-- Idempotency in layers, none of them a partial index:
--   (a) the settlement row is locked FOR UPDATE and moves PREPARED -> RELEASED by a conditional UPDATE;
--   (b) meeting_reward_settlement.meeting_id is UNIQUE (one settlement per meeting);
--   (c) UNIQUE (meeting_id, member_id) and UNIQUE idempotency_key (VARCHAR(100): "meeting-reward:" + two UUIDs) on meeting_reward_line;
--   (d) UNIQUE ledger_entry_id on meeting_reward_line (several NULLs are allowed on H2 and PostgreSQL: a NULL is never equal to another).
--
--   * meeting_reward_policy: singleton (fixed id ...f9), seeded OFF with the default amounts. last_change_id / open_change_id have deliberately NO
--     foreign key (no cycle with meeting_reward_policy_change; the code keeps them consistent).
--   * meeting_reward_policy_change: every proposal / confirmation / withdrawal / switch-off, with the old and the new values. A CHANGE needs a
--     resolution of the general assembly (resolution_id) and is confirmed by somebody else than the proposer (CHECK).
--   * meeting_reward_committee: which committees take part (a snapshot of the last confirmed change).
--   * meeting_reward_settlement / meeting_reward_line: the prepared / released settlement of one meeting and its lines.
--   * ltr_ledger_entry.entry_type + MEETING_REWARD (14 chars, width 21 fits), reference_type + MEETING (7 chars, width 20 fits); the lists are
--     V157's, copied verbatim, plus the new literal.
--   * audit_log_entry.entity_type + MEETING_REWARD_POLICY (21) and MEETING_REWARD_SETTLEMENT (25), both fit VARCHAR(29); the list is V160's, copied
--     verbatim, plus the two new literals.
--
-- Backups made before this migration are refused by the restore (the schema checksum differs), like after every migration.

CREATE TABLE IF NOT EXISTS meeting_reward_policy (
    id                        UUID          NOT NULL PRIMARY KEY,
    enabled                   BOOLEAN       NOT NULL DEFAULT FALSE,
    attendance_amount         DECIMAL(9,2)  NOT NULL DEFAULT 0,
    bonus_chair               DECIMAL(9,2)  NOT NULL DEFAULT 0,
    bonus_deputy_chair        DECIMAL(9,2)  NOT NULL DEFAULT 0,
    bonus_secretary           DECIMAL(9,2)  NOT NULL DEFAULT 0,
    bonus_managing_director   DECIMAL(9,2)  NOT NULL DEFAULT 0,
    cap_per_person_per_year   DECIMAL(12,2) NOT NULL DEFAULT 240.00,
    cap_per_meeting           DECIMAL(12,2) NOT NULL DEFAULT 1000.00,
    resolution_id             UUID          NULL REFERENCES resolution (id),
    resolution_reference      VARCHAR(200)  NULL,
    last_change_id            UUID          NULL,
    open_change_id            UUID          NULL,
    updated_at                TIMESTAMP     NOT NULL
);

ALTER TABLE meeting_reward_policy DROP CONSTRAINT IF EXISTS chk_meeting_reward_policy_ranges;
ALTER TABLE meeting_reward_policy ADD CONSTRAINT chk_meeting_reward_policy_ranges CHECK (
        attendance_amount >= 0 AND attendance_amount <= 10000.00
        AND bonus_chair >= 0 AND bonus_chair <= 10000.00
        AND bonus_deputy_chair >= 0 AND bonus_deputy_chair <= 10000.00
        AND bonus_secretary >= 0 AND bonus_secretary <= 10000.00
        AND bonus_managing_director >= 0 AND bonus_managing_director <= 10000.00
        AND cap_per_person_per_year >= 0 AND cap_per_person_per_year <= 1000000.00
        AND cap_per_meeting >= 0 AND cap_per_meeting <= 1000000.00);

INSERT INTO meeting_reward_policy (id, enabled, updated_at)
SELECT CAST('00000000-0000-0000-0000-0000000000f9' AS UUID), FALSE, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM meeting_reward_policy);

CREATE TABLE IF NOT EXISTS meeting_reward_policy_change (
    id                              UUID          NOT NULL PRIMARY KEY,
    kind                            VARCHAR(7)    NOT NULL,
    status                          VARCHAR(9)    NOT NULL,
    proposed_by                     UUID          NOT NULL REFERENCES member (id),
    proposed_at                     TIMESTAMP     NOT NULL,
    decided_by                      UUID          NULL REFERENCES member (id),
    decided_at                      TIMESTAMP     NULL,
    resolution_id                   UUID          NULL REFERENCES resolution (id),
    resolution_reference            VARCHAR(200)  NULL,
    old_enabled                     BOOLEAN       NOT NULL,
    old_attendance_amount           DECIMAL(9,2)  NOT NULL,
    old_bonus_chair                 DECIMAL(9,2)  NOT NULL,
    old_bonus_deputy_chair          DECIMAL(9,2)  NOT NULL,
    old_bonus_secretary             DECIMAL(9,2)  NOT NULL,
    old_bonus_managing_director     DECIMAL(9,2)  NOT NULL,
    old_cap_per_person_per_year     DECIMAL(12,2) NOT NULL,
    old_cap_per_meeting             DECIMAL(12,2) NOT NULL,
    new_enabled                     BOOLEAN       NOT NULL,
    new_attendance_amount           DECIMAL(9,2)  NOT NULL,
    new_bonus_chair                 DECIMAL(9,2)  NOT NULL,
    new_bonus_deputy_chair          DECIMAL(9,2)  NOT NULL,
    new_bonus_secretary             DECIMAL(9,2)  NOT NULL,
    new_bonus_managing_director     DECIMAL(9,2)  NOT NULL,
    new_cap_per_person_per_year     DECIMAL(12,2) NOT NULL,
    new_cap_per_meeting             DECIMAL(12,2) NOT NULL,
    old_committee_ids               VARCHAR(4000) NOT NULL,
    new_committee_ids               VARCHAR(4000) NOT NULL
);

ALTER TABLE meeting_reward_policy_change DROP CONSTRAINT IF EXISTS chk_meeting_reward_change_kind;
ALTER TABLE meeting_reward_policy_change ADD CONSTRAINT chk_meeting_reward_change_kind CHECK (kind IN ('CHANGE', 'DISABLE'));
ALTER TABLE meeting_reward_policy_change DROP CONSTRAINT IF EXISTS chk_meeting_reward_change_status;
ALTER TABLE meeting_reward_policy_change ADD CONSTRAINT chk_meeting_reward_change_status CHECK (status IN ('PROPOSED', 'CONFIRMED', 'WITHDRAWN'));
ALTER TABLE meeting_reward_policy_change DROP CONSTRAINT IF EXISTS chk_meeting_reward_change_resolution;
ALTER TABLE meeting_reward_policy_change ADD CONSTRAINT chk_meeting_reward_change_resolution CHECK (kind <> 'CHANGE' OR resolution_id IS NOT NULL);
ALTER TABLE meeting_reward_policy_change DROP CONSTRAINT IF EXISTS chk_meeting_reward_change_four_eyes;
ALTER TABLE meeting_reward_policy_change ADD CONSTRAINT chk_meeting_reward_change_four_eyes
    CHECK (kind <> 'CHANGE' OR status <> 'CONFIRMED' OR (decided_by IS NOT NULL AND decided_by <> proposed_by));
ALTER TABLE meeting_reward_policy_change DROP CONSTRAINT IF EXISTS chk_meeting_reward_change_proposed_open;
ALTER TABLE meeting_reward_policy_change ADD CONSTRAINT chk_meeting_reward_change_proposed_open CHECK (status <> 'PROPOSED' OR decided_by IS NULL);
ALTER TABLE meeting_reward_policy_change DROP CONSTRAINT IF EXISTS chk_meeting_reward_change_disable_confirmed;
ALTER TABLE meeting_reward_policy_change ADD CONSTRAINT chk_meeting_reward_change_disable_confirmed CHECK (kind <> 'DISABLE' OR status = 'CONFIRMED');
ALTER TABLE meeting_reward_policy_change DROP CONSTRAINT IF EXISTS chk_meeting_reward_change_old_ranges;
ALTER TABLE meeting_reward_policy_change ADD CONSTRAINT chk_meeting_reward_change_old_ranges CHECK (
        old_attendance_amount >= 0 AND old_attendance_amount <= 10000.00
        AND old_bonus_chair >= 0 AND old_bonus_chair <= 10000.00
        AND old_bonus_deputy_chair >= 0 AND old_bonus_deputy_chair <= 10000.00
        AND old_bonus_secretary >= 0 AND old_bonus_secretary <= 10000.00
        AND old_bonus_managing_director >= 0 AND old_bonus_managing_director <= 10000.00
        AND old_cap_per_person_per_year >= 0 AND old_cap_per_person_per_year <= 1000000.00
        AND old_cap_per_meeting >= 0 AND old_cap_per_meeting <= 1000000.00);
ALTER TABLE meeting_reward_policy_change DROP CONSTRAINT IF EXISTS chk_meeting_reward_change_new_ranges;
ALTER TABLE meeting_reward_policy_change ADD CONSTRAINT chk_meeting_reward_change_new_ranges CHECK (
        new_attendance_amount >= 0 AND new_attendance_amount <= 10000.00
        AND new_bonus_chair >= 0 AND new_bonus_chair <= 10000.00
        AND new_bonus_deputy_chair >= 0 AND new_bonus_deputy_chair <= 10000.00
        AND new_bonus_secretary >= 0 AND new_bonus_secretary <= 10000.00
        AND new_bonus_managing_director >= 0 AND new_bonus_managing_director <= 10000.00
        AND new_cap_per_person_per_year >= 0 AND new_cap_per_person_per_year <= 1000000.00
        AND new_cap_per_meeting >= 0 AND new_cap_per_meeting <= 1000000.00);

CREATE INDEX IF NOT EXISTS ix_meeting_reward_change_proposed_at ON meeting_reward_policy_change (proposed_at);

CREATE TABLE IF NOT EXISTS meeting_reward_committee (
    committee_id          UUID      NOT NULL PRIMARY KEY REFERENCES committee (id),
    enabled_by_change_id  UUID      NOT NULL REFERENCES meeting_reward_policy_change (id),
    enabled_at            TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS meeting_reward_settlement (
    id                    UUID          NOT NULL PRIMARY KEY,
    meeting_id            UUID          NOT NULL REFERENCES meeting (id),
    status                VARCHAR(8)    NOT NULL,
    prepared_by           UUID          NOT NULL REFERENCES member (id),
    prepared_at           TIMESTAMP     NOT NULL,
    released_by           UUID          NULL REFERENCES member (id),
    released_at           TIMESTAMP     NULL,
    preview_hash          VARCHAR(64)   NOT NULL,
    policy_change_id      UUID          NOT NULL REFERENCES meeting_reward_policy_change (id),
    protocol_version_id   UUID          NOT NULL REFERENCES protocol_version (id),
    total_amount          DECIMAL(14,2) NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_meeting_reward_settlement_meeting ON meeting_reward_settlement (meeting_id);
ALTER TABLE meeting_reward_settlement DROP CONSTRAINT IF EXISTS chk_meeting_reward_settlement_status;
ALTER TABLE meeting_reward_settlement ADD CONSTRAINT chk_meeting_reward_settlement_status CHECK (status IN ('PREPARED', 'RELEASED'));
ALTER TABLE meeting_reward_settlement DROP CONSTRAINT IF EXISTS chk_meeting_reward_settlement_released;
ALTER TABLE meeting_reward_settlement ADD CONSTRAINT chk_meeting_reward_settlement_released
    CHECK (status <> 'RELEASED' OR (released_by IS NOT NULL AND released_at IS NOT NULL AND released_by <> prepared_by));
ALTER TABLE meeting_reward_settlement DROP CONSTRAINT IF EXISTS chk_meeting_reward_settlement_total;
ALTER TABLE meeting_reward_settlement ADD CONSTRAINT chk_meeting_reward_settlement_total CHECK (total_amount >= 0);

CREATE TABLE IF NOT EXISTS meeting_reward_line (
    id                 UUID         NOT NULL PRIMARY KEY,
    settlement_id      UUID         NOT NULL REFERENCES meeting_reward_settlement (id),
    meeting_id         UUID         NOT NULL REFERENCES meeting (id),
    member_id          UUID         NOT NULL REFERENCES member (id),
    attendance_status  VARCHAR(11)  NOT NULL,
    reason             VARCHAR(19)  NULL,
    function_role      VARCHAR(20)  NULL,
    base_amount        DECIMAL(9,2) NOT NULL,
    bonus_amount       DECIMAL(9,2) NOT NULL,
    amount             DECIMAL(9,2) NOT NULL,
    reduced            BOOLEAN      NOT NULL DEFAULT FALSE,
    period_year        INTEGER      NOT NULL,
    idempotency_key    VARCHAR(100)  NOT NULL,
    ledger_entry_id    UUID         NULL REFERENCES ltr_ledger_entry (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_meeting_reward_line_meeting_member ON meeting_reward_line (meeting_id, member_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_meeting_reward_line_idempotency ON meeting_reward_line (idempotency_key);
CREATE UNIQUE INDEX IF NOT EXISTS uq_meeting_reward_line_ledger_entry ON meeting_reward_line (ledger_entry_id);
CREATE INDEX IF NOT EXISTS ix_meeting_reward_line_member_year ON meeting_reward_line (member_id, period_year);
CREATE INDEX IF NOT EXISTS ix_meeting_reward_line_settlement ON meeting_reward_line (settlement_id);

ALTER TABLE meeting_reward_line DROP CONSTRAINT IF EXISTS chk_meeting_reward_line_status;
ALTER TABLE meeting_reward_line ADD CONSTRAINT chk_meeting_reward_line_status
    CHECK (attendance_status IN ('PRESENT', 'EXCUSED', 'UNEXCUSED', 'REPRESENTED', 'GUEST'));
ALTER TABLE meeting_reward_line DROP CONSTRAINT IF EXISTS chk_meeting_reward_line_reason;
ALTER TABLE meeting_reward_line ADD CONSTRAINT chk_meeting_reward_line_reason
    CHECK (reason IS NULL OR reason IN ('EXCUSED', 'REPRESENTED', 'NOT_PRESENT', 'GUEST', 'CAP_REACHED', 'CAP_REDUCED', 'ALREADY_SETTLED', 'MEMBER_NOT_ELIGIBLE'));
ALTER TABLE meeting_reward_line DROP CONSTRAINT IF EXISTS chk_meeting_reward_line_role;
ALTER TABLE meeting_reward_line ADD CONSTRAINT chk_meeting_reward_line_role
    CHECK (function_role IS NULL OR function_role IN ('CHAIR', 'DEPUTY_CHAIR', 'SECRETARY', 'MEMBER', 'ASSESSOR', 'GENERAL_SECRETARY', 'PRESS_SPOKESPERSON', 'MANAGING_DIRECTOR', 'TREASURER'));
ALTER TABLE meeting_reward_line DROP CONSTRAINT IF EXISTS chk_meeting_reward_line_amounts;
ALTER TABLE meeting_reward_line ADD CONSTRAINT chk_meeting_reward_line_amounts
    CHECK (base_amount >= 0 AND bonus_amount >= 0 AND amount >= 0 AND amount <= base_amount + bonus_amount);

-- Ledger widening, same dual-DROP discipline as V4/V5/V149/V157 (the guessed Postgres auto-name plus the explicit name).
ALTER TABLE ltr_ledger_entry DROP CONSTRAINT IF EXISTS ltr_ledger_entry_entry_type_check;
ALTER TABLE ltr_ledger_entry DROP CONSTRAINT IF EXISTS chk_ltr_ledger_entry_entry_type;
ALTER TABLE ltr_ledger_entry ADD CONSTRAINT chk_ltr_ledger_entry_entry_type
    CHECK (entry_type IN (
        'MINT', 'PROJECT_STAKE', 'PROJECT_STAKE_RELEASE', 'VOTE_STAKE', 'PEER_TRANSFER_OUT', 'PEER_TRANSFER_IN',
        'AUCTION_LISTING_FEE', 'AUCTION_HOLD', 'AUCTION_HOLD_RELEASE', 'AUCTION_SALE_OUT', 'AUCTION_SALE_IN',
        'SOCIAL_POST_STAKE', 'SOCIAL_POST_BOOST', 'NOTICE_BOOST', 'TENDER_PAYOUT', 'MEETING_REWARD'
    ));
ALTER TABLE ltr_ledger_entry DROP CONSTRAINT IF EXISTS ltr_ledger_entry_reference_type_check;
ALTER TABLE ltr_ledger_entry DROP CONSTRAINT IF EXISTS chk_ltr_ledger_entry_reference_type;
ALTER TABLE ltr_ledger_entry ADD CONSTRAINT chk_ltr_ledger_entry_reference_type
    CHECK (reference_type IN ('CROWDFUNDING_PROJECT', 'VOTE', 'PEER_TRANSFER', 'AUCTION', 'SOCIAL_POST', 'NOTICE', 'TENDER', 'MEETING'));

-- audit_log_entry.entity_type: the list is V160's, copied verbatim, plus the two new literals.
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
                                'MEETING_REWARD_POLICY', 'MEETING_REWARD_SETTLEMENT'
        ));
