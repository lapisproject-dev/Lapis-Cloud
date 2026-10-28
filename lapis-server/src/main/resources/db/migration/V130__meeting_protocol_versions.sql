-- V1.9.240 "Protokoll fertigstellen" (Welle D2): finalised, immutable versions of the Protokoll of a Sitzung, their confirmations, the tasks
-- of a version, the done-state of a task, the "approve the last Protokoll" agenda item and the organisation's confirmation mode. See
-- 05-governance.kuml.kts, 11-organization-settings.kuml.kts and docs/architecture/governance-committees.adoc.
-- Purely additive and repeatable: CREATE TABLE IF NOT EXISTS, ADD COLUMN IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT,
-- no CASCADE. V1 to V129 are NOT edited (V129 is on master and may already be applied: its Flyway checksum must not change).
--   * protocol_version: one row per finalised version of a Sitzung (content_json = canonical snapshot, content_sha256 = hash over a fixed
--     prefix + that JSON). After the INSERT only `status` and `pdf_document_version_id` ever change, each by a CONDITIONAL update in
--     ProtocolVersionStore (the ONLY writer; ProtocolVersionStoreTripwireTest pins that). There is NO database trigger and NO REVOKE: this
--     repository has no such pattern (one DB role, Flyway runs as it) and a trigger would differ between H2 and PostgreSQL; the guarantee is
--     the single writer plus the tripwire plus the hash, which makes any later change detectable.
--   * protocol_version_confirmation: who confirmed a version in which role; one row per (version, role). A person holding both roles gets two rows.
--   * protocol_version_task: the tasks of one version. task_key is stable across versions of the same task.
--   * protocol_task_status: the done-state of a task, kept OUTSIDE the immutable version (a task is ticked off after the Protokoll was finalised).
--   * protocol_draft.correction_*: the draft is a correction of that version; protocol_draft_task.task_key: the stable key of a task.
--   * meeting.protocol_approval_source_meeting_id: DELIBERATELY NO foreign key (meeting -> meeting would be a reference cycle the organisation
--     backup cannot restore, see V127's current_agenda_item_id). It is a tombstone: the Protokoll of that Sitzung was already put on this
--     Sitzung's agenda (UNIQUE, so one Protokoll is offered for approval exactly once, even if the item is removed again).
--   * agenda_item.protocol_approval_*: the item that approves the Protokoll of the previous Sitzung, and the result recorded for it.
--   * organization_settings.protocol_confirmation_mode: only APP_CONFIRMATION is ever written; PLATFORM_SEAL and QES are hooks.

CREATE TABLE IF NOT EXISTS protocol_version (
    id UUID NOT NULL PRIMARY KEY,
    meeting_id UUID NOT NULL,
    version_no INTEGER NOT NULL,
    content_json TEXT NOT NULL,
    content_sha256 VARCHAR(64) NOT NULL,
    correction_note VARCHAR(4000) NULL,
    finalised_by UUID NOT NULL,
    finalised_at TIMESTAMP NOT NULL,
    confirmation_mode VARCHAR(16) NOT NULL,
    minutes_required BOOLEAN NOT NULL,
    status VARCHAR(21) NOT NULL,
    pdf_document_version_id UUID NULL
);

ALTER TABLE protocol_version DROP CONSTRAINT IF EXISTS uq_protocol_version_meeting_no;
ALTER TABLE protocol_version ADD CONSTRAINT uq_protocol_version_meeting_no UNIQUE (meeting_id, version_no);
ALTER TABLE protocol_version DROP CONSTRAINT IF EXISTS chk_protocol_version_no;
ALTER TABLE protocol_version ADD CONSTRAINT chk_protocol_version_no CHECK (version_no >= 1);
ALTER TABLE protocol_version DROP CONSTRAINT IF EXISTS chk_protocol_version_status;
ALTER TABLE protocol_version ADD CONSTRAINT chk_protocol_version_status
    CHECK (status IN ('AWAITING_CONFIRMATION', 'CONFIRMED', 'SUPERSEDED'));
ALTER TABLE protocol_version DROP CONSTRAINT IF EXISTS chk_protocol_version_mode;
ALTER TABLE protocol_version ADD CONSTRAINT chk_protocol_version_mode
    CHECK (confirmation_mode IN ('APP_CONFIRMATION', 'PLATFORM_SEAL', 'QES'));
ALTER TABLE protocol_version DROP CONSTRAINT IF EXISTS chk_protocol_version_sha;
ALTER TABLE protocol_version ADD CONSTRAINT chk_protocol_version_sha CHECK (LENGTH(content_sha256) = 64);
ALTER TABLE protocol_version DROP CONSTRAINT IF EXISTS fk_protocol_version_meeting;
ALTER TABLE protocol_version ADD CONSTRAINT fk_protocol_version_meeting FOREIGN KEY (meeting_id) REFERENCES meeting (id);
ALTER TABLE protocol_version DROP CONSTRAINT IF EXISTS fk_protocol_version_finalised_by;
ALTER TABLE protocol_version ADD CONSTRAINT fk_protocol_version_finalised_by FOREIGN KEY (finalised_by) REFERENCES member (id);
ALTER TABLE protocol_version DROP CONSTRAINT IF EXISTS fk_protocol_version_pdf;
ALTER TABLE protocol_version ADD CONSTRAINT fk_protocol_version_pdf FOREIGN KEY (pdf_document_version_id) REFERENCES document_version (id);
CREATE INDEX IF NOT EXISTS idx_protocol_version_finalised_by ON protocol_version (finalised_by);

CREATE TABLE IF NOT EXISTS protocol_version_confirmation (
    id UUID NOT NULL PRIMARY KEY,
    version_id UUID NOT NULL,
    member_id UUID NOT NULL,
    role VARCHAR(7) NOT NULL,
    confirmed_at TIMESTAMP NOT NULL
);

ALTER TABLE protocol_version_confirmation DROP CONSTRAINT IF EXISTS uq_protocol_confirmation_role;
ALTER TABLE protocol_version_confirmation ADD CONSTRAINT uq_protocol_confirmation_role UNIQUE (version_id, role);
ALTER TABLE protocol_version_confirmation DROP CONSTRAINT IF EXISTS chk_protocol_confirmation_role;
ALTER TABLE protocol_version_confirmation ADD CONSTRAINT chk_protocol_confirmation_role CHECK (role IN ('LEAD', 'MINUTES'));
ALTER TABLE protocol_version_confirmation DROP CONSTRAINT IF EXISTS fk_protocol_confirmation_version;
ALTER TABLE protocol_version_confirmation ADD CONSTRAINT fk_protocol_confirmation_version FOREIGN KEY (version_id) REFERENCES protocol_version (id);
ALTER TABLE protocol_version_confirmation DROP CONSTRAINT IF EXISTS fk_protocol_confirmation_member;
ALTER TABLE protocol_version_confirmation ADD CONSTRAINT fk_protocol_confirmation_member FOREIGN KEY (member_id) REFERENCES member (id);
CREATE INDEX IF NOT EXISTS idx_protocol_confirmation_member ON protocol_version_confirmation (member_id);

CREATE TABLE IF NOT EXISTS protocol_version_task (
    id UUID NOT NULL PRIMARY KEY,
    version_id UUID NOT NULL,
    task_key UUID NOT NULL,
    assignee_member_id UUID NOT NULL,
    due_date DATE NULL,
    description VARCHAR(500) NOT NULL
);

ALTER TABLE protocol_version_task DROP CONSTRAINT IF EXISTS uq_protocol_version_task_key;
ALTER TABLE protocol_version_task ADD CONSTRAINT uq_protocol_version_task_key UNIQUE (version_id, task_key);
ALTER TABLE protocol_version_task DROP CONSTRAINT IF EXISTS fk_protocol_version_task_version;
ALTER TABLE protocol_version_task ADD CONSTRAINT fk_protocol_version_task_version FOREIGN KEY (version_id) REFERENCES protocol_version (id);
ALTER TABLE protocol_version_task DROP CONSTRAINT IF EXISTS fk_protocol_version_task_assignee;
ALTER TABLE protocol_version_task ADD CONSTRAINT fk_protocol_version_task_assignee FOREIGN KEY (assignee_member_id) REFERENCES member (id);
CREATE INDEX IF NOT EXISTS idx_protocol_version_task_assignee ON protocol_version_task (assignee_member_id);

CREATE TABLE IF NOT EXISTS protocol_task_status (
    task_key UUID NOT NULL PRIMARY KEY,
    meeting_id UUID NOT NULL,
    assignee_member_id UUID NOT NULL,
    done_at TIMESTAMP NULL,
    updated_at TIMESTAMP NOT NULL
);

ALTER TABLE protocol_task_status DROP CONSTRAINT IF EXISTS fk_protocol_task_status_meeting;
ALTER TABLE protocol_task_status ADD CONSTRAINT fk_protocol_task_status_meeting FOREIGN KEY (meeting_id) REFERENCES meeting (id);
ALTER TABLE protocol_task_status DROP CONSTRAINT IF EXISTS fk_protocol_task_status_assignee;
ALTER TABLE protocol_task_status ADD CONSTRAINT fk_protocol_task_status_assignee FOREIGN KEY (assignee_member_id) REFERENCES member (id);
CREATE INDEX IF NOT EXISTS idx_protocol_task_status_assignee ON protocol_task_status (assignee_member_id);

ALTER TABLE protocol_draft ADD COLUMN IF NOT EXISTS correction_of_version_no INTEGER NULL;
ALTER TABLE protocol_draft ADD COLUMN IF NOT EXISTS correction_note VARCHAR(4000) NULL;
ALTER TABLE protocol_draft_task ADD COLUMN IF NOT EXISTS task_key UUID NULL;

ALTER TABLE meeting ADD COLUMN IF NOT EXISTS protocol_approval_source_meeting_id UUID NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_meeting_protocol_approval_source ON meeting (protocol_approval_source_meeting_id);

ALTER TABLE agenda_item ADD COLUMN IF NOT EXISTS protocol_approval_source_meeting_id UUID NULL;
ALTER TABLE agenda_item ADD COLUMN IF NOT EXISTS protocol_approval_result VARCHAR(25) NULL;
ALTER TABLE agenda_item ADD COLUMN IF NOT EXISTS protocol_approval_version_no INTEGER NULL;
ALTER TABLE agenda_item ADD COLUMN IF NOT EXISTS approval_recorded_by UUID NULL;
ALTER TABLE agenda_item ADD COLUMN IF NOT EXISTS approval_recorded_at TIMESTAMP NULL;
ALTER TABLE agenda_item DROP CONSTRAINT IF EXISTS chk_agenda_item_approval_result;
ALTER TABLE agenda_item ADD CONSTRAINT chk_agenda_item_approval_result
    CHECK (protocol_approval_result IS NULL OR protocol_approval_result IN ('APPROVED', 'APPROVED_WITH_CORRECTIONS', 'NOT_APPROVED'));
ALTER TABLE agenda_item DROP CONSTRAINT IF EXISTS fk_agenda_item_approval_source;
ALTER TABLE agenda_item ADD CONSTRAINT fk_agenda_item_approval_source FOREIGN KEY (protocol_approval_source_meeting_id) REFERENCES meeting (id);
ALTER TABLE agenda_item DROP CONSTRAINT IF EXISTS fk_agenda_item_approval_recorded_by;
ALTER TABLE agenda_item ADD CONSTRAINT fk_agenda_item_approval_recorded_by FOREIGN KEY (approval_recorded_by) REFERENCES member (id);
CREATE INDEX IF NOT EXISTS idx_agenda_item_approval_recorded_by ON agenda_item (approval_recorded_by);

ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS protocol_confirmation_mode VARCHAR(16) NOT NULL DEFAULT 'APP_CONFIRMATION';
ALTER TABLE organization_settings DROP CONSTRAINT IF EXISTS chk_organization_settings_protocol_mode;
ALTER TABLE organization_settings ADD CONSTRAINT chk_organization_settings_protocol_mode
    CHECK (protocol_confirmation_mode IN ('APP_CONFIRMATION', 'PLATFORM_SEAL', 'QES'));
