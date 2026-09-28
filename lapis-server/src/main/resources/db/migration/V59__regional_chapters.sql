-- Welle V1.9.13 "Gliederungsverwaltung (Landesverbaende)" -- Datenmodell-Teil. Rein additiv,
-- idempotent, H2 MODE=PostgreSQL- und Postgres-kompatibel, same discipline as V58: CREATE
-- TABLE/INDEX IF NOT EXISTS, DROP CONSTRAINT IF EXISTS vor jedem ADD CONSTRAINT, kein partieller
-- UNIQUE-Index (H2-Einschraenkung).
--
-- name_key (statt eines Index auf lower(name)) -- H2 kann keine Indizes auf Ausdruecke. Der
-- Dienst setzt name_key = name.trim().lowercase(Locale.ROOT), siehe RegionalChapterRules.

CREATE TABLE IF NOT EXISTS regional_chapter (
    id          UUID         NOT NULL PRIMARY KEY,
    name        VARCHAR(80)  NOT NULL,
    name_key    VARCHAR(80)  NOT NULL,
    created_at  TIMESTAMP    NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_regional_chapter_name_key ON regional_chapter(name_key);

ALTER TABLE member ADD COLUMN IF NOT EXISTS regional_chapter_id UUID NULL;
ALTER TABLE member DROP CONSTRAINT IF EXISTS fk_member_regional_chapter;
ALTER TABLE member ADD CONSTRAINT fk_member_regional_chapter
    FOREIGN KEY (regional_chapter_id) REFERENCES regional_chapter(id);
CREATE INDEX IF NOT EXISTS idx_member_regional_chapter ON member(regional_chapter_id);

-- active_for_member_id is the uniqueness guard for "at most one ACTIVE officer grant per member"
-- (H2 has no partial/expression indexes -- see 58-regional-chapter.kuml.kts file header). It is
-- deliberately NOT a Foreign Key -- see that file's header, section "active_for_member_id
-- deliberately WITHOUT FK". chk_regional_chapter_officer_active_consistency keeps it from ever
-- drifting out of sync with revoked_at.
CREATE TABLE IF NOT EXISTS regional_chapter_officer (
    id                    UUID      NOT NULL PRIMARY KEY,
    member_id             UUID      NOT NULL,
    regional_chapter_id   UUID      NOT NULL,
    granted_at            TIMESTAMP NOT NULL,
    granted_by_member_id  UUID      NULL,
    revoked_at            TIMESTAMP NULL,
    active_for_member_id  UUID      NULL
);

ALTER TABLE regional_chapter_officer DROP CONSTRAINT IF EXISTS fk_regional_chapter_officer_member;
ALTER TABLE regional_chapter_officer ADD CONSTRAINT fk_regional_chapter_officer_member
    FOREIGN KEY (member_id) REFERENCES member(id);

ALTER TABLE regional_chapter_officer DROP CONSTRAINT IF EXISTS fk_regional_chapter_officer_chapter;
ALTER TABLE regional_chapter_officer ADD CONSTRAINT fk_regional_chapter_officer_chapter
    FOREIGN KEY (regional_chapter_id) REFERENCES regional_chapter(id);

ALTER TABLE regional_chapter_officer DROP CONSTRAINT IF EXISTS fk_regional_chapter_officer_granted_by;
ALTER TABLE regional_chapter_officer ADD CONSTRAINT fk_regional_chapter_officer_granted_by
    FOREIGN KEY (granted_by_member_id) REFERENCES member(id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_regional_chapter_officer_active ON regional_chapter_officer(active_for_member_id);
CREATE INDEX IF NOT EXISTS idx_regional_chapter_officer_chapter ON regional_chapter_officer(regional_chapter_id);
CREATE INDEX IF NOT EXISTS idx_regional_chapter_officer_member ON regional_chapter_officer(member_id);

ALTER TABLE regional_chapter_officer DROP CONSTRAINT IF EXISTS chk_regional_chapter_officer_active_consistency;
ALTER TABLE regional_chapter_officer ADD CONSTRAINT chk_regional_chapter_officer_active_consistency
    CHECK (
        (revoked_at IS NULL AND active_for_member_id = member_id) OR
        (revoked_at IS NOT NULL AND active_for_member_id IS NULL)
    );

-- Two new AuditEntityType literals (REGIONAL_CHAPTER/REGIONAL_CHAPTER_OFFICER), appended LAST --
-- same DROP/ADD dance on the NAMED chk_audit_log_entry_entity_type constraint every prior wave's
-- migration establishes (see V55__article.sql for the most recent precedent). Per CLAUDE.md
-- OPERATOR NOTE: on an already-migrated real instance (PdV/ELB/Staging), run
-- ./gradlew :lapis-server:flywayRepair BEFORE deploying this version -- V1__baseline.sql's own
-- still-unnamed inline CHECK is ALSO widened below (in place), and Flyway detects that as a
-- checksum mismatch on an already-applied V1, exactly the precedent this file's own header in
-- CLAUDE.md documents for every prior entity_type widening.
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
                                'DOCUMENT', 'DOCUMENT_FOLDER', 'ARTICLE', 'REGIONAL_CHAPTER', 'REGIONAL_CHAPTER_OFFICER'
        ));
