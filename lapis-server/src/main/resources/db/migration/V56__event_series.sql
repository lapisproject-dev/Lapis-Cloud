-- Welle V1.4.37 "Wiederkehrende Veranstaltungen, Folgewelle (Rest)" -- Datenmodell-Teil. Rein
-- additiv, idempotent, H2 MODE=PostgreSQL- und Postgres-kompatibel, same discipline as V36/V54:
-- CREATE TABLE/COLUMN/INDEX IF NOT EXISTS, DROP CONSTRAINT IF EXISTS vor jedem ADD CONSTRAINT, kein
-- partieller UNIQUE-Index (siehe V8__sepa_mandates.sql's eigene Begruendung, hier uebernommen ueber
-- 39-events.kuml.kts's eigene "active_participant_key"-Sektion).
--
-- Table creation order matters: `event_series` muss existieren, bevor `event.series_id`'s FK
-- (unten) darauf verweisen kann. `event_series.split_from_series_id` ist ein Selbstbezug -- siehe
-- 39-events.kuml.kts file header addendum fuer die "kein fkEntity im kUML-Modell"-Begruendung
-- (gleiche Behandlung wie document_folder.parent_folder_id, 02-document.kuml.kts).

CREATE TABLE IF NOT EXISTS event_series (
    id                    UUID          NOT NULL PRIMARY KEY,
    rrule                 VARCHAR(255)  NOT NULL,
    dtstart               TIMESTAMP     NOT NULL,
    timezone              VARCHAR(64)   NOT NULL,
    duration_minutes      INT           NOT NULL,
    split_from_series_id  UUID          NULL,
    created_by            UUID          NOT NULL,
    created_at            TIMESTAMP     NOT NULL
);

ALTER TABLE event_series DROP CONSTRAINT IF EXISTS fk_event_series_created_by;
ALTER TABLE event_series ADD CONSTRAINT fk_event_series_created_by
    FOREIGN KEY (created_by) REFERENCES member(id);

ALTER TABLE event_series DROP CONSTRAINT IF EXISTS fk_event_series_split_from;
ALTER TABLE event_series ADD CONSTRAINT fk_event_series_split_from
    FOREIGN KEY (split_from_series_id) REFERENCES event_series(id);

-- timezone: single supported value for this wave (EventSeriesLimits.SUPPORTED_TIMEZONES), see plan
-- open question F1 for the JVM-default-zone follow-up this whitelist is a placeholder for.
ALTER TABLE event_series DROP CONSTRAINT IF EXISTS chk_event_series_timezone;
ALTER TABLE event_series ADD CONSTRAINT chk_event_series_timezone
    CHECK (timezone = 'Europe/Berlin');

-- duration_minutes: > 0 and <= 1440 (one calendar day) -- EventSeriesLimits.MAX_INSTANCE_DURATION_MINUTES.
ALTER TABLE event_series DROP CONSTRAINT IF EXISTS chk_event_series_duration;
ALTER TABLE event_series ADD CONSTRAINT chk_event_series_duration
    CHECK (duration_minutes > 0 AND duration_minutes <= 1440);

-- ---------------------------------------------------------------------------
-- event.series_id / .series_original_start / .series_detached -- nullable, an event need not
-- belong to a series. Overlap/materialization logic lives serverside (EventSeriesMaterializer/
-- EventSeriesScopeEngine, later wave), not in a DB constraint -- same posture V36's own room_id
-- addendum already establishes for overlap-checking.
-- ---------------------------------------------------------------------------
ALTER TABLE event ADD COLUMN IF NOT EXISTS series_id UUID NULL;
ALTER TABLE event ADD COLUMN IF NOT EXISTS series_original_start TIMESTAMP NULL;
ALTER TABLE event ADD COLUMN IF NOT EXISTS series_detached BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE event DROP CONSTRAINT IF EXISTS fk_event_series;
ALTER TABLE event ADD CONSTRAINT fk_event_series FOREIGN KEY (series_id) REFERENCES event_series(id);

-- series_id and series_original_start are set together or not at all (a non-series event has
-- neither; a series instance always carries its own RECURRENCE-ID-equivalent wall-clock moment).
ALTER TABLE event DROP CONSTRAINT IF EXISTS chk_event_series_pair;
ALTER TABLE event ADD CONSTRAINT chk_event_series_pair
    CHECK ((series_id IS NULL) = (series_original_start IS NULL));

-- series_detached can only be TRUE for an event that actually belongs to a series.
ALTER TABLE event DROP CONSTRAINT IF EXISTS chk_event_series_detached;
ALTER TABLE event ADD CONSTRAINT chk_event_series_detached
    CHECK (series_detached = FALSE OR series_id IS NOT NULL);

-- Multiple NULLs are permitted under a plain UNIQUE INDEX on both H2 (MODE=PostgreSQL) and
-- Postgres -- same property uq_event_registration_active_participant/uq_event_registration_ticket_code
-- already rely on (see 39-events.kuml.kts file header / V19__event_tickets.sql). Every non-series
-- event has (NULL, NULL) here, which does not collide with itself.
CREATE UNIQUE INDEX IF NOT EXISTS uq_event_series_occurrence ON event (series_id, series_original_start);

CREATE INDEX IF NOT EXISTS idx_event_series_starts ON event (series_id, starts_at);
