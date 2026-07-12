-- Dokumentenablage — Basis, versioniert (siehe docs/architecture/domain-model.adoc).
-- storage_key wird ausschliesslich serverseitig generiert (documentId/versionUuid.bin), nie
-- aus dem client-gelieferten Dateinamen abgeleitet (Path-Traversal-Schutz, siehe
-- DocumentRoutes.kt).

CREATE TABLE document_folder (
    id               UUID PRIMARY KEY,
    name             VARCHAR(200) NOT NULL,
    parent_folder_id UUID REFERENCES document_folder (id)
);

CREATE TABLE document (
    id                  UUID PRIMARY KEY,
    folder_id           UUID          NOT NULL REFERENCES document_folder (id),
    title               VARCHAR(300)  NOT NULL,
    current_version_id  UUID,
    created_by          UUID          NOT NULL REFERENCES member (id),
    created_at          TIMESTAMP     NOT NULL,
    access_level        VARCHAR(20)   NOT NULL,
    is_deleted          BOOLEAN       NOT NULL DEFAULT FALSE
);

CREATE TABLE document_version (
    id               UUID          PRIMARY KEY,
    document_id      UUID          NOT NULL REFERENCES document (id),
    version_number   INT           NOT NULL,
    file_name        VARCHAR(300)  NOT NULL,
    mime_type        VARCHAR(150)  NOT NULL,
    file_size_bytes  BIGINT        NOT NULL,
    storage_key      VARCHAR(300)  NOT NULL,
    checksum_sha256  VARCHAR(64)   NOT NULL,
    uploaded_by      UUID          NOT NULL REFERENCES member (id),
    uploaded_at      TIMESTAMP     NOT NULL,
    change_note      VARCHAR(1000),
    CONSTRAINT uq_document_version_number UNIQUE (document_id, version_number)
);

ALTER TABLE document
    ADD CONSTRAINT fk_document_current_version FOREIGN KEY (current_version_id) REFERENCES document_version (id);

CREATE INDEX idx_document_folder ON document (folder_id);
CREATE INDEX idx_document_version_document ON document_version (document_id);
