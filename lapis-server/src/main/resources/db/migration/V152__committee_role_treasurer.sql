-- "board-position-treasurer" wave: CommitteeRole.TREASURER (German label "Finanzvorstand"), a ninth board seat. It is a pure
-- seat/title and grants no access right. V1..V151 untouched (V151 is released with 0.53.0; changing it would break its Flyway checksum).
-- Purely additive and repeatable, H2 MODE=PostgreSQL- and PostgreSQL-compatible: DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT.
-- The four CHECK constraints that carry CommitteeRole were named explicitly by V42, so the drop works on both engines. All columns are
-- already VARCHAR(20) ('TREASURER' has 9 characters). election_seat_outcome.role (V84) has no CHECK and needs nothing. No data is converted;
-- existing "Beisitz" seats stay until an administrator changes them.
--
-- ROLLBACK HAZARD: 0.53.0 cannot read a database holding the value TREASURER. Roll back only by restoring the backup taken before V152.
-- Backups taken before V152 are rejected on restore by the schema checksum (BackupManifest.schemaChecksum), by design.

ALTER TABLE committee_membership DROP CONSTRAINT IF EXISTS committee_membership_role_check;
ALTER TABLE committee_membership ADD CONSTRAINT committee_membership_role_check
    CHECK (role IN ('CHAIR', 'DEPUTY_CHAIR', 'SECRETARY', 'MEMBER', 'ASSESSOR',
                    'GENERAL_SECRETARY', 'PRESS_SPOKESPERSON', 'MANAGING_DIRECTOR', 'TREASURER'));

ALTER TABLE board_membership DROP CONSTRAINT IF EXISTS board_membership_committee_role_check;
ALTER TABLE board_membership ADD CONSTRAINT board_membership_committee_role_check
    CHECK (committee_role IN ('CHAIR', 'DEPUTY_CHAIR', 'SECRETARY', 'MEMBER', 'ASSESSOR',
                    'GENERAL_SECRETARY', 'PRESS_SPOKESPERSON', 'MANAGING_DIRECTOR', 'TREASURER'));

ALTER TABLE transparenzregister_reminder DROP CONSTRAINT IF EXISTS transparenzregister_reminder_committee_role_check;
ALTER TABLE transparenzregister_reminder ADD CONSTRAINT transparenzregister_reminder_committee_role_check
    CHECK (committee_role IN ('CHAIR', 'DEPUTY_CHAIR', 'SECRETARY', 'MEMBER', 'ASSESSOR',
                    'GENERAL_SECRETARY', 'PRESS_SPOKESPERSON', 'MANAGING_DIRECTOR', 'TREASURER'));

ALTER TABLE election DROP CONSTRAINT IF EXISTS election_target_role_check;
ALTER TABLE election ADD CONSTRAINT election_target_role_check
    CHECK (target_role IN ('CHAIR', 'DEPUTY_CHAIR', 'SECRETARY', 'MEMBER', 'ASSESSOR',
                    'GENERAL_SECRETARY', 'PRESS_SPOKESPERSON', 'MANAGING_DIRECTOR', 'TREASURER'));
