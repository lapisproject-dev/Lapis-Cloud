-- V1.2.1 "Zahlungs-Fundament" -- see lapis-server/src/main/kuml/33-payments.kuml.kts (and the
-- Welle V1.2.1 addenda in 01-contribution.kuml.kts / 11-organization-settings.kuml.kts) for the
-- full fachlich model. Vault plan: "Lapis Cloud V1.2 -- Zahlungsverkehr" Teil 0 Befund B-1.
--
-- Idempotent by construction (IF NOT EXISTS / dual-DROP-then-ADD), same discipline as V2-V6.
--
-- Aufteilungsregel (Plan § 0.10/2.6): the FOUR in-place V1__baseline.sql edits this migration
-- mirrors are (1) contribution.status widening, (2) contribution.due_date/payment_method columns,
-- (3) membership_tier.payment_term_days, (4) organization_settings' six new columns + three new
-- FKs. All FOUR are repeated here, idempotently, so a fresh DB (every test run) and the already-
-- migrated pdv2 instance reach the same end state. The genuinely NEW tables below
-- (payment_transaction, sepa_compliance_acknowledgment, payment_gateway_compliance_acknowledgment)
-- live ONLY here, never in V1__baseline.sql (V4-V6 precedent).

-- ---------------------------------------------------------------------------
-- contribution: status widening. THREE synchronised changes, see Plan Teil 0 Befund B-6b: column
-- width, CHECK constraint (ANONYM inline in the regenerated V1__baseline.sql -- on an already-
-- migrated pdv2 instance it carries PostgreSQL's auto-generated name `contribution_status_check`),
-- and ContributionTable.status's enumerationByName width (see db/generated/ContributionTable.kt).
-- VERIFY WITH `\d contribution` ON pdv2 BEFORE DEPLOY.
-- ---------------------------------------------------------------------------
ALTER TABLE contribution ALTER COLUMN status TYPE VARCHAR(15);

ALTER TABLE contribution DROP CONSTRAINT IF EXISTS contribution_status_check;
ALTER TABLE contribution DROP CONSTRAINT IF EXISTS chk_contribution_status;
ALTER TABLE contribution ADD CONSTRAINT chk_contribution_status
    CHECK (status IN ('OPEN', 'PAID', 'WAIVED', 'OVERDUE',
                      'DEBIT_SCHEDULED', 'DEBIT_SUBMITTED', 'RETURNED', 'IN_DUNNING'));

-- due_date: two-step, because NOT NULL on an already-populated table would otherwise fail. Backfill
-- deliberately period_start (NOT period_end): conservative, never makes a pre-existing row
-- retroactively "overdue for longer than it actually was" -- and the dunning run is a later
-- sub-wave (V1.2.3) anyway, so this backfill has no immediate behavioural consequence.
ALTER TABLE contribution ADD COLUMN IF NOT EXISTS due_date DATE NULL;
UPDATE contribution SET due_date = period_start WHERE due_date IS NULL;
ALTER TABLE contribution ALTER COLUMN due_date SET NOT NULL;

ALTER TABLE contribution ADD COLUMN IF NOT EXISTS payment_method VARCHAR(12) NOT NULL DEFAULT 'MANUAL';

ALTER TABLE contribution DROP CONSTRAINT IF EXISTS chk_contribution_payment_method;
ALTER TABLE contribution ADD CONSTRAINT chk_contribution_payment_method
    CHECK (payment_method IN ('MANUAL', 'SEPA_DEBIT', 'GATEWAY'));

-- ---------------------------------------------------------------------------
-- membership_tier.payment_term_days -- "Zahlungsziel" in days, read by
-- ContributionService.generateContributionsForPeriod. Default 14 for both fresh and backfilled rows
-- -- see 01-contribution.kuml.kts file header "Welle V1.2.1" for why 14, not 0.
-- ---------------------------------------------------------------------------
ALTER TABLE membership_tier ADD COLUMN IF NOT EXISTS payment_term_days INT NOT NULL DEFAULT 14;

-- ---------------------------------------------------------------------------
-- organization_settings: two compliance gates (settable ONLY via ISepaService/IPaymentGatewayService,
-- never via updateOrganizationSettings -- see 11-organization-settings.kuml.kts file header "Welle
-- V1.2.1") plus three ordinary ADMIN-writable ledger-account-mapping columns.
-- ---------------------------------------------------------------------------
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS sepa_debit_enabled              BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS payment_gateway_enabled         BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS payment_gateway_provider        VARCHAR(8) NULL;
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS payment_bank_account_id         UUID NULL;
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS payment_fee_account_id          UUID NULL;
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS contribution_income_account_id  UUID NULL;

ALTER TABLE organization_settings DROP CONSTRAINT IF EXISTS chk_organization_settings_payment_gateway_provider;
ALTER TABLE organization_settings ADD CONSTRAINT chk_organization_settings_payment_gateway_provider
    CHECK (payment_gateway_provider IS NULL OR payment_gateway_provider IN ('PAYPAL', 'STRIPE', 'MANUAL'));

ALTER TABLE organization_settings DROP CONSTRAINT IF EXISTS fk_organization_settings_payment_bank_account_id;
ALTER TABLE organization_settings ADD CONSTRAINT fk_organization_settings_payment_bank_account_id
    FOREIGN KEY (payment_bank_account_id) REFERENCES ledger_account(id);

ALTER TABLE organization_settings DROP CONSTRAINT IF EXISTS fk_organization_settings_payment_fee_account_id;
ALTER TABLE organization_settings ADD CONSTRAINT fk_organization_settings_payment_fee_account_id
    FOREIGN KEY (payment_fee_account_id) REFERENCES ledger_account(id);

ALTER TABLE organization_settings DROP CONSTRAINT IF EXISTS fk_organization_settings_contribution_income_account_id;
ALTER TABLE organization_settings ADD CONSTRAINT fk_organization_settings_contribution_income_account_id
    FOREIGN KEY (contribution_income_account_id) REFERENCES ledger_account(id);

-- ---------------------------------------------------------------------------
-- payment_transaction -- methodenneutral, PSP-logic-free skeleton (Plan § 2.3, V1.2.1 shape). No
-- V1.2.1 code path writes into this table yet (webhook ingestion is V1.2.4) -- see
-- 33-payments.kuml.kts file header. The unique index is the idempotency anchor a later wave's
-- webhook route will rely on -- a DB constraint, not the in-memory FederationReplayGuard (documented
-- "per-JVM-instance state", too weak for money, see Plan § 3.4).
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS payment_transaction (
    id                   UUID          NOT NULL PRIMARY KEY,
    provider             VARCHAR(8)    NOT NULL,
    provider_event_id    VARCHAR(255)  NOT NULL,
    provider_payment_id  VARCHAR(255)  NOT NULL,
    status               VARCHAR(9)    NOT NULL,
    amount               DECIMAL(14,2) NOT NULL,
    currency             VARCHAR(3)    NOT NULL,
    fee_amount           DECIMAL(14,2) NULL,
    intent               VARCHAR(12)   NOT NULL,
    contribution_id      UUID          NULL,
    member_id            UUID          NULL,
    payer_reference      VARCHAR(255)  NULL,
    received_at          TIMESTAMP     NOT NULL,
    reconciled_at        TIMESTAMP     NULL,
    reconciled_by        UUID          NULL,
    journal_entry_id     UUID          NULL,
    reconciliation_note  VARCHAR(2000) NULL,
    raw_payload_digest   VARCHAR(64)   NOT NULL
);

ALTER TABLE payment_transaction DROP CONSTRAINT IF EXISTS chk_payment_transaction_provider;
ALTER TABLE payment_transaction ADD CONSTRAINT chk_payment_transaction_provider
    CHECK (provider IN ('PAYPAL', 'STRIPE', 'MANUAL'));

ALTER TABLE payment_transaction DROP CONSTRAINT IF EXISTS chk_payment_transaction_status;
ALTER TABLE payment_transaction ADD CONSTRAINT chk_payment_transaction_status
    CHECK (status IN ('PENDING', 'CAPTURED', 'FAILED', 'REFUNDED', 'DISPUTED'));

ALTER TABLE payment_transaction DROP CONSTRAINT IF EXISTS chk_payment_transaction_intent;
ALTER TABLE payment_transaction ADD CONSTRAINT chk_payment_transaction_intent
    CHECK (intent IN ('CONTRIBUTION', 'DONATION'));

ALTER TABLE payment_transaction DROP CONSTRAINT IF EXISTS fk_payment_transaction_contribution_id;
ALTER TABLE payment_transaction ADD CONSTRAINT fk_payment_transaction_contribution_id
    FOREIGN KEY (contribution_id) REFERENCES contribution(id);

ALTER TABLE payment_transaction DROP CONSTRAINT IF EXISTS fk_payment_transaction_member_id;
ALTER TABLE payment_transaction ADD CONSTRAINT fk_payment_transaction_member_id
    FOREIGN KEY (member_id) REFERENCES member(id);

ALTER TABLE payment_transaction DROP CONSTRAINT IF EXISTS fk_payment_transaction_reconciled_by;
ALTER TABLE payment_transaction ADD CONSTRAINT fk_payment_transaction_reconciled_by
    FOREIGN KEY (reconciled_by) REFERENCES member(id);

ALTER TABLE payment_transaction DROP CONSTRAINT IF EXISTS fk_payment_transaction_journal_entry_id;
ALTER TABLE payment_transaction ADD CONSTRAINT fk_payment_transaction_journal_entry_id
    FOREIGN KEY (journal_entry_id) REFERENCES journal_entry(id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_transaction_provider_event
    ON payment_transaction (provider, provider_event_id);
CREATE INDEX IF NOT EXISTS idx_payment_transaction_contribution ON payment_transaction (contribution_id);
CREATE INDEX IF NOT EXISTS idx_payment_transaction_member       ON payment_transaction (member_id);
CREATE INDEX IF NOT EXISTS idx_payment_transaction_status       ON payment_transaction (status);

-- ---------------------------------------------------------------------------
-- Compliance-gate acknowledgment tables -- mirror auction_compliance_acknowledgment exactly, see
-- 33-payments.kuml.kts file header.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sepa_compliance_acknowledgment (
    id                        UUID         NOT NULL PRIMARY KEY,
    acknowledged_by_member_id UUID         NOT NULL,
    acknowledged_at           TIMESTAMP    NOT NULL,
    disclaimer_version        VARCHAR(50)  NOT NULL,
    disclaimer_sha256         VARCHAR(64)  NOT NULL
);

ALTER TABLE sepa_compliance_acknowledgment DROP CONSTRAINT IF EXISTS fk_sepa_compliance_ack_member_id;
ALTER TABLE sepa_compliance_acknowledgment ADD CONSTRAINT fk_sepa_compliance_ack_member_id
    FOREIGN KEY (acknowledged_by_member_id) REFERENCES member(id);

CREATE INDEX IF NOT EXISTS idx_sepa_compliance_ack_acknowledged_at ON sepa_compliance_acknowledgment (acknowledged_at);

CREATE TABLE IF NOT EXISTS payment_gateway_compliance_acknowledgment (
    id                        UUID         NOT NULL PRIMARY KEY,
    acknowledged_by_member_id UUID         NOT NULL,
    acknowledged_at           TIMESTAMP    NOT NULL,
    disclaimer_version        VARCHAR(50)  NOT NULL,
    disclaimer_sha256         VARCHAR(64)  NOT NULL,
    provider                  VARCHAR(8)   NOT NULL
);

ALTER TABLE payment_gateway_compliance_acknowledgment DROP CONSTRAINT IF EXISTS chk_payment_gateway_compliance_ack_provider;
ALTER TABLE payment_gateway_compliance_acknowledgment ADD CONSTRAINT chk_payment_gateway_compliance_ack_provider
    CHECK (provider IN ('PAYPAL', 'STRIPE', 'MANUAL'));

ALTER TABLE payment_gateway_compliance_acknowledgment DROP CONSTRAINT IF EXISTS fk_payment_gateway_compliance_ack_member_id;
ALTER TABLE payment_gateway_compliance_acknowledgment ADD CONSTRAINT fk_payment_gateway_compliance_ack_member_id
    FOREIGN KEY (acknowledged_by_member_id) REFERENCES member(id);

CREATE INDEX IF NOT EXISTS idx_payment_gateway_compliance_ack_acknowledged_at ON payment_gateway_compliance_acknowledgment (acknowledged_at);

-- ---------------------------------------------------------------------------
-- audit_log_entry.entity_type CHECK-Verbreiterung fuer ORGANIZATION_SETTINGS (Security Round 1,
-- 2026-08-19, MAJOR-2) -- dual-DROP-Muster wie V4 (ltr_ledger_entry)/V6 (SOCIAL_POST) und jede
-- spaetere V1__baseline.sql-in-place-Aenderung dieses Repos. Folded into THIS migration rather
-- than a new V8 -- this branch (feature/v1.2.1-zahlungs-fundament) has not been merged/released/
-- deployed anywhere yet, so V7's checksum has not been consumed by any real environment; this is
-- still pre-release iteration on the SAME wave, not a separate later wave finding something to fix.
-- On a FRESH DB the constraint already carries the literal (through the in-place edit of V1's own
-- CHECK above), the auto-generated name on an already-migrated instance is the rare case both
-- DROPs cover -- VERIFY WITH `\d audit_log_entry` ON pdv2 BEFORE DEPLOY. ORGANIZATION_SETTINGS is
-- 21 chars, fits within the existing VARCHAR(29) width (CONFERENCE_STREAM_DESTINATION, 29 chars,
-- is still the longest literal) -- no column-width change needed.
-- ---------------------------------------------------------------------------
ALTER TABLE audit_log_entry DROP CONSTRAINT IF EXISTS audit_log_entry_entity_type_check;
ALTER TABLE audit_log_entry DROP CONSTRAINT IF EXISTS chk_audit_log_entry_entity_type;
ALTER TABLE audit_log_entry ADD CONSTRAINT chk_audit_log_entry_entity_type
    CHECK (entity_type IN (
        'JOURNAL_ENTRY', 'PARTY_DONATION_VERDICT', 'RESOLUTION', 'BOARD_MEMBERSHIP',
        'CONFERENCE_RECORDING', 'CONFERENCE_STREAM', 'CONFERENCE_STREAM_DESTINATION', 'CONFERENCE_ROOM',
        'SOCIAL_POST', 'ORGANIZATION_SETTINGS'
    ));
