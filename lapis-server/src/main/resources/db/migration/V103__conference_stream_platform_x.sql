-- Welle V1.9.131 "X als Streaming-Plattform" -- see 29-conference-streaming.kuml.kts. V1..V102 untouched.
-- Purely additive, H2 MODE=PostgreSQL- and Postgres-compatible (DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT, no DO block,
-- no partial index, no ON DELETE CASCADE).
--
-- A dedicated V103 rather than an edit of V1 or V102: V1 is applied everywhere, and V102 (V1.9.129) may already be migrated on a
-- deployed database, so changing either would break the Flyway checksum.
--
-- conference_stream_destination.platform carries an INLINE, unnamed CHECK from V1 ('YOUTUBE','TWITCH','PEERTUBE','OWNCAST',
-- 'GENERIC_RTMP'). It is replaced by a NAMED one that also allows 'X'. The column is VARCHAR(12); 'X' fits.
--   * H2 (tests, dev): the generated name is the UPPER-CASE "CONSTRAINT_328" (measured on a schema migrated to V102; deterministic
--     because V1 is immutable). ConferenceStreamPlatformXMigrationTest pins that exactly one CHECK on `platform` exists afterwards, so a
--     drifted number fails loudly.
--   * PostgreSQL: the auto name is conference_stream_destination_platform_check (<table>_<column>_check).
ALTER TABLE conference_stream_destination DROP CONSTRAINT IF EXISTS "CONSTRAINT_328";
ALTER TABLE conference_stream_destination DROP CONSTRAINT IF EXISTS conference_stream_destination_platform_check;
ALTER TABLE conference_stream_destination DROP CONSTRAINT IF EXISTS chk_conference_stream_destination_platform;
ALTER TABLE conference_stream_destination ADD CONSTRAINT chk_conference_stream_destination_platform
    CHECK (platform IN ('YOUTUBE', 'TWITCH', 'PEERTUBE', 'OWNCAST', 'GENERIC_RTMP', 'X'));
