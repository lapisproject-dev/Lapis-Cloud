-- Welle V1.8.2 "MCP-Server: Schreibwerkzeuge". Rein additiv und idempotent (IF NOT EXISTS überall);
-- keine frühere Migration wird angefasst.

-- Entwurf eines Agenten -- KEIN Beitrag. Erst die Freigabe durch das Mitglied in der
-- Weboberfläche erzeugt über SocialNetworkService einen echten social_post.
CREATE TABLE IF NOT EXISTS mcp_post_draft (
    id                UUID         NOT NULL PRIMARY KEY,
    member_id         UUID         NOT NULL,
    token_id          UUID         NULL,          -- KEIN FK: überlebt den Token-Widerruf
    agent_label       VARCHAR(60)  NOT NULL,      -- Momentaufnahme der connection_label
    content           TEXT         NOT NULL,
    visibility        VARCHAR(24)  NOT NULL,      -- SocialPostVisibility
    status            VARCHAR(20)  NOT NULL,      -- OPEN|RELEASED|DISCARDED
    created_at        TIMESTAMP    NOT NULL,
    updated_at        TIMESTAMP    NOT NULL,
    status_changed_at TIMESTAMP    NULL,
    released_post_id  UUID         NULL
);
ALTER TABLE mcp_post_draft DROP CONSTRAINT IF EXISTS fk_mcp_post_draft_member;
ALTER TABLE mcp_post_draft ADD CONSTRAINT fk_mcp_post_draft_member
    FOREIGN KEY (member_id) REFERENCES member(id);
ALTER TABLE mcp_post_draft DROP CONSTRAINT IF EXISTS fk_mcp_post_draft_post;
ALTER TABLE mcp_post_draft ADD CONSTRAINT fk_mcp_post_draft_post
    FOREIGN KEY (released_post_id) REFERENCES social_post(id);
CREATE INDEX IF NOT EXISTS ix_mcp_post_draft_member_status
    ON mcp_post_draft (member_id, status);

-- KI-Kennzeichnung: hängt am Beitrag, überlebt die Freigabe, wird nie zurückgesetzt.
ALTER TABLE social_post ADD COLUMN IF NOT EXISTS ai_assisted BOOLEAN NOT NULL DEFAULT FALSE;

-- Welle V1.8.2b Aufbewahrung: der Aufräum-Poller (PostDraftRetentionPoller) scannt nach
-- (status, status_changed_at) -- siehe PostDraftRetention KDoc für die 7/90-Tage-Fristen.
CREATE INDEX IF NOT EXISTS ix_mcp_post_draft_status_changed
    ON mcp_post_draft (status, status_changed_at);
