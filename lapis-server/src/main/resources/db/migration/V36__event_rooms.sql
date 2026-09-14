-- Welle V1.4.3.4 "Raumverwaltung für Veranstaltungen" -- see 49-event-room.kuml.kts for the full
-- entity rationale. Idempotent by construction (IF NOT EXISTS / dual-DROP-then-ADD), same
-- discipline as V2-V35. V35__open_items.sql is NOT touched -- committet auf master, Checksummen
-- verbraucht.
--
-- Portability note: H2 MODE=PostgreSQL (die gesamte Testsuite, DatabaseConfig.kt). KEIN partieller
-- Unique-Index, DROP CONSTRAINT IF EXISTS vor jedem ADD CONSTRAINT, CREATE TABLE/INDEX IF NOT
-- EXISTS, KEIN ON DELETE CASCADE.
--
-- Table creation order matters: `event_room` must exist before `event.room_id`'s FK (below) can
-- reference it.

CREATE TABLE IF NOT EXISTS event_room (
    id              UUID          NOT NULL PRIMARY KEY,
    name            VARCHAR(200)  NOT NULL,
    capacity        INT           NULL,
    equipment_tags  VARCHAR(1000) NOT NULL DEFAULT '',
    status          VARCHAR(8)    NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMP     NOT NULL,
    created_by      UUID          NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_event_room_name ON event_room (name);

ALTER TABLE event_room DROP CONSTRAINT IF EXISTS fk_event_room_created_by;
ALTER TABLE event_room ADD CONSTRAINT fk_event_room_created_by
    FOREIGN KEY (created_by) REFERENCES member(id);

ALTER TABLE event_room DROP CONSTRAINT IF EXISTS chk_event_room_capacity;
ALTER TABLE event_room ADD CONSTRAINT chk_event_room_capacity
    CHECK (capacity IS NULL OR capacity > 0);

-- status: longest literal INACTIVE/ACTIVE (8) -> VARCHAR(8).
ALTER TABLE event_room DROP CONSTRAINT IF EXISTS chk_event_room_status;
ALTER TABLE event_room ADD CONSTRAINT chk_event_room_status
    CHECK (status IN ('ACTIVE', 'INACTIVE'));

-- ---------------------------------------------------------------------------
-- event.room_id -- nullable FK, an event need not have a room assigned. Overlap collision-checking
-- is deliberately NOT a DB constraint (H2 has no GiST/btree_gist range-exclusion support) -- it is
-- enforced serverside under a row lock, see EventRoomCollisionGuard.
-- ---------------------------------------------------------------------------
ALTER TABLE event ADD COLUMN IF NOT EXISTS room_id UUID NULL;

ALTER TABLE event DROP CONSTRAINT IF EXISTS fk_event_room;
ALTER TABLE event ADD CONSTRAINT fk_event_room FOREIGN KEY (room_id) REFERENCES event_room(id);

CREATE INDEX IF NOT EXISTS idx_event_room_starts_at ON event (room_id, starts_at, ends_at);
