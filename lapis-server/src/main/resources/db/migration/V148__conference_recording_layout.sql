-- V1.9.294: the layout chosen when a conference recording is started (GRID, SPEAKER, SINGLE_PARTICIPANT) and, for SINGLE_PARTICIPANT,
-- the chosen person's LiveKit identity (a plain string echo, no FK -- like conference_recording_track.participant_identity).
--
-- A NEW migration, not an edit of V94: V94 shipped long ago and this adds columns plus new CHECK constraints on an existing table.
-- Old rows: the layout follows the broadcast view frozen at their start (SPEAKER stays SPEAKER, everything else is GRID).
ALTER TABLE conference_recording ADD COLUMN IF NOT EXISTS layout VARCHAR(18) NOT NULL DEFAULT 'GRID';
ALTER TABLE conference_recording ADD COLUMN IF NOT EXISTS single_participant_identity VARCHAR(64);

UPDATE conference_recording SET layout = 'SPEAKER' WHERE broadcast_view = 'SPEAKER';

ALTER TABLE conference_recording DROP CONSTRAINT IF EXISTS chk_conference_recording_layout;
ALTER TABLE conference_recording ADD CONSTRAINT chk_conference_recording_layout
    CHECK (layout IN ('GRID', 'SPEAKER', 'SINGLE_PARTICIPANT'));

ALTER TABLE conference_recording DROP CONSTRAINT IF EXISTS chk_conference_recording_single_identity;
ALTER TABLE conference_recording ADD CONSTRAINT chk_conference_recording_single_identity CHECK (
    (layout = 'SINGLE_PARTICIPANT' AND single_participant_identity IS NOT NULL)
    OR (layout <> 'SINGLE_PARTICIPANT' AND single_participant_identity IS NULL)
);
