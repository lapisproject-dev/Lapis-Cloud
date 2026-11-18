-- V1.9.313 "Mitfahrzentrale-Foederation" W3 (withdrawal on the origin side): the tombstones of withdrawn teasers and the moment of the consent.
-- See docs/architecture/carpool-federation.adoc. V160 is NOT edited (it is already on origin/master and may have run on a database).
-- Additive and repeatable (CREATE TABLE IF NOT EXISTS, ADD COLUMN IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT), H2
-- MODE=PostgreSQL- and PostgreSQL-compatible. No regex CHECK (not portable); the 14 day cap lives in the code (CarpoolTeaserTombstonePolicy.TTL_DAYS).
-- While this wave is unreleased V161 may still be corrected in place on the feature branch; after the release every correction is a NEW migration.
--
--   * carpool_teaser_tombstone: one row says "the hint with this pseudonymous id was withdrawn". NO member, NO trip, NO text: only the HMAC
--     pseudonym (teaser_id, 64 hex), the scope (partner_id NULL = every partner) and a short life (expires_at, at most 14 days and never later
--     than two days after the departure). The primary key is DERIVED from (teaser_id, scope), so a second withdrawal is a no-op (INSERT ... ON
--     CONFLICT DO NOTHING) and the life is never extended. Written and read ONLY by CarpoolFederationStore.
--   * carpool_posting.federation_opt_in_at: when the owner gave the consent (backfilled with created_at for existing opt-ins). A tombstone is only
--     written when some partner fetched the feed after that moment (nothing can have been handed out before).
--
-- ROLLBACK HAZARD: builds without V161 reject a database with V161 applied (Flyway validation). Roll back only by restoring the backup taken before
-- V161. Backups taken before V161 are rejected on restore by the schema checksum (BackupManifest.schemaChecksum), by design.

CREATE TABLE IF NOT EXISTS carpool_teaser_tombstone (
    id         UUID        NOT NULL PRIMARY KEY,
    teaser_id  VARCHAR(64) NOT NULL,
    partner_id UUID        NULL,
    created_at TIMESTAMP   NOT NULL,
    expires_at TIMESTAMP   NOT NULL
);

ALTER TABLE carpool_teaser_tombstone DROP CONSTRAINT IF EXISTS fk_carpool_teaser_tombstone_partner;
ALTER TABLE carpool_teaser_tombstone ADD CONSTRAINT fk_carpool_teaser_tombstone_partner
    FOREIGN KEY (partner_id) REFERENCES partner_server (id) ON DELETE CASCADE;
ALTER TABLE carpool_teaser_tombstone DROP CONSTRAINT IF EXISTS chk_carpool_teaser_tombstone_id_len;
ALTER TABLE carpool_teaser_tombstone ADD CONSTRAINT chk_carpool_teaser_tombstone_id_len CHECK (CHAR_LENGTH(teaser_id) = 64);
ALTER TABLE carpool_teaser_tombstone DROP CONSTRAINT IF EXISTS chk_carpool_teaser_tombstone_ttl;
ALTER TABLE carpool_teaser_tombstone ADD CONSTRAINT chk_carpool_teaser_tombstone_ttl CHECK (expires_at > created_at);

CREATE INDEX IF NOT EXISTS idx_carpool_teaser_tombstone_teaser ON carpool_teaser_tombstone (teaser_id);
CREATE INDEX IF NOT EXISTS idx_carpool_teaser_tombstone_expires ON carpool_teaser_tombstone (expires_at);
CREATE INDEX IF NOT EXISTS idx_carpool_teaser_tombstone_partner ON carpool_teaser_tombstone (partner_id);

ALTER TABLE carpool_posting ADD COLUMN IF NOT EXISTS federation_opt_in_at TIMESTAMP;
UPDATE carpool_posting SET federation_opt_in_at = created_at WHERE federation_opt_in = TRUE AND federation_opt_in_at IS NULL;
