-- Welle V1.9.127 -- reactions in a video conference: a moderator can switch them off for the whole room. V1..V100 untouched.
-- Purely additive, H2 MODE=PostgreSQL- and Postgres-compatible (ADD COLUMN IF NOT EXISTS, no DO block).
--
-- A dedicated V101 rather than an edit of V100 in place: V100 (carpool_participation) is part of the released v0.34.0 (squashed master
-- commit, deployed databases), so changing it would break the Flyway checksum there (same reasoning as the V95 header).
--
--   * conference_room.reactions_enabled: TRUE (default, every existing room keeps the reactions) or FALSE (moderator switched them off).
--     Client-side only; no server enforcement and no LiveKit permission change (the chat shares can_publish_data). The raised hand
--     ("Wortmeldeliste") is NOT affected by this switch.

ALTER TABLE conference_room ADD COLUMN IF NOT EXISTS reactions_enabled BOOLEAN NOT NULL DEFAULT TRUE;
