-- V1.9.236 "Sitzungsmodus im Konferenzraum": a Sitzung can be run in its conference room. See 05-governance.kuml.kts, 14-audit-log.kuml.kts and
-- docs/architecture/governance-committees.adoc.
-- Purely additive and repeatable: ADD COLUMN IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT. V1 and V126 are NOT edited
-- (no flywayRepair needed anywhere).
--   * meeting.opened_at: the moment the lead opened the Sitzung in its room (NULL = not opened). The phase is derived from status + this column.
--   * meeting.quorum_*: the quorum as the lead established it (moment + the three numbers of that moment, all NULL or all set); quorum_overridden
--     says the calculation read "not met" and the lead acknowledged that.
--   * meeting.current_agenda_item_id: the agenda item being treated. DELIBERATELY NO foreign key: meeting <-> agenda_item would be a reference
--     cycle (agenda_item.meeting_id already points the other way) and the organization backup restores tables in FK order and refuses cycles
--     (OrganizationSchemaCatalog.restoreOrder). Integrity is kept in code instead: GovernanceService.removeAgendaItem clears a marker that points at
--     the removed item, and every reader treats a marker that is not an item of the Sitzung as "none".
--   * attendance.status: widened by GUEST (5 chars, VARCHAR(11) is unchanged). V1 carries an inline, UNNAMED CHECK on that column: on PostgreSQL it
--     is called attendance_status_check, on H2 it has a generated UPPER-CASE name (quoted below; deterministic because V1 is frozen). Exactly one
--     of the two DROPs ever matches; AttendanceStatusMigrationScenarios pins the result on both databases, so a shifted name fails loudly.
--   * audit_log_entry.entity_type: widened by MEETING_SESSION (15 chars, VARCHAR(29) is unchanged). The list is V123's, copied verbatim.

ALTER TABLE meeting ADD COLUMN IF NOT EXISTS opened_at TIMESTAMP NULL;
ALTER TABLE meeting ADD COLUMN IF NOT EXISTS quorum_established_at TIMESTAMP NULL;
ALTER TABLE meeting ADD COLUMN IF NOT EXISTS quorum_present_count INT NULL;
ALTER TABLE meeting ADD COLUMN IF NOT EXISTS quorum_required_count INT NULL;
ALTER TABLE meeting ADD COLUMN IF NOT EXISTS quorum_eligible_count INT NULL;
ALTER TABLE meeting ADD COLUMN IF NOT EXISTS quorum_overridden BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE meeting ADD COLUMN IF NOT EXISTS current_agenda_item_id UUID NULL;

ALTER TABLE meeting DROP CONSTRAINT IF EXISTS chk_meeting_quorum_counts;
ALTER TABLE meeting ADD CONSTRAINT chk_meeting_quorum_counts CHECK (
    (quorum_established_at IS NULL AND quorum_present_count IS NULL
        AND quorum_required_count IS NULL AND quorum_eligible_count IS NULL)
    OR (quorum_established_at IS NOT NULL
        AND quorum_present_count IS NOT NULL AND quorum_present_count >= 0
        AND quorum_required_count IS NOT NULL AND quorum_required_count >= 0
        AND quorum_eligible_count IS NOT NULL AND quorum_eligible_count >= 0));

ALTER TABLE attendance DROP CONSTRAINT IF EXISTS "CONSTRAINT_711";
ALTER TABLE attendance DROP CONSTRAINT IF EXISTS attendance_status_check;
ALTER TABLE attendance DROP CONSTRAINT IF EXISTS chk_attendance_status;
ALTER TABLE attendance ADD CONSTRAINT chk_attendance_status
    CHECK (status IN ('PRESENT', 'EXCUSED', 'UNEXCUSED', 'REPRESENTED', 'GUEST'));

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
                                'MEETING_SESSION'
        ));
