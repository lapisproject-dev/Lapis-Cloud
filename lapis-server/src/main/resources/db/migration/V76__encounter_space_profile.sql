-- Welle V1.9.67 -- Begegnungsraum Stufe 1: room profile + allowed reaction set. V1..V75 untouched, purely additive,
-- H2 MODE=PostgreSQL- and Postgres-compatible (ADD COLUMN IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before ADD CONSTRAINT).
-- Existing rooms become CHURCH_SERVICE with HAND,AMEN: ELB's behaviour is unchanged. theme_key stays frozen at 'CHURCH'
-- (old cached clients decode EncounterTheme). Both columns describe the ROOM, never a person (Art. 9: no participation trace).
-- No time fields, hence no entry in time-fields.tsv. No flywayRepair needed (V1..V75 untouched); take a backup before deploying.
ALTER TABLE encounter_space ADD COLUMN IF NOT EXISTS profile VARCHAR(16) NOT NULL DEFAULT 'CHURCH_SERVICE';
ALTER TABLE encounter_space DROP CONSTRAINT IF EXISTS chk_encounter_space_profile;
ALTER TABLE encounter_space ADD CONSTRAINT chk_encounter_space_profile CHECK (profile IN ('CHURCH_SERVICE', 'ASSEMBLY'));
ALTER TABLE encounter_space ADD COLUMN IF NOT EXISTS reaction_set VARCHAR(64) NOT NULL DEFAULT 'HAND,AMEN';
ALTER TABLE encounter_space DROP CONSTRAINT IF EXISTS chk_encounter_space_reaction_set;
ALTER TABLE encounter_space ADD CONSTRAINT chk_encounter_space_reaction_set
    CHECK (reaction_set = 'HAND' OR reaction_set LIKE 'HAND,%');
