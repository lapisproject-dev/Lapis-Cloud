-- V0.11.0: MemberStatus literals renamed German -> English + new FRIEND literal, plus the FRIEND
-- self-registration schema (member.friend_since/email_verified_at, friend_email_verification_token,
-- and conference_guest_consent_acknowledgment.homeserver_url made nullable for a FRIEND's join).
--
-- Idempotent by construction, same discipline as V2: on a FRESH database V1__baseline.sql already
-- creates the widened CHECK, the FRIEND-ready columns/table, and the nullable homeserver_url, and
-- there are no German-valued rows -- so every statement below is a no-op there (Flyway applies every
-- unrecorded migration in one pass against a schema-history-empty database, so V3 runs immediately
-- after the already-edited V1 on every test run). On pdv2 (live since 2026-08-14, migrated against
-- the pre-rename baseline) it does the real work: it rewrites live production `member.status` rows.
--
-- **Same dual-DROP discipline as V2**: the pre-rename baseline's member.status CHECK was UNNAMED, so
-- PostgreSQL auto-named it `member_status_check`; a freshly-created database carries the explicitly
-- named `chk_member_status` instead. Exactly one of the two DROPs ever matches.
-- VERIFY WITH `\d member` ON pdv2 BEFORE DEPLOY.

-- 1. Drop the constraint FIRST -- the UPDATEs below would violate the old literal set.
ALTER TABLE member DROP CONSTRAINT IF EXISTS member_status_check;
ALTER TABLE member DROP CONSTRAINT IF EXISTS chk_member_status;

-- 2. Rewrite existing rows. No-ops on a fresh database (zero matching rows).
UPDATE member SET status = 'APPLICATION' WHERE status = 'ANTRAG';
UPDATE member SET status = 'ACTIVE'      WHERE status = 'AKTIV';
UPDATE member SET status = 'GUEST'       WHERE status = 'GAST';
UPDATE member SET status = 'WITHDRAWN'   WHERE status = 'AUSGETRETEN';
UPDATE member SET status = 'REJECTED'    WHERE status = 'ABGELEHNT';

-- 3. Re-add with the English literal set + FRIEND.
ALTER TABLE member ADD CONSTRAINT chk_member_status
    CHECK (status IN ('APPLICATION', 'ACTIVE', 'GUEST', 'WITHDRAWN', 'REJECTED', 'FRIEND'));

-- 4. FRIEND self-registration schema.
ALTER TABLE member ADD COLUMN IF NOT EXISTS friend_since DATE NULL;
ALTER TABLE member ADD COLUMN IF NOT EXISTS email_verified_at TIMESTAMP NULL;

CREATE TABLE IF NOT EXISTS friend_email_verification_token (
    id UUID NOT NULL PRIMARY KEY,
    member_id UUID NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    consumed_at TIMESTAMP NULL
);
ALTER TABLE friend_email_verification_token DROP CONSTRAINT IF EXISTS fk_friend_email_verification_token_member_id;
ALTER TABLE friend_email_verification_token ADD CONSTRAINT fk_friend_email_verification_token_member_id FOREIGN KEY (member_id) REFERENCES member(id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_friend_email_verification_token_token_hash ON friend_email_verification_token (token_hash);
CREATE INDEX IF NOT EXISTS idx_friend_email_verification_token_member ON friend_email_verification_token (member_id);
CREATE INDEX IF NOT EXISTS idx_friend_email_verification_token_expires_at ON friend_email_verification_token (expires_at);

CREATE TABLE IF NOT EXISTS friend_terms_acknowledgment (
    id UUID NOT NULL PRIMARY KEY,
    member_id UUID NOT NULL,
    acknowledged_at TIMESTAMP NOT NULL,
    terms_version VARCHAR(50) NOT NULL,
    terms_sha256 VARCHAR(64) NOT NULL
);
ALTER TABLE friend_terms_acknowledgment DROP CONSTRAINT IF EXISTS fk_friend_terms_acknowledgment_member_id;
ALTER TABLE friend_terms_acknowledgment ADD CONSTRAINT fk_friend_terms_acknowledgment_member_id FOREIGN KEY (member_id) REFERENCES member(id);
CREATE INDEX IF NOT EXISTS idx_friend_terms_acknowledgment_member ON friend_terms_acknowledgment (member_id);

-- 5. A FRIEND has no federated home server -- conference_guest_consent_acknowledgment.homeserver_url
-- must become nullable so joinRoom can still write the same one acknowledgment-row-per-join proof
-- for a FRIEND that it already writes for a GUEST. No-op on a fresh database (already nullable).
ALTER TABLE conference_guest_consent_acknowledgment ALTER COLUMN homeserver_url DROP NOT NULL;
