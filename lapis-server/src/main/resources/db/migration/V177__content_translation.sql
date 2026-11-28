-- Welle V1.9.342 -- just-in-time translation of public content (core, events only, off by default). V1..V176 untouched.
-- Purely additive, H2 MODE=PostgreSQL- and Postgres-compatible (IF NOT EXISTS, DROP CONSTRAINT IF EXISTS, no DO block).
--
-- A dedicated V177 rather than an edit of V176: V176 is released (v0.57.0) and applied on deployed databases, so changing it would break the
-- Flyway checksum there.
--
--   * content_translation: cache of machine translations, keyed by the content, the field, the SHA-256 of the exact source text, and the
--     language pair. A changed source text has a new hash, so a stale row can never be served. content_id has deliberately NO foreign key
--     (the column is polymorphic: later auctions and carpool hints); the delete hooks plus a tripwire test replace it. Written and read only by
--     ContentTranslationStore. Not part of a backup (nachfuellbarer Cache, see OrganizationSchemaCatalog.EXCLUDED_TABLES).
--   * event.translation_consent / _by / _at: the author's consent to have the public text of THIS event translated by the configured
--     provider. Default FALSE (every existing and every new, imported, copied or series event). _by/_at hold the LAST decision (grant or revoke).
--     Written only by EventTranslationConsent.
--   * organization_settings.content_language: source language of the instance's public content (one of de, en, es, fr, it, nl, pl, ru),
--     default 'de'. No setter in this wave (it comes with the UI); validated on restore.

CREATE TABLE IF NOT EXISTS content_translation (
    content_type    VARCHAR(16)    NOT NULL,
    content_id      UUID           NOT NULL,
    field           VARCHAR(24)    NOT NULL,
    source_sha256   VARCHAR(64)    NOT NULL,
    source_lang     VARCHAR(8)     NOT NULL,
    target_lang     VARCHAR(8)     NOT NULL,
    translated_text VARCHAR(20000) NOT NULL,
    model_profile   VARCHAR(200)   NOT NULL,
    created_at      TIMESTAMP      NOT NULL,
    CONSTRAINT pk_content_translation PRIMARY KEY (content_type, content_id, field, source_sha256, source_lang, target_lang)
);

CREATE INDEX IF NOT EXISTS idx_content_translation_content ON content_translation (content_type, content_id);

ALTER TABLE content_translation DROP CONSTRAINT IF EXISTS chk_content_translation_type;
ALTER TABLE content_translation ADD CONSTRAINT chk_content_translation_type CHECK (content_type IN ('EVENT'));

ALTER TABLE content_translation DROP CONSTRAINT IF EXISTS chk_content_translation_field;
ALTER TABLE content_translation ADD CONSTRAINT chk_content_translation_field
    CHECK (field IN ('TITLE', 'SUMMARY', 'DESCRIPTION', 'COVER_IMAGE_ALT'));

ALTER TABLE content_translation DROP CONSTRAINT IF EXISTS chk_content_translation_lang;
ALTER TABLE content_translation ADD CONSTRAINT chk_content_translation_lang
    CHECK (target_lang IN ('de', 'en', 'es', 'fr', 'it', 'nl', 'pl', 'ru')
        AND source_lang IN ('de', 'en', 'es', 'fr', 'it', 'nl', 'pl', 'ru')
        AND target_lang <> source_lang);

ALTER TABLE event ADD COLUMN IF NOT EXISTS translation_consent BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE event ADD COLUMN IF NOT EXISTS translation_consent_by UUID NULL;
ALTER TABLE event ADD COLUMN IF NOT EXISTS translation_consent_at TIMESTAMP NULL;

ALTER TABLE event DROP CONSTRAINT IF EXISTS fk_event_translation_consent_by;
ALTER TABLE event ADD CONSTRAINT fk_event_translation_consent_by FOREIGN KEY (translation_consent_by) REFERENCES member (id);

ALTER TABLE event DROP CONSTRAINT IF EXISTS chk_event_translation_consent;
ALTER TABLE event ADD CONSTRAINT chk_event_translation_consent
    CHECK (translation_consent = FALSE OR (translation_consent_by IS NOT NULL AND translation_consent_at IS NOT NULL));

ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS content_language VARCHAR(8) NOT NULL DEFAULT 'de';
