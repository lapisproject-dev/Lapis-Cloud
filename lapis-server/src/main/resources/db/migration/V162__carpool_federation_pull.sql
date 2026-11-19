-- V1.9.314 "Mitfahrzentrale-Foederation" W4 (pull client, further hops, pass-on of withdrawals): the cache of hints received from partners, the
-- local blocks against re-import, the pull state per partner and the pinned signing key of discovered servers.
-- See docs/architecture/carpool-federation.adoc. V161 is NOT edited (it is already on origin/master and may have run on a database).
-- Additive and repeatable (CREATE TABLE IF NOT EXISTS, ADD COLUMN IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT), H2
-- MODE=PostgreSQL- and PostgreSQL-compatible. No regex CHECK (not portable); the syntax of the pseudonymous id and the host lives in the code.
-- While this wave is unreleased V162 may still be corrected in place on the feature branch; after the release every correction is a NEW migration.
--
--   * carpool_received_hint: a hint of ANOTHER server (date + two postcode digits + the HMAC pseudonym of its origin). NO member, NO trip, NO name,
--     NO contact, NO place text. One row per (origin_host, teaser_id); the shortest path wins. Read and written ONLY by CarpoolReceivedStore.
--     Life: until the end of the departure day (expires_at). A cache of foreign data: excluded from the backup.
--   * carpool_received_tombstone: "this hint was withdrawn, do not import it again from another path" (local, never sent anywhere).
--   * partner_server.carpool_pull_*: the pull state of this server towards the partner (last try, last success, back-off, ETag, counters).
--   * partner_discovered_server.signing_*: the signing key of a server reached through the partner recursion, pinned from the TLS feed fetch that
--     the recursion makes anyway; it verifies the origin signature of hints of hop 2 and further. No new request.
--
-- ROLLBACK HAZARD: builds without V162 reject a database with V162 applied (Flyway validation). Roll back only by restoring the backup taken before
-- V162. Backups taken before V162 are rejected on restore by the schema checksum (BackupManifest.schemaChecksum), by design.

CREATE TABLE IF NOT EXISTS carpool_received_hint (
    id                 UUID           NOT NULL PRIMARY KEY,
    teaser_id          VARCHAR(64)    NOT NULL,
    origin_host        VARCHAR(253)   NOT NULL,
    hops               SMALLINT       NOT NULL,
    max_hops           SMALLINT       NOT NULL,
    via_hosts          VARCHAR(800)   NULL,
    source_partner_id  UUID           NOT NULL,
    departure_date     DATE           NOT NULL,
    from_postcode_area VARCHAR(2)     NOT NULL,
    to_postcode_area   VARCHAR(2)     NOT NULL,
    origin_signature   VARCHAR(1024)  NOT NULL,
    received_at        TIMESTAMP      NOT NULL,
    last_seen_at       TIMESTAMP      NOT NULL,
    expires_at         TIMESTAMP      NOT NULL
);

ALTER TABLE carpool_received_hint DROP CONSTRAINT IF EXISTS fk_carpool_received_hint_source;
ALTER TABLE carpool_received_hint ADD CONSTRAINT fk_carpool_received_hint_source
    FOREIGN KEY (source_partner_id) REFERENCES partner_server (id) ON DELETE CASCADE;
ALTER TABLE carpool_received_hint DROP CONSTRAINT IF EXISTS uq_carpool_received_hint_origin_teaser;
ALTER TABLE carpool_received_hint ADD CONSTRAINT uq_carpool_received_hint_origin_teaser UNIQUE (origin_host, teaser_id);
ALTER TABLE carpool_received_hint DROP CONSTRAINT IF EXISTS chk_carpool_received_hint_id_len;
ALTER TABLE carpool_received_hint ADD CONSTRAINT chk_carpool_received_hint_id_len CHECK (CHAR_LENGTH(teaser_id) = 64);
ALTER TABLE carpool_received_hint DROP CONSTRAINT IF EXISTS chk_carpool_received_hint_hops;
ALTER TABLE carpool_received_hint ADD CONSTRAINT chk_carpool_received_hint_hops CHECK (hops BETWEEN 1 AND 3);
ALTER TABLE carpool_received_hint DROP CONSTRAINT IF EXISTS chk_carpool_received_hint_max_hops;
ALTER TABLE carpool_received_hint ADD CONSTRAINT chk_carpool_received_hint_max_hops CHECK (max_hops BETWEEN 1 AND 3);
ALTER TABLE carpool_received_hint DROP CONSTRAINT IF EXISTS chk_carpool_received_hint_hops_le_max;
ALTER TABLE carpool_received_hint ADD CONSTRAINT chk_carpool_received_hint_hops_le_max CHECK (hops <= max_hops);
ALTER TABLE carpool_received_hint DROP CONSTRAINT IF EXISTS chk_carpool_received_hint_areas;
ALTER TABLE carpool_received_hint ADD CONSTRAINT chk_carpool_received_hint_areas
    CHECK (CHAR_LENGTH(from_postcode_area) = 2 AND CHAR_LENGTH(to_postcode_area) = 2);
ALTER TABLE carpool_received_hint DROP CONSTRAINT IF EXISTS chk_carpool_received_hint_ttl;
ALTER TABLE carpool_received_hint ADD CONSTRAINT chk_carpool_received_hint_ttl CHECK (expires_at > received_at);

CREATE INDEX IF NOT EXISTS idx_carpool_received_hint_source ON carpool_received_hint (source_partner_id);
CREATE INDEX IF NOT EXISTS idx_carpool_received_hint_expires ON carpool_received_hint (expires_at);
CREATE INDEX IF NOT EXISTS idx_carpool_received_hint_teaser ON carpool_received_hint (teaser_id);

CREATE TABLE IF NOT EXISTS carpool_received_tombstone (
    id                     UUID         NOT NULL PRIMARY KEY,
    teaser_id              VARCHAR(64)  NOT NULL,
    origin_host            VARCHAR(253) NOT NULL,
    reported_by_partner_id UUID         NULL,
    created_at             TIMESTAMP    NOT NULL,
    expires_at             TIMESTAMP    NOT NULL
);

ALTER TABLE carpool_received_tombstone DROP CONSTRAINT IF EXISTS fk_carpool_received_tombstone_reporter;
ALTER TABLE carpool_received_tombstone ADD CONSTRAINT fk_carpool_received_tombstone_reporter
    FOREIGN KEY (reported_by_partner_id) REFERENCES partner_server (id) ON DELETE SET NULL;
ALTER TABLE carpool_received_tombstone DROP CONSTRAINT IF EXISTS uq_carpool_received_tombstone_origin_teaser;
ALTER TABLE carpool_received_tombstone ADD CONSTRAINT uq_carpool_received_tombstone_origin_teaser UNIQUE (origin_host, teaser_id);
ALTER TABLE carpool_received_tombstone DROP CONSTRAINT IF EXISTS chk_carpool_received_tombstone_id_len;
ALTER TABLE carpool_received_tombstone ADD CONSTRAINT chk_carpool_received_tombstone_id_len CHECK (CHAR_LENGTH(teaser_id) = 64);
ALTER TABLE carpool_received_tombstone DROP CONSTRAINT IF EXISTS chk_carpool_received_tombstone_ttl;
ALTER TABLE carpool_received_tombstone ADD CONSTRAINT chk_carpool_received_tombstone_ttl CHECK (expires_at > created_at);

CREATE INDEX IF NOT EXISTS idx_carpool_received_tombstone_expires ON carpool_received_tombstone (expires_at);

ALTER TABLE partner_server ADD COLUMN IF NOT EXISTS carpool_pull_at TIMESTAMP;
ALTER TABLE partner_server ADD COLUMN IF NOT EXISTS carpool_pull_ok_at TIMESTAMP;
ALTER TABLE partner_server ADD COLUMN IF NOT EXISTS carpool_pull_next_at TIMESTAMP;
ALTER TABLE partner_server ADD COLUMN IF NOT EXISTS carpool_pull_failures INT NOT NULL DEFAULT 0;
ALTER TABLE partner_server ADD COLUMN IF NOT EXISTS carpool_pull_status VARCHAR(32);
ALTER TABLE partner_server ADD COLUMN IF NOT EXISTS carpool_pull_error VARCHAR(64);
ALTER TABLE partner_server ADD COLUMN IF NOT EXISTS carpool_pull_etag VARCHAR(200);
ALTER TABLE partner_server ADD COLUMN IF NOT EXISTS carpool_pull_last_accepted INT;
ALTER TABLE partner_server ADD COLUMN IF NOT EXISTS carpool_pull_last_rejected INT;

ALTER TABLE partner_discovered_server ADD COLUMN IF NOT EXISTS signing_key_id VARCHAR(512);
ALTER TABLE partner_discovered_server ADD COLUMN IF NOT EXISTS signing_public_key_pem VARCHAR(4096);
ALTER TABLE partner_discovered_server ADD COLUMN IF NOT EXISTS signing_key_pinned_at TIMESTAMP;

-- Servers discovered before this migration never announced a key to us. A stored ETag would make
-- their (unchanged) partner feed answer 304 forever, so the key would never be pinned (hop 2+ dead).
UPDATE partner_discovered_server SET etag = NULL;
