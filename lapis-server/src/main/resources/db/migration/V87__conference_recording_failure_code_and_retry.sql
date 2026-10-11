-- Welle V1.9.107 -- recording repair: machine-readable failure code and manual composition retry bookkeeping on conference_recording.
-- V1..V86 untouched. Purely additive, H2 MODE=PostgreSQL- and Postgres-compatible (ADD COLUMN IF NOT EXISTS, DROP CONSTRAINT IF EXISTS
-- before every ADD CONSTRAINT, no DO block). File encoding is UTF-8 (the backfill text contains an umlaut).
--
-- A dedicated V87 rather than an edit of V82..V86 in place: those are already part of squashed master commits and applied in local
-- H2/dev databases, so changing them would break the Flyway checksum there (same reasoning as the V86 header).
--
--   * failure_code: why a FAILED recording failed, so the UI does not parse the German failure_reason text. Only set while status = FAILED.
--   * manual_retry_count / manual_retry_window_started_at / last_manual_retry_at: rate limit of the moderator action
--     "retryRecordingComposition" (minimum spacing and a per-24h cap, enforced in ConferenceRecordingService).
--     last_manual_retry_at doubles as "retry started at" for the UI estimate while the row is PROCESSING.

ALTER TABLE conference_recording ADD COLUMN IF NOT EXISTS failure_code VARCHAR(32) NULL;
ALTER TABLE conference_recording ADD COLUMN IF NOT EXISTS manual_retry_count INT NOT NULL DEFAULT 0;
ALTER TABLE conference_recording ADD COLUMN IF NOT EXISTS manual_retry_window_started_at TIMESTAMP NULL;
ALTER TABLE conference_recording ADD COLUMN IF NOT EXISTS last_manual_retry_at TIMESTAMP NULL;

ALTER TABLE conference_recording DROP CONSTRAINT IF EXISTS chk_conference_recording_failure_code;
ALTER TABLE conference_recording
    ADD CONSTRAINT chk_conference_recording_failure_code
        CHECK (failure_code IS NULL OR failure_code IN ('COMPOSE_FAILED', 'EGRESS_TIMEOUT', 'NO_TRACKS', 'NO_TRACK_STORED'));

ALTER TABLE conference_recording DROP CONSTRAINT IF EXISTS chk_conference_recording_failure_code_status;
ALTER TABLE conference_recording
    ADD CONSTRAINT chk_conference_recording_failure_code_status
        CHECK (failure_code IS NULL OR status = 'FAILED');

ALTER TABLE conference_recording DROP CONSTRAINT IF EXISTS chk_conference_recording_manual_retry_count;
ALTER TABLE conference_recording
    ADD CONSTRAINT chk_conference_recording_manual_retry_count
        CHECK (manual_retry_count >= 0);

-- Backfill from the exact, fixed vocabulary RecordingPoller has always written. Unknown text stays NULL.
UPDATE conference_recording SET failure_code = 'COMPOSE_FAILED'
    WHERE status = 'FAILED' AND failure_code IS NULL AND failure_reason = 'Die Aufzeichnung konnte nicht zusammengesetzt werden.';
UPDATE conference_recording SET failure_code = 'EGRESS_TIMEOUT'
    WHERE status = 'FAILED' AND failure_code IS NULL AND failure_reason = 'Zeitüberschreitung beim Abschluss der Aufzeichnung.';
UPDATE conference_recording SET failure_code = 'NO_TRACKS'
    WHERE status = 'FAILED' AND failure_code IS NULL AND failure_reason = 'Es wurde keine Audio- oder Videospur aufgezeichnet.';
UPDATE conference_recording SET failure_code = 'NO_TRACK_STORED'
    WHERE status = 'FAILED' AND failure_code IS NULL AND failure_reason = 'Der Aufzeichnungsdienst konnte keine Spur speichern (Server-Konfiguration prüfen).';
