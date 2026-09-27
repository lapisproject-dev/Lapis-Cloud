-- Welle V1.9.1 "Zugriffsrechte für Dokumente und Ordner sichtbar und editierbar". Rein additiv und
-- idempotent (IF NOT EXISTS/IF EXISTS überall); keine frühere Migration wird angefasst.
--
-- Block 1: document_folder bekommt eine eigene Zugriffsstufe, in derselben Form wie
-- document.access_level (V1__baseline.sql). DEFAULT bleibt bewusst stehen (kein DROP DEFAULT
-- danach) -- jede bestehende Ordner-Zeile wird durch ADD COLUMN ... NOT NULL DEFAULT auf
-- PUBLIC_MEMBERS gesetzt: niemand verliert Zugriff auf einen heute schon sichtbaren Ordner. Der
-- Vorstand korrigiert danach bewusst in der Oberfläche (setFolderAccessLevel), welche Ordner
-- tatsächlich BOARD_ONLY/ADMIN_ONLY sein sollen -- kein Namens-Update hier, Ordnernamen sind freier
-- Text und damit kein verlässlicher Schlüssel für ein gezieltes UPDATE.
ALTER TABLE document_folder ADD COLUMN IF NOT EXISTS access_level VARCHAR(14) NOT NULL DEFAULT 'PUBLIC_MEMBERS';
ALTER TABLE document_folder DROP CONSTRAINT IF EXISTS chk_document_folder_access_level;
ALTER TABLE document_folder ADD CONSTRAINT chk_document_folder_access_level
    CHECK (access_level IN ('PUBLIC_MEMBERS', 'BOARD_ONLY', 'ADMIN_ONLY'));

-- Block 2: audit_log_entry.entity_type-CHECK-Verbreiterung: DOCUMENT (8), DOCUMENT_FOLDER
-- (15 Zeichen) -- laengstes neues Literal 15 Zeichen, passt in die bestehende VARCHAR(29)-Breite.
-- Dual-DROP-Muster wie V11/V13/V14/V15/V20/V25/V26/V28/V29/V30/V32/V35, vollstaendige Literalliste
-- muss wiederholt werden, H2 hat kein ALTER TYPE ... ADD VALUE.
--
-- WICHTIG (gefunden live via FederationGuestJourneyTest): V1__baseline.sql traegt selbst noch ein
-- INLINE, unbenanntes CHECK auf entity_type (H2-generierter Name, z.B. CONSTRAINT_407) -- dieses
-- wird von H2 UNABHAENGIG vom hier benannten Constraint durchgesetzt. Jede frische Test-Datenbank
-- lehnt einen INSERT mit entity_type = 'DOCUMENT_FOLDER' ab, bis auch V1__baseline.sql's eigene
-- Literalliste 'DOCUMENT'/'DOCUMENT_FOLDER' enthaelt -- siehe den entsprechenden Kommentar dort
-- (gleiche Falle wie bei jedem Vorgaenger-Wave seit V11). V1__baseline.sql wurde deshalb in
-- gleicher Weise ergaenzt -- auf einer bereits migrierten Instanz braucht das einen
-- `flyway repair` (die Checksumme von V1 aendert sich), analog jedem Vorgaenger-Fund.
ALTER TABLE audit_log_entry DROP CONSTRAINT IF EXISTS audit_log_entry_entity_type_check;
ALTER TABLE audit_log_entry DROP CONSTRAINT IF EXISTS chk_audit_log_entry_entity_type;
ALTER TABLE audit_log_entry ADD CONSTRAINT chk_audit_log_entry_entity_type
    CHECK (entity_type IN (
        'JOURNAL_ENTRY', 'PARTY_DONATION_VERDICT', 'RESOLUTION', 'BOARD_MEMBERSHIP',
        'CONFERENCE_RECORDING', 'CONFERENCE_STREAM', 'CONFERENCE_STREAM_DESTINATION',
        'CONFERENCE_ROOM', 'SOCIAL_POST', 'ORGANIZATION_SETTINGS', 'SEPA_MANDATE',
        'SEPA_DEBIT_BATCH', 'DUNNING_NOTICE', 'MEMBER', 'PAYMENT_TRANSACTION', 'API_KEY',
        'WEBHOOK_ENDPOINT', 'BANK_STATEMENT_IMPORT', 'ACCOUNTING_EXPORT_CONNECTION',
        'ACCOUNTING_EXPORT_RUN', 'ACCOUNTING_EXPORT_MAPPING', 'CONTRIBUTION_RELIEF_REQUEST',
        'TRAVEL_EXPENSE_REPORT', 'VOLUNTEER_ALLOWANCE_PAYMENT', 'VOLUNTEER_DECLARATION',
        'BANK_ACCOUNT', 'OPEN_ITEM', 'OPEN_ITEM_NETTING', 'RECEIVABLE_DUNNING_NOTICE',
        'DOCUMENT', 'DOCUMENT_FOLDER'
    ));

-- ---------------------------------------------------------------------------------------------
-- Operator-Note fuer ein bereits migriertes Instance (pdv2/ELB), analog V9/V29/V35: dieses
-- `ALTER TABLE audit_log_entry ADD CONSTRAINT chk_audit_log_entry_entity_type` in V51 aendert NUR
-- die zuvor gueltige Form aus V35. Eine bereits laufende Produktionsinstanz hat den NAMED
-- chk_audit_log_entry_entity_type-Constraint bereits aus V35 -- Flyway validiert die Checksumme
-- jeder bereits angewendeten Migration (`validateOnMigrate = true`, DatabaseConfig.kt); WEIL diese
-- Datei NEU ist (V51, keine vorher migrierte Version), ist dafuer KEIN `flyway repair` noetig --
-- das ist nur fuer bereits migrierte Dateien noetig, deren INHALT sich nachtraeglich geaendert hat.
-- Diese Datei wird beim naechsten `flyway migrate`-Lauf einfach normal angewendet.
-- ---------------------------------------------------------------------------------------------
