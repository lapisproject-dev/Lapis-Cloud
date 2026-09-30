-- Welle V1.9.19 "Mitglieder-Foto" (Self-Service, freiwillig, Einwilligung zur Veroeffentlichung).
-- Rein additiv und idempotent (Muster wie V52__conference_background_image.sql). V1 bleibt
-- unveraendert -- kein flywayRepair noetig.
--
-- Ein Foto pro Mitglied (uq_member_photo_member). Die Bilddatei liegt NICHT in der Datenbank,
-- sondern unter <LAPIS_DOCUMENT_STORAGE_ROOT>/member-photos/<storage_key>; diese Zeile ist nur der
-- Metadaten-Zeiger. storage_key ist eine zufaellige UUID, nie die Mitglieds-ID.
--
-- Zustandsinvariante (chk_member_photo_public_state): PUBLIC <=> public_token, Einwilligungszeit
-- und Einwilligungs-Textversion sind ALLE gesetzt; PRIVATE <=> alle drei sind NULL. Der
-- public_token wird bei JEDER Veroeffentlichung neu erzeugt und beim Widerruf auf NULL gesetzt.
CREATE TABLE IF NOT EXISTS member_photo (
    id                   UUID         NOT NULL PRIMARY KEY,
    member_id            UUID         NOT NULL,
    storage_key          VARCHAR(64)  NOT NULL,
    content_type         VARCHAR(32)  NOT NULL,
    width_px             INT          NOT NULL,
    height_px            INT          NOT NULL,
    size_bytes           BIGINT       NOT NULL,
    uploaded_at          TIMESTAMP    NOT NULL,
    visibility           VARCHAR(10)  NOT NULL DEFAULT 'PRIVATE',
    public_token         VARCHAR(64)  NULL,
    consent_granted_at   TIMESTAMP    NULL,
    consent_text_version VARCHAR(40)  NULL
);

-- Bewusst KEIN CASCADE -- die Dateien loescht der aufrufende Code, nicht die DB.
ALTER TABLE member_photo DROP CONSTRAINT IF EXISTS fk_member_photo_member;
ALTER TABLE member_photo ADD CONSTRAINT fk_member_photo_member
    FOREIGN KEY (member_id) REFERENCES member(id);

ALTER TABLE member_photo DROP CONSTRAINT IF EXISTS chk_member_photo_visibility;
ALTER TABLE member_photo ADD CONSTRAINT chk_member_photo_visibility
    CHECK (visibility IN ('PRIVATE', 'PUBLIC'));

ALTER TABLE member_photo DROP CONSTRAINT IF EXISTS chk_member_photo_content_type;
ALTER TABLE member_photo ADD CONSTRAINT chk_member_photo_content_type
    CHECK (content_type = 'image/jpeg');

ALTER TABLE member_photo DROP CONSTRAINT IF EXISTS chk_member_photo_public_state;
ALTER TABLE member_photo ADD CONSTRAINT chk_member_photo_public_state CHECK (
    (visibility = 'PUBLIC'  AND public_token IS NOT NULL AND consent_granted_at IS NOT NULL AND consent_text_version IS NOT NULL)
    OR
    (visibility = 'PRIVATE' AND public_token IS NULL     AND consent_granted_at IS NULL     AND consent_text_version IS NULL)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_member_photo_member ON member_photo (member_id);
-- Mehrere NULL-Tokens sind erlaubt (H2 und PostgreSQL), Eindeutigkeit gilt nur fuer gesetzte Tokens.
CREATE UNIQUE INDEX IF NOT EXISTS uq_member_photo_public_token ON member_photo (public_token);
