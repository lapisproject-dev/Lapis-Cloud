-- Welle V1.9.12 "Mitfahrerzentrale" -- Datenmodell-Teil. Rein additiv, idempotent, H2
-- MODE=PostgreSQL- und Postgres-kompatibel, same discipline as V56/V57: CREATE TABLE/INDEX IF NOT
-- EXISTS, DROP CONSTRAINT IF EXISTS vor jedem ADD CONSTRAINT, kein partieller UNIQUE-Index.
--
-- Kein Feld fuer eine genaue Adresse -- from_place/to_place sind Ort-oder-PLZ-Freitext, kein
-- Treffpunkt mit Strasse/Hausnummer (Design-Entscheidung, siehe CarpoolValidation/CarpoolScreen).

CREATE TABLE IF NOT EXISTS carpool_posting (
    id                UUID          NOT NULL PRIMARY KEY,
    author_member_id  UUID          NOT NULL,
    type              VARCHAR(16)   NOT NULL,
    from_place        VARCHAR(60)   NOT NULL,
    to_place          VARCHAR(60)   NOT NULL,
    departure_date    DATE          NOT NULL,
    departure_time    TIME          NULL,
    seats_offered     INT           NULL,
    notes             VARCHAR(500)  NULL,
    created_at        TIMESTAMP     NOT NULL,
    updated_at        TIMESTAMP     NOT NULL
);

ALTER TABLE carpool_posting DROP CONSTRAINT IF EXISTS fk_carpool_posting_author;
ALTER TABLE carpool_posting ADD CONSTRAINT fk_carpool_posting_author
    FOREIGN KEY (author_member_id) REFERENCES member(id);

ALTER TABLE carpool_posting DROP CONSTRAINT IF EXISTS chk_carpool_posting_type;
ALTER TABLE carpool_posting ADD CONSTRAINT chk_carpool_posting_type
    CHECK (type IN ('OFFER', 'REQUEST'));

-- seats_offered genau dann gesetzt (1..8) wenn type = OFFER, sonst NULL.
ALTER TABLE carpool_posting DROP CONSTRAINT IF EXISTS chk_carpool_posting_seats;
ALTER TABLE carpool_posting ADD CONSTRAINT chk_carpool_posting_seats
    CHECK ((type = 'OFFER' AND seats_offered BETWEEN 1 AND 8) OR (type = 'REQUEST' AND seats_offered IS NULL));

CREATE INDEX IF NOT EXISTS idx_carpool_posting_departure_date ON carpool_posting(departure_date);
CREATE INDEX IF NOT EXISTS idx_carpool_posting_author ON carpool_posting(author_member_id);
