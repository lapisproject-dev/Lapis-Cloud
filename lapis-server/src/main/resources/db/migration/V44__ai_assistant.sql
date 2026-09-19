-- Welle V1.6.1 "KI-Fundament + Pilot Satzungs-Q&A" -- adds the five tables backing the optional,
-- default-OFF AI assistance layer (network.lapis.cloud.server.ai, see 53-ai-assistant.kuml.kts and
-- docs/architecture/ai-assistant.adoc). Purely additive and idempotent (IF NOT EXISTS everywhere);
-- no earlier migration is touched.
--
-- Deliberately NO tsvector column, NO GIN index and NO `CREATE EXTENSION` in this file: none of the
-- three parses on H2 MODE=PostgreSQL, which every test migrates against. The Postgres full-text
-- index over ai_knowledge_chunk.content is an optional accelerator created at runtime instead
-- (network.lapis.cloud.server.ai.retrieval.PostgresFullTextIndexInitializer) -- a missing index
-- makes retrieval slower, never wrong, so its failure must not be a startup failure.
--
-- Deliberately NO access_level column on ai_knowledge_chunk / ai_knowledge_index_state: the access
-- level is joined LIVE from `document` at retrieval time. A copied level would silently go stale
-- when a document is re-classified (PUBLIC_MEMBERS -> BOARD_ONLY) and thereby bypass the check.

CREATE TABLE IF NOT EXISTS ai_knowledge_release (
    id          UUID      NOT NULL PRIMARY KEY,
    document_id UUID      NOT NULL,
    released_by UUID      NULL,
    released_at TIMESTAMP NOT NULL
);
ALTER TABLE ai_knowledge_release DROP CONSTRAINT IF EXISTS fk_ai_knowledge_release_document;
ALTER TABLE ai_knowledge_release ADD CONSTRAINT fk_ai_knowledge_release_document
    FOREIGN KEY (document_id) REFERENCES document(id);
ALTER TABLE ai_knowledge_release DROP CONSTRAINT IF EXISTS fk_ai_knowledge_release_released_by;
ALTER TABLE ai_knowledge_release ADD CONSTRAINT fk_ai_knowledge_release_released_by
    FOREIGN KEY (released_by) REFERENCES member(id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_ai_knowledge_release_document ON ai_knowledge_release (document_id);

CREATE TABLE IF NOT EXISTS ai_knowledge_index_state (
    id                  UUID        NOT NULL PRIMARY KEY,
    document_id         UUID        NOT NULL,
    document_version_id UUID        NULL,
    status              VARCHAR(20) NOT NULL,
    chunk_count         INT         NOT NULL,
    indexed_at          TIMESTAMP   NULL,
    failure_code        VARCHAR(40) NULL
);
ALTER TABLE ai_knowledge_index_state DROP CONSTRAINT IF EXISTS fk_ai_knowledge_index_state_document;
ALTER TABLE ai_knowledge_index_state ADD CONSTRAINT fk_ai_knowledge_index_state_document
    FOREIGN KEY (document_id) REFERENCES document(id);
ALTER TABLE ai_knowledge_index_state DROP CONSTRAINT IF EXISTS fk_ai_knowledge_index_state_version;
ALTER TABLE ai_knowledge_index_state ADD CONSTRAINT fk_ai_knowledge_index_state_version
    FOREIGN KEY (document_version_id) REFERENCES document_version(id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_ai_knowledge_index_state_document ON ai_knowledge_index_state (document_id);

CREATE TABLE IF NOT EXISTS ai_knowledge_chunk (
    id                  UUID         NOT NULL PRIMARY KEY,
    document_id         UUID         NOT NULL,
    document_version_id UUID         NOT NULL,
    chunk_index         INT          NOT NULL,
    section_label       VARCHAR(200) NULL,
    page_number         INT          NULL,
    content             TEXT         NOT NULL,
    char_count          INT          NOT NULL,
    created_at          TIMESTAMP    NOT NULL
);
ALTER TABLE ai_knowledge_chunk DROP CONSTRAINT IF EXISTS fk_ai_knowledge_chunk_document;
ALTER TABLE ai_knowledge_chunk ADD CONSTRAINT fk_ai_knowledge_chunk_document
    FOREIGN KEY (document_id) REFERENCES document(id);
ALTER TABLE ai_knowledge_chunk DROP CONSTRAINT IF EXISTS fk_ai_knowledge_chunk_version;
ALTER TABLE ai_knowledge_chunk ADD CONSTRAINT fk_ai_knowledge_chunk_version
    FOREIGN KEY (document_version_id) REFERENCES document_version(id);
CREATE INDEX IF NOT EXISTS idx_ai_knowledge_chunk_document ON ai_knowledge_chunk (document_id);

CREATE TABLE IF NOT EXISTS ai_member_opt_in (
    id         UUID        NOT NULL PRIMARY KEY,
    member_id  UUID        NOT NULL,
    feature    VARCHAR(30) NOT NULL,
    enabled    BOOLEAN     NOT NULL,
    updated_at TIMESTAMP   NOT NULL
);
ALTER TABLE ai_member_opt_in DROP CONSTRAINT IF EXISTS fk_ai_member_opt_in_member;
ALTER TABLE ai_member_opt_in ADD CONSTRAINT fk_ai_member_opt_in_member
    FOREIGN KEY (member_id) REFERENCES member(id);
-- Full (not partial) unique index -- H2 MODE=PostgreSQL rejects partial ones.
CREATE UNIQUE INDEX IF NOT EXISTS uq_ai_member_opt_in_member_feature ON ai_member_opt_in (member_id, feature);

CREATE TABLE IF NOT EXISTS ai_call_audit (
    id                    UUID         NOT NULL PRIMARY KEY,
    occurred_at           TIMESTAMP    NOT NULL,
    member_id             UUID         NULL,
    agent_type            VARCHAR(40)  NOT NULL,
    provider              VARCHAR(30)  NOT NULL,
    model                 VARCHAR(120) NOT NULL,
    input_hash            VARCHAR(64)  NOT NULL,
    output_hash           VARCHAR(64)  NULL,
    tokens_in             INT          NULL,
    tokens_out            INT          NULL,
    tools_called          VARCHAR(200) NOT NULL,
    outcome               VARCHAR(30)  NOT NULL,
    retrieved_chunk_count INT          NOT NULL
);
ALTER TABLE ai_call_audit DROP CONSTRAINT IF EXISTS fk_ai_call_audit_member;
ALTER TABLE ai_call_audit ADD CONSTRAINT fk_ai_call_audit_member
    FOREIGN KEY (member_id) REFERENCES member(id);
CREATE INDEX IF NOT EXISTS idx_ai_call_audit_occurred_at ON ai_call_audit (occurred_at);
