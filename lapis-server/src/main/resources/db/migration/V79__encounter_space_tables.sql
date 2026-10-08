-- Welle V1.9.80 -- Begegnungsraum Stufe 2b: tables with their own audio group. V1..V78 untouched, purely additive,
-- H2 MODE=PostgreSQL- and Postgres-compatible (ADD COLUMN IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before ADD CONSTRAINT).
-- The three columns describe the ROOM, never a person (Art. 9: no participation trace; who sits where lives in memory only).
-- Existing rooms get tables_enabled = FALSE: behaviour unchanged. The CHECK tables_profile guarantees on schema level that the
-- church-service profile never has tables.
-- No time fields, hence no entry in time-fields.tsv. No flywayRepair needed (V1..V78 untouched); take a backup before deploying.
ALTER TABLE encounter_space ADD COLUMN IF NOT EXISTS tables_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE encounter_space ADD COLUMN IF NOT EXISTS table_count SMALLINT NOT NULL DEFAULT 4;
ALTER TABLE encounter_space ADD COLUMN IF NOT EXISTS table_seats SMALLINT NOT NULL DEFAULT 6;
ALTER TABLE encounter_space DROP CONSTRAINT IF EXISTS chk_encounter_space_table_count;
ALTER TABLE encounter_space ADD CONSTRAINT chk_encounter_space_table_count CHECK (table_count BETWEEN 1 AND 12);
ALTER TABLE encounter_space DROP CONSTRAINT IF EXISTS chk_encounter_space_table_seats;
ALTER TABLE encounter_space ADD CONSTRAINT chk_encounter_space_table_seats CHECK (table_seats BETWEEN 2 AND 8);
ALTER TABLE encounter_space DROP CONSTRAINT IF EXISTS chk_encounter_space_tables_profile;
ALTER TABLE encounter_space ADD CONSTRAINT chk_encounter_space_tables_profile
    CHECK (tables_enabled = FALSE OR profile = 'ASSEMBLY');
