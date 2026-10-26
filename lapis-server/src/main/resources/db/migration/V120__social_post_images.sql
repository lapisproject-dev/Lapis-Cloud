-- V1.9.223 "Bilder im Sozialen Netzwerk" (pictures in social posts). See 75-image-gallery.kuml.kts and docs/features/image-gallery.adoc.
-- Purely additive and repeatable, H2 MODE=PostgreSQL- and PostgreSQL-compatible. V119 is NOT edited (V1.9.222 may already run on an
-- instance, a changed checksum would break Flyway). Only the owner_type CHECK is widened by SOCIAL_POST; no new column, no new index.
ALTER TABLE attached_image DROP CONSTRAINT IF EXISTS chk_attached_image_owner_type;
ALTER TABLE attached_image ADD CONSTRAINT chk_attached_image_owner_type
    CHECK (owner_type IN ('CROWDFUNDING_PROJECT', 'AUCTION_LISTING', 'SOCIAL_POST'));
