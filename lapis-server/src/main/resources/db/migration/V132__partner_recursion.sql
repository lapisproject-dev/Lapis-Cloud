-- V1.9.245 "Partnerserver P2": partners of partners (bounded breadth-first recursion), provenance of every foreign event, source filter.
-- See 79-partner-servers.kuml.kts, 11-organization-settings.kuml.kts, docs/features/partner-servers.adoc and docs/architecture/partner-server-feed.adoc.
-- Purely additive and repeatable: IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT. V131 is NOT edited (it may already be applied).
--   * organization_settings.partner_recursion_*: the switch (default ON), the number of additional levels (1..3, default 2) and the
--     bookkeeping of the last recursion run. Written by PartnerDiscoveryStore only.
--   * partner_discovered_server: servers reached through a direct partner (hop >= 2). A pure cache that can be rediscovered; rooted at the
--     direct partner it was reached through (ON DELETE CASCADE removes the whole sub-tree). Excluded from the backup.
--   * partner_event: provenance columns (discovered_server_id, hops, origin_host, via_hosts). partner_server_id stays the ROOT (direct partner).
--     The unique constraint becomes (remote_server_id, remote_event_id): one foreign event exists once, whoever it was reached through.

ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS partner_recursion_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS partner_recursion_depth SMALLINT NOT NULL DEFAULT 2;
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS partner_recursion_last_run_at TIMESTAMP NULL;
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS partner_recursion_last_summary VARCHAR(2000) NULL;
ALTER TABLE organization_settings DROP CONSTRAINT IF EXISTS chk_org_partner_recursion_depth;
ALTER TABLE organization_settings ADD CONSTRAINT chk_org_partner_recursion_depth CHECK (partner_recursion_depth BETWEEN 1 AND 3);

CREATE TABLE IF NOT EXISTS partner_discovered_server (
    id UUID NOT NULL PRIMARY KEY,
    host VARCHAR(253) NOT NULL,
    remote_server_id UUID NULL,
    remote_name VARCHAR(100) NULL,
    root_partner_server_id UUID NOT NULL,
    hops SMALLINT NOT NULL,
    via_hosts VARCHAR(800) NULL,
    created_at TIMESTAMP NOT NULL,
    last_reached_at TIMESTAMP NULL,
    last_fetch_at TIMESTAMP NULL,
    last_status VARCHAR(5) NOT NULL DEFAULT 'NEVER',
    last_error VARCHAR(200) NULL,
    event_count INTEGER NOT NULL DEFAULT 0,
    consecutive_failures INTEGER NOT NULL DEFAULT 0,
    next_fetch_at TIMESTAMP NOT NULL,
    etag VARCHAR(200) NULL,
    remote_partners_json VARCHAR(65536) NULL,
    partners_hidden BOOLEAN NOT NULL DEFAULT FALSE
);

ALTER TABLE partner_discovered_server DROP CONSTRAINT IF EXISTS uq_partner_discovered_host;
ALTER TABLE partner_discovered_server ADD CONSTRAINT uq_partner_discovered_host UNIQUE (host);
ALTER TABLE partner_discovered_server DROP CONSTRAINT IF EXISTS uq_partner_discovered_remote_id;
ALTER TABLE partner_discovered_server ADD CONSTRAINT uq_partner_discovered_remote_id UNIQUE (remote_server_id);
ALTER TABLE partner_discovered_server DROP CONSTRAINT IF EXISTS chk_partner_discovered_host_lower;
ALTER TABLE partner_discovered_server ADD CONSTRAINT chk_partner_discovered_host_lower CHECK (host = LOWER(host));
ALTER TABLE partner_discovered_server DROP CONSTRAINT IF EXISTS chk_partner_discovered_hops;
ALTER TABLE partner_discovered_server ADD CONSTRAINT chk_partner_discovered_hops CHECK (hops BETWEEN 2 AND 4);
ALTER TABLE partner_discovered_server DROP CONSTRAINT IF EXISTS chk_partner_discovered_status;
ALTER TABLE partner_discovered_server ADD CONSTRAINT chk_partner_discovered_status CHECK (last_status IN ('OK', 'ERROR', 'NEVER'));
ALTER TABLE partner_discovered_server DROP CONSTRAINT IF EXISTS chk_partner_discovered_counters;
ALTER TABLE partner_discovered_server ADD CONSTRAINT chk_partner_discovered_counters CHECK (consecutive_failures >= 0 AND event_count >= 0);
ALTER TABLE partner_discovered_server DROP CONSTRAINT IF EXISTS fk_partner_discovered_root;
ALTER TABLE partner_discovered_server ADD CONSTRAINT fk_partner_discovered_root FOREIGN KEY (root_partner_server_id) REFERENCES partner_server (id) ON DELETE CASCADE;
CREATE INDEX IF NOT EXISTS idx_partner_discovered_due ON partner_discovered_server (next_fetch_at);
CREATE INDEX IF NOT EXISTS idx_partner_discovered_root ON partner_discovered_server (root_partner_server_id);

ALTER TABLE partner_event ADD COLUMN IF NOT EXISTS discovered_server_id UUID NULL;
ALTER TABLE partner_event ADD COLUMN IF NOT EXISTS hops SMALLINT NOT NULL DEFAULT 1;
ALTER TABLE partner_event ADD COLUMN IF NOT EXISTS origin_host VARCHAR(253) NULL;
ALTER TABLE partner_event ADD COLUMN IF NOT EXISTS via_hosts VARCHAR(800) NULL;
ALTER TABLE partner_event DROP CONSTRAINT IF EXISTS chk_partner_event_hops;
ALTER TABLE partner_event ADD CONSTRAINT chk_partner_event_hops CHECK (hops BETWEEN 1 AND 4);

UPDATE partner_event SET origin_host = (SELECT ps.host FROM partner_server ps WHERE ps.id = partner_event.partner_server_id) WHERE origin_host IS NULL;
ALTER TABLE partner_event ALTER COLUMN origin_host SET NOT NULL;

-- Guard: P1 makes duplicates of (remote_server_id, remote_event_id) impossible, but the table is a pure cache -- drop duplicates rather than fail.
-- The id is compared by its text form so that H2 and PostgreSQL keep the same row (as in V1.9.243).
DELETE FROM partner_event WHERE EXISTS (SELECT 1 FROM partner_event o WHERE o.remote_server_id = partner_event.remote_server_id AND o.remote_event_id = partner_event.remote_event_id AND CAST(o.id AS VARCHAR(36)) < CAST(partner_event.id AS VARCHAR(36)));

ALTER TABLE partner_event DROP CONSTRAINT IF EXISTS uq_partner_event_remote;
ALTER TABLE partner_event ADD CONSTRAINT uq_partner_event_remote UNIQUE (remote_server_id, remote_event_id);
ALTER TABLE partner_event DROP CONSTRAINT IF EXISTS fk_partner_event_discovered;
ALTER TABLE partner_event ADD CONSTRAINT fk_partner_event_discovered FOREIGN KEY (discovered_server_id) REFERENCES partner_discovered_server (id) ON DELETE CASCADE;
CREATE INDEX IF NOT EXISTS idx_partner_event_discovered ON partner_event (discovered_server_id);
