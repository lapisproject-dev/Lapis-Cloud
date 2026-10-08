-- Welle V1.9.76 -- Begegnungsraum: anonymous entry notice for office holders. V1..V77 untouched, purely additive,
-- H2 MODE=PostgreSQL- and Postgres-compatible (ADD COLUMN IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before ADD CONSTRAINT).
-- notify_mode describes the ROOM, never a person (Art. 9: no participation trace). Existing rooms become NONE: behaviour unchanged.
-- No time fields, hence no entry in time-fields.tsv. No flywayRepair needed (V1..V77 untouched); take a backup before deploying.
ALTER TABLE encounter_space ADD COLUMN IF NOT EXISTS notify_mode VARCHAR(16) NOT NULL DEFAULT 'NONE';
ALTER TABLE encounter_space DROP CONSTRAINT IF EXISTS chk_encounter_space_notify_mode;
ALTER TABLE encounter_space ADD CONSTRAINT chk_encounter_space_notify_mode
    CHECK (notify_mode IN ('NONE', 'FIRST_GUEST', 'EVERY_GUEST'));
