-- Welle V1.9.102 -- automatic end of committee seats on a member status change. V1..V82 untouched. Purely additive, H2 MODE=PostgreSQL- and
-- Postgres-compatible (ADD COLUMN IF NOT EXISTS, CREATE INDEX IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT, no DO block).
--
-- A dedicated V83 rather than an edit in place: extending an already-applied migration would break the checksum of local H2/dev databases.
--
--   * committee_membership.auto_end_reason: NULL for every seat that was never ended automatically (and for a seat an administrator later ended
--     or re-ended by hand -- the manual path resets it). Otherwise the MemberStatus (WITHDRAWN, REJECTED, DECEASED, DONOR) whose change closed the
--     seat. The CHECK pins the allowed values; VARCHAR(16) leaves room above the longest value (9 chars).
--   * No backfill: seats of members who changed status before this wave are NOT touched. A silent retroactive change is forbidden; the board
--     sees them as a read-only list instead (CommitteeAutoEndNoticeDto.openSeatsWithoutMembership).
--
-- The audit constraint follows the V81 pattern: the inline unnamed CHECK of V1 got the generated name CONSTRAINT_407 on H2 (dropped already by V81,
-- the IF EXISTS keeps this idempotent), the NAMED chk_audit_log_entry_entity_type is dropped and re-added with the list of V81 plus
-- 'COMMITTEE_MEMBERSHIP' (20 chars, fits VARCHAR(29)). Migration scenarios pin the result on both databases.

ALTER TABLE committee_membership ADD COLUMN IF NOT EXISTS auto_end_reason VARCHAR(16) NULL;

ALTER TABLE committee_membership
    DROP CONSTRAINT IF EXISTS chk_committee_membership_auto_end_reason;
ALTER TABLE committee_membership
    ADD CONSTRAINT chk_committee_membership_auto_end_reason
        CHECK (auto_end_reason IS NULL OR auto_end_reason IN ('WITHDRAWN', 'REJECTED', 'DECEASED', 'DONOR'));

CREATE INDEX IF NOT EXISTS idx_committee_membership_auto_end ON committee_membership (auto_end_reason, until);

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
                                'COMMITTEE_MEMBERSHIP'
        ));
