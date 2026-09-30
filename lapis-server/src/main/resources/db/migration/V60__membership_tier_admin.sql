-- Welle V1.9.18 "Verwaltung der Mitgliedschaftsstufen" -- Datenmodell-Teil. Idempotent, H2
-- MODE=PostgreSQL- und Postgres-kompatibel, same discipline as V59: ADD COLUMN IF NOT EXISTS,
-- CREATE UNIQUE INDEX IF NOT EXISTS, DROP CONSTRAINT IF EXISTS vor jedem ADD CONSTRAINT, kein
-- Ausdrucks-Index und kein DO-Block (beides kann H2 nicht).
--
-- name_key (statt eines Index auf lower(name)) -- H2 kann keine Indizes auf Ausdruecke. Der Dienst
-- setzt name_key = MembershipTierRules.nameKey(MembershipTierRules.normalizeName(name)), siehe
-- ContributionService.createMembershipTier/updateMembershipTier.

ALTER TABLE membership_tier ADD COLUMN IF NOT EXISTS name_key VARCHAR(100) NULL;

-- Backfill der Altzeilen. SUBSTRING schuetzt vor einer (praktisch ausgeschlossenen) Verlaengerung
-- durch LOWER ueber die Spaltenbreite hinaus.
UPDATE membership_tier SET name_key = SUBSTRING(LOWER(TRIM(name)), 1, 100) WHERE name_key IS NULL;

-- Entdoppelung VOR dem Index: eine selbst betriebene Instanz kann in der Vergangenheit zwei Stufen
-- mit gleichem Namen (bis auf Gross-/Kleinschreibung) angelegt haben -- der UNIQUE-Index wuerde sonst
-- scheitern und der Server nicht mehr starten. Die Stufe mit der kleinsten Id behaelt ihren Schluessel,
-- alle weiteren bekommen '#<id>' angehaengt (63 + 1 + 36 = 100 Zeichen). Der angezeigte Name bleibt
-- unveraendert. CAST auf VARCHAR, weil Postgres (vor 18) keine MIN(uuid)-Aggregation kennt. Der Lauf
-- ist reihenfolgeunabhaengig: die Zeile mit der kleinsten Id wird nie veraendert und bleibt in jedem
-- Zwischenstand der Minimalwert ihrer Gruppe.
UPDATE membership_tier
SET name_key = SUBSTRING(name_key, 1, 63) || '#' || CAST(id AS VARCHAR(36))
WHERE CAST(id AS VARCHAR(36)) <> (
    SELECT MIN(CAST(t2.id AS VARCHAR(36))) FROM membership_tier t2 WHERE t2.name_key = membership_tier.name_key
);

ALTER TABLE membership_tier ALTER COLUMN name_key SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_membership_tier_name_key ON membership_tier(name_key);

-- One new AuditEntityType literal (MEMBERSHIP_TIER), appended LAST -- same DROP/ADD dance on the
-- NAMED chk_audit_log_entry_entity_type constraint every prior wave's migration establishes (see
-- V59__regional_chapters.sql for the most recent precedent). Per CLAUDE.md OPERATOR NOTE: on an
-- already-migrated real instance (PdV/ELB/Staging), run ./gradlew :lapis-server:flywayRepair BEFORE
-- deploying this version -- V1__baseline.sql's own still-unnamed inline CHECK is ALSO widened (in
-- place), and Flyway detects that as a checksum mismatch on an already-applied V1, exactly the
-- precedent every prior entity_type widening documents.
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
                                'MEMBERSHIP_TIER'
        ));
