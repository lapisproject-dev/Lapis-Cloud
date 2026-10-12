-- Welle V1.9.113 -- Online-Teilnahme an Veranstaltungen. V1..V92 untouched (V92 is already on origin/master and belongs to another wave).
-- Purely additive and H2 MODE=PostgreSQL- and Postgres-compatible (CREATE ... IF NOT EXISTS, ADD COLUMN IF NOT EXISTS, DROP CONSTRAINT IF
-- EXISTS before every ADD CONSTRAINT, no DO block, no partial or expression indexes). No unnamed V1 constraint is touched
-- (chk_event_venue has been named since V18), so NO flywayRepair is needed for this migration.
--
-- event.online_kind: NONE (purely on site) | EXTERNAL_LINK (the pre-existing free-text link, online_url) | LAPIS_CONFERENCE (a Lapis video
-- conference created on the first join) | LAPIS_ENCOUNTER (entry into a permanent encounter space, encounter_space_id).

ALTER TABLE event ADD COLUMN IF NOT EXISTS online_kind VARCHAR(32) NOT NULL DEFAULT 'NONE';
ALTER TABLE event ADD COLUMN IF NOT EXISTS encounter_space_id UUID NULL;

-- Backfill: every event with an online link was an external link. Idempotent (re-running changes nothing: only NONE rows with a link).
UPDATE event SET online_kind = 'EXTERNAL_LINK' WHERE online_url IS NOT NULL AND online_kind = 'NONE';

-- Data fix for the stricter shape check below: a link that is "public" without a link cannot exist (the form never stores it); should an old row have
-- slipped through, it is made consistent here so that adding the constraint can never fail an existing instance. Idempotent.
UPDATE event SET online_url_public = FALSE WHERE online_url IS NULL AND online_url_public = TRUE;

ALTER TABLE event DROP CONSTRAINT IF EXISTS chk_event_online_kind;
ALTER TABLE event ADD CONSTRAINT chk_event_online_kind
    CHECK (online_kind IN ('NONE', 'EXTERNAL_LINK', 'LAPIS_CONFERENCE', 'LAPIS_ENCOUNTER'));

ALTER TABLE event DROP CONSTRAINT IF EXISTS fk_event_encounter_space;
ALTER TABLE event ADD CONSTRAINT fk_event_encounter_space FOREIGN KEY (encounter_space_id) REFERENCES encounter_space(id);

CREATE INDEX IF NOT EXISTS idx_event_encounter_space ON event (encounter_space_id);

-- Written out explicitly so the condition evaluates identically on H2 and Postgres. A Lapis online kind never carries an external link,
-- and the link can only be public when it IS an external link.
ALTER TABLE event DROP CONSTRAINT IF EXISTS chk_event_online_shape;
ALTER TABLE event ADD CONSTRAINT chk_event_online_shape CHECK (
    ((online_kind = 'LAPIS_ENCOUNTER' AND encounter_space_id IS NOT NULL)
        OR (online_kind <> 'LAPIS_ENCOUNTER' AND encounter_space_id IS NULL))
    AND (online_url IS NULL OR online_kind = 'EXTERNAL_LINK')
    AND (online_url_public = FALSE OR online_kind = 'EXTERNAL_LINK'));

-- A purely Lapis-online event has neither a location text nor an external link, so the venue rule of V18 is widened.
ALTER TABLE event DROP CONSTRAINT IF EXISTS chk_event_venue;
ALTER TABLE event ADD CONSTRAINT chk_event_venue CHECK (
    location_text IS NOT NULL OR online_url IS NOT NULL OR online_kind IN ('LAPIS_CONFERENCE', 'LAPIS_ENCOUNTER'));

-- The conference of an event. See 67-event-online.kuml.kts for the claim/fencing rationale.
CREATE TABLE IF NOT EXISTS event_conference (
    event_id            UUID         NOT NULL PRIMARY KEY,
    conference_room_id  UUID         NULL,
    livekit_room_name   VARCHAR(64)  NULL,
    status              VARCHAR(16)  NOT NULL,
    claim_token         UUID         NOT NULL,
    claimed_at          TIMESTAMP    NOT NULL,
    window_opens_at     TIMESTAMP    NOT NULL,
    window_closes_at    TIMESTAMP    NOT NULL,
    created_at          TIMESTAMP    NOT NULL,
    ended_at            TIMESTAMP    NULL
);

ALTER TABLE event_conference DROP CONSTRAINT IF EXISTS fk_event_conference_event;
ALTER TABLE event_conference ADD CONSTRAINT fk_event_conference_event FOREIGN KEY (event_id) REFERENCES event(id);

ALTER TABLE event_conference DROP CONSTRAINT IF EXISTS fk_event_conference_room;
ALTER TABLE event_conference ADD CONSTRAINT fk_event_conference_room FOREIGN KEY (conference_room_id) REFERENCES conference_room(id);

ALTER TABLE event_conference DROP CONSTRAINT IF EXISTS chk_event_conference_status;
ALTER TABLE event_conference ADD CONSTRAINT chk_event_conference_status CHECK (status IN ('PENDING', 'ACTIVE', 'ENDED', 'FAILED'));

-- ACTIVE implies the room exists (the explicit form: status <> 'ACTIVE' OR room IS NOT NULL).
ALTER TABLE event_conference DROP CONSTRAINT IF EXISTS chk_event_conference_active_room;
ALTER TABLE event_conference ADD CONSTRAINT chk_event_conference_active_room
    CHECK (status <> 'ACTIVE' OR conference_room_id IS NOT NULL);

ALTER TABLE event_conference DROP CONSTRAINT IF EXISTS chk_event_conference_window;
ALTER TABLE event_conference ADD CONSTRAINT chk_event_conference_window CHECK (window_closes_at > window_opens_at);

CREATE UNIQUE INDEX IF NOT EXISTS uq_event_conference_room ON event_conference (conference_room_id);
CREATE INDEX IF NOT EXISTS idx_event_conference_status_closes ON event_conference (status, window_closes_at);
