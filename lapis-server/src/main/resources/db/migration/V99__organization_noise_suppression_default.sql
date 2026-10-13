-- Welle V1.9.124 -- instance default of the optional noise suppression in video conferences. V1..V98 untouched.
-- Purely additive, H2 MODE=PostgreSQL- and Postgres-compatible (ADD COLUMN IF NOT EXISTS, no DO block).
--
-- A dedicated V99 rather than an edit of V98 in place: V98 is already part of master (and may be applied on staging/deployed databases),
-- so changing it would break the Flyway checksum there (same reasoning as the V95 header).
--
--   * organization_settings.noise_suppression_default: FALSE (default, every existing instance keeps today's behaviour: the filter is off
--     until a person switches it on) or TRUE (an ADMIN made it the default; a person's own remembered choice still wins in the browser).
--     Set only through IOrganizationSettingsService.updateNoiseSuppressionDefault, never through the generic settings update.

ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS noise_suppression_default BOOLEAN NOT NULL DEFAULT FALSE;
