-- V1.9.319 "Mitfahr-Foederation" W7 (hardening). See docs/architecture/carpool-federation.adoc (Wave 7).
-- V1..V164 untouched (V159..V164 are on the pushed master; changing any of them in place would break its Flyway checksum).
-- Purely additive and repeatable, H2 MODE=PostgreSQL- and PostgreSQL-compatible: ADD COLUMN IF NOT EXISTS, CREATE TABLE/INDEX IF NOT EXISTS,
-- no partial index, no ON DELETE CASCADE, NO backfill (every new column is NULL for existing rows).
--
--   * partner_server.signing_key_pending_id / signing_key_pending_pem / signing_key_changed_at: a DIRECT partner whose feed announces a signing key
--     other than the pinned one (or none at all) is NOT re-pinned. The pin stays, the announced key waits here as a PENDING key and the carpool
--     exchange with this partner pauses until an administrator accepts the pending key by its fingerprint. NULL = no change pending.
--   * partner_signing_key_hold: the same protection for a DISCOVERED server (partner recursion), keyed by host so that it survives the eviction
--     and re-creation of the discovered row. Holds the fingerprint of the key that was trusted before the change; no personal data.
--   * carpool_teaser_tombstone.relay_origin_host / relay_reporter_partner_id: the provenance of a PASSED-ON tombstone. NULL relay_origin_host = this
--     server's OWN withdrawal (every row written before V165 is therefore treated as own). relay_reporter_partner_id has deliberately NO foreign key:
--     the cap per reporter must keep counting after a partner was removed, because the tombstone still protects the consent.

ALTER TABLE partner_server ADD COLUMN IF NOT EXISTS signing_key_pending_id VARCHAR(512);
ALTER TABLE partner_server ADD COLUMN IF NOT EXISTS signing_key_pending_pem VARCHAR(4096);
ALTER TABLE partner_server ADD COLUMN IF NOT EXISTS signing_key_changed_at TIMESTAMP;

CREATE TABLE IF NOT EXISTS partner_signing_key_hold (
    host                VARCHAR(253) NOT NULL PRIMARY KEY,
    trusted_key_sha256 CHAR(64)     NOT NULL,
    detected_at         TIMESTAMP    NOT NULL
);

ALTER TABLE carpool_teaser_tombstone ADD COLUMN IF NOT EXISTS relay_origin_host VARCHAR(253);
ALTER TABLE carpool_teaser_tombstone ADD COLUMN IF NOT EXISTS relay_reporter_partner_id UUID;
CREATE INDEX IF NOT EXISTS idx_carpool_teaser_tombstone_relay_reporter ON carpool_teaser_tombstone (relay_reporter_partner_id);
