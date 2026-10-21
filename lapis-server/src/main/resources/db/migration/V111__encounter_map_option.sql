-- Welle V1.9.166 "Begegnungsraum Stufe 3, Welle 1" -- the grid map room option, see 65-encounter-space.kuml.kts. V1..V110 untouched.
-- A dedicated V111 rather than an edit of V110: V110 is on origin/master and may be migrated on staging/forks. Purely additive,
-- H2 MODE=PostgreSQL- and Postgres-compatible (ADD COLUMN IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before ADD CONSTRAINT).
-- map_enabled describes the ROOM (does it have a map), never a person. POSITIONS ARE NEVER STORED: they live in server memory only
-- (EncounterMapState); there is deliberately no column, table or audit field for a position, a facing or a path.
-- The option only exists in the ASSEMBLY profile (CHECK). The default FALSE keeps every existing room exactly as it was, and a row written by a
-- pre-V111 server image (rollback) still satisfies the NOT NULL.
-- The entry-notice label gets a code-owned suffix "+map" ("<label>+rec+map" = up to 46 + 8 = 54 characters), so the proof column grows to 58.
-- Widening a VARCHAR (also one that is part of the primary key) rewrites nothing that is wrong; existing proofs stay byte-identical.
-- No time fields, hence no entry in time-fields.tsv. No flywayRepair needed (V1..V110 untouched); take a backup before deploying.
ALTER TABLE encounter_space ADD COLUMN IF NOT EXISTS map_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE encounter_space DROP CONSTRAINT IF EXISTS chk_encounter_space_map_profile;
ALTER TABLE encounter_space ADD CONSTRAINT chk_encounter_space_map_profile
    CHECK (map_enabled = FALSE OR profile = 'ASSEMBLY');
ALTER TABLE encounter_consent_acknowledgment ALTER COLUMN consent_version SET DATA TYPE VARCHAR(58);
