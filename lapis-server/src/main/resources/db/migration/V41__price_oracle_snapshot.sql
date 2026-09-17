-- Welle "Price-Oracle-Preishistorie" -- persistente Zeitreihe der Oracle-Kurse (bislang nur der
-- In-Memory-Cache in PriceOracleOrchestrator, bei jedem Neustart verloren). V1__baseline.sql was
-- also updated to carry this table for fresh test databases (kUML-generated "Ist-Zustand"
-- reference only) -- V2-V40 are NOT touched, committed on master, checksums consumed. Any
-- already-migrated environment (dev/staging/prod) needs this incremental migration to actually get
-- the table; see V40__document_version_download_count.sql for the same pattern.
--
-- Portability: H2 MODE=PostgreSQL (DatabaseConfig.kt) -- IF NOT EXISTS throughout, same discipline
-- as V34/V39/V40.
CREATE TABLE IF NOT EXISTS price_oracle_snapshot (
    id UUID NOT NULL PRIMARY KEY,
    anchor_asset VARCHAR(11) NOT NULL,
    donation_currency VARCHAR(3) NOT NULL,
    median_price DECIMAL(38, 18) NOT NULL,
    price_status VARCHAR(8) NOT NULL,
    source_count INT NOT NULL,
    sources_used VARCHAR(500) NOT NULL,
    price_timestamp TIMESTAMP NOT NULL,
    captured_at TIMESTAMP NOT NULL,
    CHECK (anchor_asset IN ('BITCOIN_BTC', 'GOLD_XAU', 'FIAT')),
    CHECK (price_status IN ('LIVE', 'DEGRADED', 'CACHED', 'DEFERRED'))
);
-- Review Round finding (redundant index): a separate non-unique index over the SAME three columns
-- in the SAME order would be dead weight -- the UNIQUE index below already serves every query this
-- table sees (loadHistory's WHERE/ORDER BY, recordIfAbsent's existence check), and with this
-- table's retention (no row is ever deleted), an unused second B-tree only grows unbounded.
CREATE UNIQUE INDEX IF NOT EXISTS uq_price_oracle_snapshot_anchor_ts
    ON price_oracle_snapshot (anchor_asset, donation_currency, price_timestamp);
