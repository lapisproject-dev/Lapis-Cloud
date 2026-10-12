-- Welle V1.9.112 -- rule-based committees (the members of a committee are computed from a rule) and a frozen electorate for votes.
-- V1..V91 untouched. Purely additive, H2 MODE=PostgreSQL- and Postgres-compatible (ADD COLUMN IF NOT EXISTS, DROP CONSTRAINT IF EXISTS
-- before every ADD CONSTRAINT, no DO block).
--
-- A dedicated V92 rather than an edit of V91: V91 is part of the already published squash commit and applied in dev databases (checksum).
--
--   * committee.membership_mode   MANUAL (explicit seat rows, the behaviour up to V1.9.111) or RULE (derived from rule_json). Changes only
--                                 through GovernanceService.setCommitteeMembershipRule; never for the General Assembly / executive board.
--   * committee.rule_json         canonical JSON of a MemberRule (whitelist parser, see MemberRuleCodec); NULL for MANUAL. Holds only enums and
--                                 committee/tier ids -- never a member id, name or address. VARCHAR(4096) = MemberRuleCodec.MAX_BYTES.
--   * vote.electorate_frozen_at   class A (UTC) stamp: the electorate of this vote was frozen into vote_eligible_voter when it was opened.
--                                 NULL = a vote opened before V1.9.112 (live electorate, no backfill) -- the ONLY way to tell an empty
--                                 frozen electorate from "not frozen".
--   * vote_eligible_voter         the frozen electorate (one row per eligible member), same shape as election_eligible_voter.
--   * committee_membership.auto_end_reason   additionally 'RULE_MODE' (the committee was switched to rule-based membership).
--   * audit entity type COMMITTEE appended to the V91 list.

ALTER TABLE committee ADD COLUMN IF NOT EXISTS membership_mode VARCHAR(16) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE committee ADD COLUMN IF NOT EXISTS rule_json VARCHAR(4096) NULL;

ALTER TABLE committee DROP CONSTRAINT IF EXISTS chk_committee_membership_mode;
ALTER TABLE committee ADD CONSTRAINT chk_committee_membership_mode CHECK (membership_mode IN ('MANUAL', 'RULE'));
ALTER TABLE committee DROP CONSTRAINT IF EXISTS chk_committee_rule_matches_mode;
ALTER TABLE committee
    ADD CONSTRAINT chk_committee_rule_matches_mode
        CHECK ((membership_mode = 'RULE' AND rule_json IS NOT NULL) OR (membership_mode = 'MANUAL' AND rule_json IS NULL));
ALTER TABLE committee DROP CONSTRAINT IF EXISTS chk_committee_rule_type;
ALTER TABLE committee
    ADD CONSTRAINT chk_committee_rule_type
        CHECK (membership_mode = 'MANUAL' OR type NOT IN ('GENERAL_ASSEMBLY', 'EXECUTIVE_BOARD'));

ALTER TABLE vote ADD COLUMN IF NOT EXISTS electorate_frozen_at TIMESTAMP NULL;

CREATE TABLE IF NOT EXISTS vote_eligible_voter (
    id        UUID NOT NULL PRIMARY KEY,
    vote_id   UUID NOT NULL REFERENCES vote (id),
    member_id UUID NOT NULL REFERENCES member (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_vote_eligible_voter_member ON vote_eligible_voter (vote_id, member_id);
CREATE INDEX IF NOT EXISTS idx_vote_eligible_voter_vote ON vote_eligible_voter (vote_id);

ALTER TABLE committee_membership
    DROP CONSTRAINT IF EXISTS chk_committee_membership_auto_end_reason;
ALTER TABLE committee_membership
    ADD CONSTRAINT chk_committee_membership_auto_end_reason
        CHECK (auto_end_reason IS NULL OR auto_end_reason IN ('WITHDRAWN', 'REJECTED', 'DECEASED', 'DONOR', 'RULE_MODE'));

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
                                'ARTICLE_IMPORT_IMAGES_APPLIED', 'MAILING_LIST', 'MAILING_MESSAGE', 'COMMITTEE'
        ));
