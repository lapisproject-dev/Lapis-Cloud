-- V1.9.233: attachments on direct messages. Purely additive; V124 and every earlier migration are untouched.
-- message_id NULL = a PENDING upload: owned by uploader_id, addressed to recipient_id, assigned to a message when the uploader sends
-- (sendDirectMessageWithAttachments) and purged by DirectMessageAttachmentPendingPoller after 24 h if it never was.
-- No ON DELETE CASCADE (repo convention, see CommunicationPersonalData): a cascade could not delete the files on disk either, so every
-- deletion runs in code -- rows and files first, the message after.
--   * storage_key: always server-generated ('dm/{uploaderId}/{attachmentId}.bin'), the file name is display metadata only.
--   * name_key: NFC + lowercase of the sanitized display name (duplicate check among the pending files of one pair).
--   * text_charset: only for txt/csv ('UTF-8' | 'windows-1252'), NULL otherwise.
CREATE TABLE IF NOT EXISTS direct_message_attachment (
    id                UUID         NOT NULL PRIMARY KEY,
    message_id        UUID         NULL REFERENCES direct_message (id),
    uploader_id       UUID         NOT NULL REFERENCES member (id),
    recipient_id      UUID         NOT NULL REFERENCES member (id),
    storage_key       VARCHAR(200) NOT NULL,
    original_filename VARCHAR(120) NOT NULL,
    name_key          VARCHAR(120) NOT NULL,
    mime_type         VARCHAR(100) NOT NULL,
    text_charset      VARCHAR(16)  NULL,
    size_bytes        BIGINT       NOT NULL,
    sha256            VARCHAR(64)  NOT NULL,
    created_at        TIMESTAMP    NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_dma_storage_key ON direct_message_attachment (storage_key);
CREATE INDEX IF NOT EXISTS idx_dma_message ON direct_message_attachment (message_id, created_at, id);
CREATE INDEX IF NOT EXISTS idx_dma_uploader ON direct_message_attachment (uploader_id, recipient_id, message_id);
CREATE INDEX IF NOT EXISTS idx_dma_created ON direct_message_attachment (created_at);

-- MIME allowlist DB-side too (identical to chk_mma_mime); no image/svg+xml, no text/html (XSS vectors).
ALTER TABLE direct_message_attachment DROP CONSTRAINT IF EXISTS chk_dma_mime;
ALTER TABLE direct_message_attachment ADD CONSTRAINT chk_dma_mime
    CHECK (mime_type IN (
        'application/pdf', 'image/png', 'image/jpeg', 'image/gif', 'image/webp',
        'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        'application/vnd.openxmlformats-officedocument.presentationml.presentation',
        'application/vnd.oasis.opendocument.text',
        'application/vnd.oasis.opendocument.spreadsheet',
        'application/vnd.oasis.opendocument.presentation',
        'text/plain', 'text/csv'));

ALTER TABLE direct_message_attachment DROP CONSTRAINT IF EXISTS chk_dma_size;
ALTER TABLE direct_message_attachment ADD CONSTRAINT chk_dma_size
    CHECK (size_bytes > 0 AND size_bytes <= 10485760);

ALTER TABLE direct_message_attachment DROP CONSTRAINT IF EXISTS chk_dma_charset;
ALTER TABLE direct_message_attachment ADD CONSTRAINT chk_dma_charset
    CHECK (text_charset IS NULL OR text_charset IN ('UTF-8', 'windows-1252'));
