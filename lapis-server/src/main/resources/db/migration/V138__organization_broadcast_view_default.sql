-- Welle V1.9.262 -- organisation default of the recording/stream view (RASTER | SPEAKER). V1..V137 untouched.
-- Additive, H2 MODE=PostgreSQL and PostgreSQL (ADD COLUMN IF NOT EXISTS, no DO block). Existing instance: RASTER (no change).
-- Copied into conference_room.broadcast_view ONLY when a room is created; set ONLY via
-- IOrganizationSettingsService.setConferenceBroadcastViewDefault, never through the generic settings update.
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS conference_broadcast_view_default VARCHAR(7) NOT NULL DEFAULT 'RASTER';
ALTER TABLE organization_settings DROP CONSTRAINT IF EXISTS chk_os_broadcast_view_default;
ALTER TABLE organization_settings ADD CONSTRAINT chk_os_broadcast_view_default
    CHECK (conference_broadcast_view_default IN ('RASTER', 'SPEAKER'));
