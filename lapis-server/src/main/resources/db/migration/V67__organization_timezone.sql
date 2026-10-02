-- V1.9.38 -- see 11-organization-settings.kuml.kts header addendum. V1..V66 untouched.
-- The IANA zone id in which the organization's wall-clock times (class B: event times, poll deadlines,
-- meeting times) are typed in, and in which class-A system timestamps (stored as UTC) are DISPLAYED.
-- No CHECK against a zone list on purpose: the IANA list changes with tzdata, validity is enforced
-- server-side (OrganizationTimeZoneRules). Existing instances keep today's behaviour (Europe/Berlin).
-- Idempotent and H2-portable.

ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS timezone VARCHAR(64) NOT NULL DEFAULT 'Europe/Berlin';
