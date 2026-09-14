-- Welle V1.4.3.7 "Helfer-/Schichtplanung für Veranstaltungen" -- see 51-event-volunteer.kuml.kts
-- for the full entity rationale. Idempotent by construction (IF NOT EXISTS / dual-DROP-then-ADD),
-- same discipline as V2-V38. V38__event_invoice.sql is NOT touched -- committet auf master,
-- Checksummen verbraucht.
--
-- Portability note: H2 MODE=PostgreSQL (die gesamte Testsuite, DatabaseConfig.kt). KEIN partieller
-- Unique-Index, DROP CONSTRAINT IF EXISTS vor jedem ADD CONSTRAINT, CREATE TABLE/INDEX IF NOT
-- EXISTS, KEIN ON DELETE CASCADE.
--
-- `active_member_key` ist eine app-gepflegte Schattenspalte für portable "höchstens eine aktive
-- Zusage pro Mitglied pro Schicht"-Erzwingung (kein partieller Index, H2 MODE=PostgreSQL) -- exakt
-- das Muster von event_registration.active_participant_key (siehe 39-events.kuml.kts file header
-- "active_participant_key"). NULL genau dann, wenn status = CANCELLED, sonst der Mitglieds-UUID-
-- String -- siehe EventVolunteerStore für die einzige Schreibstelle.

CREATE TABLE IF NOT EXISTS event_volunteer_shift (
    id            UUID          NOT NULL PRIMARY KEY,
    event_id      UUID          NOT NULL,
    description   VARCHAR(500)  NOT NULL,
    starts_at     TIMESTAMP     NOT NULL,
    ends_at       TIMESTAMP     NOT NULL,
    needed_count  INT           NOT NULL,
    status        VARCHAR(9)    NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMP     NOT NULL,
    created_by    UUID          NOT NULL
);

ALTER TABLE event_volunteer_shift DROP CONSTRAINT IF EXISTS fk_event_volunteer_shift_event;
ALTER TABLE event_volunteer_shift ADD CONSTRAINT fk_event_volunteer_shift_event
    FOREIGN KEY (event_id) REFERENCES event(id);

ALTER TABLE event_volunteer_shift DROP CONSTRAINT IF EXISTS fk_event_volunteer_shift_created_by;
ALTER TABLE event_volunteer_shift ADD CONSTRAINT fk_event_volunteer_shift_created_by
    FOREIGN KEY (created_by) REFERENCES member(id);

ALTER TABLE event_volunteer_shift DROP CONSTRAINT IF EXISTS chk_event_volunteer_shift_needed_count;
ALTER TABLE event_volunteer_shift ADD CONSTRAINT chk_event_volunteer_shift_needed_count
    CHECK (needed_count > 0);

ALTER TABLE event_volunteer_shift DROP CONSTRAINT IF EXISTS chk_event_volunteer_shift_times;
ALTER TABLE event_volunteer_shift ADD CONSTRAINT chk_event_volunteer_shift_times
    CHECK (ends_at > starts_at);

-- status: longest literal CANCELLED (9) -> VARCHAR(9).
ALTER TABLE event_volunteer_shift DROP CONSTRAINT IF EXISTS chk_event_volunteer_shift_status;
ALTER TABLE event_volunteer_shift ADD CONSTRAINT chk_event_volunteer_shift_status
    CHECK (status IN ('ACTIVE', 'CANCELLED'));

CREATE INDEX IF NOT EXISTS idx_event_volunteer_shift_event ON event_volunteer_shift (event_id);

CREATE TABLE IF NOT EXISTS event_volunteer_signup (
    id                 UUID          NOT NULL PRIMARY KEY,
    shift_id           UUID          NOT NULL,
    member_id          UUID          NOT NULL,
    status             VARCHAR(9)    NOT NULL DEFAULT 'CONFIRMED',
    signed_up_at       TIMESTAMP     NOT NULL,
    cancelled_at       TIMESTAMP     NULL,
    active_member_key  VARCHAR(36)   NULL
);

ALTER TABLE event_volunteer_signup DROP CONSTRAINT IF EXISTS fk_event_volunteer_signup_shift;
ALTER TABLE event_volunteer_signup ADD CONSTRAINT fk_event_volunteer_signup_shift
    FOREIGN KEY (shift_id) REFERENCES event_volunteer_shift(id);

ALTER TABLE event_volunteer_signup DROP CONSTRAINT IF EXISTS fk_event_volunteer_signup_member;
ALTER TABLE event_volunteer_signup ADD CONSTRAINT fk_event_volunteer_signup_member
    FOREIGN KEY (member_id) REFERENCES member(id);

-- status: longest literal CANCELLED (9) -> VARCHAR(9).
ALTER TABLE event_volunteer_signup DROP CONSTRAINT IF EXISTS chk_event_volunteer_signup_status;
ALTER TABLE event_volunteer_signup ADD CONSTRAINT chk_event_volunteer_signup_status
    CHECK (status IN ('CONFIRMED', 'CANCELLED'));

ALTER TABLE event_volunteer_signup DROP CONSTRAINT IF EXISTS chk_event_volunteer_signup_active_key_consistency;
ALTER TABLE event_volunteer_signup ADD CONSTRAINT chk_event_volunteer_signup_active_key_consistency
    CHECK ((status = 'CANCELLED' AND active_member_key IS NULL) OR (status = 'CONFIRMED' AND active_member_key IS NOT NULL));

CREATE UNIQUE INDEX IF NOT EXISTS uq_event_volunteer_signup_active ON event_volunteer_signup (shift_id, active_member_key);

CREATE INDEX IF NOT EXISTS idx_event_volunteer_signup_member ON event_volunteer_signup (member_id);
