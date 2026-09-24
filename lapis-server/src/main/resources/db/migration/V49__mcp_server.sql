-- Welle V1.8.1 "MCP-Server für Mitglieder-Agenten (Fundament, lesend)". Rein additiv und
-- idempotent (IF NOT EXISTS überall); keine frühere Migration wird angefasst.

-- RFC 8707 Audience-Bindung + Verbindungs-Name auf den bestehenden OIDC-Tabellen. Alle NULLABLE:
-- jede bestehende Zeile (Gast-Federation) hat keinen resource-Wert.
ALTER TABLE oidc_authorization_code ADD COLUMN IF NOT EXISTS resource         VARCHAR(2048) NULL;
ALTER TABLE oidc_authorization_code ADD COLUMN IF NOT EXISTS connection_label VARCHAR(60)   NULL;
ALTER TABLE oidc_issued_token       ADD COLUMN IF NOT EXISTS resource         VARCHAR(2048) NULL;
ALTER TABLE oidc_issued_token       ADD COLUMN IF NOT EXISTS connection_label VARCHAR(60)   NULL;
ALTER TABLE oidc_issued_token       ADD COLUMN IF NOT EXISTS last_used_at     TIMESTAMP     NULL;
ALTER TABLE oidc_client_registration ADD COLUMN IF NOT EXISTS token_endpoint_auth_method
    VARCHAR(40) NOT NULL DEFAULT 'client_secret_post';

CREATE TABLE IF NOT EXISTS mcp_member_block (
    id         UUID      NOT NULL PRIMARY KEY,
    member_id  UUID      NOT NULL,
    blocked    BOOLEAN   NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
ALTER TABLE mcp_member_block DROP CONSTRAINT IF EXISTS fk_mcp_member_block_member;
ALTER TABLE mcp_member_block ADD CONSTRAINT fk_mcp_member_block_member
    FOREIGN KEY (member_id) REFERENCES member(id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_mcp_member_block_member ON mcp_member_block (member_id);

-- Nur Metadaten: NIEMALS Argumente, NIEMALS Ergebnisse.
CREATE TABLE IF NOT EXISTS mcp_tool_call_audit (
    id          UUID        NOT NULL PRIMARY KEY,
    member_id   UUID        NULL,          -- nullable: DSGVO retain-and-redact
    token_id    UUID        NULL,          -- nullable: Token darf gelöscht werden, KEIN FK (siehe unten)
    tool_name   VARCHAR(60) NOT NULL,
    outcome     VARCHAR(30) NOT NULL,      -- OK|TIMEOUT|FORBIDDEN|ERROR|UNKNOWN_TOOL (rate-limited calls are never audited)
    duration_ms INT         NOT NULL,
    called_at   TIMESTAMP   NOT NULL
);
ALTER TABLE mcp_tool_call_audit DROP CONSTRAINT IF EXISTS fk_mcp_tool_call_audit_member;
ALTER TABLE mcp_tool_call_audit ADD CONSTRAINT fk_mcp_tool_call_audit_member
    FOREIGN KEY (member_id) REFERENCES member(id);
CREATE INDEX IF NOT EXISTS ix_mcp_tool_call_audit_member ON mcp_tool_call_audit (member_id, called_at);
