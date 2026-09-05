-- Welle V1.4.3.2 "Veranstaltungen: Ticketing/QR-Codes" -- see 39-events.kuml.kts.
-- V18__events.sql wird NICHT angefasst (deployed, Checksum verbraucht) -- gleiche Disziplin,
-- die V18 selbst gegenueber V17 dokumentiert.
--
-- ticket_code_sha256 VARCHAR(64) bleibt unabhaengig von der Codelaenge (SHA-256-Hex ist immer
-- 64 Zeichen) -- die Codelaenge selbst (16 Crockford-Base32-Zeichen, 80 Bit) steht NICHT hier,
-- sondern ausschliesslich in Kotlin (network.lapis.cloud.shared.domain.EventTicketCode.CANONICAL_LENGTH
-- / network.lapis.cloud.server.events.EventTicketPolicy.CODE_BYTES). Design-Entscheidung: 16 statt
-- der anfangs erwogenen 24 Zeichen -- der Code wird von einem Tuerhelfer von einem fremden
-- Telefondisplay abgetippt, 80 Bit gegen einen authentifizierten, ratenbegrenzten Endpunkt sind
-- jenseits jeder Ratewahrscheinlichkeit; vier statt sechs Vierergruppen sind schneller fehlerfrei
-- abzutippen. Dieser Kommentar wurde VOR dem ersten Commit dieser Datei ergaenzt -- danach ist
-- die Migrations-Checksum verbraucht.

ALTER TABLE event_registration ADD COLUMN IF NOT EXISTS ticket_code_sha256 VARCHAR(64) NULL;
ALTER TABLE event_registration ADD COLUMN IF NOT EXISTS ticket_issued_at    TIMESTAMP    NULL;
ALTER TABLE event_registration ADD COLUMN IF NOT EXISTS checked_in_at       TIMESTAMP    NULL;
ALTER TABLE event_registration ADD COLUMN IF NOT EXISTS checked_in_by       UUID         NULL;

-- Global eindeutig (nicht (event_id, hash)): der Check-in schlaegt den Code OHNE Event-Kontext
-- nach, um WRONG_EVENT von UNKNOWN_CODE unterscheiden zu koennen. Mehrere NULLs sind unter einem
-- UNIQUE-Index sowohl auf H2 (MODE=PostgreSQL) als auch auf Postgres erlaubt -- dieselbe
-- Eigenschaft, auf die uq_event_registration_active_participant sich bereits stuetzt.
CREATE UNIQUE INDEX IF NOT EXISTS uq_event_registration_ticket_code
    ON event_registration (ticket_code_sha256);

CREATE INDEX IF NOT EXISTS idx_event_registration_checked_in
    ON event_registration (event_id, checked_in_at);

ALTER TABLE event_registration DROP CONSTRAINT IF EXISTS fk_event_registration_checked_in_by;
ALTER TABLE event_registration ADD CONSTRAINT fk_event_registration_checked_in_by
    FOREIGN KEY (checked_in_by) REFERENCES member(id);

-- Code und Ausgabezeitpunkt existieren immer gemeinsam.
ALTER TABLE event_registration DROP CONSTRAINT IF EXISTS chk_event_registration_ticket_issued;
ALTER TABLE event_registration ADD CONSTRAINT chk_event_registration_ticket_issued
    CHECK ((ticket_code_sha256 IS NULL     AND ticket_issued_at IS NULL)
        OR (ticket_code_sha256 IS NOT NULL AND ticket_issued_at IS NOT NULL));

-- Wer eingecheckt hat, ist genauso Pflicht wie wann.
ALTER TABLE event_registration DROP CONSTRAINT IF EXISTS chk_event_registration_checkin_pair;
ALTER TABLE event_registration ADD CONSTRAINT chk_event_registration_checkin_pair
    CHECK ((checked_in_at IS NULL     AND checked_in_by IS NULL)
        OR (checked_in_at IS NOT NULL AND checked_in_by IS NOT NULL));

-- Ein Check-in ohne ausgestelltes Ticket ist strukturell unmoeglich.
ALTER TABLE event_registration DROP CONSTRAINT IF EXISTS chk_event_registration_checkin_ticket;
ALTER TABLE event_registration ADD CONSTRAINT chk_event_registration_checkin_ticket
    CHECK (checked_in_at IS NULL OR ticket_code_sha256 IS NOT NULL);
