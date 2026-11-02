-- Welle V1.9.255 -- pause picture and pause music of the live stream (the server sends them to the stream targets while the room pause holds
-- the stream). V1..V134 untouched. Purely additive, H2 MODE=PostgreSQL- and Postgres-compatible (ADD COLUMN IF NOT EXISTS, DROP CONSTRAINT IF
-- EXISTS before every ADD CONSTRAINT, no DO block).
--
-- A dedicated V135 rather than an edit of V134: V134 belongs to wave V1.9.254, which is already a squash commit of its own; changing an applied
-- migration would break the Flyway checksum of every database that ran it.
--
--   * pause_image_key / pause_image_updated_at: the stored pause picture (a server-generated UUID, the file is <key>.png); NULL = the built-in
--     default picture is used.
--   * pause_music_key / pause_music_label / pause_music_duration_seconds / pause_music_updated_at: the stored pause music (<key>.m4a); NULL = no
--     music (the feed sends silence).

ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS pause_image_key UUID NULL;
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS pause_image_updated_at TIMESTAMP NULL;
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS pause_music_key UUID NULL;
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS pause_music_label VARCHAR(80) NULL;
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS pause_music_duration_seconds SMALLINT NULL;
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS pause_music_updated_at TIMESTAMP NULL;

ALTER TABLE organization_settings DROP CONSTRAINT IF EXISTS chk_org_pause_music_duration;
ALTER TABLE organization_settings
    ADD CONSTRAINT chk_org_pause_music_duration
        CHECK (pause_music_duration_seconds IS NULL OR pause_music_duration_seconds BETWEEN 5 AND 600);
