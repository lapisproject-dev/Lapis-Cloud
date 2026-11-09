-- V1.9.297 "Schwarzes Brett" (members' bulletin board). See 81-bulletin-board.kuml.kts and docs/features/bulletin-board.adoc.
-- V1..V148 untouched (V148 is already on master, so this wave gets its own migration; changing V148 in place would break its Flyway checksum on
-- every database that already ran it).
-- Purely additive and repeatable, H2 MODE=PostgreSQL- and PostgreSQL-compatible: CREATE TABLE/INDEX IF NOT EXISTS, DROP CONSTRAINT IF EXISTS
-- before every ADD CONSTRAINT, no partial index, NO backfill.
--
--   * bulletin_notice: one notice (author, category, title, text, expiry, pin, status, removal). expires_at is a class-A UTC instant
--     (`created_at < expires_at <= created_at + 90 days`); every read decides on it, the status EXPIRED is only bookkeeping of the retention poller.
--   * bulletin_notice_boost: one LTR stake on a notice (who spent how much when). NO UNIQUE(notice_id, member_id): a boost is a real payment, two
--     payments are twice the weight (same posture as social_post_boost). NO ON DELETE CASCADE: the retention poller and the DSGVO erasure delete
--     the boost rows explicitly first. The weight is DERIVED at read time (`SocialPostWeight`), never stored.
--   * ltr_ledger_entry.entry_type + NOTICE_BOOST (12 chars), reference_type + NOTICE (6 chars); both fit the existing widths.
--   * attached_image.owner_type + NOTICE (VARCHAR(32)).
--   * audit_log_entry.entity_type + BULLETIN_NOTICE (15 chars, VARCHAR(29)); the list is V147's, copied verbatim, plus the new literal.
--
-- NOT expressible in SQL (checked in the service): the cap of 5 active notices per member, the cap of 5 pinned notices, rate limits, the lock
-- order (notice -> member -> audit), the LTR weight and ordering, the visibility rules (author must still be an active member).
-- DSGVO: the notice is personal data of its AUTHOR (free text, a picture). The data-subject erasure deletes the notices, their boost rows, the
-- picture rows and the files in EVERY erasure mode (no counterpart copy exists); a member's OWN boosts on somebody else's notices are RETAINED
-- (part of the LTR booking history, GoBD). pinned_by / removed_by are set to NULL on erasure of the moderator, hence no CHECK on them beyond
-- "pinned_by needs pinned_at".

CREATE TABLE IF NOT EXISTS bulletin_notice (
    id                  UUID          NOT NULL PRIMARY KEY,
    author_member_id    UUID          NOT NULL,
    category            VARCHAR(8)    NOT NULL,
    title               VARCHAR(120)  NOT NULL,
    body                VARCHAR(2000) NOT NULL,
    created_at          TIMESTAMP     NOT NULL,
    updated_at          TIMESTAMP     NULL,
    expires_at          TIMESTAMP     NOT NULL,
    pinned_at           TIMESTAMP     NULL,
    pinned_by           UUID          NULL,
    status              VARCHAR(9)    NOT NULL,
    withdrawn_at        TIMESTAMP     NULL,
    removed_at          TIMESTAMP     NULL,
    removed_by          UUID          NULL,
    removal_reason_code VARCHAR(9)    NULL,
    removal_note        VARCHAR(500)  NULL
);

ALTER TABLE bulletin_notice DROP CONSTRAINT IF EXISTS fk_bulletin_notice_author;
ALTER TABLE bulletin_notice ADD CONSTRAINT fk_bulletin_notice_author FOREIGN KEY (author_member_id) REFERENCES member(id);
ALTER TABLE bulletin_notice DROP CONSTRAINT IF EXISTS fk_bulletin_notice_pinned_by;
ALTER TABLE bulletin_notice ADD CONSTRAINT fk_bulletin_notice_pinned_by FOREIGN KEY (pinned_by) REFERENCES member(id);
ALTER TABLE bulletin_notice DROP CONSTRAINT IF EXISTS fk_bulletin_notice_removed_by;
ALTER TABLE bulletin_notice ADD CONSTRAINT fk_bulletin_notice_removed_by FOREIGN KEY (removed_by) REFERENCES member(id);

ALTER TABLE bulletin_notice DROP CONSTRAINT IF EXISTS chk_bulletin_notice_category;
ALTER TABLE bulletin_notice ADD CONSTRAINT chk_bulletin_notice_category CHECK (category IN ('SEARCH', 'OFFER', 'INFO', 'OTHER'));

ALTER TABLE bulletin_notice DROP CONSTRAINT IF EXISTS chk_bulletin_notice_status;
ALTER TABLE bulletin_notice ADD CONSTRAINT chk_bulletin_notice_status CHECK (status IN ('ACTIVE', 'EXPIRED', 'WITHDRAWN', 'REMOVED'));

ALTER TABLE bulletin_notice DROP CONSTRAINT IF EXISTS chk_bulletin_notice_reason;
ALTER TABLE bulletin_notice ADD CONSTRAINT chk_bulletin_notice_reason
    CHECK (removal_reason_code IS NULL OR removal_reason_code IN ('SPAM', 'RULES', 'DUPLICATE', 'LEGAL'));

ALTER TABLE bulletin_notice DROP CONSTRAINT IF EXISTS chk_bulletin_notice_title;
ALTER TABLE bulletin_notice ADD CONSTRAINT chk_bulletin_notice_title CHECK (CHAR_LENGTH(title) BETWEEN 3 AND 120);

ALTER TABLE bulletin_notice DROP CONSTRAINT IF EXISTS chk_bulletin_notice_body;
ALTER TABLE bulletin_notice ADD CONSTRAINT chk_bulletin_notice_body CHECK (CHAR_LENGTH(body) BETWEEN 1 AND 2000);

-- Expiry: strictly after creation, at most 90 days after it.
ALTER TABLE bulletin_notice DROP CONSTRAINT IF EXISTS chk_bulletin_notice_expiry;
ALTER TABLE bulletin_notice ADD CONSTRAINT chk_bulletin_notice_expiry
    CHECK (expires_at > created_at AND expires_at <= created_at + INTERVAL '90' DAY);

-- pinned_by only together with pinned_at (the reverse is allowed: erasing the moderator sets pinned_by to NULL and keeps the pin).
ALTER TABLE bulletin_notice DROP CONSTRAINT IF EXISTS chk_bulletin_notice_pin;
ALTER TABLE bulletin_notice ADD CONSTRAINT chk_bulletin_notice_pin CHECK (pinned_by IS NULL OR pinned_at IS NOT NULL);

-- REMOVED <=> removed_at and removal_reason_code are set; a note only on a removed notice. removed_by may become NULL (erased moderator).
ALTER TABLE bulletin_notice DROP CONSTRAINT IF EXISTS chk_bulletin_notice_removed;
ALTER TABLE bulletin_notice ADD CONSTRAINT chk_bulletin_notice_removed CHECK (
    (status = 'REMOVED' AND removed_at IS NOT NULL AND removal_reason_code IS NOT NULL)
    OR (status <> 'REMOVED' AND removed_at IS NULL AND removed_by IS NULL AND removal_reason_code IS NULL AND removal_note IS NULL)
);

-- WITHDRAWN <=> withdrawn_at is set (the 30 day retention after withdrawing counts from it).
ALTER TABLE bulletin_notice DROP CONSTRAINT IF EXISTS chk_bulletin_notice_withdrawn;
ALTER TABLE bulletin_notice ADD CONSTRAINT chk_bulletin_notice_withdrawn CHECK (
    (status = 'WITHDRAWN' AND withdrawn_at IS NOT NULL) OR (status <> 'WITHDRAWN' AND withdrawn_at IS NULL)
);

CREATE INDEX IF NOT EXISTS idx_bulletin_notice_status_expires ON bulletin_notice (status, expires_at);
CREATE INDEX IF NOT EXISTS idx_bulletin_notice_author_status ON bulletin_notice (author_member_id, status);
CREATE INDEX IF NOT EXISTS idx_bulletin_notice_pinned ON bulletin_notice (pinned_at);

CREATE TABLE IF NOT EXISTS bulletin_notice_boost (
    id          UUID          NOT NULL PRIMARY KEY,
    notice_id   UUID          NOT NULL,
    member_id   UUID          NOT NULL,
    amount_ltr  DECIMAL(18,2) NOT NULL,
    boosted_at  TIMESTAMP     NOT NULL
);

ALTER TABLE bulletin_notice_boost DROP CONSTRAINT IF EXISTS fk_bulletin_notice_boost_notice;
ALTER TABLE bulletin_notice_boost ADD CONSTRAINT fk_bulletin_notice_boost_notice FOREIGN KEY (notice_id) REFERENCES bulletin_notice(id);
ALTER TABLE bulletin_notice_boost DROP CONSTRAINT IF EXISTS fk_bulletin_notice_boost_member;
ALTER TABLE bulletin_notice_boost ADD CONSTRAINT fk_bulletin_notice_boost_member FOREIGN KEY (member_id) REFERENCES member(id);

-- SocialPostWeight.MIN_WEIGHT_LTR, mirrored database-side (same as chk_social_post_boost_min_amount).
ALTER TABLE bulletin_notice_boost DROP CONSTRAINT IF EXISTS chk_bulletin_notice_boost_min_amount;
ALTER TABLE bulletin_notice_boost ADD CONSTRAINT chk_bulletin_notice_boost_min_amount CHECK (amount_ltr >= 0.01);

CREATE INDEX IF NOT EXISTS idx_bulletin_notice_boost_notice ON bulletin_notice_boost (notice_id);
CREATE INDEX IF NOT EXISTS idx_bulletin_notice_boost_member ON bulletin_notice_boost (member_id);
-- Carries the duplicate-click window (notice + member + boosted_at) in ONE index.
CREATE INDEX IF NOT EXISTS idx_bulletin_notice_boost_dup ON bulletin_notice_boost (notice_id, member_id, boosted_at);

-- Ledger widening, same dual-DROP discipline as V4/V5 (the guessed Postgres auto-name plus the explicit name).
ALTER TABLE ltr_ledger_entry DROP CONSTRAINT IF EXISTS ltr_ledger_entry_entry_type_check;
ALTER TABLE ltr_ledger_entry DROP CONSTRAINT IF EXISTS chk_ltr_ledger_entry_entry_type;
ALTER TABLE ltr_ledger_entry ADD CONSTRAINT chk_ltr_ledger_entry_entry_type
    CHECK (entry_type IN (
        'MINT', 'PROJECT_STAKE', 'PROJECT_STAKE_RELEASE', 'VOTE_STAKE', 'PEER_TRANSFER_OUT', 'PEER_TRANSFER_IN',
        'AUCTION_LISTING_FEE', 'AUCTION_HOLD', 'AUCTION_HOLD_RELEASE', 'AUCTION_SALE_OUT', 'AUCTION_SALE_IN',
        'SOCIAL_POST_STAKE', 'SOCIAL_POST_BOOST', 'NOTICE_BOOST'
    ));
ALTER TABLE ltr_ledger_entry DROP CONSTRAINT IF EXISTS ltr_ledger_entry_reference_type_check;
ALTER TABLE ltr_ledger_entry DROP CONSTRAINT IF EXISTS chk_ltr_ledger_entry_reference_type;
ALTER TABLE ltr_ledger_entry ADD CONSTRAINT chk_ltr_ledger_entry_reference_type
    CHECK (reference_type IN ('CROWDFUNDING_PROJECT', 'VOTE', 'PEER_TRANSFER', 'AUCTION', 'SOCIAL_POST', 'NOTICE'));

ALTER TABLE attached_image DROP CONSTRAINT IF EXISTS chk_attached_image_owner_type;
ALTER TABLE attached_image ADD CONSTRAINT chk_attached_image_owner_type
    CHECK (owner_type IN ('CROWDFUNDING_PROJECT', 'AUCTION_LISTING', 'SOCIAL_POST', 'NOTICE'));

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
                                'BULLETIN_NOTICE'
        ));
