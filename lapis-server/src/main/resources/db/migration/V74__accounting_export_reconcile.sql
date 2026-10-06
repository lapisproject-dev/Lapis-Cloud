-- Welle V1.9.65 -- automatic reconciliation of UNKNOWN accounting-export items (lexoffice voucherlist lookup by voucher number).
-- V1..V73 untouched. Purely additive, H2 MODE=PostgreSQL- and Postgres-compatible (ADD COLUMN IF NOT EXISTS, DROP CONSTRAINT IF EXISTS
-- before every ADD CONSTRAINT, no DO block, no partial indexes).
--
-- These three columns are deliberately NOT the existing next_attempt_at / attempts: those carry SEND semantics (MAX_ATTEMPTS, retryFailed
-- resets them) and overloading them would let a reconciliation lookup consume or reset the send retry budget.
--   reconcile_checks       how many lookups have been made for this UNKNOWN item (capped by the poller, six in total)
--   reconcile_next_at      earliest time of the next lookup (NULL = as soon as the minimum age has passed)
--   reconcile_last_result  outcome of the last lookup: MATCHED, NOT_FOUND, MISMATCH, DUPLICATE, LOOKUP_FAILED, ALREADY_EXPORTED, UNSUPPORTED

ALTER TABLE accounting_export_item ADD COLUMN IF NOT EXISTS reconcile_checks INT NOT NULL DEFAULT 0;
ALTER TABLE accounting_export_item ADD COLUMN IF NOT EXISTS reconcile_next_at TIMESTAMP NULL;
ALTER TABLE accounting_export_item ADD COLUMN IF NOT EXISTS reconcile_last_result VARCHAR(32) NULL;

ALTER TABLE accounting_export_item DROP CONSTRAINT IF EXISTS chk_accounting_export_item_reconcile_checks;
ALTER TABLE accounting_export_item ADD CONSTRAINT chk_accounting_export_item_reconcile_checks
    CHECK (reconcile_checks >= 0);
