-- Welle V1.9.274 "Stream-Schluessel pro Sendung + Zuschauer-Adresse". V1..V139 untouched. Purely additive, H2 MODE=PostgreSQL and PostgreSQL.
-- Unreleased until the next release: may be corrected in place on its feature branch, afterwards only by a new migration.
ALTER TABLE conference_stream_destination ADD COLUMN IF NOT EXISTS key_kind VARCHAR(16) NOT NULL DEFAULT 'PERSISTENT';
ALTER TABLE conference_stream_destination DROP CONSTRAINT IF EXISTS chk_conference_stream_destination_key_kind;
ALTER TABLE conference_stream_destination ADD CONSTRAINT chk_conference_stream_destination_key_kind
    CHECK (key_kind IN ('PERSISTENT', 'PER_BROADCAST'));
-- A PER_BROADCAST destination stores no key; an organisation backup nulls the key columns too (OrganizationSchemaCatalog.REDACTED_COLUMNS).
-- There is deliberately NO CHECK "PERSISTENT implies a key": a restore with nulled keys must stay possible, the service enforces the rule
-- (ConferenceStreamStoredKeyMissingException at start).
ALTER TABLE conference_stream_destination ALTER COLUMN stream_key_ciphertext DROP NOT NULL;
ALTER TABLE conference_stream_destination ALTER COLUMN stream_key_set_at DROP NOT NULL;

-- The key of ONE broadcast (sealed with the target id as AAD), deleted when the stream reaches ENDED/FAILED; and the viewer page address
-- (https only, normalised by StreamViewerUrls) the moderator opens to read the platform comments.
ALTER TABLE conference_stream_target ADD COLUMN IF NOT EXISTS broadcast_key_ciphertext VARCHAR(1024) NULL;
ALTER TABLE conference_stream_target ADD COLUMN IF NOT EXISTS viewer_url VARCHAR(512) NULL;
ALTER TABLE conference_stream_target DROP CONSTRAINT IF EXISTS chk_conference_stream_target_viewer_url;
ALTER TABLE conference_stream_target ADD CONSTRAINT chk_conference_stream_target_viewer_url
    CHECK (viewer_url IS NULL OR (viewer_url LIKE 'https://%' AND length(viewer_url) <= 512));
