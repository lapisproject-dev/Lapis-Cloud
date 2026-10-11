-- Welle V1.9.108 -- moderator audio policy of a conference room. V1..V87 untouched. Purely additive, H2 MODE=PostgreSQL- and
-- Postgres-compatible (ADD COLUMN IF NOT EXISTS, no DO block).
--
-- A dedicated V88: V87 is already rolled out on every instance, so it cannot be extended in place without breaking the Flyway checksum.
--
--   * mute_on_join: participants who are not moderators join with the microphone off (a client-side default only).
--   * participants_may_unmute: when FALSE the room is "locked" -- a non-moderator token carries no MICROPHONE publish source
--     (server enforced via token grant, UpdateParticipant and the ConferenceAudioPolicyReconciler).
ALTER TABLE conference_room ADD COLUMN IF NOT EXISTS mute_on_join BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE conference_room ADD COLUMN IF NOT EXISTS participants_may_unmute BOOLEAN NOT NULL DEFAULT TRUE;
