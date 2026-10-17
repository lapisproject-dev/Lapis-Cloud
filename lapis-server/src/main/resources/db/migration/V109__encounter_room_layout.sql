-- Welle V1.9.152 "Begegnungsraum Stufe 2c" -- fixed room templates, see 65-encounter-space.kuml.kts. V1..V108 untouched.
-- A dedicated V109 rather than an edit of V108: V108 is on origin/master and may be migrated on staging/forks. Purely additive,
-- H2 MODE=PostgreSQL- and Postgres-compatible (ADD COLUMN IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before ADD CONSTRAINT).
-- room_layout describes the ROOM (what it looks like), never a person (Art. 9: no participation trace). It is identical for everybody present
-- and does not enter the consent text.
-- ORDER IS MANDATORY: add nullable -> backfill -> SET NOT NULL -> CHECKs (a CHECK or NOT NULL before the backfill fails on existing rows).
-- Backfill: the church-service rooms keep what they always looked like (NAVE), every other room gets the hall.
-- The column keeps a DEFAULT 'HALL' after the backfill on purpose: HALL is valid for BOTH profiles, so a row written by a pre-V109 server image
-- (rollback, staging) still satisfies the CHECK instead of failing every createSpace. The current server always writes the column explicitly.
-- No time fields, hence no entry in time-fields.tsv. No flywayRepair needed (V1..V108 untouched); take a backup before deploying.
ALTER TABLE encounter_space ADD COLUMN IF NOT EXISTS room_layout VARCHAR(16);
UPDATE encounter_space SET room_layout = CASE WHEN profile = 'CHURCH_SERVICE' THEN 'NAVE' ELSE 'HALL' END WHERE room_layout IS NULL;
ALTER TABLE encounter_space ALTER COLUMN room_layout SET DEFAULT 'HALL';
ALTER TABLE encounter_space ALTER COLUMN room_layout SET NOT NULL;
ALTER TABLE encounter_space DROP CONSTRAINT IF EXISTS chk_encounter_space_room_layout;
ALTER TABLE encounter_space ADD CONSTRAINT chk_encounter_space_room_layout
    CHECK (room_layout IN ('NAVE', 'FOYER', 'HALL', 'SMALL_GROUP'));
ALTER TABLE encounter_space DROP CONSTRAINT IF EXISTS chk_encounter_space_room_layout_profile;
ALTER TABLE encounter_space ADD CONSTRAINT chk_encounter_space_room_layout_profile
    CHECK (
        (room_layout = 'HALL')
        OR (room_layout = 'NAVE' AND profile = 'CHURCH_SERVICE')
        OR (room_layout IN ('FOYER', 'SMALL_GROUP') AND profile = 'ASSEMBLY')
    );
