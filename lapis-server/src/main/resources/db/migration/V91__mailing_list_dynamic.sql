-- Welle V1.9.111 -- dynamic mailing lists (recipients are computed from a rule at send time) and mandatory lists (no opt-out).
-- V1..V90 untouched. Purely additive, H2 MODE=PostgreSQL- and Postgres-compatible (ADD COLUMN IF NOT EXISTS, DROP CONSTRAINT IF EXISTS
-- before every ADD CONSTRAINT, no DO block).
--
-- A dedicated V91 rather than an edit of V90: V90 is part of the already published squash commit and applied in dev databases (checksum).
--
--   * mailing_list.kind          MANUAL (explicit subscriptions, the behaviour up to V1.9.110) or DYNAMIC (rule decides). Immutable after
--                                creation -- no RPC writes it after the insert.
--   * mailing_list.rule_json     canonical JSON of a MemberRule (whitelist parser, see MemberRuleCodec); NULL for MANUAL. Holds only enums
--                                and committee/tier ids -- never a member id, name or address.
--   * mailing_list.mandatory     mandatory list (association communication): no opt-out. DYNAMIC only, immutable after creation.
--   * mailing_list.rule_updated_at  class A (UTC) stamp of the last rule change.
--   * mailing_message.recipients_resolved_at  class A: set by the worker once the recipients of a DYNAMIC list were resolved (idempotency
--                                marker; NULL = not resolved yet, the worker must not close such a message).
--   * mailing_list_opt_out       a member's opt-out of a DYNAMIC (non-mandatory) list.
--   * audit entity types MAILING_LIST and MAILING_MESSAGE appended to the V86 list.

ALTER TABLE mailing_list ADD COLUMN IF NOT EXISTS kind VARCHAR(16) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE mailing_list ADD COLUMN IF NOT EXISTS rule_json VARCHAR(4096) NULL;
ALTER TABLE mailing_list ADD COLUMN IF NOT EXISTS mandatory BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE mailing_list ADD COLUMN IF NOT EXISTS rule_updated_at TIMESTAMP NULL;

ALTER TABLE mailing_list DROP CONSTRAINT IF EXISTS chk_mailing_list_kind;
ALTER TABLE mailing_list ADD CONSTRAINT chk_mailing_list_kind CHECK (kind IN ('MANUAL', 'DYNAMIC'));
ALTER TABLE mailing_list DROP CONSTRAINT IF EXISTS chk_mailing_list_rule_matches_kind;
ALTER TABLE mailing_list
    ADD CONSTRAINT chk_mailing_list_rule_matches_kind
        CHECK ((kind = 'DYNAMIC' AND rule_json IS NOT NULL) OR (kind = 'MANUAL' AND rule_json IS NULL));
ALTER TABLE mailing_list DROP CONSTRAINT IF EXISTS chk_mailing_list_mandatory_dynamic;
ALTER TABLE mailing_list ADD CONSTRAINT chk_mailing_list_mandatory_dynamic CHECK (mandatory = FALSE OR kind = 'DYNAMIC');

ALTER TABLE mailing_message ADD COLUMN IF NOT EXISTS recipients_resolved_at TIMESTAMP NULL;

CREATE TABLE IF NOT EXISTS mailing_list_opt_out (
    id              UUID      NOT NULL PRIMARY KEY,
    mailing_list_id UUID      NOT NULL REFERENCES mailing_list (id) ON DELETE CASCADE,
    member_id       UUID      NOT NULL REFERENCES member (id),
    opted_out_at    TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_mailing_list_opt_out_list_member ON mailing_list_opt_out (mailing_list_id, member_id);
CREATE INDEX IF NOT EXISTS idx_mailing_list_opt_out_member ON mailing_list_opt_out (member_id);

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
                                'ARTICLE_IMPORT_IMAGES_APPLIED', 'MAILING_LIST', 'MAILING_MESSAGE'
        ));
