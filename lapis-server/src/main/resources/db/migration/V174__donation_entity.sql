-- V174 (V1.9.328) -- "Spende" (donation) as an entity of its own: a reference-date conversion converts ONE donation, once, in full.
--
-- (a) donation: one row per converted (or convertible) donation. payment_transaction_id is UNIQUE (a payment is one donation). booking_date is the bank
--     booking day, frozen when the donation was created. created_by is NULL for rows written by the backfill below.
--     uq_donation_link is a UNIQUE CONSTRAINT (not an index: H2 does not accept a unique index as a foreign-key target) and the target of the composite FK.
-- (b) Backfill: every CAPTURED payment with intent DONATION, a member, a positive amount and EXACTLY ONE POSTED bank line becomes a donation. The id is
--     deliberately the payment id (a UUID function in SQL is not portable between H2 and PostgreSQL, see V32/V34). Nobody may rely on donation.id equalling
--     payment_transaction_id: donations created later by the application get a random id.
-- (c) price_oracle_conversion.donation_id and .reference_date_override_reason (the justification for a manually chosen reference date, 10..500 characters).
-- (d) V173 conversions are linked to their donation through payment_transaction_id.
-- (e) Existing data: MANUAL conversions get the legacy text; the V173 backfill of reference_date_source is repeated (idempotent).
-- (f) Constraints (after the backfill): UNIQUE donation_id; composite FK (donation_id, payment_transaction_id, member_id, donation_amount, donation_currency)
--     -> donation (id, payment_transaction_id, member_id, amount, currency) -- the conversion must match the donation exactly (MATCH SIMPLE: the check is skipped
--     while donation_id is NULL, so CURRENT conversions and pre-V174 rows stay valid); donation and payment are set together; the override reason belongs to
--     MANUAL conversions of a donation only.
-- (g) audit_log_entry.entity_type: V172's list copied verbatim plus DONATION (8 chars, fits VARCHAR(29)).
--
-- The migration ABORTS (by design) on an inconsistent V173 row:
--   * constraint chk_poc_donation_payment: a conversion's payment has no matching donation (e.g. its bank line is no longer the only POSTED line);
--   * constraint fk_poc_donation_link: amount, member or currency of the conversion differ from the donation.
-- Diagnosis before rolling out:
--   SELECT c.id, c.payment_transaction_id, c.member_id, c.donation_amount, c.donation_currency FROM price_oracle_conversion c
--    WHERE c.payment_transaction_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM payment_transaction pt JOIN bank_statement_line b
--      ON b.payment_transaction_id = pt.id AND b.status = 'POSTED' WHERE pt.id = c.payment_transaction_id AND pt.intent = 'DONATION'
--      AND pt.status = 'CAPTURED' AND pt.member_id = c.member_id AND pt.amount = c.donation_amount AND UPPER(pt.currency) = c.donation_currency);
--
-- A backup taken after this migration cannot be restored onto a server that is still before V174 (schema checksum).
-- Portability: no partial index, no CASCADE, no UUID function in SQL; DROP CONSTRAINT IF EXISTS before ADD.

-- (a)
CREATE TABLE donation (
    id                     UUID          NOT NULL PRIMARY KEY,
    payment_transaction_id UUID          NOT NULL,
    member_id              UUID          NOT NULL,
    amount                 DECIMAL(18,2) NOT NULL,
    currency               VARCHAR(3)    NOT NULL,
    booking_date           DATE          NOT NULL,
    created_at             TIMESTAMP     NOT NULL,
    created_by             UUID          NULL,
    CONSTRAINT fk_donation_payment_transaction FOREIGN KEY (payment_transaction_id) REFERENCES payment_transaction (id) ON DELETE RESTRICT,
    CONSTRAINT fk_donation_member FOREIGN KEY (member_id) REFERENCES member (id) ON DELETE RESTRICT,
    CONSTRAINT fk_donation_created_by FOREIGN KEY (created_by) REFERENCES member (id) ON DELETE RESTRICT,
    CONSTRAINT uq_donation_payment_transaction UNIQUE (payment_transaction_id),
    CONSTRAINT uq_donation_link UNIQUE (id, payment_transaction_id, member_id, amount, currency),
    CONSTRAINT chk_donation_amount CHECK (amount > 0),
    CONSTRAINT chk_donation_currency CHECK (currency = UPPER(currency))
);
CREATE INDEX idx_donation_member ON donation (member_id);

-- (b)
INSERT INTO donation (id, payment_transaction_id, member_id, amount, currency, booking_date, created_at, created_by)
SELECT pt.id, pt.id, pt.member_id, pt.amount, UPPER(pt.currency), bsl.booking_date, pt.received_at, NULL
FROM payment_transaction pt
JOIN bank_statement_line bsl ON bsl.payment_transaction_id = pt.id AND bsl.status = 'POSTED'
WHERE pt.intent = 'DONATION' AND pt.status = 'CAPTURED' AND pt.member_id IS NOT NULL AND pt.amount > 0
  AND NOT EXISTS (SELECT 1 FROM bank_statement_line o WHERE o.payment_transaction_id = pt.id AND o.status = 'POSTED' AND o.id <> bsl.id);

-- (c)
ALTER TABLE price_oracle_conversion ADD COLUMN donation_id UUID NULL;
ALTER TABLE price_oracle_conversion ADD COLUMN reference_date_override_reason VARCHAR(500) NULL;

-- (d)
UPDATE price_oracle_conversion
   SET donation_id = (SELECT d.id FROM donation d WHERE d.payment_transaction_id = price_oracle_conversion.payment_transaction_id)
 WHERE payment_transaction_id IS NOT NULL;

-- (e)
UPDATE price_oracle_conversion SET reference_date_source = 'MANUAL'
 WHERE rate_basis IN ('SNAPSHOT', 'MANUAL') AND reference_date_source IS NULL;
UPDATE price_oracle_conversion SET reference_date_override_reason = 'Altbestand vor V174: Stichtag ohne Begründung erfasst'
 WHERE donation_id IS NOT NULL AND reference_date_source = 'MANUAL' AND reference_date_override_reason IS NULL;

-- (f)
ALTER TABLE price_oracle_conversion DROP CONSTRAINT IF EXISTS uq_poc_donation;
ALTER TABLE price_oracle_conversion ADD CONSTRAINT uq_poc_donation UNIQUE (donation_id);
ALTER TABLE price_oracle_conversion DROP CONSTRAINT IF EXISTS chk_poc_donation_payment;
ALTER TABLE price_oracle_conversion ADD CONSTRAINT chk_poc_donation_payment CHECK (
    (donation_id IS NULL AND payment_transaction_id IS NULL) OR (donation_id IS NOT NULL AND payment_transaction_id IS NOT NULL));
ALTER TABLE price_oracle_conversion DROP CONSTRAINT IF EXISTS fk_poc_donation_link;
ALTER TABLE price_oracle_conversion ADD CONSTRAINT fk_poc_donation_link
    FOREIGN KEY (donation_id, payment_transaction_id, member_id, donation_amount, donation_currency)
    REFERENCES donation (id, payment_transaction_id, member_id, amount, currency) ON DELETE RESTRICT;
ALTER TABLE price_oracle_conversion DROP CONSTRAINT IF EXISTS chk_poc_override_reason;
ALTER TABLE price_oracle_conversion ADD CONSTRAINT chk_poc_override_reason CHECK (
       (donation_id IS NULL AND reference_date_override_reason IS NULL)
    OR (donation_id IS NOT NULL AND reference_date_source = 'BANK_BOOKING' AND reference_date_override_reason IS NULL)
    OR (donation_id IS NOT NULL AND reference_date_source = 'MANUAL' AND reference_date_override_reason IS NOT NULL
        AND CHAR_LENGTH(reference_date_override_reason) BETWEEN 10 AND 500));

-- (g)
ALTER TABLE audit_log_entry DROP CONSTRAINT IF EXISTS chk_audit_log_entry_entity_type;
ALTER TABLE audit_log_entry
    ADD CONSTRAINT chk_audit_log_entry_entity_type
        CHECK (entity_type IN ('JOURNAL_ENTRY', 'PARTY_DONATION_VERDICT', 'RESOLUTION', 'BOARD_MEMBERSHIP',
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
                                'TRAVEL_EXPENSE_SEPA_BATCH', 'CARPOOL_PARTICIPATION', 'VIDEO_OVERLAY', 'AGREEMENT_TEXT', 'PULPIT_RECORDING',
                                'EVENT_CAPTURE', 'TRAVEL_EXPENSE_DOCUMENT', 'TENDER', 'EVENT_REGISTRATION_EXPORT', 'ATTACHED_IMAGE',
                                'PARTG_REPORT_SHEET', 'PARTG_REPORT_POSITION', 'LUPABB_SYNC_RUN', 'LUPABB_SYNC_CONFIG', 'MEETING_EVENT_LINK',
                                'MEETING_SESSION', 'DIRECT_MESSAGE_GROUP_SEND', 'PARTNER_SERVER', 'PARTNER_BLOCKLIST',
                                'ACTIVISM_ACTION', 'ACTIVISM_CONTRIBUTION', 'ACTIVISM_REPORT', 'ACTIVISM_SETTINGS',
                                'PARTG_REPORT_EXPORT',
                                'PARTG_REPORT_ANNEX', 'PARTG_REPORT_NOTES',
                                'PARTG_ACCOUNT_MAPPING', 'PARTG_UNIT_MODE', 'REGIONAL_CHAPTER_TREASURER',
                                'PARTG_INTRA_TRANSFER', 'PARTG_DONOR_COMBINATION',
                                'PARTG_REPORT_VERSION', 'PARTG_REPORT_DECLARATION', 'PARTG_REPORT_DEADLINE',
                                'PARTG_LARGE_DONOR_PROPOSAL',
                                'BULLETIN_NOTICE',
                                'CONFERENCE_ROOM_POLL',
                                'PARTG_REVIEWER_ACCESS',
                                'CARPOOL_FEDERATION_OPT_IN',
                                'CARPOOL_FEDERATION_FEED',
                                'MEETING_REWARD_POLICY', 'MEETING_REWARD_SETTLEMENT',
                                'AUCTION',
                                'AUCTION_FEDERATION_FEED',
                                'PRICE_ORACLE_REFERENCE_RATE', 'PRICE_ORACLE_CONVERSION',
                                'TRAVEL_EXPENSE_LTR_PAYOUT',
                                'DONATION'));
