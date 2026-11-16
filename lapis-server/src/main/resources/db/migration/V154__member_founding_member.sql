-- V1.9.305 "Founding member marker". See 00-foundation.kuml.kts and docs/features/founding-member.adoc.
-- V1..V153 untouched (V153 is the unreleased room-polls migration of another feature; this one is additive and separate).
--
--   * member.founding_member: set by BOARD/ADMIN only (default FALSE).
--   * member.founding_member_since: optional date, only while the marker is set.
--   * member.founding_member_consent_granted_at / _consent_version: the member's consent to show the label publicly (only while the marker is set;
--     both NULL or both set). The consent ends when the board removes the marker or the member leaves ACTIVE.
--
-- ROLLBACK: the columns are additive; release 0.53.0 simply ignores them. Backups made before V154 are refused on restore by the schema checksum.

ALTER TABLE member ADD COLUMN IF NOT EXISTS founding_member BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE member ADD COLUMN IF NOT EXISTS founding_member_since DATE;
ALTER TABLE member ADD COLUMN IF NOT EXISTS founding_member_consent_granted_at TIMESTAMP;
ALTER TABLE member ADD COLUMN IF NOT EXISTS founding_member_consent_version VARCHAR(32);

ALTER TABLE member ADD CONSTRAINT chk_member_founding_member_since_requires_flag CHECK (founding_member OR founding_member_since IS NULL);
ALTER TABLE member ADD CONSTRAINT chk_member_founding_member_consent_requires_flag CHECK (founding_member OR founding_member_consent_granted_at IS NULL);
ALTER TABLE member ADD CONSTRAINT chk_member_founding_member_consent_state CHECK ((founding_member_consent_granted_at IS NULL) = (founding_member_consent_version IS NULL));
