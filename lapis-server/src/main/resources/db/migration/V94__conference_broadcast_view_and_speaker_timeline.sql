-- Welle V1.9.115 -- switchable conference views: the view a recording (and the start dialog of a stream) shows, and the speaker timeline
-- of a recording. V1..V93 untouched. Purely additive, H2 MODE=PostgreSQL- and Postgres-compatible (ADD COLUMN / CREATE ... IF NOT EXISTS,
-- DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT, no DO block).
--
-- A dedicated V94 rather than an edit of V88..V93 in place: those are already applied in local H2/dev databases (and part of squashed
-- master commits), so changing them would break the Flyway checksum there (same reasoning as the V89/V90 headers); V93 additionally belongs
-- to another feature (events).
--
--   * conference_room.broadcast_view: RASTER (tile grid) or SPEAKER (one large tile for the speaker, a filmstrip of up to five more).
--     Set by a moderator; applies from the NEXT recording / stream start.
--   * conference_recording.broadcast_view: the room setting FROZEN when the recording starts; a recompute uses this one.
--   * conference_recording.speaker_timeline_computed: whether conference_recording_speaker_span holds the timeline of this recording.
--   * conference_recording_speaker_span: the SMOOTHED speaker timeline, derived by the server from the recorded microphone audio while the
--     raw files exist. Privacy: no name, no identity -- only an offset range plus the id of the person's video track row; it is deleted
--     together with the raw files (and cascades with the recording), it is not exposed through any API, and it is excluded from the
--     organization backup (OrganizationSchemaCatalog.EXCLUDED_TABLES). Time fields are BIGINT millisecond offsets, not date/time columns.

ALTER TABLE conference_room ADD COLUMN IF NOT EXISTS broadcast_view VARCHAR(7) NOT NULL DEFAULT 'RASTER';
ALTER TABLE conference_room DROP CONSTRAINT IF EXISTS chk_conference_room_broadcast_view;
ALTER TABLE conference_room
    ADD CONSTRAINT chk_conference_room_broadcast_view CHECK (broadcast_view IN ('RASTER', 'SPEAKER'));

ALTER TABLE conference_recording ADD COLUMN IF NOT EXISTS broadcast_view VARCHAR(7) NOT NULL DEFAULT 'RASTER';
ALTER TABLE conference_recording DROP CONSTRAINT IF EXISTS chk_conference_recording_broadcast_view;
ALTER TABLE conference_recording
    ADD CONSTRAINT chk_conference_recording_broadcast_view CHECK (broadcast_view IN ('RASTER', 'SPEAKER'));

ALTER TABLE conference_recording ADD COLUMN IF NOT EXISTS speaker_timeline_computed BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS conference_recording_speaker_span (
    id           UUID   NOT NULL PRIMARY KEY,
    recording_id UUID   NOT NULL,
    track_id     UUID   NOT NULL,
    start_ms     BIGINT NOT NULL,
    end_ms       BIGINT NOT NULL,
    CONSTRAINT chk_conference_recording_speaker_span_range CHECK (start_ms >= 0 AND end_ms > start_ms),
    CONSTRAINT fk_crss_recording FOREIGN KEY (recording_id) REFERENCES conference_recording (id) ON DELETE CASCADE,
    CONSTRAINT fk_crss_track FOREIGN KEY (track_id) REFERENCES conference_recording_track (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_conference_recording_speaker_span_recording ON conference_recording_speaker_span (recording_id);
