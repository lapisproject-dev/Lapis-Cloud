-- V1.9.261: canonical allowlist origin of the partner website an embed registration came through. Audit trail only.
-- Additive, repeatable (H2 MODE=PostgreSQL and PostgreSQL). Existing rows: NULL. No data rewrite.
ALTER TABLE event_registration ADD COLUMN IF NOT EXISTS embed_origin VARCHAR(255) NULL;

-- Only a guest registration can come through the embed widget (same coupling style as
-- chk_payment_checkout_session_embed_origin_external).
ALTER TABLE event_registration DROP CONSTRAINT IF EXISTS chk_event_registration_embed_origin_guest;
ALTER TABLE event_registration ADD CONSTRAINT chk_event_registration_embed_origin_guest
    CHECK (embed_origin IS NULL OR member_id IS NULL);
