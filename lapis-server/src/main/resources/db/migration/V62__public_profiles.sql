-- Welle V1.9.20 "Oeffentliche Seiten Vorstand, Politiker und Landesverbaende".
-- Rein additiv und idempotent, laeuft auf H2 (PostgreSQL-Modus) und PostgreSQL. V61 bleibt
-- unveraendert (Checksum-Schutz fuer bereits migrierte Datenbanken).

-- 1. Kurzvorstellung eines Mitglieds (max. 500 Code Points; VARCHAR(2000) als UTF-16-Reserve, die
--    Anwendungsvalidierung ist massgeblich). Eine Zeile pro Mitglied (uq_member_public_bio_member).
--    Einwilligungszustand (consent_*) gemeinsam NULL oder gemeinsam gesetzt. Bewusst KEIN CASCADE.
CREATE TABLE IF NOT EXISTS member_public_bio (
    id                   UUID          NOT NULL PRIMARY KEY,
    member_id            UUID          NOT NULL,
    bio_text             VARCHAR(2000) NOT NULL,
    updated_at           TIMESTAMP     NOT NULL,
    consent_granted_at   TIMESTAMP     NULL,
    consent_text_version VARCHAR(40)   NULL
);

ALTER TABLE member_public_bio DROP CONSTRAINT IF EXISTS fk_member_public_bio_member;
ALTER TABLE member_public_bio ADD CONSTRAINT fk_member_public_bio_member
    FOREIGN KEY (member_id) REFERENCES member(id);

ALTER TABLE member_public_bio DROP CONSTRAINT IF EXISTS chk_member_public_bio_consent_state;
ALTER TABLE member_public_bio ADD CONSTRAINT chk_member_public_bio_consent_state CHECK (
    (consent_granted_at IS NULL AND consent_text_version IS NULL)
    OR
    (consent_granted_at IS NOT NULL AND consent_text_version IS NOT NULL)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_member_public_bio_member ON member_public_bio (member_id);

-- 2. Landesverband: oeffentliche Beschreibung und Wappen. Die Bilddatei liegt unter
--    <LAPIS_DOCUMENT_STORAGE_ROOT>/chapter-crests/<crest_image_id>.<jpg|png>; in der Datenbank steht nie ein
--    Dateipfad, nur die Server-UUID. crest_public_token ist der 256-Bit-Schluessel der
--    oeffentlichen URL und wird bei JEDEM Hochladen neu erzeugt.
ALTER TABLE regional_chapter ADD COLUMN IF NOT EXISTS description        VARCHAR(1200) NULL;
ALTER TABLE regional_chapter ADD COLUMN IF NOT EXISTS crest_image_id     UUID          NULL;
ALTER TABLE regional_chapter ADD COLUMN IF NOT EXISTS crest_public_token VARCHAR(64)   NULL;
ALTER TABLE regional_chapter ADD COLUMN IF NOT EXISTS crest_content_type VARCHAR(32)   NULL;

ALTER TABLE regional_chapter DROP CONSTRAINT IF EXISTS chk_regional_chapter_crest_state;
ALTER TABLE regional_chapter ADD CONSTRAINT chk_regional_chapter_crest_state CHECK (
    (crest_image_id IS NULL AND crest_public_token IS NULL AND crest_content_type IS NULL)
    OR
    (crest_image_id IS NOT NULL AND crest_public_token IS NOT NULL AND crest_content_type IS NOT NULL)
);

ALTER TABLE regional_chapter DROP CONSTRAINT IF EXISTS chk_regional_chapter_crest_content_type;
ALTER TABLE regional_chapter ADD CONSTRAINT chk_regional_chapter_crest_content_type CHECK (
    crest_content_type IS NULL OR crest_content_type IN ('image/jpeg', 'image/png')
);

-- Mehrere NULL-Tokens sind erlaubt (H2 und PostgreSQL), Eindeutigkeit gilt nur fuer gesetzte Tokens.
CREATE UNIQUE INDEX IF NOT EXISTS uq_regional_chapter_crest_token ON regional_chapter (crest_public_token);

-- 3. Einwilligungsart POLITICIAN_LISTING (18 Zeichen) -- die Spalte war auf 12 Zeichen begrenzt.
ALTER TABLE public_ranking_consent_event ALTER COLUMN ranking_kind SET DATA TYPE VARCHAR(24);
ALTER TABLE public_ranking_consent_event DROP CONSTRAINT IF EXISTS chk_prce_ranking_kind;
ALTER TABLE public_ranking_consent_event ADD CONSTRAINT chk_prce_ranking_kind
    CHECK (ranking_kind IN ('LTR_HOLDINGS', 'DONATIONS', 'POLITICIAN_LISTING'));
