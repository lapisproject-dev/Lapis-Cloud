-- V172 (V1.9.325) -- Refund of a travel expense report in LTR (Reisekostenerstattung in LTR).
--
-- Why a NEW migration and not an extension of V171: V171 is on the pushed origin/master (and may already be applied on staging); a changed checksum would
-- make Flyway refuse it there (same reasoning as V164 vs. V163).
--
-- (a) travel_expense_report.payout_choice + 'LTR' (the column is wide enough); chk_ter_ltr_not_sepa: an LTR report is never a SEPA / outside-booked report.
-- (b) organization_settings: the switch (default OFF) and two ledger accounts (the liability booked at approval, the account it is settled against).
-- (c) travel_expense_ltr_payout: one row per credit attempt, with the full receipt of the rate. A row is inserted once and changed only by its single
--     reversal. Not price_oracle_conversion: that table has donation semantics (donation_amount, ltr_minted) which a PartG or DSGVO reader would
--     misread. The rate itself stays in price_oracle_snapshot (+ _source) / price_oracle_reference_rate, referenced here.
--     active_report_id is the double-credit guard WITHOUT a partial index (it equals report_id while the credit is active, NULL after the reversal;
--     a plain UNIQUE index allows many NULLs on H2 and PostgreSQL alike).
-- (d) ltr_ledger_entry.entry_type + TRAVEL_REFUND (13) and TRAVEL_REFUND_REVERSE (21, width 21 fits), reference_type + TRAVEL_EXPENSE (14, width 20 fits);
--     the lists are V164's, copied verbatim, plus the new literals.
-- (e) audit_log_entry.entity_type: V171's list copied verbatim plus TRAVEL_EXPENSE_LTR_PAYOUT (25 chars, fits VARCHAR(29)).
--
-- A backup taken after this migration cannot be restored onto a server that is still before V172 (schema checksum).

-- (a)
ALTER TABLE travel_expense_report DROP CONSTRAINT IF EXISTS travel_expense_report_payout_choice_check;
ALTER TABLE travel_expense_report DROP CONSTRAINT IF EXISTS chk_ter_payout_choice;
ALTER TABLE travel_expense_report ADD CONSTRAINT chk_ter_payout_choice
    CHECK (payout_choice IS NULL OR payout_choice IN ('PAYOUT', 'DONATION_WAIVER', 'LTR'));
ALTER TABLE travel_expense_report ADD CONSTRAINT chk_ter_ltr_not_sepa
    CHECK (NOT (payout_choice = 'LTR' AND (sepa_exported_at IS NOT NULL OR paid_outside_lapis)));

-- (b)
ALTER TABLE organization_settings ADD COLUMN travel_ltr_payout_enabled BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE organization_settings ADD COLUMN travel_ltr_payable_account_id UUID NULL;
ALTER TABLE organization_settings ADD COLUMN travel_ltr_settlement_account_id UUID NULL;
ALTER TABLE organization_settings ADD CONSTRAINT fk_os_travel_ltr_payable_account
    FOREIGN KEY (travel_ltr_payable_account_id) REFERENCES ledger_account (id);
ALTER TABLE organization_settings ADD CONSTRAINT fk_os_travel_ltr_settlement_account
    FOREIGN KEY (travel_ltr_settlement_account_id) REFERENCES ledger_account (id);

-- (d) first, the payout table points at the ledger
ALTER TABLE ltr_ledger_entry DROP CONSTRAINT IF EXISTS ltr_ledger_entry_entry_type_check;
ALTER TABLE ltr_ledger_entry DROP CONSTRAINT IF EXISTS chk_ltr_ledger_entry_entry_type;
ALTER TABLE ltr_ledger_entry ADD CONSTRAINT chk_ltr_ledger_entry_entry_type
    CHECK (entry_type IN (
        'MINT', 'PROJECT_STAKE', 'PROJECT_STAKE_RELEASE', 'VOTE_STAKE', 'PEER_TRANSFER_OUT', 'PEER_TRANSFER_IN',
        'AUCTION_LISTING_FEE', 'AUCTION_HOLD', 'AUCTION_HOLD_RELEASE', 'AUCTION_SALE_OUT', 'AUCTION_SALE_IN',
        'SOCIAL_POST_STAKE', 'SOCIAL_POST_BOOST', 'NOTICE_BOOST', 'TENDER_PAYOUT', 'MEETING_REWARD',
        'TRAVEL_REFUND', 'TRAVEL_REFUND_REVERSE'
    ));
ALTER TABLE ltr_ledger_entry DROP CONSTRAINT IF EXISTS ltr_ledger_entry_reference_type_check;
ALTER TABLE ltr_ledger_entry DROP CONSTRAINT IF EXISTS chk_ltr_ledger_entry_reference_type;
ALTER TABLE ltr_ledger_entry ADD CONSTRAINT chk_ltr_ledger_entry_reference_type
    CHECK (reference_type IN ('CROWDFUNDING_PROJECT', 'VOTE', 'PEER_TRANSFER', 'AUCTION', 'SOCIAL_POST', 'NOTICE', 'TENDER', 'MEETING', 'TRAVEL_EXPENSE'));

-- (c)
CREATE TABLE travel_expense_ltr_payout (
    id UUID NOT NULL PRIMARY KEY,
    report_id UUID NOT NULL,
    attempt_no INT NOT NULL,
    member_id UUID NOT NULL,
    eur_amount DECIMAL(12, 2) NOT NULL,
    ltr_amount DECIMAL(18, 2) NOT NULL,
    anchor_asset VARCHAR(11) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    anchor_price DECIMAL(38, 18) NOT NULL,
    anchor_units_per_ltr DECIMAL(38, 18) NOT NULL,
    rate_basis VARCHAR(8) NOT NULL,
    reference_date DATE NOT NULL,
    effective_date DATE NOT NULL,
    fallback_reason VARCHAR(7) NOT NULL,
    snapshot_id UUID NULL,
    reference_rate_id UUID NULL,
    quorum_required INT NOT NULL,
    source_count INT NOT NULL,
    sources_used VARCHAR(500) NOT NULL,
    rate_confirmed_by UUID NULL,
    ltr_ledger_entry_id UUID NOT NULL,
    journal_entry_id UUID NOT NULL,
    triggered_by UUID NOT NULL,
    triggered_at TIMESTAMP NOT NULL,
    repeat_reason VARCHAR(500) NULL,
    reversed_at TIMESTAMP NULL,
    reversed_by UUID NULL,
    reversal_reason VARCHAR(500) NULL,
    reversal_ledger_entry_id UUID NULL,
    reversal_journal_entry_id UUID NULL,
    active_report_id UUID NULL,
    CONSTRAINT fk_telp_report FOREIGN KEY (report_id) REFERENCES travel_expense_report (id),
    CONSTRAINT fk_telp_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT fk_telp_snapshot FOREIGN KEY (snapshot_id) REFERENCES price_oracle_snapshot (id),
    CONSTRAINT fk_telp_reference_rate FOREIGN KEY (reference_rate_id) REFERENCES price_oracle_reference_rate (id),
    CONSTRAINT fk_telp_rate_confirmed_by FOREIGN KEY (rate_confirmed_by) REFERENCES member (id),
    CONSTRAINT fk_telp_ledger_entry FOREIGN KEY (ltr_ledger_entry_id) REFERENCES ltr_ledger_entry (id),
    CONSTRAINT fk_telp_journal_entry FOREIGN KEY (journal_entry_id) REFERENCES journal_entry (id),
    CONSTRAINT fk_telp_triggered_by FOREIGN KEY (triggered_by) REFERENCES member (id),
    CONSTRAINT fk_telp_reversed_by FOREIGN KEY (reversed_by) REFERENCES member (id),
    CONSTRAINT fk_telp_reversal_ledger_entry FOREIGN KEY (reversal_ledger_entry_id) REFERENCES ltr_ledger_entry (id),
    CONSTRAINT fk_telp_reversal_journal_entry FOREIGN KEY (reversal_journal_entry_id) REFERENCES journal_entry (id),
    CONSTRAINT uq_telp_report_attempt UNIQUE (report_id, attempt_no),
    CONSTRAINT uq_telp_active_report UNIQUE (active_report_id),
    CONSTRAINT uq_telp_ledger_entry UNIQUE (ltr_ledger_entry_id),
    CONSTRAINT uq_telp_journal_entry UNIQUE (journal_entry_id),
    CONSTRAINT uq_telp_reversal_ledger_entry UNIQUE (reversal_ledger_entry_id),
    CONSTRAINT uq_telp_reversal_journal_entry UNIQUE (reversal_journal_entry_id),
    CONSTRAINT chk_telp_amounts CHECK (ltr_amount > 0 AND eur_amount > 0 AND anchor_price > 0 AND anchor_units_per_ltr > 0),
    CONSTRAINT chk_telp_attempt CHECK (attempt_no >= 1),
    CONSTRAINT chk_telp_rate_basis CHECK (rate_basis IN ('SNAPSHOT', 'MANUAL')),
    CONSTRAINT chk_telp_fallback_reason CHECK (fallback_reason IN ('NONE', 'WEEKEND', 'HOLIDAY')),
    CONSTRAINT chk_telp_anchor CHECK (anchor_asset IN ('BITCOIN_BTC', 'GOLD_XAU', 'FIAT')),
    CONSTRAINT chk_telp_rate_consistency CHECK (
        (rate_basis = 'SNAPSHOT' AND snapshot_id IS NOT NULL AND reference_rate_id IS NULL AND rate_confirmed_by IS NULL)
        OR (rate_basis = 'MANUAL' AND reference_rate_id IS NOT NULL AND snapshot_id IS NULL AND rate_confirmed_by IS NOT NULL)
    ),
    CONSTRAINT chk_telp_repeat_reason CHECK (
        (attempt_no = 1 AND repeat_reason IS NULL)
        OR (attempt_no > 1 AND repeat_reason IS NOT NULL AND CHAR_LENGTH(repeat_reason) >= 10 AND CHAR_LENGTH(repeat_reason) <= 500)
    ),
    CONSTRAINT chk_telp_reversal CHECK (
        (reversed_at IS NULL AND reversed_by IS NULL AND reversal_reason IS NULL AND reversal_ledger_entry_id IS NULL
            AND reversal_journal_entry_id IS NULL AND active_report_id = report_id)
        OR (reversed_at IS NOT NULL AND reversed_by IS NOT NULL AND reversal_reason IS NOT NULL AND reversal_ledger_entry_id IS NOT NULL
            AND reversal_journal_entry_id IS NOT NULL AND active_report_id IS NULL
            AND CHAR_LENGTH(reversal_reason) >= 10 AND CHAR_LENGTH(reversal_reason) <= 500)
    )
);
CREATE INDEX idx_telp_member ON travel_expense_ltr_payout (member_id);

-- (e)
ALTER TABLE audit_log_entry
    DROP CONSTRAINT IF EXISTS chk_audit_log_entry_entity_type;
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
                                'TRAVEL_EXPENSE_LTR_PAYOUT'));
