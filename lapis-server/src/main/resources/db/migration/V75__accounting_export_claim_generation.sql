-- Welle V1.9.65 -- separate, never-reset fencing token for accounting-export claims. V1..V74 untouched; purely additive.
-- `attempts` is the SEND retry budget and is reset by retryFailed, so it cannot be a fencing token (ABA: a stale sender holding token 1
-- matches again after abort + resolve + retryFailed + claim). claim_generation is incremented by every claim and never reset.
ALTER TABLE accounting_export_item ADD COLUMN IF NOT EXISTS claim_generation INT NOT NULL DEFAULT 0;

ALTER TABLE accounting_export_item DROP CONSTRAINT IF EXISTS chk_accounting_export_item_claim_generation;
ALTER TABLE accounting_export_item ADD CONSTRAINT chk_accounting_export_item_claim_generation
    CHECK (claim_generation >= 0);
