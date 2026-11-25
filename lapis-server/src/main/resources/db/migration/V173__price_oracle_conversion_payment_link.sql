-- V173 (V1.9.327) -- Follow-up of "gold price at a reference date": a conversion can be tied to the bank payment (donation) it converts.
--
-- (a) price_oracle_conversion.payment_transaction_id: the donation payment that was converted (nullable: a conversion "without a bank payment" stays possible).
--     ON DELETE RESTRICT: a payment with a conversion must not disappear. UNIQUE: a payment is converted at most once (the double-conversion guard;
--     several NULLs are allowed by both H2 and Postgres, so a plain UNIQUE constraint is enough, no partial index needed).
-- (b) price_oracle_conversion.reference_date_source: where the reference date came from -- BANK_BOOKING (the date equals the bank booking day of the
--     linked payment) or MANUAL. Derived by the SERVER only, never sent by a client. NULL for a CURRENT conversion.
-- (c) Backfill: every V171/V172 conversion with rate_basis SNAPSHOT or MANUAL gets reference_date_source = 'MANUAL' (no payment link is known). The backfill
--     MUST run before the new CHECK constraint below, otherwise it would reject those rows.
-- (d) chk_poc_rate_consistency is replaced: the V171 expression stays verbatim, plus the rules for the two new columns.
--
-- A backup taken after this migration cannot be restored onto a server that is still before V173.

ALTER TABLE price_oracle_conversion ADD COLUMN payment_transaction_id UUID NULL;
ALTER TABLE price_oracle_conversion ADD COLUMN reference_date_source VARCHAR(12) NULL;

UPDATE price_oracle_conversion SET reference_date_source = 'MANUAL' WHERE rate_basis IN ('SNAPSHOT', 'MANUAL');

ALTER TABLE price_oracle_conversion ADD CONSTRAINT fk_poc_payment_transaction
    FOREIGN KEY (payment_transaction_id) REFERENCES payment_transaction (id) ON DELETE RESTRICT;
ALTER TABLE price_oracle_conversion ADD CONSTRAINT chk_poc_reference_date_source
    CHECK (reference_date_source IS NULL OR reference_date_source IN ('BANK_BOOKING', 'MANUAL'));
ALTER TABLE price_oracle_conversion ADD CONSTRAINT uq_poc_payment_transaction UNIQUE (payment_transaction_id);

ALTER TABLE price_oracle_conversion DROP CONSTRAINT chk_poc_rate_consistency;
ALTER TABLE price_oracle_conversion ADD CONSTRAINT chk_poc_rate_consistency CHECK (
    (
        (rate_basis = 'CURRENT' AND snapshot_id IS NULL AND reference_rate_id IS NULL AND reference_date IS NULL
            AND effective_date IS NULL AND fallback_reason IS NULL AND quorum_required IS NULL)
        OR (rate_basis = 'SNAPSHOT' AND snapshot_id IS NOT NULL AND reference_rate_id IS NULL AND reference_date IS NOT NULL
            AND effective_date IS NOT NULL AND fallback_reason IS NOT NULL AND quorum_required IS NOT NULL)
        OR (rate_basis = 'MANUAL' AND reference_rate_id IS NOT NULL AND snapshot_id IS NULL AND reference_date IS NOT NULL
            AND effective_date IS NOT NULL AND fallback_reason IS NOT NULL AND quorum_required IS NOT NULL)
    )
    AND (
        (rate_basis = 'CURRENT' AND reference_date_source IS NULL AND payment_transaction_id IS NULL)
        OR (rate_basis IN ('SNAPSHOT', 'MANUAL') AND reference_date_source IS NOT NULL)
    )
    AND (reference_date_source IS NULL OR reference_date_source <> 'BANK_BOOKING' OR payment_transaction_id IS NOT NULL)
);
