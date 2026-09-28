-- Welle "Veranstaltungs-Titelbild" (Event Cover Image). Rein additiv, idempotent. Keine FK: die
-- UUID referenziert eine Datei (<uuid>.jpg|.png im Event-Cover-Speicher, siehe
-- network.lapis.cloud.server.events.EventCoverStorage), keine Tabelle -- ein Cover-Bild ist keine
-- versionierte, nachverfolgte Ressource wie ein document_version-Blob, sondern ein einzelnes,
-- ersetzbares Anzeige-Asset. Kein Audit-Log-Eintrag, siehe 39-events.kuml.kts Datei-Header
-- ("Why no audit_log_entry coverage") -- setzen/entfernen eines Titelbilds ist dieselbe fachlich
-- unauditierte Kategorie wie updateEvent selbst.
ALTER TABLE event ADD COLUMN IF NOT EXISTS cover_image_id UUID NULL;
