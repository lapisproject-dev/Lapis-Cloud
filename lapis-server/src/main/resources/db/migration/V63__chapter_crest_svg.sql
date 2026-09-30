-- V1.9.21: regional chapter crest may be a sanitized SVG (image/svg+xml). Additive, no data change.
ALTER TABLE regional_chapter DROP CONSTRAINT IF EXISTS chk_regional_chapter_crest_content_type;
ALTER TABLE regional_chapter ADD CONSTRAINT chk_regional_chapter_crest_content_type CHECK (
    crest_content_type IS NULL OR crest_content_type IN ('image/jpeg', 'image/png', 'image/svg+xml')
);
