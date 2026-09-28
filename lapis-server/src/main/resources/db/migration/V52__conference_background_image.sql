-- Welle V1.9.4 "private Hintergrundbild-Uploads für Videokonferenzen".
-- Rein additiv und idempotent (Muster wie V43__member_card.sql). Keine Spalten für Dateiname,
-- MIME-Typ oder updated_at -- siehe 55-conference-background.kuml.kts Datei-Kopf. VARCHAR(64)
-- (nicht CHAR(64)) fuer sha256, wie travel_expense_receipt.sha256 und member_card_code.code_hash.
CREATE TABLE IF NOT EXISTS conference_background_image (
    id                UUID         NOT NULL PRIMARY KEY,
    member_id         UUID         NOT NULL,
    storage_key       VARCHAR(200) NOT NULL,
    thumb_storage_key VARCHAR(200) NOT NULL,
    width             INT          NOT NULL,
    height            INT          NOT NULL,
    size_bytes        BIGINT       NOT NULL,
    sha256            VARCHAR(64)  NOT NULL,
    created_at        TIMESTAMP    NOT NULL
);

-- Bewusst KEIN CASCADE -- die Dateien werden vom aufrufenden Code geloescht, nicht von der DB
-- (siehe Datei-Kopf "kein CASCADE, Dateien loescht der Contributor").
ALTER TABLE conference_background_image DROP CONSTRAINT IF EXISTS fk_conference_background_image_member;
ALTER TABLE conference_background_image ADD CONSTRAINT fk_conference_background_image_member
    FOREIGN KEY (member_id) REFERENCES member(id);

CREATE INDEX IF NOT EXISTS idx_conference_background_image_member ON conference_background_image (member_id);
