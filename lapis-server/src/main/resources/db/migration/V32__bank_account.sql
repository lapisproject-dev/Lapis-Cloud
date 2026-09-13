-- Welle V1.4.14 "Mehrere Bankkonten" -- fachliches Modell: 47-bank-account.kuml.kts, Addendum in
-- 40-bank-statement.kuml.kts (bank_statement_import.bank_account_id) und 14-audit-log.kuml.kts
-- (BANK_ACCOUNT entity_type). Architekturdoku: docs/architecture/bank-account.adoc.
--
-- SCOPE-CUT dieser Welle (bewusst, siehe docs/architecture/bank-account.adoc "Scope"): FinTS/HBCI
-- Live-Abruf ist NICHT Teil dieser Migration. Es gibt noch keine offene Lizenzentscheidung
-- (LGPL-2.1 hbci4j-core) und keine Bank-Protokoll-Implementierung -- nur das Mehrkonten-Fundament
-- (mehrere `bank_account`-Zeilen, jeder Datei-Import einem Konto zugeordnet). Die Spalten fuer
-- FinTS-Zugangsdaten/Live-Status folgen als eigene Migration, sobald diese Entscheidung getroffen
-- ist.
--
-- V31__vat.sql wird NICHT angefasst -- committet auf master (a535e20) und auf jeder lokalen
-- Entwickler-/Testdatenbank bereits ausgefuehrt, die Checksumme ist verbraucht; dass der Commit
-- noch nicht gepusht ist, aendert daran nichts (Flyway-Identitaet != Git-Historie). Gleiche
-- Disziplin, die V31 gegenueber V30 und V30 gegenueber V29 dokumentiert.
--
-- Portability: H2 in MODE=PostgreSQL (gesamte Testsuite, DatabaseConfig.kt). DROP CONSTRAINT IF
-- EXISTS vor jedem ADD CONSTRAINT, kein partieller Index (deshalb die default_marker-Shadow-
-- Spalte, gleiches Muster wie V28__contribution_relief.sql), kein ON DELETE CASCADE. Kein
-- RANDOM_UUID()/gen_random_uuid() in dieser Migration -- der Legacy-Backfill (aus einer
-- vorhandenen organization_settings.bank_iban) laeuft Kotlin-seitig, siehe
-- BankAccountStore.backfillLegacyDefaultAccountIfNeeded (aufgerufen beim Applikationsstart), nicht
-- per SQL INSERT...SELECT -- RANDOM_UUID() ist H2-spezifisch und auf echtem PostgreSQL nicht
-- portabel.

-- ---------------------------------------------------------------------------
-- bank_account -- MEHRERE Bankkonten je Organisation. Ersetzt organization_settings.bank_iban/
-- bank_bic NICHT als Spalte, wird aber deren alleiniger Schreiber, sobald mindestens eine
-- bank_account-Zeile existiert (siehe OrganizationSettingsService.updateOrganizationSettings) --
-- die beiden Altspalten bleiben ein Spiegel des Default-Kontos, weil SEPA-Creditor (pain.008),
-- Beitragsrechnung- und Mahnungs-Briefkopf sie unveraendert lesen.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS bank_account (
    id           UUID          NOT NULL PRIMARY KEY,
    label        VARCHAR(120)  NOT NULL,
    iban         VARCHAR(34)   NOT NULL,
    bic          VARCHAR(11)   NULL,
    bank_name    VARCHAR(140)  NULL,
    is_default   BOOLEAN       NOT NULL DEFAULT FALSE,
    -- Application-maintained Shadow-Spalte: 'X' genau auf der is_default-Zeile, sonst NULL. Traegt
    -- uq_bank_account_default (plain UNIQUE, mehrere NULLs erlaubt) -- ein partieller Index WHERE
    -- is_default wird von H2 MODE=PostgreSQL abgelehnt (V28-Praezedenz).
    default_marker VARCHAR(1)  NULL,
    created_by   UUID          NOT NULL,
    created_at   TIMESTAMP     NOT NULL,
    updated_at   TIMESTAMP     NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_bank_account_iban    ON bank_account (iban);
CREATE UNIQUE INDEX IF NOT EXISTS uq_bank_account_default ON bank_account (default_marker);

ALTER TABLE bank_account DROP CONSTRAINT IF EXISTS chk_bank_account_default_marker;
ALTER TABLE bank_account ADD CONSTRAINT chk_bank_account_default_marker
    CHECK ((is_default = TRUE AND default_marker = 'X') OR (is_default = FALSE AND default_marker IS NULL));

ALTER TABLE bank_account DROP CONSTRAINT IF EXISTS fk_bank_account_created_by;
ALTER TABLE bank_account ADD CONSTRAINT fk_bank_account_created_by
    FOREIGN KEY (created_by) REFERENCES member (id);

-- ---------------------------------------------------------------------------
-- bank_statement_import.bank_account_id -- welches Konto ein Datei-Import betrifft. Nullable hier,
-- weil zum Zeitpunkt dieser Migration noch KEINE bank_account-Zeile existiert -- ein SQL-seitiges
-- Backfill koennte also noch gar kein Ziel zuweisen. Jeder VOR dieser Welle importierte Datensatz
-- bleibt zunaechst NULL und wird stattdessen Kotlin-seitig adoptiert, in genau dem Moment, in dem
-- die Konto-Identitaet erstmals bekannt wird (siehe
-- BankAccountStore.adoptLegacyBankStatementImports, aufgerufen sowohl aus
-- backfillLegacyDefaultAccountIfNeeded als auch aus create()s allererstem Konto) -- nicht per SQL
-- INSERT...SELECT hier, aus demselben RANDOM_UUID()-Portabilitaetsgrund wie beim Hauptkonto-Insert
-- selbst (siehe Kommentar oben).
-- ---------------------------------------------------------------------------
ALTER TABLE bank_statement_import ADD COLUMN IF NOT EXISTS bank_account_id UUID NULL;

ALTER TABLE bank_statement_import DROP CONSTRAINT IF EXISTS fk_bank_statement_import_bank_account_id;
ALTER TABLE bank_statement_import ADD CONSTRAINT fk_bank_statement_import_bank_account_id
    FOREIGN KEY (bank_account_id) REFERENCES bank_account (id);

CREATE INDEX IF NOT EXISTS idx_bank_statement_import_account ON bank_statement_import (bank_account_id, uploaded_at DESC);

-- ---------------------------------------------------------------------------
-- audit_log_entry.entity_type-CHECK-Verbreiterung: BANK_ACCOUNT (12 Zeichen, passt in die
-- bestehende VARCHAR(29)-Breite) -- Dual-DROP-Muster wie V11/V13/V14/V15/V20/V25/V26/V28/V29/V30,
-- vollstaendige Literalliste muss wiederholt werden, H2 hat kein ALTER TYPE ... ADD VALUE.
-- ---------------------------------------------------------------------------
ALTER TABLE audit_log_entry DROP CONSTRAINT IF EXISTS audit_log_entry_entity_type_check;
ALTER TABLE audit_log_entry DROP CONSTRAINT IF EXISTS chk_audit_log_entry_entity_type;
ALTER TABLE audit_log_entry ADD CONSTRAINT chk_audit_log_entry_entity_type
    CHECK (entity_type IN (
        'JOURNAL_ENTRY', 'PARTY_DONATION_VERDICT', 'RESOLUTION', 'BOARD_MEMBERSHIP',
        'CONFERENCE_RECORDING', 'CONFERENCE_STREAM', 'CONFERENCE_STREAM_DESTINATION',
        'CONFERENCE_ROOM', 'SOCIAL_POST', 'ORGANIZATION_SETTINGS', 'SEPA_MANDATE',
        'SEPA_DEBIT_BATCH', 'DUNNING_NOTICE', 'MEMBER', 'PAYMENT_TRANSACTION', 'API_KEY',
        'WEBHOOK_ENDPOINT', 'BANK_STATEMENT_IMPORT', 'ACCOUNTING_EXPORT_CONNECTION',
        'ACCOUNTING_EXPORT_RUN', 'ACCOUNTING_EXPORT_MAPPING', 'CONTRIBUTION_RELIEF_REQUEST',
        'TRAVEL_EXPENSE_REPORT', 'VOLUNTEER_ALLOWANCE_PAYMENT', 'VOLUNTEER_DECLARATION',
        'BANK_ACCOUNT'
    ));
