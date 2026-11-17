-- V1.9.308 "Ausschreibungen" (tenders), wave 3: the LTR payout. See 74-tender.kuml.kts, 08-ltr-balance.kuml.kts and docs/features/tenders.adoc.
-- V1..V156 untouched (V115/V149/V156 are released and migrated on every instance; changing them in place would break their Flyway checksum).
-- Purely additive and repeatable, H2 MODE=PostgreSQL- and PostgreSQL-compatible: DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT,
-- CREATE TABLE/INDEX IF NOT EXISTS, no partial index, no ON DELETE CASCADE, NO backfill.
--
-- DECISION: the payout is an ISSUANCE by the organisation (like MINT), NOT a transfer. The ledger knows member accounts only
-- (ltr_ledger_entry.member_id NOT NULL) and a system/organisation member is forbidden by doctrine, so a payout is ONE credit row of the new type
-- TENDER_PAYOUT on the winner (reference TENDER = tender.id). There is no debit row; "insufficient balance" cannot happen.
--
-- THREE idempotency layers, none of them a partial index:
--   (a) the tender row is locked FOR UPDATE and the status moves COMPLETED/PAYOUT_FAILED -> PAID by a conditional UPDATE;
--   (b) tender_payout.tender_id is the PRIMARY KEY (one payout per tender);
--   (c) UNIQUE (ledger_entry_id) and UNIQUE (bid_id) on tender_payout.
--
--   * tender.status: VARCHAR(9) -> VARCHAR(13), CHECK + PAID and PAYOUT_FAILED (13 chars). PAYOUT_FAILED = the payout was attempted and the winner
--     cannot receive LTR; only the board ever sees this status (the readers see COMPLETED).
--   * tender.payout_failure_reason / payout_failed_at / payout_failed_by: the last failed attempt, kept as history after a successful retry.
--     payout_ledger_entry_id stays WITHOUT a foreign key on tender (reserved in V115); the foreign key lives on tender_payout.
--   * tender_payout: the booking record (which ledger row, which bid, how much, who, when).
--   * ltr_ledger_entry.entry_type + TENDER_PAYOUT (13 chars, width 21 fits), reference_type + TENDER (6 chars, width 20 fits); the lists are V149's,
--     copied verbatim, plus the new literal.
--   * No audit CHECK change: entity_type TENDER exists since V115; the events PAYOUT / PAYOUT_FAILED live inside the snapshot.
--
-- Backups made before this migration are refused by the restore (the schema checksum differs), like after every migration.

-- The CHECK is dropped BEFORE the column is widened (a constraint on the column must not block the type change on any engine).
ALTER TABLE tender DROP CONSTRAINT IF EXISTS chk_tender_status;
ALTER TABLE tender ALTER COLUMN status SET DATA TYPE VARCHAR(13);
ALTER TABLE tender ADD CONSTRAINT chk_tender_status
    CHECK (status IN ('DRAFT', 'PUBLISHED', 'CLOSED', 'AWARDED', 'COMPLETED', 'CANCELLED', 'PAID', 'PAYOUT_FAILED'));

ALTER TABLE tender ADD COLUMN IF NOT EXISTS payout_failure_reason VARCHAR(23) NULL;
ALTER TABLE tender ADD COLUMN IF NOT EXISTS payout_failed_at TIMESTAMP NULL;
ALTER TABLE tender ADD COLUMN IF NOT EXISTS payout_failed_by UUID NULL;

ALTER TABLE tender DROP CONSTRAINT IF EXISTS fk_tender_payout_failed_by;
ALTER TABLE tender ADD CONSTRAINT fk_tender_payout_failed_by FOREIGN KEY (payout_failed_by) REFERENCES member (id);

ALTER TABLE tender DROP CONSTRAINT IF EXISTS chk_tender_payout_failure_reason;
ALTER TABLE tender ADD CONSTRAINT chk_tender_payout_failure_reason
    CHECK (payout_failure_reason IS NULL OR payout_failure_reason IN ('WINNER_GUEST', 'WINNER_NOT_LTR_ELIGIBLE'));

ALTER TABLE tender DROP CONSTRAINT IF EXISTS chk_tender_payout_failed_consistent;
ALTER TABLE tender ADD CONSTRAINT chk_tender_payout_failed_consistent
    CHECK (status <> 'PAYOUT_FAILED' OR (payout_failure_reason IS NOT NULL AND payout_failed_at IS NOT NULL AND payout_failed_by IS NOT NULL));

ALTER TABLE tender DROP CONSTRAINT IF EXISTS chk_tender_paid_has_entry;
ALTER TABLE tender ADD CONSTRAINT chk_tender_paid_has_entry CHECK (status <> 'PAID' OR payout_ledger_entry_id IS NOT NULL);

CREATE TABLE IF NOT EXISTS tender_payout (
    tender_id       UUID          NOT NULL PRIMARY KEY REFERENCES tender (id),
    ledger_entry_id UUID          NOT NULL REFERENCES ltr_ledger_entry (id),
    bid_id          UUID          NOT NULL REFERENCES tender_bid (id),
    amount_ltr      DECIMAL(18,2) NOT NULL,
    executed_by     UUID          NOT NULL REFERENCES member (id),
    executed_at     TIMESTAMP     NOT NULL
);

ALTER TABLE tender_payout DROP CONSTRAINT IF EXISTS chk_tender_payout_amount;
ALTER TABLE tender_payout ADD CONSTRAINT chk_tender_payout_amount CHECK (amount_ltr >= 0.01);

CREATE UNIQUE INDEX IF NOT EXISTS uq_tender_payout_ledger_entry ON tender_payout (ledger_entry_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_tender_payout_bid ON tender_payout (bid_id);

-- Ledger widening, same dual-DROP discipline as V4/V5/V149 (the guessed Postgres auto-name plus the explicit name).
ALTER TABLE ltr_ledger_entry DROP CONSTRAINT IF EXISTS ltr_ledger_entry_entry_type_check;
ALTER TABLE ltr_ledger_entry DROP CONSTRAINT IF EXISTS chk_ltr_ledger_entry_entry_type;
ALTER TABLE ltr_ledger_entry ADD CONSTRAINT chk_ltr_ledger_entry_entry_type
    CHECK (entry_type IN (
        'MINT', 'PROJECT_STAKE', 'PROJECT_STAKE_RELEASE', 'VOTE_STAKE', 'PEER_TRANSFER_OUT', 'PEER_TRANSFER_IN',
        'AUCTION_LISTING_FEE', 'AUCTION_HOLD', 'AUCTION_HOLD_RELEASE', 'AUCTION_SALE_OUT', 'AUCTION_SALE_IN',
        'SOCIAL_POST_STAKE', 'SOCIAL_POST_BOOST', 'NOTICE_BOOST', 'TENDER_PAYOUT'
    ));
ALTER TABLE ltr_ledger_entry DROP CONSTRAINT IF EXISTS ltr_ledger_entry_reference_type_check;
ALTER TABLE ltr_ledger_entry DROP CONSTRAINT IF EXISTS chk_ltr_ledger_entry_reference_type;
ALTER TABLE ltr_ledger_entry ADD CONSTRAINT chk_ltr_ledger_entry_reference_type
    CHECK (reference_type IN ('CROWDFUNDING_PROJECT', 'VOTE', 'PEER_TRANSFER', 'AUCTION', 'SOCIAL_POST', 'NOTICE', 'TENDER'));
