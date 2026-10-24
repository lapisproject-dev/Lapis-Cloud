-- V1.9.202 "Ausschreibungen" wave 2 (screens): the description of a tender may be Markdown (rendered to sanitized HTML by the SERVER).
-- Purely additive and repeatable (H2 MODE=PostgreSQL and PostgreSQL). V115 is NOT edited (it may already have run on staging/demo, a changed
-- checksum would stop Flyway). Existing rows keep the legacy plain-text reading ('PLAIN').
ALTER TABLE tender ADD COLUMN IF NOT EXISTS description_format VARCHAR(16) NOT NULL DEFAULT 'PLAIN';
ALTER TABLE tender DROP CONSTRAINT IF EXISTS chk_tender_description_format;
ALTER TABLE tender ADD CONSTRAINT chk_tender_description_format CHECK (description_format IN ('PLAIN', 'MARKDOWN'));
