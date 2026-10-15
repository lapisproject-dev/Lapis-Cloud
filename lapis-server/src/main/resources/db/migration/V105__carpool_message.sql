-- Welle V1.9.139 "Mitfahr-Nachrichten" -- a short message history per CONFIRMED carpool participation. V1..V104 untouched.
-- Purely additive, idempotent, H2 MODE=PostgreSQL- and Postgres-compatible (CREATE TABLE/INDEX IF NOT EXISTS, DROP CONSTRAINT IF EXISTS
-- before every ADD CONSTRAINT) -- same discipline as V58/V100.
--
--   * carpool_message_thread: at most ONE row per participation (created with the first message). ended_at/ended_by_side record that one
--     side closed the history; last_mail_at throttles the notification mail to one per history and hour.
--   * carpool_message: the messages. sender_side / ended_by_side are 'DRIVER' | 'PASSENGER' instead of a member id: a history has exactly two
--     parties, both derived from the participation, so no second person reference (and nothing to erase beyond the rows) is stored.
--   * Timestamps are TIMESTAMP (the server clock via DbClock, stored as UTC), like every other column of this schema.
--   * Both tables are ON DELETE CASCADE from the participation; the application deletes explicitly as well (the generated Exposed table
--     cannot express the rule). Both tables are EXCLUDED from the organization backup (short-lived, deletion is promised).

CREATE TABLE IF NOT EXISTS carpool_message_thread (
    participation_id UUID        NOT NULL PRIMARY KEY,
    ended_at         TIMESTAMP   NULL,
    ended_by_side    VARCHAR(16) NULL,
    last_mail_at     TIMESTAMP   NULL
);

ALTER TABLE carpool_message_thread DROP CONSTRAINT IF EXISTS fk_carpool_message_thread_participation;
ALTER TABLE carpool_message_thread ADD CONSTRAINT fk_carpool_message_thread_participation
    FOREIGN KEY (participation_id) REFERENCES carpool_participation(id) ON DELETE CASCADE;

ALTER TABLE carpool_message_thread DROP CONSTRAINT IF EXISTS chk_carpool_message_thread_ended;
ALTER TABLE carpool_message_thread ADD CONSTRAINT chk_carpool_message_thread_ended
    CHECK ((ended_at IS NULL AND ended_by_side IS NULL) OR (ended_at IS NOT NULL AND ended_by_side IN ('DRIVER', 'PASSENGER')));

CREATE TABLE IF NOT EXISTS carpool_message (
    id               UUID          NOT NULL PRIMARY KEY,
    participation_id UUID          NOT NULL,
    sender_side      VARCHAR(16)   NOT NULL,
    body             VARCHAR(1000) NOT NULL,
    sent_at          TIMESTAMP     NOT NULL
);

ALTER TABLE carpool_message DROP CONSTRAINT IF EXISTS fk_carpool_message_participation;
ALTER TABLE carpool_message ADD CONSTRAINT fk_carpool_message_participation
    FOREIGN KEY (participation_id) REFERENCES carpool_participation(id) ON DELETE CASCADE;

ALTER TABLE carpool_message DROP CONSTRAINT IF EXISTS chk_carpool_message_side;
ALTER TABLE carpool_message ADD CONSTRAINT chk_carpool_message_side
    CHECK (sender_side IN ('DRIVER', 'PASSENGER'));

ALTER TABLE carpool_message DROP CONSTRAINT IF EXISTS chk_carpool_message_body;
ALTER TABLE carpool_message ADD CONSTRAINT chk_carpool_message_body
    CHECK (LENGTH(TRIM(body)) > 0);

CREATE INDEX IF NOT EXISTS idx_carpool_message_participation_sent ON carpool_message(participation_id, sent_at, id);
