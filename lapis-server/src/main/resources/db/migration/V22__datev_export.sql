-- Welle V1.4.5.2 "DATEV-Format-Export" -- see 11-organization-settings.kuml.kts file header
-- addendum. V21__member_honors.sql wird NICHT angefasst (deployed, Checksum verbraucht) --
-- gleiche Disziplin, die V21 selbst gegenueber V20 dokumentiert.
--
-- KEINE neue Tabelle: der Export ist reiner Lesezugriff auf journal_entry/posting/ledger_account.
-- Diese Migration existiert AUSSCHLIESSLICH fuer die zwei Kopfzeilen-Pflichtangaben des
-- DATEV-EXTF-Formats (Berater-/Mandantennummer), die es in organization_settings bisher nicht gab.
-- Die Sachkontenlaenge (Kopfzeilenfeld 14) wird bewusst NICHT persistiert -- sie ist aus
-- ledger_account.account_number ableitbar; ein eingegebener Wert koennte von den echten Konten
-- abweichen und wuerde jeden Import zerstoeren.

ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS datev_berater_nummer INT NULL;
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS datev_mandant_nummer INT NULL;

ALTER TABLE organization_settings DROP CONSTRAINT IF EXISTS chk_organization_settings_datev_berater_nummer;
ALTER TABLE organization_settings ADD CONSTRAINT chk_organization_settings_datev_berater_nummer
    CHECK (datev_berater_nummer IS NULL OR (datev_berater_nummer BETWEEN 1001 AND 9999999));

ALTER TABLE organization_settings DROP CONSTRAINT IF EXISTS chk_organization_settings_datev_mandant_nummer;
ALTER TABLE organization_settings ADD CONSTRAINT chk_organization_settings_datev_mandant_nummer
    CHECK (datev_mandant_nummer IS NULL OR (datev_mandant_nummer BETWEEN 1 AND 99999));
