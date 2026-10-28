-- V1.9.239 "Protokollentwurf in der Sitzung" (Welle D1): the minute taker and the chair write the Protokoll of a Sitzung while it runs. See
-- 05-governance.kuml.kts and docs/architecture/governance-committees.adoc.
-- Purely additive and repeatable: CREATE TABLE IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT, no CASCADE anywhere.
-- V1, V127 and V128 are NOT edited (no flywayRepair).
--   * protocol_draft: at most one per Sitzung, created lazily by the FIRST write (a read never creates it). phase DRAFT is the only value D1
--     ever writes; LOCKED is the hook for the finalisation of a later wave (D2) and makes every write refuse.
--   * protocol_draft_block: the free text of ONE agenda item, plain text, versioned for optimistic concurrency (a write names the version it
--     saw). Deliberately NO "updated_by" column: who wrote is in the audit log, so the table holds less personal data.
--   * protocol_draft_task: a task noted in the draft ("who does what until when"). Nothing is pushed to anybody in D1.
--   * No change to audit_log_entry: the existing entity type MEETING_SESSION is reused.

CREATE TABLE IF NOT EXISTS protocol_draft (
    id UUID NOT NULL PRIMARY KEY,
    meeting_id UUID NOT NULL,
    phase VARCHAR(6) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

ALTER TABLE protocol_draft DROP CONSTRAINT IF EXISTS uq_protocol_draft_meeting;
ALTER TABLE protocol_draft ADD CONSTRAINT uq_protocol_draft_meeting UNIQUE (meeting_id);
ALTER TABLE protocol_draft DROP CONSTRAINT IF EXISTS fk_protocol_draft_meeting;
ALTER TABLE protocol_draft ADD CONSTRAINT fk_protocol_draft_meeting FOREIGN KEY (meeting_id) REFERENCES meeting (id);
ALTER TABLE protocol_draft DROP CONSTRAINT IF EXISTS chk_protocol_draft_phase;
ALTER TABLE protocol_draft ADD CONSTRAINT chk_protocol_draft_phase CHECK (phase IN ('DRAFT', 'LOCKED'));

CREATE TABLE IF NOT EXISTS protocol_draft_block (
    id UUID NOT NULL PRIMARY KEY,
    draft_id UUID NOT NULL,
    agenda_item_id UUID NOT NULL,
    body VARCHAR(8000) NOT NULL,
    version BIGINT NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

ALTER TABLE protocol_draft_block DROP CONSTRAINT IF EXISTS uq_protocol_draft_block_item;
ALTER TABLE protocol_draft_block ADD CONSTRAINT uq_protocol_draft_block_item UNIQUE (draft_id, agenda_item_id);
ALTER TABLE protocol_draft_block DROP CONSTRAINT IF EXISTS chk_protocol_draft_block_version;
ALTER TABLE protocol_draft_block ADD CONSTRAINT chk_protocol_draft_block_version CHECK (version >= 0);
ALTER TABLE protocol_draft_block DROP CONSTRAINT IF EXISTS fk_protocol_draft_block_draft;
ALTER TABLE protocol_draft_block ADD CONSTRAINT fk_protocol_draft_block_draft FOREIGN KEY (draft_id) REFERENCES protocol_draft (id);
ALTER TABLE protocol_draft_block DROP CONSTRAINT IF EXISTS fk_protocol_draft_block_agenda_item;
ALTER TABLE protocol_draft_block ADD CONSTRAINT fk_protocol_draft_block_agenda_item FOREIGN KEY (agenda_item_id) REFERENCES agenda_item (id);

CREATE TABLE IF NOT EXISTS protocol_draft_task (
    id UUID NOT NULL PRIMARY KEY,
    draft_id UUID NOT NULL,
    agenda_item_id UUID NOT NULL,
    assignee_member_id UUID NOT NULL,
    description VARCHAR(500) NOT NULL,
    due_date DATE NULL,
    created_at TIMESTAMP NOT NULL
);

ALTER TABLE protocol_draft_task DROP CONSTRAINT IF EXISTS fk_protocol_draft_task_draft;
ALTER TABLE protocol_draft_task ADD CONSTRAINT fk_protocol_draft_task_draft FOREIGN KEY (draft_id) REFERENCES protocol_draft (id);
ALTER TABLE protocol_draft_task DROP CONSTRAINT IF EXISTS fk_protocol_draft_task_agenda_item;
ALTER TABLE protocol_draft_task ADD CONSTRAINT fk_protocol_draft_task_agenda_item FOREIGN KEY (agenda_item_id) REFERENCES agenda_item (id);
ALTER TABLE protocol_draft_task DROP CONSTRAINT IF EXISTS fk_protocol_draft_task_assignee;
ALTER TABLE protocol_draft_task ADD CONSTRAINT fk_protocol_draft_task_assignee FOREIGN KEY (assignee_member_id) REFERENCES member (id);
CREATE INDEX IF NOT EXISTS idx_protocol_draft_task_draft ON protocol_draft_task (draft_id);
CREATE INDEX IF NOT EXISTS idx_protocol_draft_task_assignee ON protocol_draft_task (assignee_member_id);
