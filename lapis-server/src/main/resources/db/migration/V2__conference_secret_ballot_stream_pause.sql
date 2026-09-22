-- V1.0 Videokonferenzen, Wave 9 "Stream-Pause bei geheimen Abstimmungen" -- see
-- 27-conference.kuml.kts / 29-conference-streaming.kuml.kts file headers ("Wave 9 addition") for the
-- full fachlich model. This is the FIRST genuine incremental migration in this codebase (every prior
-- change was folded directly into V1__baseline.sql) -- required because the pdv2 production instance
-- already ran V1__baseline.sql (live since 2026-08-14) and Flyway's checksum validation would
-- otherwise reject an in-place edit of an already-applied migration on next deploy.
--
-- **Deliberately fully idempotent** -- this file applies the SAME diff that was ALSO folded directly
-- into V1__baseline.sql's CREATE TABLE statements (repo convention: fresh installs/tests get the
-- widened schema straight from the baseline). That means on any FRESH database (every test run, every
-- new deployment) V1 already creates conference_room.meeting_id/conference_stream.pause_reason/the
-- widened status CHECK, and THIS file still runs immediately afterwards (Flyway applies every
-- unrecorded migration in one pass against a schema-history-empty database) -- so every statement
-- below must be a safe no-op when the target already exists. `IF NOT EXISTS`/`IF EXISTS` guards make
-- that true on both PostgreSQL (production) and H2-in-PostgreSQL-mode (tests, see DatabaseConfig.kt).
--
-- **The `chk_conference_stream_status` DROP targets TWO possible names on purpose.** The pdv2
-- production database was created by the ORIGINAL (pre-Wave-9) V1__baseline.sql, whose `status` CHECK
-- was UNNAMED -- PostgreSQL auto-generates a name for a single-column table-level CHECK constraint as
-- `<table>_<column>_check`, i.e. `conference_stream_status_check` (this naming rule is NOT verified
-- against the live pdv2 database as of this migration's authorship -- verify with `\d conference_stream`
-- on pdv2 BEFORE this migration is deployed there, and adjust the constraint name below if it differs).
-- A FRESH database created by the now-edited V1__baseline.sql instead already carries the EXPLICITLY
-- named `chk_conference_stream_status` constraint. Both DROP statements are `IF EXISTS` -- exactly one
-- of the two ever matches anything on a given database, the other is a harmless no-op.

ALTER TABLE conference_room ADD COLUMN IF NOT EXISTS meeting_id UUID NULL;
ALTER TABLE conference_room DROP CONSTRAINT IF EXISTS fk_conference_room_meeting_id;
ALTER TABLE conference_room ADD CONSTRAINT fk_conference_room_meeting_id FOREIGN KEY (meeting_id) REFERENCES meeting(id);
CREATE INDEX IF NOT EXISTS idx_conference_room_meeting ON conference_room (meeting_id);

ALTER TABLE conference_stream ADD COLUMN IF NOT EXISTS pause_reason VARCHAR(13) NULL;
ALTER TABLE conference_stream DROP CONSTRAINT IF EXISTS chk_conference_stream_pause_reason;
ALTER TABLE conference_stream ADD CONSTRAINT chk_conference_stream_pause_reason CHECK (pause_reason IS NULL OR pause_reason IN ('MANUAL', 'SECRET_BALLOT'));

-- PostgreSQL/H2 have no ALTER-in-place for a CHECK constraint's expression -- drop (whichever name
-- exists, see header comment above) and re-add with the widened literal set.
ALTER TABLE conference_stream DROP CONSTRAINT IF EXISTS conference_stream_status_check;
ALTER TABLE conference_stream DROP CONSTRAINT IF EXISTS chk_conference_stream_status;
ALTER TABLE conference_stream ADD CONSTRAINT chk_conference_stream_status CHECK (status IN ('STARTING', 'LIVE', 'PAUSED', 'STOPPING', 'ENDED', 'FAILED', 'PAUSING'));
