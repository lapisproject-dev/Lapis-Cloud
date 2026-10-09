-- Welle V1.9.81 -- hourly e-mail send budget + durable outbox for system mails + resumable mailing-list delivery.
-- V1..V79 untouched, purely additive, H2 MODE=PostgreSQL- and Postgres-compatible (CREATE ... IF NOT EXISTS, ADD COLUMN IF NOT EXISTS,
-- DROP CONSTRAINT IF EXISTS before ADD CONSTRAINT).
--
--   * mail_outbox       -- one row per queued system mail. The payload (recipient, subject, bodies) is stored ONLY as AES-GCM ciphertext
--                          (*_enc, network.lapis.cloud.server.crypto.SecretBox) and ONLY while the row is open (QUEUED / SENDING). Every final state
--                          (SENT / FAILED / EXPIRED) carries NULL in all five payload columns; the two CHECK constraints below enforce that on schema level.
--   * mail_send_slot    -- one row per reserved send slot of the hourly budget (a sliding 3600 s window is counted over reserved_at).
--   * mail_budget_lock  -- singleton row (id = 1): serialises slot reservation (SELECT ... FOR UPDATE) and holds the global bulk pause.
--   * mailing_delivery_log gains claimed_at / attempt_count / next_attempt_at (claim before send => at-most-once, resumable after a restart);
--     delivery_status gains INTERRUPTED (claimed, outcome unknown after a crash -- never re-sent).
--   * mailing_message gains queued_at (FIFO order of the worker). mailing_message.status is VARCHAR(6): NO new message status is introduced.
--
-- Time fields (all class A, see time-fields.tsv): created_at, next_attempt_at, expires_at, claimed_at, finished_at, reserved_at,
-- bulk_paused_until, queued_at. No flywayRepair needed (V1..V79 untouched); take a backup before deploying.
CREATE TABLE IF NOT EXISTS mail_outbox (
    id UUID NOT NULL PRIMARY KEY,
    purpose VARCHAR(64) NOT NULL,
    priority SMALLINT NOT NULL,
    status VARCHAR(10) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NULL,
    claimed_at TIMESTAMP NULL,
    finished_at TIMESTAMP NULL,
    last_error_class VARCHAR(64) NULL,
    recipient_enc TEXT NULL,
    subject_enc TEXT NULL,
    text_enc TEXT NULL,
    html_enc TEXT NULL,
    recipient_lookup_hash VARCHAR(64) NULL,
    log_recipient BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT chk_mail_outbox_status CHECK (status IN ('QUEUED', 'SENDING', 'SENT', 'FAILED', 'EXPIRED')),
    CONSTRAINT chk_mail_outbox_priority CHECK (priority IN (0, 1)),
    CONSTRAINT chk_mail_outbox_attempts CHECK (attempt_count BETWEEN 0 AND 20),
    CONSTRAINT chk_mail_outbox_payload_final CHECK (
        status IN ('QUEUED', 'SENDING')
        OR (recipient_enc IS NULL AND subject_enc IS NULL AND text_enc IS NULL AND html_enc IS NULL AND recipient_lookup_hash IS NULL)),
    CONSTRAINT chk_mail_outbox_payload_open CHECK (
        status NOT IN ('QUEUED', 'SENDING')
        OR (recipient_enc IS NOT NULL AND subject_enc IS NOT NULL AND text_enc IS NOT NULL AND html_enc IS NOT NULL)));
CREATE INDEX IF NOT EXISTS idx_mail_outbox_due ON mail_outbox (status, priority, next_attempt_at);
CREATE INDEX IF NOT EXISTS idx_mail_outbox_lookup ON mail_outbox (recipient_lookup_hash);
CREATE INDEX IF NOT EXISTS idx_mail_outbox_finished ON mail_outbox (status, finished_at);

CREATE TABLE IF NOT EXISTS mail_send_slot (
    id UUID NOT NULL PRIMARY KEY,
    reserved_at TIMESTAMP NOT NULL,
    lane VARCHAR(6) NOT NULL,
    CONSTRAINT chk_mail_send_slot_lane CHECK (lane IN ('SYSTEM', 'BULK')));
CREATE INDEX IF NOT EXISTS idx_mail_send_slot_reserved ON mail_send_slot (reserved_at);

CREATE TABLE IF NOT EXISTS mail_budget_lock (
    id SMALLINT NOT NULL PRIMARY KEY,
    bulk_paused_until TIMESTAMP NULL,
    CONSTRAINT chk_mail_budget_lock_singleton CHECK (id = 1));
INSERT INTO mail_budget_lock (id) SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM mail_budget_lock WHERE id = 1);

ALTER TABLE mailing_delivery_log ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMP NULL;
ALTER TABLE mailing_delivery_log ADD COLUMN IF NOT EXISTS attempt_count INT NOT NULL DEFAULT 0;
ALTER TABLE mailing_delivery_log ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMP NULL;
ALTER TABLE mailing_message ADD COLUMN IF NOT EXISTS queued_at TIMESTAMP NULL;

-- INTERRUPTED has 11 characters and fits the existing VARCHAR(20). H2 has no ADD VALUE: the whole list is repeated.
--
-- V1__baseline.sql stays UNTOUCHED (no flywayRepair needed). It carries an inline, UNNAMED CHECK on delivery_status. On PostgreSQL that
-- constraint is called mailing_delivery_log_delivery_status_check (dropped by V53 and here again, harmlessly). On H2 -- tests, dev --
-- the same constraint gets a generated UPPER-CASE name (hence the quoted identifier: with DATABASE_TO_LOWER an unquoted one would be folded); unlike earlier waves, which widened V1 in place and demanded a flywayRepair on every
-- instance, it is dropped here BY THAT GENERATED NAME. The name is deterministic because V1 is frozen; on PostgreSQL no constraint of
-- that name exists and IF EXISTS makes the statement a no-op. MailOutboxMigrationScenarios pins the result (INTERRUPTED accepted, an
-- unknown status still rejected) on both databases, so a shifted name fails loudly instead of silently.
ALTER TABLE mailing_delivery_log DROP CONSTRAINT IF EXISTS "CONSTRAINT_E19";
ALTER TABLE mailing_delivery_log DROP CONSTRAINT IF EXISTS mailing_delivery_log_delivery_status_check;
ALTER TABLE mailing_delivery_log DROP CONSTRAINT IF EXISTS chk_mailing_delivery_log_delivery_status;
ALTER TABLE mailing_delivery_log ADD CONSTRAINT chk_mailing_delivery_log_delivery_status CHECK (delivery_status IN
    ('SENT', 'BOUNCED', 'SKIPPED_UNSUBSCRIBED', 'PENDING', 'FAILED', 'SKIPPED_NO_ADDRESS', 'INTERRUPTED'));
CREATE INDEX IF NOT EXISTS idx_mailing_delivery_log_msg_status ON mailing_delivery_log (mailing_message_id, delivery_status);
