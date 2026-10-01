-- Welle V1.9.30 "Umfragen auf LTR-Basis" (Server) -- Datenmodell-Teil. Rein additiv, idempotent, H2
-- MODE=PostgreSQL- und Postgres-kompatibel, same discipline as V58-V63: CREATE TABLE/INDEX IF NOT
-- EXISTS, DROP CONSTRAINT IF EXISTS vor jedem ADD CONSTRAINT, keine partiellen Indizes, keine
-- Ausdrucks-Indizes, kein DO-Block.
--
-- OPERATOR NOTE: V1__baseline.sql's still-unnamed inline CHECK on audit_log_entry.entity_type is
-- widened IN PLACE by 'POLL' (the exact precedent V59/V60 document). Flyway therefore sees a checksum
-- mismatch for the already-applied V1 on an existing instance: run
-- `./gradlew :lapis-server:flywayRepair` BEFORE deploying this version on PdV, ELB and Staging, and
-- take a backup first.
--
-- ANONYMITY MODEL (do NOT "repair"): poll_participation says WHO answered, poll_response says WHAT was
-- answered; the two tables share no key. poll_response has deliberately NO member_id and NO time column
-- (a constant column would carry no information, an omitted one is stricter); poll_participation has no
-- time column either. Both use random UUIDv4 ids. weight_ltr is a snapshot of the responder's free LTR
-- balance at cast time.

CREATE TABLE IF NOT EXISTS poll (
    id           UUID           NOT NULL PRIMARY KEY,
    question     VARCHAR(500)   NOT NULL,
    description  VARCHAR(2000)  NULL,
    status       VARCHAR(10)    NOT NULL,
    created_by   UUID           NOT NULL,
    created_at   TIMESTAMP      NOT NULL,
    closes_at    TIMESTAMP      NULL,
    closed_at    TIMESTAMP      NULL,
    closed_by    UUID           NULL
);

ALTER TABLE poll DROP CONSTRAINT IF EXISTS fk_poll_created_by;
ALTER TABLE poll ADD CONSTRAINT fk_poll_created_by FOREIGN KEY (created_by) REFERENCES member(id);

ALTER TABLE poll DROP CONSTRAINT IF EXISTS fk_poll_closed_by;
ALTER TABLE poll ADD CONSTRAINT fk_poll_closed_by FOREIGN KEY (closed_by) REFERENCES member(id);

ALTER TABLE poll DROP CONSTRAINT IF EXISTS chk_poll_status;
ALTER TABLE poll ADD CONSTRAINT chk_poll_status CHECK (status IN ('OPEN', 'CLOSED', 'ABORTED'));

-- The STORED status only: an OPEN poll whose closes_at has passed is CLOSED for every reader (lazy
-- expiry, never materialised), see PollLifecycle.
ALTER TABLE poll DROP CONSTRAINT IF EXISTS chk_poll_closed_state;
ALTER TABLE poll ADD CONSTRAINT chk_poll_closed_state
    CHECK ((status = 'OPEN' AND closed_at IS NULL AND closed_by IS NULL)
        OR (status <> 'OPEN' AND closed_at IS NOT NULL AND closed_by IS NOT NULL));

ALTER TABLE poll DROP CONSTRAINT IF EXISTS chk_poll_closes_after_created;
ALTER TABLE poll ADD CONSTRAINT chk_poll_closes_after_created CHECK (closes_at IS NULL OR closes_at > created_at);

ALTER TABLE poll DROP CONSTRAINT IF EXISTS chk_poll_question_not_blank;
ALTER TABLE poll ADD CONSTRAINT chk_poll_question_not_blank CHECK (LENGTH(TRIM(question)) > 0);

CREATE INDEX IF NOT EXISTS idx_poll_status_created ON poll (status, created_at);
CREATE INDEX IF NOT EXISTS idx_poll_created_by ON poll (created_by);

CREATE TABLE IF NOT EXISTS poll_option (
    id        UUID          NOT NULL PRIMARY KEY,
    poll_id   UUID          NOT NULL,
    position  INT           NOT NULL,
    text      VARCHAR(200)  NOT NULL
);

ALTER TABLE poll_option DROP CONSTRAINT IF EXISTS fk_poll_option_poll;
ALTER TABLE poll_option ADD CONSTRAINT fk_poll_option_poll FOREIGN KEY (poll_id) REFERENCES poll(id);

ALTER TABLE poll_option DROP CONSTRAINT IF EXISTS chk_poll_option_position;
ALTER TABLE poll_option ADD CONSTRAINT chk_poll_option_position CHECK (position BETWEEN 0 AND 9);

CREATE UNIQUE INDEX IF NOT EXISTS uq_poll_option_position ON poll_option (poll_id, position);

-- WHO answered. No timestamp, random UUIDv4 id.
CREATE TABLE IF NOT EXISTS poll_participation (
    id         UUID  NOT NULL PRIMARY KEY,
    poll_id    UUID  NOT NULL,
    member_id  UUID  NOT NULL
);

ALTER TABLE poll_participation DROP CONSTRAINT IF EXISTS fk_poll_participation_poll;
ALTER TABLE poll_participation ADD CONSTRAINT fk_poll_participation_poll FOREIGN KEY (poll_id) REFERENCES poll(id);

ALTER TABLE poll_participation DROP CONSTRAINT IF EXISTS fk_poll_participation_member;
ALTER TABLE poll_participation ADD CONSTRAINT fk_poll_participation_member FOREIGN KEY (member_id) REFERENCES member(id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_poll_participation_member ON poll_participation (poll_id, member_id);

-- WHAT was answered. NO member_id, NO timestamp, random UUIDv4 id, weight snapshot at cast time.
CREATE TABLE IF NOT EXISTS poll_response (
    id          UUID           NOT NULL PRIMARY KEY,
    poll_id     UUID           NOT NULL,
    option_id   UUID           NOT NULL,
    weight_ltr  DECIMAL(18,2)  NOT NULL
);

ALTER TABLE poll_response DROP CONSTRAINT IF EXISTS fk_poll_response_poll;
ALTER TABLE poll_response ADD CONSTRAINT fk_poll_response_poll FOREIGN KEY (poll_id) REFERENCES poll(id);

ALTER TABLE poll_response DROP CONSTRAINT IF EXISTS fk_poll_response_option;
ALTER TABLE poll_response ADD CONSTRAINT fk_poll_response_option FOREIGN KEY (option_id) REFERENCES poll_option(id);

ALTER TABLE poll_response DROP CONSTRAINT IF EXISTS chk_poll_response_weight;
ALTER TABLE poll_response ADD CONSTRAINT chk_poll_response_weight CHECK (weight_ltr >= 0);

CREATE INDEX IF NOT EXISTS idx_poll_response_poll ON poll_response (poll_id);

-- One new AuditEntityType literal (POLL), appended LAST -- same DROP/ADD dance on the NAMED
-- chk_audit_log_entry_entity_type constraint every prior wave's migration establishes (list copied
-- from V60__membership_tier_admin.sql, plus 'POLL').
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
                                'MEMBERSHIP_TIER', 'POLL'
        ));
