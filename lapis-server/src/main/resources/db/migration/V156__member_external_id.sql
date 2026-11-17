-- V1.9.307 "External member id". See 00-foundation.kuml.kts and docs/features/external-member-id.adoc.
-- V1..V155 untouched (V155 is part of the shipped release 0.54.0; this one is additive and separate).
--
--   * member.external_member_id: the identifier the member carries in an external system (for example the Wabeo/LupaBB id), set by the
--     Keycloak claim or by BOARD/ADMIN only. NULL = none.
--   * member.external_member_id_source: the label of that system (instance configuration). Both columns are NULL or both are set.
--   * (source, id) is unique: one external id belongs to exactly one member. Several NULL rows are allowed (H2 MODE=PostgreSQL and PostgreSQL 17
--     treat NULLs as distinct in a unique index).
--
-- Why `member` and not `account`: the voting right hangs on the member (like voting_right and founding_member); `account` is the sign-in
-- identity (oidc_subject, keycloak_account_link) and the id must survive a re-link; CSV-imported members have no account row at all.
--
-- ROLLBACK: the columns are additive; release 0.54.x simply ignores them. Backups made before V156 are refused on restore by the schema checksum.

ALTER TABLE member ADD COLUMN IF NOT EXISTS external_member_id VARCHAR(64);
ALTER TABLE member ADD COLUMN IF NOT EXISTS external_member_id_source VARCHAR(32);

ALTER TABLE member ADD CONSTRAINT chk_member_external_id_pair CHECK ((external_member_id IS NULL) = (external_member_id_source IS NULL));

CREATE UNIQUE INDEX IF NOT EXISTS ux_member_external_id ON member (external_member_id_source, external_member_id);
