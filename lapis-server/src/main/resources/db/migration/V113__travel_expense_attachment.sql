-- V1.9.198: report-level attachments for travel-expense reports (V113).
-- Purely additive; V29 (travel_expense_receipt) is untouched. Files live on disk like receipts,
-- the storage_key is always server-generated (see TravelExpenseAttachmentRoutes KDoc).
CREATE TABLE IF NOT EXISTS travel_expense_attachment (
    id                UUID         NOT NULL PRIMARY KEY,
    report_id         UUID         NOT NULL REFERENCES travel_expense_report (id),
    storage_key       VARCHAR(200) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    mime_type         VARCHAR(64)  NOT NULL,
    size_bytes        BIGINT       NOT NULL,
    sha256            VARCHAR(64)  NOT NULL,
    uploaded_by       UUID         NOT NULL REFERENCES member (id),
    uploaded_at       TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_tea_report ON travel_expense_attachment (report_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_tea_storage_key ON travel_expense_attachment (storage_key);

-- MIME allowlist DB-side too; no image/svg+xml (XSS vector).
ALTER TABLE travel_expense_attachment DROP CONSTRAINT IF EXISTS chk_tea_mime;
ALTER TABLE travel_expense_attachment ADD CONSTRAINT chk_tea_mime
    CHECK (mime_type IN ('application/pdf', 'image/jpeg', 'image/png'));

ALTER TABLE travel_expense_attachment DROP CONSTRAINT IF EXISTS chk_tea_size;
ALTER TABLE travel_expense_attachment ADD CONSTRAINT chk_tea_size
    CHECK (size_bytes > 0 AND size_bytes <= 10485760);
