-- Welle V1.9.100 -- MCP article drafts. Purely additive, H2 MODE=PostgreSQL- and Postgres-compatible. V1..V81 untouched.
-- NULL = created manually; otherwise a snapshot of oidc_issued_token.connection_label (VARCHAR(60)), like mcp_post_draft.agent_label.
-- No partial index (H2 cannot build one) and no new index: idx_article_author_updated_at (author_id, ...) already covers the lookups,
-- at most ~100 rows per author are ever counted.
ALTER TABLE article ADD COLUMN IF NOT EXISTS mcp_agent_label VARCHAR(60) NULL;
