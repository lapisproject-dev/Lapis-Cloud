-- Wave "Bankabgleich: Journal-Verknuepfung" (fix/payment-journal-link). V1..V167 untouched (V167 is a different, unreleased feature).
-- Until the release of this wave V168 may still be corrected on the feature branch; afterwards only a NEW migration may change it.
--
-- WHAT: the three bank-statement booking paths (manual contribution, manual donation, import auto-post) booked the journal entry but never wrote
-- payment_transaction.journal_entry_id, so the payment list showed those payments as "not booked". This migration back-fills the link for
-- existing payments. It changes NOTHING else: journal_entry and posting rows are never touched (GoBD).
--
-- No audit entry can be written from SQL (the hash chain lives in Kotlin). The link stays traceable because the journal description or voucher
-- carries the payment id, the line id or import/ordinal.
--
-- received_at is deliberately NOT changed here: a portable zone conversion is not possible in H2 MODE=PostgreSQL and PostgreSQL alike. It is
-- repaired at startup by BankPaymentReceivedAtRepair.
--
-- A payment is linked ONLY when exactly one journal entry matches it unambiguously. Skipped (left unlinked, safe):
--   (a) no match: voucher/description differ, the entry is not POSTED, or no DEBIT posting with the payment amount exists;
--   (b) more than one candidate journal entry for the payment;
--   (c) several payments claim the same journal entry;
--   (d) the journal entry is already linked to another payment;
--   (e) the payment belongs to more than one line, or the line is not POSTED;
--   (f) payments that already have journal_entry_id are unchanged; non-MANUAL (PSP) payments are never touched.
--
-- Portable for H2 MODE=PostgreSQL and PostgreSQL: no DO block, no partial index. ROLLBACK HAZARD / BACKUPS: a backup taken before V168 cannot be
-- restored on V168 and the other way round (schema checksum differs).

CREATE TABLE IF NOT EXISTS mig_v168_link_candidate (
    payment_transaction_id UUID NOT NULL,
    journal_entry_id       UUID NOT NULL
);
DELETE FROM mig_v168_link_candidate;

INSERT INTO mig_v168_link_candidate (payment_transaction_id, journal_entry_id)
SELECT pt.id, je.id
FROM payment_transaction pt
JOIN bank_statement_line l ON l.payment_transaction_id = pt.id AND l.status = 'POSTED'
JOIN journal_entry je ON je.status = 'POSTED' AND (
    (pt.intent = 'DONATION'
        AND pt.provider_event_id = 'manual-donation:' || CAST(l.id AS VARCHAR(36))
        AND je.description = 'Spende ' || CAST(pt.id AS VARCHAR(36)) || ' (GATEWAY)')
    OR (pt.intent = 'CONTRIBUTION' AND pt.contribution_id IS NOT NULL
        AND pt.provider_event_id = 'manual-assign:' || CAST(l.id AS VARCHAR(36))
        AND je.description = 'Mitgliedsbeitrag ' || CAST(pt.contribution_id AS VARCHAR(36)) || ' (manual)'
        AND je.voucher_reference = COALESCE(SUBSTRING(pt.reconciliation_note, 1, 140), 'KA-MANUAL-' || CAST(l.id AS VARCHAR(36))))
    OR (pt.intent = 'CONTRIBUTION' AND pt.contribution_id IS NOT NULL
        AND pt.provider_event_id = l.fingerprint
        AND je.description = 'Mitgliedsbeitrag ' || CAST(pt.contribution_id AS VARCHAR(36)) || ' (manual)'
        AND je.voucher_reference = 'KA-' || SUBSTRING(CAST(l.import_id AS VARCHAR(36)), 1, 8) || '-' || CAST(l.line_ordinal AS VARCHAR(10)))
)
WHERE pt.provider = 'MANUAL'
  AND pt.journal_entry_id IS NULL
  AND EXISTS (SELECT 1 FROM posting p WHERE p.journal_entry_id = je.id AND p.side = 'DEBIT' AND p.amount = pt.amount)
  AND NOT EXISTS (SELECT 1 FROM payment_transaction o WHERE o.journal_entry_id = je.id);

UPDATE payment_transaction
SET journal_entry_id = (SELECT c.journal_entry_id FROM mig_v168_link_candidate c WHERE c.payment_transaction_id = payment_transaction.id)
WHERE journal_entry_id IS NULL
  AND id IN (SELECT payment_transaction_id FROM mig_v168_link_candidate GROUP BY payment_transaction_id HAVING COUNT(*) = 1)
  AND id IN (SELECT c.payment_transaction_id FROM mig_v168_link_candidate c
             WHERE c.journal_entry_id IN (SELECT journal_entry_id FROM mig_v168_link_candidate GROUP BY journal_entry_id HAVING COUNT(*) = 1));

DROP TABLE mig_v168_link_candidate;
