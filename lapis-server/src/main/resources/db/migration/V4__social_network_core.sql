-- V1.1.1 "Fundament & Post-Kern" -- see lapis-server/src/main/kuml/32-social-network.kuml.kts
-- file header for the full fachlich model this table implements.
--
-- Idempotent by construction (IF NOT EXISTS / repeatable-safe ALTERs), same discipline as V2/V3.
--
-- Deliberately does NOT create `social_post_boost`/`social_post_report`/`social_post_erasure` or
-- the `content_erased_at`/`content_erasure_note` columns -- those belong to later waves
-- (V1.1.2/V1.1.5) and adding them now, before the corresponding kUML model attributes and Table
-- object columns exist, would only let this migration and the model/Table objects drift apart
-- (see 32-social-network.kuml.kts file header "Only SocialPost plus the two Welle-1 enums exist").

CREATE TABLE IF NOT EXISTS social_post (
    id                    UUID          NOT NULL PRIMARY KEY,
    parent_id             UUID          NULL,
    root_id               UUID          NOT NULL,
    depth                 INT           NOT NULL DEFAULT 0,
    author_member_id      UUID          NOT NULL,
    content               TEXT          NOT NULL,
    visibility            VARCHAR(24)   NOT NULL,
    initial_weight_ltr    DECIMAL(18,2) NOT NULL,
    published_at          TIMESTAMP     NOT NULL,
    state                 VARCHAR(20)   NOT NULL DEFAULT 'VISIBLE',
    state_changed_at      TIMESTAMP     NULL,
    state_changed_by      UUID          NULL,
    state_reason          VARCHAR(2000) NULL
);

ALTER TABLE social_post DROP CONSTRAINT IF EXISTS chk_social_post_visibility;
ALTER TABLE social_post ADD CONSTRAINT chk_social_post_visibility
    CHECK (visibility IN ('PUBLIC', 'MEMBERS_ONLY', 'MEMBERS_AND_EXTERNAL'));

ALTER TABLE social_post DROP CONSTRAINT IF EXISTS chk_social_post_state;
ALTER TABLE social_post ADD CONSTRAINT chk_social_post_state
    CHECK (state IN ('VISIBLE', 'HIDDEN_BY_AUTHOR', 'REMOVED_LEGAL'));

-- Mindestgrenze aus dem Meritokratie-Konzept (SocialPostWeight.MIN_WEIGHT_LTR), DB-seitig gespiegelt.
ALTER TABLE social_post DROP CONSTRAINT IF EXISTS chk_social_post_min_weight;
ALTER TABLE social_post ADD CONSTRAINT chk_social_post_min_weight
    CHECK (initial_weight_ltr >= 0.01);

-- SocialPostWeight.MAX_DEPTH DoS-Guard, DB-seitig gespiegelt.
ALTER TABLE social_post DROP CONSTRAINT IF EXISTS chk_social_post_depth;
ALTER TABLE social_post ADD CONSTRAINT chk_social_post_depth
    CHECK (depth >= 0 AND depth <= 64);

ALTER TABLE social_post DROP CONSTRAINT IF EXISTS fk_social_post_parent;
ALTER TABLE social_post ADD CONSTRAINT fk_social_post_parent
    FOREIGN KEY (parent_id) REFERENCES social_post(id);

ALTER TABLE social_post DROP CONSTRAINT IF EXISTS fk_social_post_root;
ALTER TABLE social_post ADD CONSTRAINT fk_social_post_root
    FOREIGN KEY (root_id) REFERENCES social_post(id);

ALTER TABLE social_post DROP CONSTRAINT IF EXISTS fk_social_post_author;
ALTER TABLE social_post ADD CONSTRAINT fk_social_post_author
    FOREIGN KEY (author_member_id) REFERENCES member(id);

ALTER TABLE social_post DROP CONSTRAINT IF EXISTS fk_social_post_state_changed_by;
ALTER TABLE social_post ADD CONSTRAINT fk_social_post_state_changed_by
    FOREIGN KEY (state_changed_by) REFERENCES member(id);

CREATE INDEX IF NOT EXISTS idx_social_post_parent   ON social_post (parent_id);
CREATE INDEX IF NOT EXISTS idx_social_post_root     ON social_post (root_id);
CREATE INDEX IF NOT EXISTS idx_social_post_author   ON social_post (author_member_id);
CREATE INDEX IF NOT EXISTS idx_social_post_timeline ON social_post (state, visibility, published_at);

-- Widen ltr_ledger_entry.entry_type/reference_type's CHECK constraints for the new
-- SOCIAL_POST_STAKE/SOCIAL_POST literals (see 08-ltr-balance.kuml.kts "V1.1.1" addendum). Both
-- fit the existing VARCHAR(21)/VARCHAR(20) column widths unchanged.
--
-- **Same dual-DROP discipline as V2/V3**: on a FRESH database (this now-edited V1__baseline.sql),
-- the constraint already carries the EXPLICIT name chk_ltr_ledger_entry_entry_type/
-- chk_ltr_ledger_entry_reference_type -- V4 runs immediately after V1 on every test run (Flyway
-- applies every unrecorded migration in one pass against a schema-history-empty database), so both
-- DROPs below are needed: the explicit-name one matches on a fresh DB, the guessed
-- Postgres-auto-generated `<table>_<column>_check` name is the best-effort target for the
-- already-migrated pdv2 deployment, whose CHECK constraint predates this wave's explicit naming
-- (VERIFY WITH `\d ltr_ledger_entry` ON pdv2 BEFORE DEPLOY -- same caveat V2's own header states
-- for its analogous conference_stream_status_check guess).
ALTER TABLE ltr_ledger_entry DROP CONSTRAINT IF EXISTS ltr_ledger_entry_entry_type_check;
ALTER TABLE ltr_ledger_entry DROP CONSTRAINT IF EXISTS chk_ltr_ledger_entry_entry_type;
ALTER TABLE ltr_ledger_entry ADD CONSTRAINT chk_ltr_ledger_entry_entry_type
    CHECK (entry_type IN (
        'MINT', 'PROJECT_STAKE', 'PROJECT_STAKE_RELEASE', 'VOTE_STAKE', 'PEER_TRANSFER_OUT', 'PEER_TRANSFER_IN',
        'AUCTION_LISTING_FEE', 'AUCTION_HOLD', 'AUCTION_HOLD_RELEASE', 'AUCTION_SALE_OUT', 'AUCTION_SALE_IN',
        'SOCIAL_POST_STAKE'
    ));

ALTER TABLE ltr_ledger_entry DROP CONSTRAINT IF EXISTS ltr_ledger_entry_reference_type_check;
ALTER TABLE ltr_ledger_entry DROP CONSTRAINT IF EXISTS chk_ltr_ledger_entry_reference_type;
ALTER TABLE ltr_ledger_entry ADD CONSTRAINT chk_ltr_ledger_entry_reference_type
    CHECK (reference_type IN ('CROWDFUNDING_PROJECT', 'VOTE', 'PEER_TRANSFER', 'AUCTION', 'SOCIAL_POST'));
