-- Welle V1.4.5.3 "lexoffice-Live-Anbindung" -- see 43-accounting-export.kuml.kts for the full
-- fachlich model and the rationale for every non-obvious column. V24__member_date_of_death.sql
-- wird NICHT angefasst (deployed, Checksum verbraucht) -- gleiche Disziplin, die V24 selbst
-- gegenueber V23 dokumentiert.
--
-- Portability note: NO partial unique index (`CREATE UNIQUE INDEX ... WHERE`) appears in this
-- file. H2 in MODE=PostgreSQL (the mode the WHOLE test suite runs against, DatabaseConfig.kt)
-- rejects that syntax, and rejects the generated-column cross-dialect workaround too (Postgres
-- wants STORED, H2 rejects STORED) -- verified empirically twice already, see
-- V8__sepa_mandates.sql/V9__dunning.sql. This migration instead uses the SAME portable
-- application-maintained-shadow-column pattern V18__events.sql established for
-- event_registration.active_participant_key: accounting_export_item.exported_key and
-- accounting_export_run.active_key are both NULLABLE VARCHAR columns backed by a PLAIN unique
-- index -- multiple NULLs are allowed under a unique index on both H2 and real Postgres (the same
-- property uq_crm_contact_email/uq_event_registration_active_participant already rely on). Both
-- columns are written EXCLUSIVELY by AccountingExportStore.

CREATE TABLE IF NOT EXISTS accounting_export_connection (
    id                          UUID          NOT NULL PRIMARY KEY,
    provider                    VARCHAR(9)    NOT NULL,
    token_ciphertext            VARCHAR(1024) NULL,
    token_last4                 VARCHAR(4)    NULL,
    connected_company_name      VARCHAR(300)  NULL,
    last_tested_at              TIMESTAMP     NULL,
    zero_vat_acknowledged_at    TIMESTAMP     NULL,
    zero_vat_acknowledged_by    UUID          NULL,
    zero_vat_disclaimer_version VARCHAR(50)   NULL,
    zero_vat_disclaimer_sha256  VARCHAR(64)   NULL,
    created_at                  TIMESTAMP     NOT NULL,
    updated_at                  TIMESTAMP     NOT NULL
);

CREATE TABLE IF NOT EXISTS accounting_export_run (
    id              UUID        NOT NULL PRIMARY KEY,
    provider        VARCHAR(9)  NOT NULL,
    period_from     DATE        NOT NULL,
    period_to       DATE        NOT NULL,
    status          VARCHAR(22) NOT NULL,
    active_key      VARCHAR(16) NULL,
    started_by      UUID        NOT NULL,
    started_at      TIMESTAMP   NOT NULL,
    finished_at     TIMESTAMP   NULL,
    total_count     INT         NOT NULL,
    succeeded_count INT         NOT NULL,
    failed_count    INT         NOT NULL,
    skipped_count   INT         NOT NULL,
    unknown_count   INT         NOT NULL
);

CREATE TABLE IF NOT EXISTS accounting_export_item (
    id                  UUID          NOT NULL PRIMARY KEY,
    run_id              UUID          NOT NULL,
    provider            VARCHAR(9)    NOT NULL,
    journal_entry_id    UUID          NOT NULL,
    entry_date          DATE          NOT NULL,
    external_category_id VARCHAR(64)  NOT NULL,
    voucher_number      VARCHAR(32)   NOT NULL,
    direction           VARCHAR(7)    NOT NULL,
    gross_amount        DECIMAL(14,2) NOT NULL,
    status              VARCHAR(25)   NOT NULL,
    exported_key        VARCHAR(64)   NULL,
    external_voucher_id VARCHAR(64)   NULL,
    error_code          VARCHAR(50)   NULL,
    error_message       VARCHAR(500)  NULL,
    attempts            INT           NOT NULL,
    next_attempt_at      TIMESTAMP    NULL,
    claimed_at          TIMESTAMP     NULL,
    finished_at         TIMESTAMP     NULL
);

CREATE TABLE IF NOT EXISTS accounting_export_category_map (
    id                    UUID         NOT NULL PRIMARY KEY,
    provider              VARCHAR(9)   NOT NULL,
    ledger_account_id     UUID         NOT NULL,
    external_category_id  VARCHAR(64)  NOT NULL,
    external_category_name VARCHAR(200) NULL,
    mapped_by             UUID         NOT NULL,
    mapped_at             TIMESTAMP    NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_accounting_export_connection_provider
    ON accounting_export_connection (provider);

CREATE UNIQUE INDEX IF NOT EXISTS uq_accounting_export_run_active
    ON accounting_export_run (active_key);
CREATE INDEX IF NOT EXISTS idx_accounting_export_run_started
    ON accounting_export_run (started_at);

CREATE UNIQUE INDEX IF NOT EXISTS uq_accounting_export_item_exported
    ON accounting_export_item (exported_key);
CREATE INDEX IF NOT EXISTS idx_accounting_export_item_run_status
    ON accounting_export_item (run_id, status);
CREATE INDEX IF NOT EXISTS idx_accounting_export_item_journal
    ON accounting_export_item (journal_entry_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_accounting_export_category_map
    ON accounting_export_category_map (provider, ledger_account_id);

ALTER TABLE accounting_export_run DROP CONSTRAINT IF EXISTS fk_accounting_export_run_started_by;
ALTER TABLE accounting_export_run ADD CONSTRAINT fk_accounting_export_run_started_by
    FOREIGN KEY (started_by) REFERENCES member(id);

ALTER TABLE accounting_export_item DROP CONSTRAINT IF EXISTS fk_accounting_export_item_run_id;
ALTER TABLE accounting_export_item ADD CONSTRAINT fk_accounting_export_item_run_id
    FOREIGN KEY (run_id) REFERENCES accounting_export_run(id);

ALTER TABLE accounting_export_item DROP CONSTRAINT IF EXISTS fk_accounting_export_item_journal_entry_id;
ALTER TABLE accounting_export_item ADD CONSTRAINT fk_accounting_export_item_journal_entry_id
    FOREIGN KEY (journal_entry_id) REFERENCES journal_entry(id);

ALTER TABLE accounting_export_category_map DROP CONSTRAINT IF EXISTS fk_accounting_export_category_map_ledger_account_id;
ALTER TABLE accounting_export_category_map ADD CONSTRAINT fk_accounting_export_category_map_ledger_account_id
    FOREIGN KEY (ledger_account_id) REFERENCES ledger_account(id);

ALTER TABLE accounting_export_category_map DROP CONSTRAINT IF EXISTS fk_accounting_export_category_map_mapped_by;
ALTER TABLE accounting_export_category_map ADD CONSTRAINT fk_accounting_export_category_map_mapped_by
    FOREIGN KEY (mapped_by) REFERENCES member(id);

ALTER TABLE accounting_export_connection DROP CONSTRAINT IF EXISTS fk_accounting_export_connection_zero_vat_acknowledged_by;
ALTER TABLE accounting_export_connection ADD CONSTRAINT fk_accounting_export_connection_zero_vat_acknowledged_by
    FOREIGN KEY (zero_vat_acknowledged_by) REFERENCES member(id);

-- provider: only literal LEXOFFICE this wave.
ALTER TABLE accounting_export_connection DROP CONSTRAINT IF EXISTS chk_accounting_export_connection_provider;
ALTER TABLE accounting_export_connection ADD CONSTRAINT chk_accounting_export_connection_provider
    CHECK (provider IN ('LEXOFFICE'));

ALTER TABLE accounting_export_run DROP CONSTRAINT IF EXISTS chk_accounting_export_run_provider;
ALTER TABLE accounting_export_run ADD CONSTRAINT chk_accounting_export_run_provider
    CHECK (provider IN ('LEXOFFICE'));

ALTER TABLE accounting_export_item DROP CONSTRAINT IF EXISTS chk_accounting_export_item_provider;
ALTER TABLE accounting_export_item ADD CONSTRAINT chk_accounting_export_item_provider
    CHECK (provider IN ('LEXOFFICE'));

ALTER TABLE accounting_export_category_map DROP CONSTRAINT IF EXISTS chk_accounting_export_category_map_provider;
ALTER TABLE accounting_export_category_map ADD CONSTRAINT chk_accounting_export_category_map_provider
    CHECK (provider IN ('LEXOFFICE'));

ALTER TABLE accounting_export_run DROP CONSTRAINT IF EXISTS chk_accounting_export_run_status;
ALTER TABLE accounting_export_run ADD CONSTRAINT chk_accounting_export_run_status
    CHECK (status IN ('PLANNED', 'RUNNING', 'COMPLETED', 'COMPLETED_WITH_ERRORS', 'ABORTED'));

ALTER TABLE accounting_export_item DROP CONSTRAINT IF EXISTS chk_accounting_export_item_status;
ALTER TABLE accounting_export_item ADD CONSTRAINT chk_accounting_export_item_status
    CHECK (status IN ('PENDING', 'SENDING', 'SUCCEEDED', 'FAILED', 'SKIPPED_ALREADY_EXPORTED', 'UNKNOWN'));

ALTER TABLE accounting_export_item DROP CONSTRAINT IF EXISTS chk_accounting_export_item_direction;
ALTER TABLE accounting_export_item ADD CONSTRAINT chk_accounting_export_item_direction
    CHECK (direction IN ('INCOME', 'EXPENSE'));

-- Portability note (see file header): the DB-level backstop for the two application-maintained
-- shadow columns -- exported_key is set iff status == SUCCEEDED; active_key is set iff status is
-- non-terminal. Same shape as V18__events.sql's chk_event_registration_active_key.
ALTER TABLE accounting_export_item DROP CONSTRAINT IF EXISTS chk_accounting_export_item_exported_key;
ALTER TABLE accounting_export_item ADD CONSTRAINT chk_accounting_export_item_exported_key
    CHECK ((status = 'SUCCEEDED') = (exported_key IS NOT NULL));

ALTER TABLE accounting_export_run DROP CONSTRAINT IF EXISTS chk_accounting_export_run_active_key;
ALTER TABLE accounting_export_run ADD CONSTRAINT chk_accounting_export_run_active_key
    CHECK ((status IN ('PLANNED', 'RUNNING')) = (active_key IS NOT NULL));

-- audit_log_entry.entity_type CHECK-Verbreiterung fuer ACCOUNTING_EXPORT_CONNECTION (siehe
-- 14-audit-log.kuml.kts) -- same dual-DROP pattern V11/V13/V14/V15/V20 already use. 27 characters,
-- widens the existing VARCHAR(29) constant no further (already wide enough).
ALTER TABLE audit_log_entry DROP CONSTRAINT IF EXISTS audit_log_entry_entity_type_check;
ALTER TABLE audit_log_entry DROP CONSTRAINT IF EXISTS chk_audit_log_entry_entity_type;
ALTER TABLE audit_log_entry ADD CONSTRAINT chk_audit_log_entry_entity_type
    CHECK (entity_type IN (
        'JOURNAL_ENTRY', 'PARTY_DONATION_VERDICT', 'RESOLUTION', 'BOARD_MEMBERSHIP',
        'CONFERENCE_RECORDING', 'CONFERENCE_STREAM', 'CONFERENCE_STREAM_DESTINATION',
        'CONFERENCE_ROOM', 'SOCIAL_POST', 'ORGANIZATION_SETTINGS', 'SEPA_MANDATE',
        'SEPA_DEBIT_BATCH', 'DUNNING_NOTICE', 'MEMBER', 'PAYMENT_TRANSACTION', 'API_KEY',
        'WEBHOOK_ENDPOINT', 'BANK_STATEMENT_IMPORT', 'ACCOUNTING_EXPORT_CONNECTION'
    ));
