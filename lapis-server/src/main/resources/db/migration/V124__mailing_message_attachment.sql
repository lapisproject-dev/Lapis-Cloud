-- V1.9.232: attachments on mailing messages (mailing-list mails, manual and dynamic lists). Purely additive; V123 and every earlier
-- migration are untouched. Files live on disk like travel-expense attachments (storage_key is always server-generated, see
-- MailingAttachmentRoutes KDoc); the row keeps the display name, the server-chosen MIME type, the size and the SHA-256 of the stored file.
--   * name_key: NFC + lowercase of the sanitized display name -- the case-insensitive duplicate check per message (backstop unique index).
--   * text_charset: only for txt/csv ('UTF-8' | 'windows-1252'), NULL otherwise.
--   * file_deleted_at: set by the retention job once the file was removed; the metadata row stays ("Datei geloescht").
CREATE TABLE IF NOT EXISTS mailing_message_attachment (
    id                 UUID         NOT NULL PRIMARY KEY,
    mailing_message_id UUID         NOT NULL REFERENCES mailing_message (id),
    storage_key        VARCHAR(200) NOT NULL,
    original_filename  VARCHAR(120) NOT NULL,
    name_key           VARCHAR(120) NOT NULL,
    mime_type          VARCHAR(100) NOT NULL,
    text_charset       VARCHAR(16)  NULL,
    size_bytes         BIGINT       NOT NULL,
    sha256             VARCHAR(64)  NOT NULL,
    uploaded_by        UUID         NOT NULL REFERENCES member (id),
    uploaded_at        TIMESTAMP    NOT NULL,
    file_deleted_at    TIMESTAMP    NULL
);

CREATE INDEX IF NOT EXISTS idx_mma_message ON mailing_message_attachment (mailing_message_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_mma_storage_key ON mailing_message_attachment (storage_key);
CREATE UNIQUE INDEX IF NOT EXISTS uq_mma_message_name ON mailing_message_attachment (mailing_message_id, name_key);

-- MIME allowlist DB-side too; no image/svg+xml, no text/html (XSS vectors).
ALTER TABLE mailing_message_attachment DROP CONSTRAINT IF EXISTS chk_mma_mime;
ALTER TABLE mailing_message_attachment ADD CONSTRAINT chk_mma_mime
    CHECK (mime_type IN (
        'application/pdf', 'image/png', 'image/jpeg', 'image/gif', 'image/webp',
        'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        'application/vnd.openxmlformats-officedocument.presentationml.presentation',
        'application/vnd.oasis.opendocument.text',
        'application/vnd.oasis.opendocument.spreadsheet',
        'application/vnd.oasis.opendocument.presentation',
        'text/plain', 'text/csv'));

ALTER TABLE mailing_message_attachment DROP CONSTRAINT IF EXISTS chk_mma_size;
ALTER TABLE mailing_message_attachment ADD CONSTRAINT chk_mma_size
    CHECK (size_bytes > 0 AND size_bytes <= 10485760);

ALTER TABLE mailing_message_attachment DROP CONSTRAINT IF EXISTS chk_mma_charset;
ALTER TABLE mailing_message_attachment ADD CONSTRAINT chk_mma_charset
    CHECK (text_charset IS NULL OR text_charset IN ('UTF-8', 'windows-1252'));
