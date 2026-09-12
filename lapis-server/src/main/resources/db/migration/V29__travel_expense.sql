-- Welle V1.4.11 "Reisekostenabrechnung fuer Vorstand und Funktionstraeger" -- siehe
-- 45-travel-expense.kuml.kts fuer das vollstaendige fachliche Modell.
--
-- V28__contribution_relief.sql wird NICHT angefasst (gepusht, Checksum verbraucht) -- gleiche
-- Disziplin, die V28 selbst gegenueber V27 dokumentiert.
--
-- Portability note: KEIN partieller Unique-Index, DROP CONSTRAINT IF EXISTS vor jedem ADD
-- CONSTRAINT, plain (nicht partieller) Unique-Index auf einer nullable Spalte fuer
-- posted_journal_entry_id -- H2 in MODE=PostgreSQL (die gesamte Testsuite, DatabaseConfig.kt)
-- lehnt einen partiellen Unique-Index ab, ein plain Unique-Index erlaubt mehrere NULLs auf H2 UND
-- echtem Postgres, exakt die Eigenschaft, auf die uq_crr_active_request/
-- uq_event_registration_active_participant bereits bauen. KEIN ON DELETE CASCADE (Hauskonvention:
-- Kinder werden anwendungsseitig geloescht, siehe TravelExpenseService.removeLine).
--
-- ERSTE "Geld raus"-Buchung dieses Repos -- siehe TravelExpensePostingBridge KDoc.
-- posted_journal_entry_id ist der Idempotenz-Anker (uq_ter_posted_journal_entry unten).

CREATE TABLE IF NOT EXISTS travel_expense_report (
    id                        UUID          NOT NULL PRIMARY KEY,
    subject_member_id         UUID          NOT NULL REFERENCES member (id),
    status                    VARCHAR(9)    NOT NULL,          -- REQUESTED/WITHDRAWN = 9
    purpose                   VARCHAR(200)  NOT NULL,
    travel_from               DATE          NOT NULL,
    travel_to                 DATE          NOT NULL,
    total_amount              NUMERIC(12,2) NOT NULL,
    created_at                TIMESTAMP     NOT NULL,
    requested_by              UUID          NOT NULL REFERENCES member (id),
    submitted_at              TIMESTAMP     NULL,
    decided_at                TIMESTAMP     NULL,
    decided_by                UUID          NULL REFERENCES member (id),
    decision_note             VARCHAR(1000) NULL,
    executed_at               TIMESTAMP     NULL,
    posted_journal_entry_id   UUID          NULL REFERENCES journal_entry (id),
    execution_error           VARCHAR(500)  NULL
);

ALTER TABLE travel_expense_report DROP CONSTRAINT IF EXISTS chk_ter_status;
ALTER TABLE travel_expense_report ADD CONSTRAINT chk_ter_status
    CHECK (status IN ('DRAFT','REQUESTED','APPROVED','REJECTED','EXECUTED','WITHDRAWN'));

ALTER TABLE travel_expense_report DROP CONSTRAINT IF EXISTS chk_ter_travel_range;
ALTER TABLE travel_expense_report ADD CONSTRAINT chk_ter_travel_range
    CHECK (travel_to >= travel_from);

ALTER TABLE travel_expense_report DROP CONSTRAINT IF EXISTS chk_ter_total_nonneg;
ALTER TABLE travel_expense_report ADD CONSTRAINT chk_ter_total_nonneg
    CHECK (total_amount >= 0);

-- Jede ENTSCHIEDENE Zeile traegt eine Begruendung -- auch die Ablehnung (Pflichtbegruendung,
-- Design-Ruling): sie ist die einzige Erklaerung, die das Mitglied bekommt.
ALTER TABLE travel_expense_report DROP CONSTRAINT IF EXISTS chk_ter_decided_needs_note;
ALTER TABLE travel_expense_report ADD CONSTRAINT chk_ter_decided_needs_note
    CHECK (status NOT IN ('APPROVED','EXECUTED','REJECTED') OR decision_note IS NOT NULL);

-- execution_error nur im Zustand APPROVED sinnvoll (Spiegel von chk_crr_execution_error_state).
ALTER TABLE travel_expense_report DROP CONSTRAINT IF EXISTS chk_ter_execution_error_state;
ALTER TABLE travel_expense_report ADD CONSTRAINT chk_ter_execution_error_state
    CHECK (execution_error IS NULL OR status = 'APPROVED');

-- Buchungs-Idempotenz, Haelfte 1 (Haelfte 2 = uq_ter_posted_journal_entry unten):
-- EXECUTED <=> genau eine Buchung. Bikonditional, portabel als zwei-seitiges OR geschrieben.
ALTER TABLE travel_expense_report DROP CONSTRAINT IF EXISTS chk_ter_posted_entry_state;
ALTER TABLE travel_expense_report ADD CONSTRAINT chk_ter_posted_entry_state
    CHECK ((status  = 'EXECUTED' AND posted_journal_entry_id IS NOT NULL)
        OR (status <> 'EXECUTED' AND posted_journal_entry_id IS NULL));

-- Bewusst NICHT bikonditional: WITHDRAWN ist auch aus DRAFT erreichbar, dann gibt es kein
-- submitted_at. Folge (dokumentiert): ein aus DRAFT zurueckgezogener Antrag erscheint NIE in der
-- Vorstands-Warteschlange -- der Vorstand hat ihn auch nie gesehen.
ALTER TABLE travel_expense_report DROP CONSTRAINT IF EXISTS chk_ter_submitted_at_state;
ALTER TABLE travel_expense_report ADD CONSTRAINT chk_ter_submitted_at_state
    CHECK (status IN ('DRAFT','WITHDRAWN') OR submitted_at IS NOT NULL);

CREATE INDEX IF NOT EXISTS idx_ter_subject      ON travel_expense_report (subject_member_id);
CREATE INDEX IF NOT EXISTS idx_ter_status       ON travel_expense_report (status);
CREATE INDEX IF NOT EXISTS idx_ter_submitted_at ON travel_expense_report (submitted_at);
-- Plain (nicht partiell) -- mehrere NULLs erlaubt, gleiche Eigenschaft wie uq_crr_active_request.
CREATE UNIQUE INDEX IF NOT EXISTS uq_ter_posted_journal_entry
    ON travel_expense_report (posted_journal_entry_id);

CREATE TABLE IF NOT EXISTS travel_expense_line (
    id             UUID          NOT NULL PRIMARY KEY,
    report_id      UUID          NOT NULL REFERENCES travel_expense_report (id),
    kind           VARCHAR(9)    NOT NULL,       -- RECEIPTED = 9
    description    VARCHAR(200)  NOT NULL,
    kilometers     NUMERIC(10,2) NULL,
    days           INT           NULL,
    rate_snapshot  NUMERIC(10,4) NULL,
    amount         NUMERIC(12,2) NOT NULL,
    created_at     TIMESTAMP     NOT NULL
);

ALTER TABLE travel_expense_line DROP CONSTRAINT IF EXISTS chk_tel_kind;
ALTER TABLE travel_expense_line ADD CONSTRAINT chk_tel_kind
    CHECK (kind IN ('MILEAGE', 'PER_DIEM', 'RECEIPTED'));

-- Payload-Exklusivitaet, gleiche Grammatik wie chk_crr_payload_shape. `kind` ist NOT NULL,
-- also nie eine drei-wertige-Logik-Falle. rate_snapshot ist fuer MILEAGE/PER_DIEM PFLICHT --
-- addLine schreibt ihn sofort (provisorisch), submitReport friert ihn ein.
ALTER TABLE travel_expense_line DROP CONSTRAINT IF EXISTS chk_tel_kind_shape;
ALTER TABLE travel_expense_line ADD CONSTRAINT chk_tel_kind_shape
    CHECK ((kind = 'MILEAGE'   AND kilometers IS NOT NULL AND rate_snapshot IS NOT NULL AND days IS NULL)
        OR (kind = 'PER_DIEM'  AND days       IS NOT NULL AND rate_snapshot IS NOT NULL AND kilometers IS NULL)
        OR (kind = 'RECEIPTED' AND kilometers IS NULL AND days IS NULL AND rate_snapshot IS NULL));

-- V-3: JournalEntryBalance.validateBalanced verlangt jeden Posting-Betrag STRIKT positiv.
ALTER TABLE travel_expense_line DROP CONSTRAINT IF EXISTS chk_tel_amount_positive;
ALTER TABLE travel_expense_line ADD CONSTRAINT chk_tel_amount_positive
    CHECK (amount > 0);

ALTER TABLE travel_expense_line DROP CONSTRAINT IF EXISTS chk_tel_kilometers_range;
ALTER TABLE travel_expense_line ADD CONSTRAINT chk_tel_kilometers_range
    CHECK (kilometers IS NULL OR (kilometers > 0 AND kilometers <= 10000));

ALTER TABLE travel_expense_line DROP CONSTRAINT IF EXISTS chk_tel_days_range;
ALTER TABLE travel_expense_line ADD CONSTRAINT chk_tel_days_range
    CHECK (days IS NULL OR (days >= 1 AND days <= 60));

CREATE INDEX IF NOT EXISTS idx_tel_report ON travel_expense_line (report_id);

CREATE TABLE IF NOT EXISTS travel_expense_receipt (
    id                 UUID         NOT NULL PRIMARY KEY,
    line_id            UUID         NOT NULL REFERENCES travel_expense_line (id),
    storage_key        VARCHAR(200) NOT NULL,
    original_filename  VARCHAR(255) NOT NULL,
    mime_type          VARCHAR(64)  NOT NULL,
    size_bytes         BIGINT       NOT NULL,
    sha256             VARCHAR(64)  NOT NULL,
    uploaded_by        UUID         NOT NULL REFERENCES member (id),
    uploaded_at        TIMESTAMP    NOT NULL
);

-- MIME-Allowlist DB-seitig, nicht nur anwendungsseitig. KEIN image/svg+xml (XSS-Vektor,
-- Forstall-Ruling) -- und mime_type wird serverseitig aus den Magic Bytes abgeleitet, der
-- Client-Content-Type wird verworfen (siehe TravelExpenseReceiptRoutes KDoc).
ALTER TABLE travel_expense_receipt DROP CONSTRAINT IF EXISTS chk_terc_mime;
ALTER TABLE travel_expense_receipt ADD CONSTRAINT chk_terc_mime
    CHECK (mime_type IN ('application/pdf', 'image/jpeg', 'image/png'));

ALTER TABLE travel_expense_receipt DROP CONSTRAINT IF EXISTS chk_terc_size;
ALTER TABLE travel_expense_receipt ADD CONSTRAINT chk_terc_size
    CHECK (size_bytes > 0 AND size_bytes <= 10485760);

CREATE INDEX IF NOT EXISTS idx_terc_line ON travel_expense_receipt (line_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_terc_storage_key ON travel_expense_receipt (storage_key);

-- organization_settings: +3 Spalten. KEIN DEFAULT auf den beiden Saetzen (Jobs-Auflage):
-- ein voreingetragener 0.30-Wert waere ein Gesetzesdatum im Code, das nie nachgefuehrt wird.
-- NULL == "nicht konfiguriert" -> der betroffene Zeilentyp ist im Formular deaktiviert.
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS travel_expense_account_id UUID NULL
    REFERENCES ledger_account (id);
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS travel_mileage_rate_per_km NUMERIC(10,4) NULL;
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS travel_per_diem_rate NUMERIC(12,2) NULL;

ALTER TABLE organization_settings DROP CONSTRAINT IF EXISTS chk_os_travel_rates_positive;
ALTER TABLE organization_settings ADD CONSTRAINT chk_os_travel_rates_positive
    CHECK ((travel_mileage_rate_per_km IS NULL OR travel_mileage_rate_per_km > 0)
       AND (travel_per_diem_rate       IS NULL OR travel_per_diem_rate       > 0));

-- audit_log_entry.entity_type-CHECK-Verbreiterung: TRAVEL_EXPENSE_REPORT (22 Zeichen, passt in
-- VARCHAR(29)). Dual-DROP-Muster wie V11/V13/V14/V15/V20/V25/V26/V28 -- die vollstaendige
-- Literalliste muss wiederholt werden, H2 hat kein `ALTER TYPE ... ADD VALUE`.
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
        'TRAVEL_EXPENSE_REPORT'
    ));
