-- Welle V1.4.12 "Übungsleiter- und Ehrenamtspauschale" (§3 Nr. 26 / 26a EStG) -- siehe
-- 46-volunteer-allowance.kuml.kts fuer das vollstaendige fachliche Modell.
--
-- V29__travel_expense.sql wird NICHT angefasst (gepusht, Checksum verbraucht) -- gleiche
-- Disziplin, die V29 selbst gegenueber V28 dokumentiert.
--
-- Portability note: KEIN partieller Unique-Index, DROP CONSTRAINT IF EXISTS vor jedem ADD
-- CONSTRAINT, plain (nicht partieller) Unique-Index auf einer nullable Spalte fuer
-- posted_journal_entry_id -- H2 in MODE=PostgreSQL (die gesamte Testsuite, DatabaseConfig.kt)
-- lehnt einen partiellen Unique-Index ab, ein plain Unique-Index erlaubt mehrere NULLs auf H2 UND
-- echtem Postgres, exakt die Eigenschaft, auf die uq_ter_posted_journal_entry bereits baut. KEIN
-- ON DELETE CASCADE (Hauskonvention).
--
-- ZWEITE "Geld raus"-Buchung dieses Repos (nach V1.4.11 Reisekosten) -- siehe
-- VolunteerAllowancePostingBridge KDoc. posted_journal_entry_id ist der Idempotenz-Anker
-- (uq_vap_posted_journal_entry unten).

CREATE TABLE IF NOT EXISTS volunteer_allowance_payment (
    id                          UUID          NOT NULL PRIMARY KEY,
    subject_member_id          UUID          NOT NULL REFERENCES member (id),
    category                    VARCHAR(10)   NOT NULL,   -- INSTRUCTOR = 10
    status                      VARCHAR(9)    NOT NULL,   -- REQUESTED/WITHDRAWN = 9
    amount                       NUMERIC(12,2) NOT NULL,
    activity_description        VARCHAR(200)  NOT NULL,
    payment_date                 DATE          NOT NULL,
    created_at                   TIMESTAMP     NOT NULL,
    requested_by                 UUID          NOT NULL REFERENCES member (id),
    submitted_at                 TIMESTAMP     NULL,
    decided_at                   TIMESTAMP     NULL,
    decided_by                   UUID          NULL REFERENCES member (id),
    decision_note                VARCHAR(1000) NULL,
    executed_at                  TIMESTAMP     NULL,
    posted_journal_entry_id      UUID          NULL REFERENCES journal_entry (id),
    execution_error              VARCHAR(500)  NULL,
    prior_total_snapshot         NUMERIC(12,2) NULL,
    free_amount_snapshot         NUMERIC(12,2) NULL,
    exceeding_amount_snapshot    NUMERIC(12,2) NULL,
    cap_disclaimer_version       VARCHAR(50)   NULL,
    cap_disclaimer_sha256        VARCHAR(64)   NULL,
    cap_acknowledged_by          UUID          NULL REFERENCES member (id),
    cap_acknowledged_at          TIMESTAMP     NULL
);

ALTER TABLE volunteer_allowance_payment DROP CONSTRAINT IF EXISTS chk_vap_category;
ALTER TABLE volunteer_allowance_payment ADD CONSTRAINT chk_vap_category
    CHECK (category IN ('INSTRUCTOR', 'HONORARY'));

ALTER TABLE volunteer_allowance_payment DROP CONSTRAINT IF EXISTS chk_vap_status;
ALTER TABLE volunteer_allowance_payment ADD CONSTRAINT chk_vap_status
    CHECK (status IN ('DRAFT', 'REQUESTED', 'APPROVED', 'REJECTED', 'EXECUTED', 'WITHDRAWN'));

-- V-3: JournalEntryBalance.validateBalanced verlangt jeden Posting-Betrag STRIKT positiv.
ALTER TABLE volunteer_allowance_payment DROP CONSTRAINT IF EXISTS chk_vap_amount_positive;
ALTER TABLE volunteer_allowance_payment ADD CONSTRAINT chk_vap_amount_positive
    CHECK (amount > 0);

-- Jede ENTSCHIEDENE Zeile traegt eine Begruendung -- auch die Ablehnung. Spiegel chk_ter_decided_needs_note.
ALTER TABLE volunteer_allowance_payment DROP CONSTRAINT IF EXISTS chk_vap_decided_needs_note;
ALTER TABLE volunteer_allowance_payment ADD CONSTRAINT chk_vap_decided_needs_note
    CHECK (status NOT IN ('APPROVED', 'EXECUTED', 'REJECTED') OR decision_note IS NOT NULL);

-- execution_error nur im Zustand APPROVED sinnvoll. Spiegel chk_ter_execution_error_state.
ALTER TABLE volunteer_allowance_payment DROP CONSTRAINT IF EXISTS chk_vap_execution_error_state;
ALTER TABLE volunteer_allowance_payment ADD CONSTRAINT chk_vap_execution_error_state
    CHECK (execution_error IS NULL OR status = 'APPROVED');

-- Buchungs-Idempotenz, Haelfte 1 (Haelfte 2 = uq_vap_posted_journal_entry unten):
-- EXECUTED <=> genau eine Buchung. Bikonditional, portabel als zwei-seitiges OR geschrieben.
ALTER TABLE volunteer_allowance_payment DROP CONSTRAINT IF EXISTS chk_vap_posted_entry_state;
ALTER TABLE volunteer_allowance_payment ADD CONSTRAINT chk_vap_posted_entry_state
    CHECK ((status  = 'EXECUTED' AND posted_journal_entry_id IS NOT NULL)
        OR (status <> 'EXECUTED' AND posted_journal_entry_id IS NULL));

-- Bewusst NICHT bikonditional: WITHDRAWN ist auch aus DRAFT erreichbar, dann gibt es kein
-- submitted_at. Spiegel chk_ter_submitted_at_state.
ALTER TABLE volunteer_allowance_payment DROP CONSTRAINT IF EXISTS chk_vap_submitted_at_state;
ALTER TABLE volunteer_allowance_payment ADD CONSTRAINT chk_vap_submitted_at_state
    CHECK (status IN ('DRAFT', 'WITHDRAWN') OR submitted_at IS NOT NULL);

-- Snapshots entstehen erst bei der Entscheidung (Duarte-Ruling: mit dem Betrag rechnen, der im
-- Moment des Klicks gilt) -- vorher (DRAFT/REQUESTED) sind alle drei NULL, ab der Entscheidung
-- duerfen sie gesetzt sein (nicht zwingend -- eine EXECUTED-Zahlung unter dem Deckel hat
-- exceeding_amount_snapshot = 0, nicht NULL, aber prior/free sind immer gesetzt sobald entschieden).
ALTER TABLE volunteer_allowance_payment DROP CONSTRAINT IF EXISTS chk_vap_snapshot_shape;
ALTER TABLE volunteer_allowance_payment ADD CONSTRAINT chk_vap_snapshot_shape
    CHECK ((status IN ('DRAFT', 'REQUESTED')
                AND prior_total_snapshot IS NULL AND free_amount_snapshot IS NULL AND exceeding_amount_snapshot IS NULL)
        OR (status IN ('APPROVED', 'EXECUTED', 'REJECTED', 'WITHDRAWN')));

-- Alles-oder-nichts, keine halbe Bestaetigung.
ALTER TABLE volunteer_allowance_payment DROP CONSTRAINT IF EXISTS chk_vap_cap_ack_shape;
ALTER TABLE volunteer_allowance_payment ADD CONSTRAINT chk_vap_cap_ack_shape
    CHECK ((cap_disclaimer_version IS NULL AND cap_disclaimer_sha256 IS NULL
                AND cap_acknowledged_by IS NULL AND cap_acknowledged_at IS NULL)
        OR (cap_disclaimer_version IS NOT NULL AND cap_disclaimer_sha256 IS NOT NULL
                AND cap_acknowledged_by IS NOT NULL AND cap_acknowledged_at IS NOT NULL));

-- Die Kern-Invariante der Welle DB-seitig: kein uebersteigender Anteil ohne Vorstands-Bestaetigung.
ALTER TABLE volunteer_allowance_payment DROP CONSTRAINT IF EXISTS chk_vap_exceeding_needs_ack;
ALTER TABLE volunteer_allowance_payment ADD CONSTRAINT chk_vap_exceeding_needs_ack
    CHECK (exceeding_amount_snapshot IS NULL OR exceeding_amount_snapshot = 0 OR cap_disclaimer_version IS NOT NULL);

CREATE INDEX IF NOT EXISTS idx_vap_subject       ON volunteer_allowance_payment (subject_member_id);
CREATE INDEX IF NOT EXISTS idx_vap_status        ON volunteer_allowance_payment (status);
CREATE INDEX IF NOT EXISTS idx_vap_submitted_at  ON volunteer_allowance_payment (submitted_at);
-- Hot path der Kumulierungs-Query (subject + category + payment_date-Jahr + status=EXECUTED).
CREATE INDEX IF NOT EXISTS idx_vap_subject_cat_date ON volunteer_allowance_payment (subject_member_id, category, payment_date);
-- Plain (nicht partiell) -- mehrere NULLs erlaubt, gleiche Eigenschaft wie uq_ter_posted_journal_entry.
CREATE UNIQUE INDEX IF NOT EXISTS uq_vap_posted_journal_entry
    ON volunteer_allowance_payment (posted_journal_entry_id);

CREATE TABLE IF NOT EXISTS volunteer_allowance_self_declaration (
    id             UUID        NOT NULL PRIMARY KEY,
    member_id      UUID        NOT NULL REFERENCES member (id),
    category       VARCHAR(10) NOT NULL,
    calendar_year  INT         NOT NULL,
    source         VARCHAR(8)  NOT NULL,   -- ON_PAPER = 8
    declared_at    TIMESTAMP   NOT NULL,
    signed_on      DATE        NULL,
    recorded_by    UUID        NOT NULL REFERENCES member (id)
);

ALTER TABLE volunteer_allowance_self_declaration DROP CONSTRAINT IF EXISTS chk_vasd_category;
ALTER TABLE volunteer_allowance_self_declaration ADD CONSTRAINT chk_vasd_category
    CHECK (category IN ('INSTRUCTOR', 'HONORARY'));

ALTER TABLE volunteer_allowance_self_declaration DROP CONSTRAINT IF EXISTS chk_vasd_source;
ALTER TABLE volunteer_allowance_self_declaration ADD CONSTRAINT chk_vasd_source
    CHECK (source IN ('IN_APP', 'ON_PAPER'));

-- Jobs-Ruling, DB-seitig: IN_APP => die Person selbst; ON_PAPER => zwingend jemand anderes
-- (sonst waere es genau die Faelschung, die Norman beschrieben hat, nur mit anderem Etikett).
ALTER TABLE volunteer_allowance_self_declaration DROP CONSTRAINT IF EXISTS chk_vasd_source_shape;
ALTER TABLE volunteer_allowance_self_declaration ADD CONSTRAINT chk_vasd_source_shape
    CHECK ((source = 'IN_APP'   AND recorded_by =  member_id AND signed_on IS NULL)
        OR (source = 'ON_PAPER' AND recorded_by <> member_id AND signed_on IS NOT NULL));

ALTER TABLE volunteer_allowance_self_declaration DROP CONSTRAINT IF EXISTS chk_vasd_year_range;
ALTER TABLE volunteer_allowance_self_declaration ADD CONSTRAINT chk_vasd_year_range
    CHECK (calendar_year >= 2000 AND calendar_year <= 2200);

CREATE UNIQUE INDEX IF NOT EXISTS uq_vasd_member_category_year
    ON volunteer_allowance_self_declaration (member_id, category, calendar_year);
CREATE INDEX IF NOT EXISTS idx_vasd_member ON volunteer_allowance_self_declaration (member_id);

-- organization_settings: +1 Spalte. KEIN DEFAULT (Jobs-Auflage, siehe V29 fuer die Rate-Spalten):
-- NULL == "nicht konfiguriert" -> VolunteerAllowancePostingBridge degradiert zu einem No-Op.
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS volunteer_allowance_account_id UUID NULL
    REFERENCES ledger_account (id);

-- audit_log_entry.entity_type-CHECK-Verbreiterung: VOLUNTEER_ALLOWANCE_PAYMENT (27 Zeichen, passt
-- in VARCHAR(29)) und VOLUNTEER_DECLARATION (21 Zeichen). "VOLUNTEER_ALLOWANCE_SELF_DECLARATION"
-- (36) und "VOLUNTEER_ALLOWANCE_DECLARATION" (31) passen NICHT in VARCHAR(29) -- deshalb das
-- kuerzere VOLUNTEER_DECLARATION fuer die Selbstauskunfts-Entitaet (siehe
-- AuditLogSchemaDriftTest's Laengen-Regressionsriegel). Dual-DROP-Muster wie V11/V13/V14/V15/V20/
-- V25/V26/V28/V29 -- die vollstaendige Literalliste muss wiederholt werden, H2 hat kein
-- `ALTER TYPE ... ADD VALUE`.
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
        'TRAVEL_EXPENSE_REPORT', 'VOLUNTEER_ALLOWANCE_PAYMENT', 'VOLUNTEER_DECLARATION'
    ));

-- audit_log_entry.action-CHECK-Erweiterung (Security-Fund dieser Welle, INFORMATIONAL: "keine
-- Korrektur-/Widerrufsmoeglichkeit fuer eine falsch oder missbraeuchlich erfasste
-- Papier-Selbstauskunft"): VOID (4 Zeichen, passt in VARCHAR(6) --
-- AuditLogEntryTable.action = enumerationByName(..., 6)) fuer
-- VolunteerAllowanceService.voidPaperDeclaration. Erstmalige Verbreiterung von `action`
-- ueberhaupt -- CREATE/UPDATE/POST sind seit V1__baseline.sql unveraendert, deshalb existiert
-- (anders als bei entity_type oben) noch KEIN vorher benannter Constraint zum Ersetzen; dieser
-- Block etabliert chk_audit_log_entry_action ERSTMALS, gleiches Dual-DROP-Muster wie oben (der
-- Postgres-Autoname `audit_log_entry_action_check` UND der eigene Name werden beide gedroppt,
-- bevor der eigene neu angelegt wird). Das inline, unbenannte CHECK in V1__baseline.sql selbst
-- wurde ebenfalls in-place erweitert -- siehe dessen eigene Kommentar-Historie zur
-- entity_type-Spalte fuer die Begruendung: H2 setzt beide Constraints unabhaengig durch, ein neu
-- benannter Constraint allein "ueberschreibt" den alten unbenannten nicht.
ALTER TABLE audit_log_entry DROP CONSTRAINT IF EXISTS audit_log_entry_action_check;
ALTER TABLE audit_log_entry DROP CONSTRAINT IF EXISTS chk_audit_log_entry_action;
ALTER TABLE audit_log_entry ADD CONSTRAINT chk_audit_log_entry_action
    CHECK (action IN ('CREATE', 'UPDATE', 'POST', 'VOID'));
