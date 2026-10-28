-- V1.9.237 "Neue Nachricht": writing a new message to a person or a committee, blocking a sender, and being findable. See 78-direct-message-block.kuml.kts,
-- 14-audit-log.kuml.kts and docs/architecture/direct-messages.adoc.
-- Purely additive and repeatable: IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT. V1 and V127 are NOT edited (no flywayRepair).
--   * member.dm_discoverable: whether the member shows up in the recipient search of "Neue Nachricht" (default TRUE, also for existing members).
--     Sending to a known id works regardless; this only hides the member from the NAME search.
--   * direct_message_block: "this member must not write to me". Own table on purpose (NOT member_finder_block): the finder table is emptied when the finder
--     switch goes off, belongs to the Art. 9 finder data and is excluded from the backup. No CASCADE on the member FKs (application layer owns cleanup).
--   * audit_log_entry.entity_type: widened by DIRECT_MESSAGE_GROUP_SEND (25 chars, VARCHAR(29) is unchanged). The list is V127's, copied verbatim.

ALTER TABLE member ADD COLUMN IF NOT EXISTS dm_discoverable BOOLEAN NOT NULL DEFAULT TRUE;

CREATE TABLE IF NOT EXISTS direct_message_block (
    id UUID NOT NULL PRIMARY KEY,
    blocker_member_id UUID NOT NULL,
    blocked_member_id UUID NOT NULL,
    created_at TIMESTAMP NOT NULL
);

ALTER TABLE direct_message_block DROP CONSTRAINT IF EXISTS uq_direct_message_block_pair;
ALTER TABLE direct_message_block ADD CONSTRAINT uq_direct_message_block_pair UNIQUE (blocker_member_id, blocked_member_id);
ALTER TABLE direct_message_block DROP CONSTRAINT IF EXISTS chk_direct_message_block_not_self;
ALTER TABLE direct_message_block ADD CONSTRAINT chk_direct_message_block_not_self CHECK (blocker_member_id <> blocked_member_id);
ALTER TABLE direct_message_block DROP CONSTRAINT IF EXISTS fk_direct_message_block_blocker;
ALTER TABLE direct_message_block ADD CONSTRAINT fk_direct_message_block_blocker FOREIGN KEY (blocker_member_id) REFERENCES member (id);
ALTER TABLE direct_message_block DROP CONSTRAINT IF EXISTS fk_direct_message_block_blocked;
ALTER TABLE direct_message_block ADD CONSTRAINT fk_direct_message_block_blocked FOREIGN KEY (blocked_member_id) REFERENCES member (id);
CREATE INDEX IF NOT EXISTS idx_direct_message_block_blocked ON direct_message_block (blocked_member_id);

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
                                'EVENT_CAPTURE', 'TRAVEL_EXPENSE_DOCUMENT', 'TENDER', 'EVENT_REGISTRATION_EXPORT', 'ATTACHED_IMAGE',
                                'PARTG_REPORT_SHEET', 'PARTG_REPORT_POSITION', 'LUPABB_SYNC_RUN', 'LUPABB_SYNC_CONFIG', 'MEETING_EVENT_LINK',
                                'MEETING_SESSION', 'DIRECT_MESSAGE_GROUP_SEND'
        ));
