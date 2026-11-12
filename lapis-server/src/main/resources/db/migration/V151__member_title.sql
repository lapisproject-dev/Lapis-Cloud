-- V1.9.302 "Title field on the member". See 00-foundation.kuml.kts and 77-lupabb-sync.kuml.kts.
-- V1..V150 untouched (V150 is already released with 0.50.0/0.51.0; changing it in place would break its Flyway checksum).
--
--   * member.title: academic/professional title shown before the name in letters (nullable, max 50, validated in code like street).
--   * Backfill: the LupaBB title (member_lupabb_profile.title) is copied once into member.title (trimmed, blank -> NULL, never
--     overwriting a value that is already set), then the side-table column is dropped: member.title is the single source of truth.
--
-- ROLLBACK HAZARD: member_lupabb_profile.title is dropped, so release 0.51.0 can no longer run against a migrated database.
-- Roll back by restoring the pre-rollout backup. Flyway never re-runs a migration, so the backfill (which references p.title)
-- is intentionally not repeatable after the DROP. Backups taken before V151 are rejected on restore by the schema checksum.

ALTER TABLE member ADD COLUMN IF NOT EXISTS title VARCHAR(50);

UPDATE member SET title = (SELECT TRIM(p.title) FROM member_lupabb_profile p WHERE p.member_id = member.id)
 WHERE title IS NULL
   AND EXISTS (SELECT 1 FROM member_lupabb_profile p WHERE p.member_id = member.id AND p.title IS NOT NULL AND TRIM(p.title) <> '');

ALTER TABLE member_lupabb_profile DROP COLUMN IF EXISTS title;
