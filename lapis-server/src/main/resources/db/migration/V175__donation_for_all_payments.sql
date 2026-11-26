-- V175 (V1.9.329) -- a donation (entity "donation", V174) for EVERY captured DONATION payment: bank, PayPal, Stripe and anonymous ones.
--
-- (a) donation.member_id and donation.booking_date become nullable. member_id is NULL for an anonymous donation (it can never be converted: the composite
--     FK fk_poc_donation_link checks price_oracle_conversion.member_id, which is NOT NULL). booking_date is NULL when the payment is not backed by exactly
--     one POSTED bank line (the reference date of a conversion is then always MANUAL and needs a justification). The dependent FK and the UNIQUE target are
--     dropped first and re-created afterwards (H2 does not like a column that is part of a referenced constraint changing under it).
-- (b) Backfill (idempotent): every CAPTURED payment with intent DONATION and amount > 0 that has no donation yet gets one. id = payment id (a UUID function in
--     SQL is not portable, see V174). booking_date = the day of the ONE POSTED bank line, else NULL. Payments with amount <= 0 are left out (chk_donation_amount), and so are PSP payments that were stored but not posted for a reason (journal_entry_id NULL and a
--     reconciliation_note: amount mismatch, delayed method), which must not become a convertible donation with the session amount.
-- (c) Earlier conversions without donation and payment (the V171 "free" conversions, reference_date_source MANUAL) are linked to a donation when and only
--     when the match is unambiguous in both directions: exactly one still free donation of the same member, amount and currency, and exactly one such
--     conversion, and the payment was received BEFORE the conversion was made (a later donation must never be claimed by an older conversion). The plan is computed into a helper table FIRST (the state before any update: H2 evaluates a self-referencing UPDATE row by row, PostgreSQL
--     from a snapshot) and dropped again. The date stays MANUAL; the legacy justification text is written (10..500 characters).
-- (d) fk_poc_donation_link is re-created, verbatim from V174.
--
-- Diagnosis (counts only):
--   linked:      SELECT COUNT(*) FROM price_oracle_conversion WHERE donation_id IS NOT NULL AND reference_date_override_reason LIKE 'Altbestand vor V175%';
--   legacy left: SELECT COUNT(*) FROM price_oracle_conversion WHERE donation_id IS NULL AND payment_transaction_id IS NULL
--                  AND rate_basis IN ('SNAPSHOT','MANUAL') AND reference_date_source = 'MANUAL';   -- ambiguous or without a match stay as they are
--   amount <= 0: SELECT COUNT(*) FROM payment_transaction pt WHERE pt.intent = 'DONATION' AND pt.status = 'CAPTURED' AND pt.amount <= 0
--                  AND NOT EXISTS (SELECT 1 FROM donation d WHERE d.payment_transaction_id = pt.id);
--
-- A backup taken after V175 cannot be restored onto a server that is still before V175 (schema checksum).
-- Portability: no partial index, no CASCADE, no UUID function in SQL; DROP CONSTRAINT IF EXISTS before ADD.

-- (a)
ALTER TABLE price_oracle_conversion DROP CONSTRAINT IF EXISTS fk_poc_donation_link;
ALTER TABLE donation DROP CONSTRAINT IF EXISTS uq_donation_link;
ALTER TABLE donation ALTER COLUMN member_id DROP NOT NULL;
ALTER TABLE donation ALTER COLUMN booking_date DROP NOT NULL;
ALTER TABLE donation ADD CONSTRAINT uq_donation_link UNIQUE (id, payment_transaction_id, member_id, amount, currency);

-- (b)
INSERT INTO donation (id, payment_transaction_id, member_id, amount, currency, booking_date, created_at, created_by)
SELECT pt.id, pt.id, pt.member_id, pt.amount, UPPER(pt.currency),
       CASE WHEN (SELECT COUNT(*) FROM bank_statement_line b WHERE b.payment_transaction_id = pt.id AND b.status = 'POSTED') = 1
            THEN (SELECT MAX(b.booking_date) FROM bank_statement_line b WHERE b.payment_transaction_id = pt.id AND b.status = 'POSTED')
            ELSE NULL END,
       COALESCE(pt.received_at, CURRENT_TIMESTAMP), NULL
FROM payment_transaction pt
WHERE pt.intent = 'DONATION' AND pt.status = 'CAPTURED' AND pt.amount > 0
  AND (pt.journal_entry_id IS NOT NULL OR pt.reconciliation_note IS NULL)
  AND NOT EXISTS (SELECT 1 FROM donation d WHERE d.payment_transaction_id = pt.id)
  AND NOT EXISTS (SELECT 1 FROM donation d WHERE d.id = pt.id);

-- (c)
CREATE TABLE v175_donation_link_plan (
    conversion_id          UUID NOT NULL PRIMARY KEY,
    donation_id            UUID NOT NULL,
    payment_transaction_id UUID NOT NULL
);
INSERT INTO v175_donation_link_plan (conversion_id, donation_id, payment_transaction_id)
SELECT c.id, d.id, d.payment_transaction_id
FROM price_oracle_conversion c
JOIN donation d ON d.member_id = c.member_id AND d.amount = c.donation_amount AND d.currency = c.donation_currency
JOIN payment_transaction pt ON pt.id = d.payment_transaction_id AND pt.received_at <= c.created_at
WHERE c.donation_id IS NULL AND c.payment_transaction_id IS NULL
  AND c.rate_basis IN ('SNAPSHOT', 'MANUAL') AND c.reference_date_source = 'MANUAL'
  AND NOT EXISTS (SELECT 1 FROM price_oracle_conversion x WHERE x.donation_id = d.id OR x.payment_transaction_id = d.payment_transaction_id)
  AND (SELECT COUNT(*) FROM donation d2
        JOIN payment_transaction pt2 ON pt2.id = d2.payment_transaction_id AND pt2.received_at <= c.created_at
        WHERE d2.member_id = c.member_id AND d2.amount = c.donation_amount AND d2.currency = c.donation_currency
          AND NOT EXISTS (SELECT 1 FROM price_oracle_conversion x2 WHERE x2.donation_id = d2.id OR x2.payment_transaction_id = d2.payment_transaction_id)) = 1
  AND (SELECT COUNT(*) FROM price_oracle_conversion c2
        WHERE c2.donation_id IS NULL AND c2.payment_transaction_id IS NULL
          AND c2.rate_basis IN ('SNAPSHOT', 'MANUAL') AND c2.reference_date_source = 'MANUAL'
          AND c2.created_at >= pt.received_at
          AND c2.member_id = d.member_id AND c2.donation_amount = d.amount AND c2.donation_currency = d.currency) = 1;

UPDATE price_oracle_conversion
   SET donation_id = (SELECT p.donation_id FROM v175_donation_link_plan p WHERE p.conversion_id = price_oracle_conversion.id),
       payment_transaction_id = (SELECT p.payment_transaction_id FROM v175_donation_link_plan p WHERE p.conversion_id = price_oracle_conversion.id),
       reference_date_override_reason = COALESCE(reference_date_override_reason,
           'Altbestand vor V175: nachträglich mit Spende verknüpft, Stichtag ohne Begründung erfasst')
 WHERE id IN (SELECT conversion_id FROM v175_donation_link_plan);

DROP TABLE v175_donation_link_plan;

-- (d)
ALTER TABLE price_oracle_conversion ADD CONSTRAINT fk_poc_donation_link
    FOREIGN KEY (donation_id, payment_transaction_id, member_id, donation_amount, donation_currency)
    REFERENCES donation (id, payment_transaction_id, member_id, amount, currency) ON DELETE RESTRICT;
