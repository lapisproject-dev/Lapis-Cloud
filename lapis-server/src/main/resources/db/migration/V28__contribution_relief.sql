-- Welle V1.4.10 "Beitragsvergünstigungen" (Stundung / Befreiung / Sozialermäßigung) -- siehe
-- 44-contribution-relief.kuml.kts fuer das vollstaendige fachliche Modell.
--
-- V27__accounting_export_sevdesk_provider.sql wird NICHT angefasst (deployed, Checksum verbraucht)
-- -- gleiche Disziplin, die V27 selbst gegenueber V26 dokumentiert.
--
-- Portability note: KEIN partieller Unique-Index (`CREATE UNIQUE INDEX ... WHERE`) in dieser Datei.
-- H2 in MODE=PostgreSQL (der Modus, gegen den die GESAMTE Testsuite laeuft, DatabaseConfig.kt)
-- lehnt diese Syntax ab -- empirisch zweimal verifiziert (V8__sepa_mandates.sql/V9__dunning.sql).
-- Stattdessen dasselbe portable application-maintained-shadow-column-Muster, das
-- V18__events.sql fuer event_registration.active_participant_key etabliert:
-- contribution_relief_request.active_request_key ist eine NULLABLE VARCHAR-Spalte, dahinter ein
-- PLAIN Unique-Index -- mehrere NULLs sind unter einem plain Unique-Index sowohl auf H2 als auch
-- auf echtem Postgres erlaubt (dieselbe Eigenschaft, auf die
-- uq_event_registration_active_participant/uq_contribution_payment_reference bereits bauen).
-- Geschrieben ausschliesslich von ContributionReliefService/ContributionReliefExecution.

CREATE TABLE IF NOT EXISTS contribution_relief_request (
    id                          UUID          NOT NULL PRIMARY KEY,
    subject_member_id          UUID          NOT NULL REFERENCES member (id),
    kind                        VARCHAR(9)    NOT NULL,
    status                      VARCHAR(9)    NOT NULL,
    reason_category             VARCHAR(18)   NOT NULL,
    reason_text                 VARCHAR(500)  NULL,
    reason_redacted_at          TIMESTAMP     NULL,
    -- DEFERRAL-Payload
    deferral_contribution_id    UUID          NULL REFERENCES contribution (id),
    deferral_new_due_date       DATE          NULL,
    deferral_previous_due_date  DATE          NULL,
    -- EXEMPTION-Payload
    exemption_from               DATE          NULL,
    exemption_until              DATE          NULL,
    -- REDUCTION-Payload
    reduction_target_tier_id    UUID          NULL REFERENCES membership_tier (id),
    -- gemeinsam
    review_due_on                DATE          NULL,
    requested_at                 TIMESTAMP     NOT NULL,
    requested_by                 UUID          NOT NULL REFERENCES member (id),
    decided_at                   TIMESTAMP     NULL,
    decided_by                   UUID          NULL REFERENCES member (id),
    decision_note                VARCHAR(1000) NULL,
    executed_at                  TIMESTAMP     NULL,
    execution_error              VARCHAR(500)  NULL,
    -- K-1: Shadow-Spalte statt partiellem Unique-Index
    active_request_key           VARCHAR(64)   NULL
);

ALTER TABLE contribution_relief_request DROP CONSTRAINT IF EXISTS chk_crr_kind;
ALTER TABLE contribution_relief_request ADD CONSTRAINT chk_crr_kind
    CHECK (kind IN ('DEFERRAL', 'EXEMPTION', 'REDUCTION'));

ALTER TABLE contribution_relief_request DROP CONSTRAINT IF EXISTS chk_crr_status;
ALTER TABLE contribution_relief_request ADD CONSTRAINT chk_crr_status
    CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED', 'EXECUTED', 'WITHDRAWN'));

ALTER TABLE contribution_relief_request DROP CONSTRAINT IF EXISTS chk_crr_reason_category;
ALTER TABLE contribution_relief_request ADD CONSTRAINT chk_crr_reason_category
    CHECK (reason_category IN ('FINANCIAL_HARDSHIP', 'UNEMPLOYMENT', 'STUDENT_TRAINEE',
                               'ILLNESS_DISABILITY', 'PARENTAL_CARE', 'OTHER'));

-- Payload-Exklusivitaet: genau die zum kind gehoerenden Spalten NOT NULL, alle anderen NULL.
-- `kind` ist NOT NULL, also nie eine drei-wertige-Logik-Falle (siehe V23__member_family.sql).
ALTER TABLE contribution_relief_request DROP CONSTRAINT IF EXISTS chk_crr_payload_shape;
ALTER TABLE contribution_relief_request ADD CONSTRAINT chk_crr_payload_shape
    CHECK (
        (kind = 'DEFERRAL'  AND deferral_contribution_id IS NOT NULL AND deferral_new_due_date IS NOT NULL
                            AND exemption_from IS NULL AND exemption_until IS NULL
                            AND reduction_target_tier_id IS NULL)
     OR (kind = 'EXEMPTION' AND exemption_from IS NOT NULL
                            AND deferral_contribution_id IS NULL AND deferral_new_due_date IS NULL
                            AND deferral_previous_due_date IS NULL AND reduction_target_tier_id IS NULL)
     OR (kind = 'REDUCTION' AND reduction_target_tier_id IS NOT NULL
                            AND deferral_contribution_id IS NULL AND deferral_new_due_date IS NULL
                            AND deferral_previous_due_date IS NULL
                            AND exemption_from IS NULL AND exemption_until IS NULL)
    );

ALTER TABLE contribution_relief_request DROP CONSTRAINT IF EXISTS chk_crr_exemption_range;
ALTER TABLE contribution_relief_request ADD CONSTRAINT chk_crr_exemption_range
    CHECK (exemption_until IS NULL OR exemption_from IS NULL OR exemption_until >= exemption_from);

-- Ein genehmigter Antrag traegt zwingend eine Entscheidungsnotiz -- der Freitext des Mitglieds wird
-- irgendwann geloescht (Redaktion nach 12 Monaten), die Notiz des Vorstands bleibt und muss ohne
-- ihn tragen (siehe ContributionReliefService KDoc).
ALTER TABLE contribution_relief_request DROP CONSTRAINT IF EXISTS chk_crr_approved_needs_note;
ALTER TABLE contribution_relief_request ADD CONSTRAINT chk_crr_approved_needs_note
    CHECK (status NOT IN ('APPROVED', 'EXECUTED') OR decision_note IS NOT NULL);

-- execution_error nur im Zustand APPROVED sinnvoll (siehe ContributionReliefExecution KDoc).
ALTER TABLE contribution_relief_request DROP CONSTRAINT IF EXISTS chk_crr_execution_error_state;
ALTER TABLE contribution_relief_request ADD CONSTRAINT chk_crr_execution_error_state
    CHECK (execution_error IS NULL OR status = 'APPROVED');

-- K-1: plain (nicht partieller) Unique-Index auf der Shadow-Spalte -- siehe Datei-Header.
CREATE UNIQUE INDEX IF NOT EXISTS uq_crr_active_request
    ON contribution_relief_request (active_request_key);
CREATE INDEX IF NOT EXISTS idx_crr_status ON contribution_relief_request (status);
CREATE INDEX IF NOT EXISTS idx_crr_subject ON contribution_relief_request (subject_member_id);
CREATE INDEX IF NOT EXISTS idx_crr_review_due ON contribution_relief_request (review_due_on);

-- member-Erweiterung fuer den EXEMPTION-Effekt (siehe 00-foundation.kuml.kts file header).
ALTER TABLE member ADD COLUMN IF NOT EXISTS contribution_exempt_from DATE NULL;
ALTER TABLE member ADD COLUMN IF NOT EXISTS contribution_exempt_until DATE NULL;
-- K-4: BEWUSST ohne FK auf contribution_relief_request -- ein Rueck-FK erzeugte einen Zyklus
-- (contribution_relief_request.subject_member_id -> member), und
-- OrganizationSchemaCatalog.restoreOrder ist eine topologische Sortierung, die daran scheitert.
-- Gleiche Behandlung wie member.reviewed_by (siehe MemberTable-Kommentar).
ALTER TABLE member ADD COLUMN IF NOT EXISTS contribution_exempt_request_id UUID NULL;

ALTER TABLE member DROP CONSTRAINT IF EXISTS chk_member_contribution_exempt_range;
ALTER TABLE member ADD CONSTRAINT chk_member_contribution_exempt_range
    CHECK (contribution_exempt_until IS NULL
           OR (contribution_exempt_from IS NOT NULL
               AND contribution_exempt_until >= contribution_exempt_from));

ALTER TABLE member DROP CONSTRAINT IF EXISTS chk_member_contribution_exempt_source;
ALTER TABLE member ADD CONSTRAINT chk_member_contribution_exempt_source
    CHECK (contribution_exempt_request_id IS NULL OR contribution_exempt_from IS NOT NULL);

-- audit_log_entry.entity_type-CHECK-Verbreiterung: neues Literal CONTRIBUTION_RELIEF_REQUEST
-- (27 Zeichen, passt in die bestehende VARCHAR(29)). Dual-DROP-Muster wie V11/V13/V14/V15/V20/
-- V25/V26 -- die vollstaendige Literalliste muss wiederholt werden, H2 hat kein
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
        'ACCOUNTING_EXPORT_RUN', 'ACCOUNTING_EXPORT_MAPPING', 'CONTRIBUTION_RELIEF_REQUEST'
    ));
