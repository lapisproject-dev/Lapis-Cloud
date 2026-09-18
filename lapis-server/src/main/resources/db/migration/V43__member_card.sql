-- Welle "Digitaler Mitgliedsausweis (PDF)" -- adds a stable, human-facing member number
-- (member.member_number, "M-<Beitrittsjahr>-<5-stellig>") plus a bearer-code table used to
-- authorize the unauthenticated public verification page ("/ausweis"). See
-- network.lapis.cloud.server.member.MemberNumberAllocator / .MemberCardStore /
-- 52-member-card.kuml.kts for the fachlogik this schema supports.
--
-- No partial unique index ("at most one ACTIVE code per member") -- same H2 MODE=PostgreSQL
-- portability limitation V9__dunning.sql's uq_dunning_notice_slot and
-- 39-events.kuml.kts's active_participant_key already document for this codebase. The invariant
-- is instead enforced procedurally: MemberCardStore.ensureActiveCode/revokeAndReissue are the
-- ONLY writers of member_card_code, and always run under a `forUpdate()` lock on the member row.
--
-- code_hash is GLOBALLY unique (mirrors uq_event_registration_ticket_code, V1__baseline.sql) --
-- the raw bearer code is never persisted, only its SHA-256 hex digest.

ALTER TABLE member ADD COLUMN IF NOT EXISTS member_number VARCHAR(16) NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_member_member_number ON member (member_number);

-- Per-Beitrittsjahr laufender Zähler für die Mitgliedsnummer. Kein Member-FK, keine PII -- siehe
-- 52-member-card.kuml.kts file header für die DSGVO-Begründung (weder Contributor noch Allowlist
-- nötig). Spalte heisst "allocation_year", nicht "year" -- "YEAR" ist ein reserviertes Wort in
-- H2s SQL-Grammatik (CREATE TABLE ... year INT scheitert mit einem Parser-Syntaxfehler).
CREATE TABLE IF NOT EXISTS member_number_sequence (
    allocation_year INT NOT NULL PRIMARY KEY,
    next_value      INT NOT NULL
);

CREATE TABLE IF NOT EXISTS member_card_code (
    id         UUID        NOT NULL PRIMARY KEY,
    member_id  UUID        NOT NULL,
    code_hash  VARCHAR(64) NOT NULL,
    issued_at  TIMESTAMP   NOT NULL,
    revoked_at TIMESTAMP   NULL
);
ALTER TABLE member_card_code DROP CONSTRAINT IF EXISTS fk_member_card_code_member;
ALTER TABLE member_card_code ADD CONSTRAINT fk_member_card_code_member
    FOREIGN KEY (member_id) REFERENCES member(id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_member_card_code_hash ON member_card_code (code_hash);
CREATE INDEX IF NOT EXISTS idx_member_card_code_member ON member_card_code (member_id);
