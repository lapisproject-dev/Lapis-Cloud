-- Welle V1.9.109 -- recording "Neu berechnen" (recompute of a READY recording from its raw per-track files): bookkeeping columns on
-- conference_recording. V1..V88 untouched. Purely additive, H2 MODE=PostgreSQL- and Postgres-compatible (ADD COLUMN IF NOT EXISTS,
-- DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT, no DO block).
--
-- A dedicated V89 rather than an edit of V88 in place: V88 is already applied in local H2/dev databases (and part of a squashed master
-- commit), so changing it would break the Flyway checksum there (same reasoning as the V86/V87 headers).
--
--   * recompute_started_at: set while a recompute of an already READY recording is queued or running; NULL otherwise. It is also the
--     FENCING VALUE of the recompute: the poller finishes (or fails) it only with "WHERE ... recompute_started_at = <value it read>", so a
--     stale worker, a second request or a deletion in between can never write over a newer state. The READY recording stays fully
--     usable meanwhile -- its document and media URL do not change until the new file is committed.
--   * recomputed_at: when the last recompute finished successfully (informational, shown in the list).
--   * last_recompute_failure_code: why the last recompute failed (the old file is untouched then); cleared by the next request.

ALTER TABLE conference_recording ADD COLUMN IF NOT EXISTS recompute_started_at TIMESTAMP NULL;
ALTER TABLE conference_recording ADD COLUMN IF NOT EXISTS recomputed_at TIMESTAMP NULL;
ALTER TABLE conference_recording ADD COLUMN IF NOT EXISTS last_recompute_failure_code VARCHAR(32) NULL;

ALTER TABLE conference_recording DROP CONSTRAINT IF EXISTS chk_conference_recording_recompute_failure_code;
ALTER TABLE conference_recording
    ADD CONSTRAINT chk_conference_recording_recompute_failure_code
        CHECK (last_recompute_failure_code IS NULL OR last_recompute_failure_code IN ('COMPOSE_FAILED'));

ALTER TABLE conference_recording DROP CONSTRAINT IF EXISTS chk_conference_recording_recompute_status;
ALTER TABLE conference_recording
    ADD CONSTRAINT chk_conference_recording_recompute_status
        CHECK (recompute_started_at IS NULL OR status = 'READY');
