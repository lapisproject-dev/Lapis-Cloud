-- Security review Runde 3, Befund 4 (Fund 2026-09-07) -- V25__accounting_export.sql itself is NOT
-- touched (same "deployed, Checksum verbraucht" discipline that file's own header already applies
-- to V24). AccountingExportService.startExport/abortRun/retryFailed/mapAccount previously wrote no
-- audit_log_entry row at all -- see AuditEntityType.ACCOUNTING_EXPORT_RUN/
-- ACCOUNTING_EXPORT_MAPPING KDoc for the forensic gap this closes (retryFailed is precisely the
-- operation that resends a run's items after an abort/reap, and had no actor trail of its own).
--
-- Same dual-DROP "audit_log_entry.entity_type CHECK-Verbreiterung" pattern
-- V11/V13/V14/V15/V20/V25 already use -- widens the literal set only, not the column width
-- (VARCHAR(29) already fits both new literals: 'ACCOUNTING_EXPORT_RUN' is 21 characters,
-- 'ACCOUNTING_EXPORT_MAPPING' is 25 -- security review Fund 2026-09-07, Runde 4, Befund 3
-- (INFO): an earlier version of this comment said "22/25-character literals", which was off by
-- one for the first literal; corrected here, no functional effect either way since both already
-- fit VARCHAR(29)).
ALTER TABLE audit_log_entry DROP CONSTRAINT IF EXISTS audit_log_entry_entity_type_check;
ALTER TABLE audit_log_entry DROP CONSTRAINT IF EXISTS chk_audit_log_entry_entity_type;
ALTER TABLE audit_log_entry ADD CONSTRAINT chk_audit_log_entry_entity_type
    CHECK (entity_type IN (
        'JOURNAL_ENTRY', 'PARTY_DONATION_VERDICT', 'RESOLUTION', 'BOARD_MEMBERSHIP',
        'CONFERENCE_RECORDING', 'CONFERENCE_STREAM', 'CONFERENCE_STREAM_DESTINATION',
        'CONFERENCE_ROOM', 'SOCIAL_POST', 'ORGANIZATION_SETTINGS', 'SEPA_MANDATE',
        'SEPA_DEBIT_BATCH', 'DUNNING_NOTICE', 'MEMBER', 'PAYMENT_TRANSACTION', 'API_KEY',
        'WEBHOOK_ENDPOINT', 'BANK_STATEMENT_IMPORT', 'ACCOUNTING_EXPORT_CONNECTION',
        'ACCOUNTING_EXPORT_RUN', 'ACCOUNTING_EXPORT_MAPPING'
    ));
