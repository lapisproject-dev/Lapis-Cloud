-- Welle V1.4.5.1 "Kontoauszugs-Import (CSV/MT940)" -- see 40-bank-statement.kuml.kts (and the
-- Welle V1.4.5.1 addenda in 01-contribution.kuml.kts / 14-audit-log.kuml.kts) for the full
-- fachlich model.
--
-- V19__event_tickets.sql wird NICHT angefasst (deployed, Checksum verbraucht) -- gleiche Disziplin,
-- die V19 selbst gegenueber V18 dokumentiert. Scope-Cut dieser Welle: DATEV/lexoffice/sevDesk-Export
-- (V1.4.5.2 ff.), camt.052/053/054-XML, FinTS/HBCI, Teilzahlung/Splitting, Rücklastschrift-Erkennung
-- ueber diesen Import, automatische Spendenverbuchung -- keins davon ist Teil dieser Migration.
--
-- Kein `raw_line` -- die Rohzeile eines Kontoauszugs wird bewusst NICHT persistiert (DSGVO,
-- Design-Entscheidung). Sie erscheint nur transient im HTTP-Fehlerbody eines abgelehnten Imports
-- (422), nie in der Datenbank, nie im Log.
--
-- Kein `CREATE SEQUENCE` -- contribution.payment_reference wird per SecureRandom + Kollisions-Retry
-- vergeben (PaymentReferenceAllocator), nicht aus einer Postgres-Sequence. Siehe Plan § 3b fuer die
-- Begruendung (insertIgnore-Schleifen in ContributionService wuerden Sequence-Werte verbrennen, und
-- der GF(32)-Pruefwert ist in portablem SQL ohnehin nicht berechenbar).

CREATE TABLE IF NOT EXISTS bank_statement_import (
    id                UUID          NOT NULL PRIMARY KEY,
    format            VARCHAR(6)    NOT NULL,
    dialect           VARCHAR(32)   NOT NULL,
    file_name         VARCHAR(255)  NOT NULL,
    file_size_bytes   BIGINT        NOT NULL,
    file_digest       VARCHAR(64)   NOT NULL,
    account_iban      VARCHAR(34)   NULL,
    statement_from    DATE          NULL,
    statement_to      DATE          NULL,
    opening_balance   DECIMAL(14,2) NULL,
    closing_balance   DECIMAL(14,2) NULL,
    line_count        INT           NOT NULL,
    duplicate_count   INT           NOT NULL,
    auto_posted_count INT           NOT NULL,
    uploaded_by       UUID          NOT NULL,
    uploaded_at       TIMESTAMP     NOT NULL
);

CREATE TABLE IF NOT EXISTS bank_statement_line (
    id                            UUID          NOT NULL PRIMARY KEY,
    import_id                     UUID          NOT NULL,
    fingerprint                   VARCHAR(64)   NOT NULL,
    line_ordinal                  INT           NOT NULL,
    booking_date                  DATE          NOT NULL,
    value_date                    DATE          NULL,
    amount                        DECIMAL(14,2) NOT NULL,
    currency                      VARCHAR(3)    NOT NULL,
    counterparty_name             VARCHAR(140)  NULL,
    counterparty_iban_last4       VARCHAR(4)    NULL,
    counterparty_iban_ciphertext  VARCHAR(1024) NULL,
    purpose                       VARCHAR(2000) NULL,
    end_to_end_reference          VARCHAR(140)  NULL,
    booking_text                  VARCHAR(64)   NULL,
    status                        VARCHAR(10)   NOT NULL,
    match_explanation             VARCHAR(500)  NULL,
    matched_contribution_id       UUID          NULL,
    payment_transaction_id        UUID          NULL,
    resolved_by                   UUID          NULL,
    resolved_at                   TIMESTAMP     NULL,
    resolution_note               VARCHAR(500)  NULL
);

-- Review fix (MINOR): the 409 "already imported" check in BankStatementImportService was a plain
-- SELECT count() against file_digest with no DB constraint backing it -- a pure race under
-- concurrent uploads of the identical file (two Kassenwarte, or one double-clicking a slow upload
-- button): both requests could see count() == 0 under READ COMMITTED and both insert a
-- bank_statement_import row. The per-line fingerprint's own UNIQUE index still prevented any actual
-- double-booking, but the second "import" would be a ghost entry (lineCount > 0,
-- duplicateCount == lineCount) cluttering the import history. A real UNIQUE index makes the 409
-- check load-bearing instead of advisory.
CREATE UNIQUE INDEX IF NOT EXISTS uq_bank_statement_import_file_digest
    ON bank_statement_import (file_digest);

CREATE UNIQUE INDEX IF NOT EXISTS uq_bank_statement_line_fingerprint
    ON bank_statement_line (fingerprint);
CREATE INDEX IF NOT EXISTS idx_bank_statement_line_status
    ON bank_statement_line (status, booking_date DESC);
CREATE INDEX IF NOT EXISTS idx_bank_statement_line_import
    ON bank_statement_line (import_id, line_ordinal);
CREATE INDEX IF NOT EXISTS idx_bank_statement_import_uploaded
    ON bank_statement_import (uploaded_at DESC);

ALTER TABLE bank_statement_import DROP CONSTRAINT IF EXISTS chk_bank_statement_import_format;
ALTER TABLE bank_statement_import ADD CONSTRAINT chk_bank_statement_import_format
    CHECK (format IN ('CSV', 'MT940'));

ALTER TABLE bank_statement_line DROP CONSTRAINT IF EXISTS chk_bank_statement_line_status;
ALTER TABLE bank_statement_line ADD CONSTRAINT chk_bank_statement_line_status
    CHECK (status IN ('UNMATCHED', 'SUGGESTED', 'AMBIGUOUS', 'POSTED', 'IGNORED'));

ALTER TABLE bank_statement_line DROP CONSTRAINT IF EXISTS chk_bank_statement_line_iban_pair;
ALTER TABLE bank_statement_line ADD CONSTRAINT chk_bank_statement_line_iban_pair
    CHECK ((counterparty_iban_last4 IS NULL) = (counterparty_iban_ciphertext IS NULL));

ALTER TABLE bank_statement_import DROP CONSTRAINT IF EXISTS fk_bank_statement_import_uploaded_by;
ALTER TABLE bank_statement_import ADD CONSTRAINT fk_bank_statement_import_uploaded_by
    FOREIGN KEY (uploaded_by) REFERENCES member(id);

ALTER TABLE bank_statement_line DROP CONSTRAINT IF EXISTS fk_bank_statement_line_import_id;
ALTER TABLE bank_statement_line ADD CONSTRAINT fk_bank_statement_line_import_id
    FOREIGN KEY (import_id) REFERENCES bank_statement_import(id);

ALTER TABLE bank_statement_line DROP CONSTRAINT IF EXISTS fk_bank_statement_line_matched_contribution_id;
ALTER TABLE bank_statement_line ADD CONSTRAINT fk_bank_statement_line_matched_contribution_id
    FOREIGN KEY (matched_contribution_id) REFERENCES contribution(id);

ALTER TABLE bank_statement_line DROP CONSTRAINT IF EXISTS fk_bank_statement_line_payment_transaction_id;
ALTER TABLE bank_statement_line ADD CONSTRAINT fk_bank_statement_line_payment_transaction_id
    FOREIGN KEY (payment_transaction_id) REFERENCES payment_transaction(id);

ALTER TABLE bank_statement_line DROP CONSTRAINT IF EXISTS fk_bank_statement_line_resolved_by;
ALTER TABLE bank_statement_line ADD CONSTRAINT fk_bank_statement_line_resolved_by
    FOREIGN KEY (resolved_by) REFERENCES member(id);

-- ---------------------------------------------------------------------------
-- contribution.payment_reference -- see PaymentReferenceCode/PaymentReferenceAllocator (lapis-shared
-- / lapis-server). Nullable, no backfill for pre-existing rows (Plan § 3b/OF-5) -- a reference
-- vergeben nach dem Rechnungsversand waere in keinem bereits verschickten Verwendungszweck
-- auffindbar und daher wertlos. Multiple NULLs are allowed under a UNIQUE index on H2 (MODE=
-- PostgreSQL) as on real Postgres -- the same property uq_event_registration_ticket_code already
-- relies on.
-- ---------------------------------------------------------------------------
ALTER TABLE contribution ADD COLUMN IF NOT EXISTS payment_reference VARCHAR(12) NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_contribution_payment_reference
    ON contribution (payment_reference);

-- ---------------------------------------------------------------------------
-- audit_log_entry.entity_type CHECK-Verbreiterung fuer BANK_STATEMENT_IMPORT (siehe
-- 14-audit-log.kuml.kts). BANK_STATEMENT_IMPORT ist 21 Zeichen, passt in die bestehende
-- VARCHAR(29)-Breite -- keine Spaltenverbreiterung noetig, nur das dual-DROP-Muster wie
-- V11/V13/V14/V15.
-- ---------------------------------------------------------------------------
ALTER TABLE audit_log_entry DROP CONSTRAINT IF EXISTS audit_log_entry_entity_type_check;
ALTER TABLE audit_log_entry DROP CONSTRAINT IF EXISTS chk_audit_log_entry_entity_type;
ALTER TABLE audit_log_entry ADD CONSTRAINT chk_audit_log_entry_entity_type
    CHECK (entity_type IN (
        'JOURNAL_ENTRY', 'PARTY_DONATION_VERDICT', 'RESOLUTION', 'BOARD_MEMBERSHIP',
        'CONFERENCE_RECORDING', 'CONFERENCE_STREAM', 'CONFERENCE_STREAM_DESTINATION',
        'CONFERENCE_ROOM', 'SOCIAL_POST', 'ORGANIZATION_SETTINGS', 'SEPA_MANDATE',
        'SEPA_DEBIT_BATCH', 'DUNNING_NOTICE', 'MEMBER', 'PAYMENT_TRANSACTION', 'API_KEY',
        'WEBHOOK_ENDPOINT', 'BANK_STATEMENT_IMPORT'
    ));
