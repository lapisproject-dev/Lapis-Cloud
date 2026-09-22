-- Welle V1.4.15 "Kreditoren-/Debitorenbuchhaltung" -- siehe 48-open-item.kuml.kts fuer das
-- vollstaendige fachliche Modell und docs/architecture/open-items.adoc fuer die fachliche
-- Begruendung (Zwei-Buchungs-Regel, Verrechnung ohne Vier-Augen, eigenstaendiges Mahnwesen).
--
-- V32__bank_account.sql / V33__bank_account_fints.sql / V34__bank_account_fints_gap.sql werden
-- NICHT angefasst -- committet auf master, Checksummen verbraucht. Gleiche Disziplin, die V34
-- gegenueber V33 und V33 gegenueber V32 bereits dokumentiert.
--
-- Portability note: H2 MODE=PostgreSQL (die gesamte Testsuite, DatabaseConfig.kt). KEIN
-- partieller Unique-Index, DROP CONSTRAINT IF EXISTS vor jedem ADD CONSTRAINT, CREATE TABLE/INDEX
-- IF NOT EXISTS, KEIN ON DELETE CASCADE (Kinder werden anwendungsseitig geloescht), KEINE
-- Generated Column (counterparty_key wird in Kotlin berechnet, siehe CounterpartyKey.of).
--
-- Reihenfolge zwingend: open_item -> open_item_netting -> open_item_settlement.
-- open_item_settlement.netting_id FKt auf open_item_netting, open_item_netting.payable_item_id/
-- receivable_item_id FKen auf open_item. Kein Zyklus.

CREATE TABLE IF NOT EXISTS open_item (
    id                             UUID          NOT NULL PRIMARY KEY,
    direction                      VARCHAR(10)   NOT NULL,          -- RECEIVABLE = 10
    counterparty_name              VARCHAR(200)  NOT NULL,
    counterparty_key               VARCHAR(200)  NOT NULL,
    crm_contact_id                 UUID          NULL REFERENCES crm_contact (id),
    reference                      VARCHAR(100)  NULL,
    item_date                      DATE          NOT NULL,
    due_date                       DATE          NOT NULL,
    amount                         NUMERIC(12,2) NOT NULL,
    contra_account_id              UUID          NOT NULL REFERENCES ledger_account (id),
    sphere                         VARCHAR(34)   NOT NULL,          -- WIRTSCHAFTLICHER_GESCHAEFTSBETRIEB = 34
    status                         VARCHAR(18)   NOT NULL,          -- PARTIALLY_SETTLED = 18
    note                           VARCHAR(1000) NULL,
    created_by_member_id           UUID          NOT NULL REFERENCES member (id),
    created_at                     TIMESTAMP     NOT NULL,
    creation_journal_entry_id      UUID          NULL REFERENCES journal_entry (id),
    creation_posting_error         VARCHAR(500)  NULL,
    cancelled_at                   TIMESTAMP     NULL,
    cancelled_by_member_id         UUID          NULL REFERENCES member (id),
    cancellation_reason            VARCHAR(500)  NULL,
    cancellation_journal_entry_id  UUID          NULL REFERENCES journal_entry (id)
);

ALTER TABLE open_item DROP CONSTRAINT IF EXISTS chk_oi_direction;
ALTER TABLE open_item ADD CONSTRAINT chk_oi_direction
    CHECK (direction IN ('PAYABLE', 'RECEIVABLE'));

ALTER TABLE open_item DROP CONSTRAINT IF EXISTS chk_oi_status;
ALTER TABLE open_item ADD CONSTRAINT chk_oi_status
    CHECK (status IN ('OPEN', 'PARTIALLY_SETTLED', 'SETTLED', 'CANCELLED'));

-- V-3: JournalEntryBalance.validateBalanced verlangt jeden Posting-Betrag STRIKT positiv.
ALTER TABLE open_item DROP CONSTRAINT IF EXISTS chk_oi_amount_positive;
ALTER TABLE open_item ADD CONSTRAINT chk_oi_amount_positive
    CHECK (amount > 0);

ALTER TABLE open_item DROP CONSTRAINT IF EXISTS chk_oi_due_after_item;
ALTER TABLE open_item ADD CONSTRAINT chk_oi_due_after_item
    CHECK (due_date >= item_date);

ALTER TABLE open_item DROP CONSTRAINT IF EXISTS chk_oi_posting_error_state;
ALTER TABLE open_item ADD CONSTRAINT chk_oi_posting_error_state
    CHECK (creation_posting_error IS NULL OR creation_journal_entry_id IS NULL);

ALTER TABLE open_item DROP CONSTRAINT IF EXISTS chk_oi_cancelled_state;
ALTER TABLE open_item ADD CONSTRAINT chk_oi_cancelled_state
    CHECK ((status = 'CANCELLED' AND cancelled_at IS NOT NULL AND cancellation_reason IS NOT NULL)
        OR (status <> 'CANCELLED' AND cancelled_at IS NULL));

ALTER TABLE open_item DROP CONSTRAINT IF EXISTS chk_oi_sphere;
ALTER TABLE open_item ADD CONSTRAINT chk_oi_sphere
    CHECK (sphere IN ('IDEELLER_BEREICH', 'VERMOEGENSVERWALTUNG', 'ZWECKBETRIEB', 'WIRTSCHAFTLICHER_GESCHAEFTSBETRIEB'));

CREATE INDEX IF NOT EXISTS idx_oi_status           ON open_item (status);
CREATE INDEX IF NOT EXISTS idx_oi_direction        ON open_item (direction);
CREATE INDEX IF NOT EXISTS idx_oi_due_date         ON open_item (due_date);
CREATE INDEX IF NOT EXISTS idx_oi_counterparty_key ON open_item (counterparty_key);
CREATE INDEX IF NOT EXISTS idx_oi_crm_contact      ON open_item (crm_contact_id);
-- Keyset-Cursor-Index fuer listOpenItems.
CREATE INDEX IF NOT EXISTS idx_oi_keyset ON open_item (due_date, id);

-- Buchungs-Idempotenz -- plain (nicht partielle) Unique-Indizes auf nullable Spalten, mehrere
-- NULLs erlaubt auf H2 UND echtem Postgres (gleiche Eigenschaft wie uq_ter_posted_journal_entry).
CREATE UNIQUE INDEX IF NOT EXISTS uq_oi_creation_journal_entry    ON open_item (creation_journal_entry_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_oi_cancellation_journal_entry ON open_item (cancellation_journal_entry_id);

CREATE TABLE IF NOT EXISTS open_item_netting (
    id                          UUID          NOT NULL PRIMARY KEY,
    counterparty_key            VARCHAR(200)  NOT NULL,
    crm_contact_id              UUID          NULL REFERENCES crm_contact (id),
    amount                      NUMERIC(12,2) NOT NULL,
    payable_item_id             UUID          NOT NULL REFERENCES open_item (id),
    receivable_item_id          UUID          NOT NULL REFERENCES open_item (id),
    journal_entry_id            UUID          NULL REFERENCES journal_entry (id),
    posting_error               VARCHAR(500)  NULL,
    created_by_member_id        UUID          NOT NULL REFERENCES member (id),
    created_at                  TIMESTAMP     NOT NULL,
    reversed_at                 TIMESTAMP     NULL,
    reversed_by_member_id       UUID          NULL REFERENCES member (id),
    reversal_journal_entry_id   UUID          NULL REFERENCES journal_entry (id),
    reversal_reason             VARCHAR(500)  NULL
);

ALTER TABLE open_item_netting DROP CONSTRAINT IF EXISTS chk_oin_amount_positive;
ALTER TABLE open_item_netting ADD CONSTRAINT chk_oin_amount_positive
    CHECK (amount > 0);

ALTER TABLE open_item_netting DROP CONSTRAINT IF EXISTS chk_oin_distinct_items;
ALTER TABLE open_item_netting ADD CONSTRAINT chk_oin_distinct_items
    CHECK (payable_item_id <> receivable_item_id);

ALTER TABLE open_item_netting DROP CONSTRAINT IF EXISTS chk_oin_reversed_state;
ALTER TABLE open_item_netting ADD CONSTRAINT chk_oin_reversed_state
    CHECK ((reversed_at IS NULL AND reversed_by_member_id IS NULL AND reversal_reason IS NULL)
        OR (reversed_at IS NOT NULL AND reversed_by_member_id IS NOT NULL AND reversal_reason IS NOT NULL));

CREATE INDEX IF NOT EXISTS idx_oin_payable_item    ON open_item_netting (payable_item_id);
CREATE INDEX IF NOT EXISTS idx_oin_receivable_item ON open_item_netting (receivable_item_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_oin_journal_entry ON open_item_netting (journal_entry_id);

CREATE TABLE IF NOT EXISTS open_item_settlement (
    id                          UUID          NOT NULL PRIMARY KEY,
    open_item_id                UUID          NOT NULL REFERENCES open_item (id),
    kind                        VARCHAR(7)    NOT NULL,   -- PAYMENT/NETTING = 7
    amount                      NUMERIC(12,2) NOT NULL,
    settled_on                  DATE          NOT NULL,
    netting_id                  UUID          NULL REFERENCES open_item_netting (id),
    journal_entry_id            UUID          NULL REFERENCES journal_entry (id),
    posting_error               VARCHAR(500)  NULL,
    created_by_member_id        UUID          NOT NULL REFERENCES member (id),
    created_at                  TIMESTAMP     NOT NULL,
    reversed_at                 TIMESTAMP     NULL,
    reversed_by_member_id       UUID          NULL REFERENCES member (id),
    reversal_journal_entry_id   UUID          NULL REFERENCES journal_entry (id),
    reversal_reason             VARCHAR(500)  NULL
);

ALTER TABLE open_item_settlement DROP CONSTRAINT IF EXISTS chk_ois_kind;
ALTER TABLE open_item_settlement ADD CONSTRAINT chk_ois_kind
    CHECK (kind IN ('PAYMENT', 'NETTING'));

ALTER TABLE open_item_settlement DROP CONSTRAINT IF EXISTS chk_ois_amount_positive;
ALTER TABLE open_item_settlement ADD CONSTRAINT chk_ois_amount_positive
    CHECK (amount > 0);

ALTER TABLE open_item_settlement DROP CONSTRAINT IF EXISTS chk_ois_netting_shape;
ALTER TABLE open_item_settlement ADD CONSTRAINT chk_ois_netting_shape
    CHECK ((kind = 'NETTING' AND netting_id IS NOT NULL) OR (kind = 'PAYMENT' AND netting_id IS NULL));

ALTER TABLE open_item_settlement DROP CONSTRAINT IF EXISTS chk_ois_reversed_state;
ALTER TABLE open_item_settlement ADD CONSTRAINT chk_ois_reversed_state
    CHECK ((reversed_at IS NULL AND reversed_by_member_id IS NULL AND reversal_reason IS NULL)
        OR (reversed_at IS NOT NULL AND reversed_by_member_id IS NOT NULL AND reversal_reason IS NOT NULL));

CREATE INDEX IF NOT EXISTS idx_ois_open_item ON open_item_settlement (open_item_id);
CREATE INDEX IF NOT EXISTS idx_ois_netting   ON open_item_settlement (netting_id);

-- Negativtest-Anker gegen Doppelverrechnung: plain, nullable netting_id -- jede PAYMENT-Zeile hat
-- netting_id = NULL, mehrere davon sind erlaubt (plain Unique-Index, mehrere NULLs zulaessig).
-- Eine Verrechnung (eine netting_id) kann denselben Posten also nie zweimal treffen.
CREATE UNIQUE INDEX IF NOT EXISTS uq_ois_netting_item ON open_item_settlement (netting_id, open_item_id);

CREATE TABLE IF NOT EXISTS receivable_dunning_level (
    id             UUID          NOT NULL PRIMARY KEY,
    level_number   INT           NOT NULL,
    name           VARCHAR(100)  NOT NULL,
    grace_days     INT           NOT NULL,
    response_days  INT           NOT NULL,
    fee_amount     NUMERIC(12,2) NULL,
    active         BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMP     NOT NULL
);

ALTER TABLE receivable_dunning_level DROP CONSTRAINT IF EXISTS chk_rdl_grace_days;
ALTER TABLE receivable_dunning_level ADD CONSTRAINT chk_rdl_grace_days
    CHECK (grace_days >= 1 AND grace_days <= 365);

ALTER TABLE receivable_dunning_level DROP CONSTRAINT IF EXISTS chk_rdl_response_days;
ALTER TABLE receivable_dunning_level ADD CONSTRAINT chk_rdl_response_days
    CHECK (response_days >= 1 AND response_days <= 365);

ALTER TABLE receivable_dunning_level DROP CONSTRAINT IF EXISTS chk_rdl_fee_amount;
ALTER TABLE receivable_dunning_level ADD CONSTRAINT chk_rdl_fee_amount
    CHECK (fee_amount IS NULL OR (fee_amount >= 0 AND fee_amount <= 25.00));

CREATE UNIQUE INDEX IF NOT EXISTS uq_rdl_level_number ON receivable_dunning_level (level_number);

CREATE TABLE IF NOT EXISTS receivable_dunning_notice (
    id                            UUID          NOT NULL PRIMARY KEY,
    open_item_id                  UUID          NOT NULL REFERENCES open_item (id),
    receivable_dunning_level_id   UUID          NOT NULL REFERENCES receivable_dunning_level (id),
    cycle_number                  INT           NOT NULL DEFAULT 1,
    level_number                  INT           NOT NULL,
    level_name                    VARCHAR(100)  NOT NULL,
    fee_amount                    NUMERIC(12,2) NULL,
    amount_due                    NUMERIC(12,2) NOT NULL,
    status                        VARCHAR(9)    NOT NULL,   -- CANCELLED = 9
    issued_at                     TIMESTAMP     NOT NULL,
    respond_by                    DATE          NOT NULL,
    document_id                   UUID          NULL REFERENCES document (id),
    fee_journal_entry_id          UUID          NULL REFERENCES journal_entry (id),
    created_by_member_id          UUID          NULL REFERENCES member (id),
    cancelled_at                  TIMESTAMP     NULL,
    cancellation_reason           VARCHAR(500)  NULL
);

ALTER TABLE receivable_dunning_notice DROP CONSTRAINT IF EXISTS chk_rdn_status;
ALTER TABLE receivable_dunning_notice ADD CONSTRAINT chk_rdn_status
    CHECK (status IN ('ISSUED', 'SKIPPED', 'CANCELLED'));

CREATE INDEX IF NOT EXISTS idx_rdn_open_item ON receivable_dunning_notice (open_item_id);

-- Idempotenzanker (Zyklus-Zaehler statt partiellem Index, exakt wie uq_dunning_notice_slot).
CREATE UNIQUE INDEX IF NOT EXISTS uq_rdn_slot
    ON receivable_dunning_notice (open_item_id, cycle_number, level_number);

-- organization_settings: +3 Spalten. KEIN DEFAULT auf den beiden Konten-Zuordnungen (Jobs-Auflage,
-- gleiche Begruendung wie travel_expense_account_id/volunteer_allowance_account_id): NULL ==
-- "nicht konfiguriert" -> die Erfassung ist im Formular blockiert. receivable_dunning_enabled
-- bekommt DEFAULT FALSE, gleiche Konvention wie dunning_enabled/vat_enabled (disclaimer-freies,
-- aber weiterhin explizites Opt-in -- siehe ReceivableDunningService KDoc "drei Sicherungen").
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS receivables_account_id UUID NULL
    REFERENCES ledger_account (id);
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS payables_account_id UUID NULL
    REFERENCES ledger_account (id);
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS receivable_dunning_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- audit_log_entry.entity_type-CHECK-Verbreiterung: OPEN_ITEM (9), OPEN_ITEM_NETTING (17),
-- RECEIVABLE_DUNNING_NOTICE (25 Zeichen) -- laengstes neues Literal 25 Zeichen, passt in die
-- bestehende VARCHAR(29)-Breite (< CONFERENCE_STREAM_DESTINATION = 29). Dual-DROP-Muster wie
-- V11/V13/V14/V15/V20/V25/V26/V28/V29/V30/V32, vollstaendige Literalliste muss wiederholt werden,
-- H2 hat kein ALTER TYPE ... ADD VALUE.
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
        'BANK_ACCOUNT', 'OPEN_ITEM', 'OPEN_ITEM_NETTING', 'RECEIVABLE_DUNNING_NOTICE'
    ));

-- ---------------------------------------------------------------------------------------------
-- Operator-Note fuer ein bereits migriertes Instance (PROD_HOST/ELB), analog V9__dunning.sql /
-- V29__travel_expense.sql: dieses `ALTER TABLE audit_log_entry ADD CONSTRAINT
-- chk_audit_log_entry_entity_type` in V35 aendert NUR die inline/unbenannte Ausgangsform aus
-- V1__baseline.sql. Eine bereits laufende Produktionsinstanz hat den NAMED
-- chk_audit_log_entry_entity_type-Constraint bereits aus einer frueheren Migration -- Flyway
-- validiert die Checksumme jeder bereits angewendeten Migration (`validateOnMigrate = true`,
-- DatabaseConfig.kt); WEIL dieses File NEU ist (V35, keine vorher migrierte Version), ist dafuer
-- KEIN `flyway repair` noetig -- `flyway repair` ist nur fuer bereits migrierte Dateien noetig,
-- deren INHALT sich nachtraeglich geaendert hat. Diese Datei ist neu und wird beim naechsten
-- `flyway migrate`-Lauf einfach normal angewendet.
-- ---------------------------------------------------------------------------------------------
