-- Welle V1.9.332 -- language of the instance's single-language mails. V1..V175 untouched.
-- Purely additive, H2 MODE=PostgreSQL- and Postgres-compatible (ADD COLUMN IF NOT EXISTS, no DO block).
--
-- A dedicated V176 rather than an edit of V175 in place: V175 is already part of master (and may be applied on staging/deployed databases),
-- so changing it would break the Flyway checksum there (same reasoning as the V99 header).
--
--   * organization_settings.mail_language: one of the supported language codes (de, en, es, fr, it, nl, pl, ru), default 'de' (every existing
--     instance keeps today's German wording). No CHECK constraint: the code is validated by IOrganizationSettingsService.updateMailLanguage
--     and a foreign value reads as 'de'. Set only through updateMailLanguage, never through the generic settings update.

ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS mail_language VARCHAR(8) NOT NULL DEFAULT 'de';
