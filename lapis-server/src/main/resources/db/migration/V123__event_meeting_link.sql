-- V1.9.227 "Sitzung in der Videokonferenz": an event may announce exactly one governance meeting (1:1, optional). See 39-events.kuml.kts,
-- 14-audit-log.kuml.kts and docs/architecture/governance-committees.adoc.
-- Purely additive and repeatable: ADD COLUMN IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT. V121/V122 are NOT edited.
--   * event.meeting_id: nullable; ON DELETE SET NULL (a purged meeting leaves the event standing); a plain unique index makes the link 1:1
--     (NULLs never collide in H2 or PostgreSQL).
--   * audit_log_entry.entity_type: widened by MEETING_EVENT_LINK (18 chars, VARCHAR(29) is unchanged). The list is V122's, copied verbatim.

ALTER TABLE event ADD COLUMN IF NOT EXISTS meeting_id UUID NULL;

ALTER TABLE event DROP CONSTRAINT IF EXISTS fk_event_meeting;
ALTER TABLE event ADD CONSTRAINT fk_event_meeting FOREIGN KEY (meeting_id) REFERENCES meeting (id) ON DELETE SET NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_event_meeting_id ON event (meeting_id);

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
                                'PARTG_REPORT_SHEET', 'PARTG_REPORT_POSITION', 'LUPABB_SYNC_RUN', 'LUPABB_SYNC_CONFIG', 'MEETING_EVENT_LINK'
        ));
