-- Welle V1.9.120 -- join/leave tones of a video conference: a moderator can switch them off for the whole room. V1..V94 untouched.
-- Purely additive, H2 MODE=PostgreSQL- and Postgres-compatible (ADD COLUMN IF NOT EXISTS, no DO block).
--
-- A dedicated V95 rather than an edit of V94 in place: V94 is already applied in local H2/dev databases and deployed ones (and part of
-- squashed master commits), so changing it would break the Flyway checksum there (same reasoning as the V94 header).
--
--   * conference_room.join_leave_sounds: TRUE (default, every existing room keeps the tones) or FALSE (moderator switched them off).
--     Client-side tones only; no server enforcement and no LiveKit call.

ALTER TABLE conference_room ADD COLUMN IF NOT EXISTS join_leave_sounds BOOLEAN NOT NULL DEFAULT TRUE;
