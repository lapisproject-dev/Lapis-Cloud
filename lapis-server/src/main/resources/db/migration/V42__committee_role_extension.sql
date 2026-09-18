-- "committee-role-titles" wave: three additional CommitteeRole values (GENERAL_SECRETARY,
-- PRESS_SPOKESPERSON, MANAGING_DIRECTOR), covering further Vorstand titles common in German
-- parties/Vereine beyond the original five (CHAIR/DEPUTY_CHAIR/SECRETARY/MEMBER/ASSESSOR). Four
-- independent VARCHAR(12)+CHECK constellations carry CommitteeRole in this schema:
-- committee_membership.role, board_membership.committee_role,
-- transparenzregister_reminder.committee_role, election.target_role -- all four widened and
-- re-constrained here. V1__baseline.sql itself is left untouched (already-deployed baseline);
-- this migration is purely additive.
--
-- **Stolperfalle (verified against this codebase's own H2 test suite, MODE=PostgreSQL)**: the
-- naive `ALTER TABLE ... DROP CONSTRAINT IF EXISTS <table>_<column>_check` approach (the
-- PostgreSQL-assigned default name for an unnamed inline CHECK, per V1__baseline.sql's own
-- convention) works on REAL PostgreSQL, but H2's PostgreSQL compatibility mode does NOT mirror
-- that naming -- it assigns an internal, non-deterministic `CONSTRAINT_<n>` name instead. A
-- guessed-name DROP therefore silently no-ops under `IF EXISTS` on H2, leaving the OLD,
-- 5-literal CHECK in place ALONGSIDE the new one this migration adds -- both constraints are then
-- enforced (AND semantics), and the old one still rejects every new literal. Verified live: this
-- exact failure mode reproduced against `GovernanceAuthorizationTest`/`BoardMembershipServiceTest`
-- before the rename-column workaround below was applied.
--
-- **Portable fix**: DROP COLUMN inherently drops any constraint attached to that column, on both
-- engines, without ever needing to know that constraint's name. Rename the existing column out of
-- the way, add a fresh column at the new width (no CHECK yet), copy every row's value across,
-- enforce NOT NULL, drop the renamed-away old column (taking its unnamed CHECK down with it), then
-- add the new, explicitly-named, widened CHECK.

ALTER TABLE committee_membership RENAME COLUMN role TO role_pre_v42;
ALTER TABLE committee_membership ADD COLUMN role VARCHAR(20) NULL;
UPDATE committee_membership SET role = role_pre_v42;
ALTER TABLE committee_membership ALTER COLUMN role SET NOT NULL;
ALTER TABLE committee_membership DROP COLUMN role_pre_v42;
ALTER TABLE committee_membership ADD CONSTRAINT committee_membership_role_check
    CHECK (role IN ('CHAIR', 'DEPUTY_CHAIR', 'SECRETARY', 'MEMBER', 'ASSESSOR',
                     'GENERAL_SECRETARY', 'PRESS_SPOKESPERSON', 'MANAGING_DIRECTOR'));

ALTER TABLE board_membership RENAME COLUMN committee_role TO committee_role_pre_v42;
ALTER TABLE board_membership ADD COLUMN committee_role VARCHAR(20) NULL;
UPDATE board_membership SET committee_role = committee_role_pre_v42;
ALTER TABLE board_membership ALTER COLUMN committee_role SET NOT NULL;
ALTER TABLE board_membership DROP COLUMN committee_role_pre_v42;
ALTER TABLE board_membership ADD CONSTRAINT board_membership_committee_role_check
    CHECK (committee_role IN ('CHAIR', 'DEPUTY_CHAIR', 'SECRETARY', 'MEMBER', 'ASSESSOR',
                               'GENERAL_SECRETARY', 'PRESS_SPOKESPERSON', 'MANAGING_DIRECTOR'));

ALTER TABLE transparenzregister_reminder RENAME COLUMN committee_role TO committee_role_pre_v42;
ALTER TABLE transparenzregister_reminder ADD COLUMN committee_role VARCHAR(20) NULL;
UPDATE transparenzregister_reminder SET committee_role = committee_role_pre_v42;
ALTER TABLE transparenzregister_reminder ALTER COLUMN committee_role SET NOT NULL;
ALTER TABLE transparenzregister_reminder DROP COLUMN committee_role_pre_v42;
ALTER TABLE transparenzregister_reminder ADD CONSTRAINT transparenzregister_reminder_committee_role_check
    CHECK (committee_role IN ('CHAIR', 'DEPUTY_CHAIR', 'SECRETARY', 'MEMBER', 'ASSESSOR',
                               'GENERAL_SECRETARY', 'PRESS_SPOKESPERSON', 'MANAGING_DIRECTOR'));

-- election.target_role is NULLABLE (unlike the three columns above) -- the copy/NOT-NULL steps
-- are adjusted accordingly (no SET NOT NULL, since NULL is a legitimate target_role value).
ALTER TABLE election RENAME COLUMN target_role TO target_role_pre_v42;
ALTER TABLE election ADD COLUMN target_role VARCHAR(20) NULL;
UPDATE election SET target_role = target_role_pre_v42;
ALTER TABLE election DROP COLUMN target_role_pre_v42;
ALTER TABLE election ADD CONSTRAINT election_target_role_check
    CHECK (target_role IN ('CHAIR', 'DEPUTY_CHAIR', 'SECRETARY', 'MEMBER', 'ASSESSOR',
                            'GENERAL_SECRETARY', 'PRESS_SPOKESPERSON', 'MANAGING_DIRECTOR'));
