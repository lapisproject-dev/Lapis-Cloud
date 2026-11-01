-- Welle V1.9.254 -- pause mode of the video conference (recording stages and cut-outs, stream pause reason ROOM_PAUSE, room banner).
-- V1..V133 untouched. Purely additive, H2 MODE=PostgreSQL- and Postgres-compatible (ADD COLUMN / CREATE ... IF NOT EXISTS, DROP CONSTRAINT IF
-- EXISTS before every ADD CONSTRAINT, no DO block).
--
-- A dedicated V134 rather than an edit of V133 in place: V133 (activism_actions) is published with release 0.45.1, an applied migration must never
-- change (Flyway checksum).
--
--   * conference_room.capture_paused_at: set while the moderators have paused the room (recording and/or stream). Also the lock anchor of
--     pause/resume (the room row is locked FOR UPDATE), which serialises double clicks.
--   * conference_recording.paused_at: set while the recording is paused (a sub-state of RECORDING, no new status value);
--     conference_recording.stage: the current stage -- every pause ends a stage, the egresses of the next stage start after the resume.
--   * conference_recording_track.stage: the stage a track row belongs to (so the same participant SID / track SID can be recorded again).
--   * conference_recording_pause: one row per pause (no person column). The composer cuts these stretches out of the finished video.
--   * conference_stream.pause_reason: ROOM_PAUSE added (the room pause as the reason for PAUSING/PAUSED). The column is VARCHAR(13), wide enough.

ALTER TABLE conference_room ADD COLUMN IF NOT EXISTS capture_paused_at TIMESTAMP NULL;

ALTER TABLE conference_recording ADD COLUMN IF NOT EXISTS paused_at TIMESTAMP NULL;
ALTER TABLE conference_recording ADD COLUMN IF NOT EXISTS stage SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE conference_recording DROP CONSTRAINT IF EXISTS chk_conference_recording_paused_at;
ALTER TABLE conference_recording
    ADD CONSTRAINT chk_conference_recording_paused_at CHECK (paused_at IS NULL OR status = 'RECORDING');

ALTER TABLE conference_recording_track ADD COLUMN IF NOT EXISTS stage SMALLINT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS conference_recording_pause (
    id           UUID      NOT NULL PRIMARY KEY,
    recording_id UUID      NOT NULL,
    seq          SMALLINT  NOT NULL,
    paused_at    TIMESTAMP NOT NULL,
    resumed_at   TIMESTAMP NULL,
    CONSTRAINT uq_conference_recording_pause_seq UNIQUE (recording_id, seq),
    CONSTRAINT chk_conference_recording_pause_range CHECK (resumed_at IS NULL OR resumed_at >= paused_at),
    CONSTRAINT fk_crp_recording FOREIGN KEY (recording_id) REFERENCES conference_recording (id) ON DELETE CASCADE
);

ALTER TABLE conference_stream DROP CONSTRAINT IF EXISTS chk_conference_stream_pause_reason;
ALTER TABLE conference_stream
    ADD CONSTRAINT chk_conference_stream_pause_reason
        CHECK (pause_reason IS NULL OR pause_reason IN ('MANUAL', 'SECRET_BALLOT', 'ROOM_PAUSE'));
