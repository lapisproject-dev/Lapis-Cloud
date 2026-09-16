-- Welle "Download-Zaehler pro Dokumentversion + Dokumentanzahl pro Ordner" -- adds a persistent
-- download counter to document_version. V1__baseline.sql was also updated to carry this column for
-- fresh test databases (kUML-generated "Ist-Zustand" reference only) -- V2-V39 are NOT touched,
-- committed on master, checksums consumed. Any already-migrated environment (dev/staging/prod)
-- needs this incremental migration to actually get the column; see review finding.
--
-- Portability: H2 MODE=PostgreSQL (DatabaseConfig.kt / whole test suite). ADD COLUMN IF NOT EXISTS
-- for idempotency, same discipline as V34__bank_account_fints_gap.sql and V2-V39 generally.

ALTER TABLE document_version ADD COLUMN IF NOT EXISTS download_count BIGINT NOT NULL DEFAULT 0;
