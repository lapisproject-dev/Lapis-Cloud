-- Welle V1.9.110 -- recording via Participant Egress (opt-in): which egress mode a recording runs in, and which kind of egress produced a
-- raw file. V1..V89 untouched. Purely additive, H2 MODE=PostgreSQL- and Postgres-compatible (ADD COLUMN IF NOT EXISTS, DROP CONSTRAINT
-- IF EXISTS before every ADD CONSTRAINT, no DO block).
--
-- A dedicated V90 rather than an edit of V89 in place: V89 is already applied in local H2/dev databases (and part of a squashed master
-- commit), so changing it would break the Flyway checksum there (same reasoning as the V86..V89 headers).
--
-- Deliberately NO new value of conference_recording_track.track_source (e.g. PARTICIPANT): the CHECK on that column is unnamed in V1.
-- Postgres names it conference_recording_track_track_source_check automatically, H2 generates a CONSTRAINT_xx name, so the usual pair of
-- "DROP CONSTRAINT IF EXISTS" statements cannot remove it on H2 and inserts of the new value would fail in the H2 test suite. A separate
-- column avoids touching that constraint at all.
--
--   * conference_recording.egress_mode: how the raw files of THIS recording are produced -- TRACK (one Track Egress per published track,
--     the behaviour up to V1.9.109) or PARTICIPANT (one Participant Egress per person, audio and video muxed by LiveKit on one clock).
--     Fixed when the recording starts, from LAPIS_RECORDING_EGRESS_MODE. Without it a server restart with a changed env value during a
--     running recording would mix the modes (one person with both kinds of egress = doubled audio).
--   * conference_recording_track.egress_kind: TRACK for a single-track file, PARTICIPANT for a per-person file with audio AND video. For
--     PARTICIPANT rows track_source is CAMERA (the main source of the picture, so every video check stays valid) and livekit_track_id
--     holds the participant SID (PA_...).

ALTER TABLE conference_recording ADD COLUMN IF NOT EXISTS egress_mode VARCHAR(11) NOT NULL DEFAULT 'TRACK';
ALTER TABLE conference_recording DROP CONSTRAINT IF EXISTS chk_conference_recording_egress_mode;
ALTER TABLE conference_recording
    ADD CONSTRAINT chk_conference_recording_egress_mode CHECK (egress_mode IN ('TRACK', 'PARTICIPANT'));

ALTER TABLE conference_recording_track ADD COLUMN IF NOT EXISTS egress_kind VARCHAR(11) NOT NULL DEFAULT 'TRACK';
ALTER TABLE conference_recording_track DROP CONSTRAINT IF EXISTS chk_conference_recording_track_egress_kind;
ALTER TABLE conference_recording_track
    ADD CONSTRAINT chk_conference_recording_track_egress_kind CHECK (egress_kind IN ('TRACK', 'PARTICIPANT'));
