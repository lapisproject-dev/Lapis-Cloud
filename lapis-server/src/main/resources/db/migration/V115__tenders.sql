-- V1.9.201 "Ausschreibungen" (tenders), wave 1: the foundation, LTR only. See 74-tender.kuml.kts and docs/features/tenders.adoc.
-- Purely additive and repeatable: CREATE TABLE IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT, no partial index, no ON DELETE
-- CASCADE, H2 MODE=PostgreSQL- and Postgres-compatible. V114 is released (0.38.0) and migrated on every instance, so it is NOT edited.
--
--   * tender: one task put out to bid, bound to an ADOPTED resolution (motion). status is the STORED status; PUBLISHED past the deadline is
--     CLOSED for every reader and is persisted by the next writing path (lazy close). deadline is a CLASS B value (wall-clock of the
--     organisation). Currency is LTR only: the CHECK makes EUR unreachable even for a direct SQL writer. payout_ledger_entry_id is RESERVED for
--     wave 3 and never written in wave 1 (deliberately no foreign key: the payout design is not decided).
--   * tender_bid: one row per (tender, bidder), upserted; a withdrawn bid keeps its row (a bidder who withdrew is still a conflicted party).
--   * tender_award: at most one per tender, and one per bid. award_motion_id / resolution_id are filled on the BEST_OFFER path.
--   * tender_acceptance: the acceptance decisions of the board; a rejection needs a reason.
--   * organization_settings.tender_default_visibility: the pre-fill of the bid visibility; NOT part of the generic update write-set.
--   * audit_log_entry.entity_type: widened by TENDER (6 chars, VARCHAR(29) is unchanged).

CREATE TABLE IF NOT EXISTS tender (
    id                     UUID          NOT NULL PRIMARY KEY,
    title                  VARCHAR(200)  NOT NULL,
    description            VARCHAR(10000) NOT NULL,
    expected_result        VARCHAR(4000) NOT NULL,
    binding_motion_id      UUID          NOT NULL REFERENCES motion (id),
    award_criterion        VARCHAR(12)   NOT NULL,
    currency               VARCHAR(3)    NOT NULL DEFAULT 'LTR',
    bid_visibility         VARCHAR(21)   NOT NULL,
    status                 VARCHAR(9)    NOT NULL,
    deadline               TIMESTAMP     NOT NULL,
    created_by             UUID          NOT NULL REFERENCES member (id),
    created_at             TIMESTAMP     NOT NULL,
    published_by           UUID          NULL REFERENCES member (id),
    published_at           TIMESTAMP     NULL,
    closed_at              TIMESTAMP     NULL,
    cancelled_by           UUID          NULL REFERENCES member (id),
    cancelled_at           TIMESTAMP     NULL,
    cancel_reason          VARCHAR(2000) NULL,
    award_motion_id        UUID          NULL REFERENCES motion (id),
    payout_ledger_entry_id UUID          NULL
);

ALTER TABLE tender DROP CONSTRAINT IF EXISTS chk_tender_award_criterion;
ALTER TABLE tender ADD CONSTRAINT chk_tender_award_criterion CHECK (award_criterion IN ('LOWEST_PRICE', 'BEST_OFFER'));

ALTER TABLE tender DROP CONSTRAINT IF EXISTS chk_tender_currency;
ALTER TABLE tender ADD CONSTRAINT chk_tender_currency CHECK (currency = 'LTR');

ALTER TABLE tender DROP CONSTRAINT IF EXISTS chk_tender_bid_visibility;
ALTER TABLE tender ADD CONSTRAINT chk_tender_bid_visibility CHECK (bid_visibility IN ('HIDDEN_UNTIL_DEADLINE', 'OPEN', 'OWN_ONLY'));

ALTER TABLE tender DROP CONSTRAINT IF EXISTS chk_tender_status;
ALTER TABLE tender ADD CONSTRAINT chk_tender_status
    CHECK (status IN ('DRAFT', 'PUBLISHED', 'CLOSED', 'AWARDED', 'COMPLETED', 'CANCELLED'));

ALTER TABLE tender DROP CONSTRAINT IF EXISTS chk_tender_award_motion_differs;
ALTER TABLE tender ADD CONSTRAINT chk_tender_award_motion_differs CHECK (award_motion_id IS NULL OR award_motion_id <> binding_motion_id);

ALTER TABLE tender DROP CONSTRAINT IF EXISTS chk_tender_title;
ALTER TABLE tender ADD CONSTRAINT chk_tender_title CHECK (CHAR_LENGTH(TRIM(title)) BETWEEN 1 AND 200);

CREATE UNIQUE INDEX IF NOT EXISTS uq_tender_award_motion ON tender (award_motion_id);
CREATE INDEX IF NOT EXISTS ix_tender_binding_motion ON tender (binding_motion_id);
CREATE INDEX IF NOT EXISTS ix_tender_status_deadline ON tender (status, deadline);

CREATE TABLE IF NOT EXISTS tender_bid (
    id               UUID          NOT NULL PRIMARY KEY,
    tender_id        UUID          NOT NULL REFERENCES tender (id),
    bidder_member_id UUID          NOT NULL REFERENCES member (id),
    price            DECIMAL(18,2) NOT NULL,
    currency         VARCHAR(3)    NOT NULL DEFAULT 'LTR',
    text             VARCHAR(4000) NOT NULL,
    status           VARCHAR(9)    NOT NULL,
    submitted_at     TIMESTAMP     NOT NULL,
    price_set_at     TIMESTAMP     NOT NULL,
    withdrawn_at     TIMESTAMP     NULL
);

ALTER TABLE tender_bid DROP CONSTRAINT IF EXISTS chk_tender_bid_price;
ALTER TABLE tender_bid ADD CONSTRAINT chk_tender_bid_price CHECK (price >= 0.01);

ALTER TABLE tender_bid DROP CONSTRAINT IF EXISTS chk_tender_bid_currency;
ALTER TABLE tender_bid ADD CONSTRAINT chk_tender_bid_currency CHECK (currency = 'LTR');

ALTER TABLE tender_bid DROP CONSTRAINT IF EXISTS chk_tender_bid_status;
ALTER TABLE tender_bid ADD CONSTRAINT chk_tender_bid_status CHECK (status IN ('SUBMITTED', 'WITHDRAWN', 'AWARDED', 'REJECTED'));

CREATE UNIQUE INDEX IF NOT EXISTS uq_tender_bid_tender_bidder ON tender_bid (tender_id, bidder_member_id);
CREATE INDEX IF NOT EXISTS ix_tender_bid_tender ON tender_bid (tender_id);
CREATE INDEX IF NOT EXISTS ix_tender_bid_bidder ON tender_bid (bidder_member_id);

CREATE TABLE IF NOT EXISTS tender_award (
    id                    UUID          NOT NULL PRIMARY KEY,
    tender_id             UUID          NOT NULL REFERENCES tender (id),
    bid_id                UUID          NOT NULL REFERENCES tender_bid (id),
    awarded_by            UUID          NOT NULL REFERENCES member (id),
    awarded_at            TIMESTAMP     NOT NULL,
    award_motion_id       UUID          NULL REFERENCES motion (id),
    resolution_id         UUID          NULL REFERENCES resolution (id),
    justification         VARCHAR(4000) NULL,
    conflicted_count      INT           NOT NULL,
    quorum_eligible_count INT           NULL
);

ALTER TABLE tender_award DROP CONSTRAINT IF EXISTS chk_tender_award_counts;
ALTER TABLE tender_award ADD CONSTRAINT chk_tender_award_counts
    CHECK (conflicted_count >= 0 AND (quorum_eligible_count IS NULL OR quorum_eligible_count >= 0));

CREATE UNIQUE INDEX IF NOT EXISTS uq_tender_award_tender ON tender_award (tender_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_tender_award_bid ON tender_award (bid_id);

CREATE TABLE IF NOT EXISTS tender_acceptance (
    id         UUID          NOT NULL PRIMARY KEY,
    tender_id  UUID          NOT NULL REFERENCES tender (id),
    decision   VARCHAR(8)    NOT NULL,
    reason     VARCHAR(4000) NULL,
    decided_by UUID          NOT NULL REFERENCES member (id),
    decided_at TIMESTAMP     NOT NULL
);

ALTER TABLE tender_acceptance DROP CONSTRAINT IF EXISTS chk_tender_acceptance_decision;
ALTER TABLE tender_acceptance ADD CONSTRAINT chk_tender_acceptance_decision CHECK (decision IN ('ACCEPTED', 'REJECTED'));

ALTER TABLE tender_acceptance DROP CONSTRAINT IF EXISTS chk_tender_acceptance_reason;
ALTER TABLE tender_acceptance ADD CONSTRAINT chk_tender_acceptance_reason
    CHECK (decision = 'ACCEPTED' OR (reason IS NOT NULL AND CHAR_LENGTH(TRIM(reason)) > 0));

CREATE INDEX IF NOT EXISTS ix_tender_acceptance_tender ON tender_acceptance (tender_id, decided_at);

ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS tender_default_visibility VARCHAR(21) NOT NULL DEFAULT 'HIDDEN_UNTIL_DEADLINE';
ALTER TABLE organization_settings DROP CONSTRAINT IF EXISTS chk_os_tender_visibility;
ALTER TABLE organization_settings ADD CONSTRAINT chk_os_tender_visibility
    CHECK (tender_default_visibility IN ('HIDDEN_UNTIL_DEADLINE', 'OPEN', 'OWN_ONLY'));

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
                                'TRAVEL_EXPENSE_SEPA_BATCH', 'CARPOOL_PARTICIPATION', 'VIDEO_OVERLAY', 'AGREEMENT_TEXT', 'PULPIT_RECORDING',
                                'EVENT_CAPTURE', 'TRAVEL_EXPENSE_DOCUMENT', 'TENDER'
        ));
