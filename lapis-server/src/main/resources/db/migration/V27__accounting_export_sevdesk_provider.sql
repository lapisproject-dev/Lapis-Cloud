-- Welle V1.4.5.4 "sevDesk-Live-Anbindung". V25__accounting_export.sql wird NICHT angefasst
-- (deployed, Checksum verbraucht) -- gleiche Disziplin, die V25 selbst gegenueber V24 und V26
-- gegenueber V25 dokumentiert. Nur die vier provider-CHECK-Constraints werden verbreitert:
-- keine Spaltenaenderung noetig (provider VARCHAR(9) fasst 'SEVDESK' mit 7 Zeichen), keine
-- Index-/Key-Aenderung (active_key = <PROVIDER>, exported_key = <PROVIDER>:<journalEntryId>
-- sind bereits providerpraefixiert -- siehe AccountingExportStore.activeKeyOf/exportedKeyOf).

ALTER TABLE accounting_export_connection DROP CONSTRAINT IF EXISTS chk_accounting_export_connection_provider;
ALTER TABLE accounting_export_connection ADD CONSTRAINT chk_accounting_export_connection_provider
    CHECK (provider IN ('LEXOFFICE', 'SEVDESK'));

ALTER TABLE accounting_export_run DROP CONSTRAINT IF EXISTS chk_accounting_export_run_provider;
ALTER TABLE accounting_export_run ADD CONSTRAINT chk_accounting_export_run_provider
    CHECK (provider IN ('LEXOFFICE', 'SEVDESK'));

ALTER TABLE accounting_export_item DROP CONSTRAINT IF EXISTS chk_accounting_export_item_provider;
ALTER TABLE accounting_export_item ADD CONSTRAINT chk_accounting_export_item_provider
    CHECK (provider IN ('LEXOFFICE', 'SEVDESK'));

ALTER TABLE accounting_export_category_map DROP CONSTRAINT IF EXISTS chk_accounting_export_category_map_provider;
ALTER TABLE accounting_export_category_map ADD CONSTRAINT chk_accounting_export_category_map_provider
    CHECK (provider IN ('LEXOFFICE', 'SEVDESK'));
