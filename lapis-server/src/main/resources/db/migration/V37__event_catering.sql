-- Welle V1.4.3.5 "Catering-Management für Veranstaltungen" -- see 50-event-catering.kuml.kts for
-- the full entity rationale. Idempotent by construction (IF NOT EXISTS / dual-DROP-then-ADD), same
-- discipline as V2-V36. V36__event_rooms.sql is NOT touched -- committet auf master, Checksummen
-- verbraucht.
--
-- Portability note: H2 MODE=PostgreSQL (die gesamte Testsuite, DatabaseConfig.kt). KEIN partieller
-- Unique-Index, DROP CONSTRAINT IF EXISTS vor jedem ADD CONSTRAINT, CREATE TABLE/INDEX IF NOT
-- EXISTS, KEIN ON DELETE CASCADE.
--
-- KEINE Personendaten: allergen_notes ist eine reine Bestellpositions-Eigenschaft (Art.-9-DSGVO-
-- Begründung siehe CateringOrderInput KDoc / 50-event-catering.kuml.kts file header) -- niemals
-- einer identifizierbaren Person zugeordnet.

CREATE TABLE IF NOT EXISTS event_catering_order (
    id              UUID          NOT NULL PRIMARY KEY,
    event_id        UUID          NOT NULL,
    description     VARCHAR(500)  NOT NULL,
    quantity        INT           NOT NULL,
    allergen_notes  VARCHAR(1000) NULL,
    status          VARCHAR(9)    NOT NULL DEFAULT 'PLANNED',
    created_at      TIMESTAMP     NOT NULL,
    created_by      UUID          NOT NULL
);

ALTER TABLE event_catering_order DROP CONSTRAINT IF EXISTS fk_event_catering_order_event;
ALTER TABLE event_catering_order ADD CONSTRAINT fk_event_catering_order_event
    FOREIGN KEY (event_id) REFERENCES event(id);

ALTER TABLE event_catering_order DROP CONSTRAINT IF EXISTS fk_event_catering_order_created_by;
ALTER TABLE event_catering_order ADD CONSTRAINT fk_event_catering_order_created_by
    FOREIGN KEY (created_by) REFERENCES member(id);

ALTER TABLE event_catering_order DROP CONSTRAINT IF EXISTS chk_event_catering_order_quantity;
ALTER TABLE event_catering_order ADD CONSTRAINT chk_event_catering_order_quantity
    CHECK (quantity > 0);

-- status: longest literal DELIVERED (9) -> VARCHAR(9).
ALTER TABLE event_catering_order DROP CONSTRAINT IF EXISTS chk_event_catering_order_status;
ALTER TABLE event_catering_order ADD CONSTRAINT chk_event_catering_order_status
    CHECK (status IN ('PLANNED', 'ORDERED', 'DELIVERED'));

CREATE INDEX IF NOT EXISTS idx_event_catering_order_event ON event_catering_order (event_id);
