-- Wave V1.9.315 "Admin-Schalter Mitglied werden" -- see 11-organization-settings.kuml.kts. V1..V162 untouched.
-- Purely additive, H2 MODE=PostgreSQL- and Postgres-compatible (IF NOT EXISTS). TRUE = today's behaviour on every existing instance.
-- Set only through IOrganizationSettingsService.updateMembershipApplicationEnabled (ADMIN, audited on change).
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS membership_application_enabled BOOLEAN NOT NULL DEFAULT TRUE;
