-- Welle V1.9.123 "SEPA-Ueberweisungsdatei (pain.001) fuer genehmigte Reisekosten" -- see 45-travel-expense.kuml.kts and
-- 14-audit-log.kuml.kts. V1..V97 untouched (V97 is tagged v0.33.0 and shipped, its checksum is spent).
--
-- Purely additive. H2 MODE=PostgreSQL and real Postgres compatible: DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT, no
-- partial index, no ON DELETE CASCADE (house convention).
--
-- DELIBERATELY NOT stored: the pain.001 XML (the file is generated and returned in one request, never persisted and never
-- re-downloadable), and any IBAN/holder/name of a member inside the batch tables. The only copy of a member's refund IBAN is
-- `travel_expense_report.refund_iban_ciphertext` (SecretBox AES-256-GCM, AAD bound to the report id).

-- ── travel_expense_report ──────────────────────────────────────────────────────────────────
ALTER TABLE travel_expense_report ADD COLUMN IF NOT EXISTS refund_iban_ciphertext VARCHAR(512) NULL;
ALTER TABLE travel_expense_report ADD COLUMN IF NOT EXISTS refund_iban_last4 VARCHAR(4) NULL;
ALTER TABLE travel_expense_report ADD COLUMN IF NOT EXISTS refund_account_holder VARCHAR(70) NULL;
ALTER TABLE travel_expense_report ADD COLUMN IF NOT EXISTS refund_account_changed_after_submit BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE travel_expense_report ADD COLUMN IF NOT EXISTS sepa_exported_at TIMESTAMP NULL;
ALTER TABLE travel_expense_report ADD COLUMN IF NOT EXISTS sepa_export_batch_id UUID NULL;
ALTER TABLE travel_expense_report ADD COLUMN IF NOT EXISTS sepa_exported_by UUID NULL;
ALTER TABLE travel_expense_report ADD COLUMN IF NOT EXISTS sepa_reexport_count INT NOT NULL DEFAULT 0;
ALTER TABLE travel_expense_report ADD COLUMN IF NOT EXISTS paid_outside_lapis BOOLEAN NOT NULL DEFAULT FALSE;

-- Legacy reports: every report that is already booked as a payout when this migration runs was refunded by hand (before V1.9.123
-- Lapis had no payout path; the booking already credits the bank). They must never be offered for a SEPA file, otherwise a member
-- who adds an IBAN afterwards would be paid a second time. Runs once, at migration time, so no report created later is affected.
UPDATE travel_expense_report SET paid_outside_lapis = TRUE WHERE status = 'EXECUTED' AND payout_choice = 'PAYOUT';

-- ── travel_expense_sepa_batch ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS travel_expense_sepa_batch (
    id                       UUID         NOT NULL PRIMARY KEY,
    debtor_bank_account_id   UUID         NOT NULL,
    debtor_account_label     VARCHAR(120) NOT NULL,   -- snapshot: the account may be renamed later
    debtor_iban_last4        VARCHAR(4)   NOT NULL,   -- snapshot, organization data
    requested_execution_date DATE         NOT NULL,
    transaction_count        INT          NOT NULL,
    control_sum_cents        BIGINT       NOT NULL,
    pain_version             VARCHAR(20)  NOT NULL,
    created_by               UUID         NOT NULL,
    created_at               TIMESTAMP    NOT NULL,
    is_reexport              BOOLEAN      NOT NULL DEFAULT FALSE,
    reason                   VARCHAR(500) NULL,
    CONSTRAINT fk_tesb_debtor_bank_account FOREIGN KEY (debtor_bank_account_id) REFERENCES bank_account (id),
    CONSTRAINT fk_tesb_created_by FOREIGN KEY (created_by) REFERENCES member (id)
);

ALTER TABLE travel_expense_sepa_batch DROP CONSTRAINT IF EXISTS chk_tesb_transaction_count;
ALTER TABLE travel_expense_sepa_batch ADD CONSTRAINT chk_tesb_transaction_count
    CHECK (transaction_count >= 1 AND transaction_count <= 500);

ALTER TABLE travel_expense_sepa_batch DROP CONSTRAINT IF EXISTS chk_tesb_control_sum;
ALTER TABLE travel_expense_sepa_batch ADD CONSTRAINT chk_tesb_control_sum
    CHECK (control_sum_cents > 0);

ALTER TABLE travel_expense_sepa_batch DROP CONSTRAINT IF EXISTS chk_tesb_reexport_reason;
ALTER TABLE travel_expense_sepa_batch ADD CONSTRAINT chk_tesb_reexport_reason
    CHECK ((is_reexport = TRUE AND reason IS NOT NULL) OR (is_reexport = FALSE AND reason IS NULL));

CREATE INDEX IF NOT EXISTS idx_tesb_created_at ON travel_expense_sepa_batch (created_at);

-- ── travel_expense_sepa_batch_item ─────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS travel_expense_sepa_batch_item (
    id             UUID        NOT NULL PRIMARY KEY,
    batch_id       UUID        NOT NULL,
    report_id      UUID        NOT NULL,
    amount_cents   BIGINT      NOT NULL,
    end_to_end_id  VARCHAR(35) NOT NULL,
    CONSTRAINT fk_tesbi_batch FOREIGN KEY (batch_id) REFERENCES travel_expense_sepa_batch (id),
    CONSTRAINT fk_tesbi_report FOREIGN KEY (report_id) REFERENCES travel_expense_report (id)
);

ALTER TABLE travel_expense_sepa_batch_item DROP CONSTRAINT IF EXISTS chk_tesbi_amount;
ALTER TABLE travel_expense_sepa_batch_item ADD CONSTRAINT chk_tesbi_amount
    CHECK (amount_cents >= 1 AND amount_cents <= 99999999999);

CREATE UNIQUE INDEX IF NOT EXISTS uq_tesbi_end_to_end_id ON travel_expense_sepa_batch_item (end_to_end_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_tesbi_batch_report ON travel_expense_sepa_batch_item (batch_id, report_id);
CREATE INDEX IF NOT EXISTS idx_tesbi_report ON travel_expense_sepa_batch_item (report_id);

-- ── travel_expense_report: constraints that reference the new table ────────────────────────
ALTER TABLE travel_expense_report DROP CONSTRAINT IF EXISTS fk_ter_sepa_export_batch;
ALTER TABLE travel_expense_report ADD CONSTRAINT fk_ter_sepa_export_batch
    FOREIGN KEY (sepa_export_batch_id) REFERENCES travel_expense_sepa_batch (id);

ALTER TABLE travel_expense_report DROP CONSTRAINT IF EXISTS fk_ter_sepa_exported_by;
ALTER TABLE travel_expense_report ADD CONSTRAINT fk_ter_sepa_exported_by
    FOREIGN KEY (sepa_exported_by) REFERENCES member (id);

ALTER TABLE travel_expense_report DROP CONSTRAINT IF EXISTS chk_ter_sepa_export_consistent;
ALTER TABLE travel_expense_report ADD CONSTRAINT chk_ter_sepa_export_consistent
    CHECK ((sepa_exported_at IS NULL AND sepa_export_batch_id IS NULL AND sepa_exported_by IS NULL)
        OR (sepa_exported_at IS NOT NULL AND sepa_export_batch_id IS NOT NULL AND sepa_exported_by IS NOT NULL));

ALTER TABLE travel_expense_report DROP CONSTRAINT IF EXISTS chk_ter_sepa_reexport_count;
ALTER TABLE travel_expense_report ADD CONSTRAINT chk_ter_sepa_reexport_count
    CHECK (sepa_reexport_count >= 0 AND sepa_reexport_count <= 99);

ALTER TABLE travel_expense_report DROP CONSTRAINT IF EXISTS chk_ter_refund_iban_pair;
ALTER TABLE travel_expense_report ADD CONSTRAINT chk_ter_refund_iban_pair
    CHECK ((refund_iban_ciphertext IS NULL AND refund_iban_last4 IS NULL)
        OR (refund_iban_ciphertext IS NOT NULL AND refund_iban_last4 IS NOT NULL));

CREATE INDEX IF NOT EXISTS idx_ter_sepa_export_batch ON travel_expense_report (sepa_export_batch_id);

-- ── audit_log_entry.entity_type: widen the CHECK for TRAVEL_EXPENSE_SEPA_BATCH (25 chars, fits VARCHAR(29)) ──
ALTER TABLE audit_log_entry
    DROP CONSTRAINT IF EXISTS "CONSTRAINT_407";
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
                                'MEMBERSHIP_TIER', 'POLL', 'ENCOUNTER_SPACE', 'EVENT', 'EVENT_IMPORT',
                                'COMMITTEE_MEMBERSHIP', 'ARTICLE_IMPORT', 'ARTICLE_BULK_APPROVED',
                                'ARTICLE_IMPORT_IMAGES_APPLIED', 'MAILING_LIST', 'MAILING_MESSAGE', 'COMMITTEE',
                                'TRAVEL_EXPENSE_SEPA_BATCH'
        ));
