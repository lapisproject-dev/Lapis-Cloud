-- Welle V1.9.275 "Live-Stream Teil 2: automatischer Wiederholungsversuch + Neuverbinden eines Ziels". V1..V140 untouched. Purely additive,
-- H2 MODE=PostgreSQL and PostgreSQL. Unreleased until the next release: may be corrected in place on its feature branch, afterwards only by a new
-- migration. LiveKit overwrites status/retries/failure_reason of a target on every poll tick, so the own retry state needs its own columns.
ALTER TABLE conference_stream_target ADD COLUMN IF NOT EXISTS failure_kind VARCHAR(16) NULL;
ALTER TABLE conference_stream_target DROP CONSTRAINT IF EXISTS chk_conference_stream_target_failure_kind;
ALTER TABLE conference_stream_target ADD CONSTRAINT chk_conference_stream_target_failure_kind
    CHECK (failure_kind IS NULL OR failure_kind IN ('REJECTED', 'TIMEOUT', 'CONNECTION', 'UNKNOWN'));
-- Own attempt counter (LiveKit never writes it). The upper bound 3 is ConferenceStreamRetryLimits.MAX_AUTO_ATTEMPTS (a test keeps both equal).
ALTER TABLE conference_stream_target ADD COLUMN IF NOT EXISTS auto_retry_attempt INT NOT NULL DEFAULT 0;
ALTER TABLE conference_stream_target DROP CONSTRAINT IF EXISTS chk_conference_stream_target_auto_retry_attempt;
ALTER TABLE conference_stream_target ADD CONSTRAINT chk_conference_stream_target_auto_retry_attempt
    CHECK (auto_retry_attempt BETWEEN 0 AND 3);
-- Planned attempt / last issued UpdateStream (automatic or manual) / anchor of the 45 second window of this target.
ALTER TABLE conference_stream_target ADD COLUMN IF NOT EXISTS auto_retry_next_at TIMESTAMP NULL;
ALTER TABLE conference_stream_target ADD COLUMN IF NOT EXISTS auto_retry_issued_at TIMESTAMP NULL;
ALTER TABLE conference_stream_target ADD COLUMN IF NOT EXISTS retry_window_started_at TIMESTAMP NULL;
