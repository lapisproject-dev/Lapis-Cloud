-- V1.9.298 "Voting right as a member attribute". See 00-foundation.kuml.kts and docs/architecture/voting-right-and-donors.adoc.
-- V1..V149 untouched (V149 is already released with 0.49.0; changing it in place would break its Flyway checksum).
-- Purely additive and repeatable, H2 MODE=PostgreSQL- and PostgreSQL-compatible: ADD COLUMN IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before every
-- ADD CONSTRAINT, NO backfill, NO index. On PostgreSQL 11+ ADD COLUMN ... DEFAULT is a metadata-only change (no table rewrite).
--
--   * member.voting_right: whether the member may vote (default TRUE, also for every existing member -- no behaviour change for them).
--     Membership (status ACTIVE) and voting right are independent: a member without voting right still counts as a member (invitations,
--     notice board, ...), but is not in the electorate of any committee vote, election or consensus round.
--   * member.voting_right_source: who set the value -- DEFAULT (never touched), MANUAL (an administrator set it; the LupaBB sync leaves it alone),
--     LUPABB (the LupaBB sync set it, or an administrator handed it back to the sync).
--   * member.voting_right_changed_at: class-A UTC instant of the last explicit change; NULL while the source is DEFAULT.
--
-- Backups taken before V150 are rejected on restore by the schema checksum (BackupManifest.schemaChecksum), by design.
-- DSGVO: the three columns are exported with the member record; the erasure resets them to the defaults.

ALTER TABLE member ADD COLUMN IF NOT EXISTS voting_right BOOLEAN DEFAULT TRUE NOT NULL;
ALTER TABLE member ADD COLUMN IF NOT EXISTS voting_right_source VARCHAR(16) DEFAULT 'DEFAULT' NOT NULL;
ALTER TABLE member ADD COLUMN IF NOT EXISTS voting_right_changed_at TIMESTAMP NULL;

ALTER TABLE member DROP CONSTRAINT IF EXISTS chk_member_voting_right_source;
ALTER TABLE member ADD CONSTRAINT chk_member_voting_right_source
    CHECK (voting_right_source IN ('DEFAULT', 'MANUAL', 'LUPABB'));

ALTER TABLE member DROP CONSTRAINT IF EXISTS chk_member_voting_right_default_shape;
ALTER TABLE member ADD CONSTRAINT chk_member_voting_right_default_shape
    CHECK (voting_right_source <> 'DEFAULT' OR (voting_right = TRUE AND voting_right_changed_at IS NULL));
