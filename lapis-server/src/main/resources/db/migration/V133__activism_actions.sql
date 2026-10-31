-- V1.9.246 "Aktionen A1": planning core of the activism module (actions of the organisation: stand, postering, signatures, ...).
-- See 80-activism.kuml.kts, 11-organization-settings.kuml.kts, 14-audit-log.kuml.kts and docs/features/activism.adoc.
-- Purely additive and repeatable: IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT. V131/V132 are NOT edited.
-- One file for H2 and PostgreSQL, portable SQL only (no partial indexes, no GENERATED, no deferrable constraints).
--   * activism_signal carries NO time column, NO free text and NO photo: a bare "I would take part" (Art. 9 DSGVO relevant, political).
--     Excluded from the backup. Only an aggregate (>= 5) is ever shown.
--   * Association tables (responsible, signal, material) use a surrogate id plus a UNIQUE pair (the model profile has no composite PK).
--   * audit_log_entry.entity_type: widened by ACTIVISM_ACTION / ACTIVISM_CONTRIBUTION / ACTIVISM_REPORT / ACTIVISM_SETTINGS (<= 29 chars).
--     The list is V131's, copied verbatim.

ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS activism_review_required BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS activism_notice_country VARCHAR(2) NOT NULL DEFAULT 'DE';

CREATE TABLE IF NOT EXISTS activism_action (
    id UUID NOT NULL PRIMARY KEY,
    title VARCHAR(120) NOT NULL,
    description VARCHAR(4000) NULL,
    goal_text VARCHAR(500) NULL,
    location VARCHAR(200) NULL,
    starts_at TIMESTAMP NULL,
    ends_at TIMESTAMP NULL,
    kind VARCHAR(20) NOT NULL,
    form_appointment BOOLEAN NOT NULL DEFAULT FALSE,
    form_progress BOOLEAN NOT NULL DEFAULT FALSE,
    form_signal BOOLEAN NOT NULL DEFAULT FALSE,
    goal_quantity INTEGER NULL,
    unit VARCHAR(40) NULL,
    confirm_contributions BOOLEAN NOT NULL DEFAULT FALSE,
    own_notice VARCHAR(2000) NULL,
    cost_center_id UUID NULL,
    event_id UUID NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'IDEA',
    withdrawn_reason VARCHAR(500) NULL,
    review_reject_reason VARCHAR(200) NULL,
    result_participants INTEGER NULL,
    result_quantity INTEGER NULL,
    result_text VARCHAR(4000) NULL,
    result_recorded_at TIMESTAMP NULL,
    created_by UUID NULL,
    approved_by UUID NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    revision INTEGER NOT NULL DEFAULT 0
);

ALTER TABLE activism_action DROP CONSTRAINT IF EXISTS fk_activism_action_cost_center;
ALTER TABLE activism_action ADD CONSTRAINT fk_activism_action_cost_center FOREIGN KEY (cost_center_id) REFERENCES cost_center (id);
ALTER TABLE activism_action DROP CONSTRAINT IF EXISTS fk_activism_action_event;
ALTER TABLE activism_action ADD CONSTRAINT fk_activism_action_event FOREIGN KEY (event_id) REFERENCES event (id) ON DELETE SET NULL;
ALTER TABLE activism_action DROP CONSTRAINT IF EXISTS fk_activism_action_created_by;
ALTER TABLE activism_action ADD CONSTRAINT fk_activism_action_created_by FOREIGN KEY (created_by) REFERENCES member (id);
ALTER TABLE activism_action DROP CONSTRAINT IF EXISTS fk_activism_action_approved_by;
ALTER TABLE activism_action ADD CONSTRAINT fk_activism_action_approved_by FOREIGN KEY (approved_by) REFERENCES member (id);
ALTER TABLE activism_action DROP CONSTRAINT IF EXISTS chk_activism_action_kind;
ALTER TABLE activism_action ADD CONSTRAINT chk_activism_action_kind CHECK (kind IN (
    'INFO_STAND', 'POSTERING', 'SIGNATURES', 'DEMONSTRATION', 'ACTION_DAY', 'DOOR_TO_DOOR', 'ONLINE', 'OTHER'));
ALTER TABLE activism_action DROP CONSTRAINT IF EXISTS chk_activism_action_status;
ALTER TABLE activism_action ADD CONSTRAINT chk_activism_action_status CHECK (status IN (
    'IDEA', 'PLANNED', 'RUNNING', 'COMPLETED', 'AWAITING_REVIEW', 'WITHDRAWN'));
ALTER TABLE activism_action DROP CONSTRAINT IF EXISTS chk_activism_action_times;
ALTER TABLE activism_action ADD CONSTRAINT chk_activism_action_times CHECK (starts_at IS NULL OR ends_at IS NULL OR ends_at >= starts_at);
ALTER TABLE activism_action DROP CONSTRAINT IF EXISTS chk_activism_action_goal;
ALTER TABLE activism_action ADD CONSTRAINT chk_activism_action_goal CHECK (goal_quantity IS NULL OR (goal_quantity >= 1 AND goal_quantity <= 1000000));
ALTER TABLE activism_action DROP CONSTRAINT IF EXISTS chk_activism_action_revision;
ALTER TABLE activism_action ADD CONSTRAINT chk_activism_action_revision CHECK (revision >= 0);
CREATE INDEX IF NOT EXISTS idx_activism_action_status ON activism_action (status, id);

CREATE TABLE IF NOT EXISTS activism_responsible (
    id UUID NOT NULL PRIMARY KEY,
    action_id UUID NOT NULL,
    member_id UUID NOT NULL
);
ALTER TABLE activism_responsible DROP CONSTRAINT IF EXISTS fk_activism_responsible_action;
ALTER TABLE activism_responsible ADD CONSTRAINT fk_activism_responsible_action FOREIGN KEY (action_id) REFERENCES activism_action (id) ON DELETE CASCADE;
ALTER TABLE activism_responsible DROP CONSTRAINT IF EXISTS fk_activism_responsible_member;
ALTER TABLE activism_responsible ADD CONSTRAINT fk_activism_responsible_member FOREIGN KEY (member_id) REFERENCES member (id);
ALTER TABLE activism_responsible DROP CONSTRAINT IF EXISTS uq_activism_responsible_pair;
ALTER TABLE activism_responsible ADD CONSTRAINT uq_activism_responsible_pair UNIQUE (action_id, member_id);
CREATE INDEX IF NOT EXISTS idx_activism_responsible_member ON activism_responsible (member_id);

CREATE TABLE IF NOT EXISTS activism_task (
    id UUID NOT NULL PRIMARY KEY,
    action_id UUID NOT NULL,
    position INTEGER NOT NULL,
    text VARCHAR(200) NOT NULL,
    assignee_member_id UUID NULL,
    due_on DATE NULL,
    done BOOLEAN NOT NULL DEFAULT FALSE,
    done_at TIMESTAMP NULL,
    done_by UUID NULL
);
ALTER TABLE activism_task DROP CONSTRAINT IF EXISTS fk_activism_task_action;
ALTER TABLE activism_task ADD CONSTRAINT fk_activism_task_action FOREIGN KEY (action_id) REFERENCES activism_action (id) ON DELETE CASCADE;
ALTER TABLE activism_task DROP CONSTRAINT IF EXISTS fk_activism_task_assignee;
ALTER TABLE activism_task ADD CONSTRAINT fk_activism_task_assignee FOREIGN KEY (assignee_member_id) REFERENCES member (id);
ALTER TABLE activism_task DROP CONSTRAINT IF EXISTS fk_activism_task_done_by;
ALTER TABLE activism_task ADD CONSTRAINT fk_activism_task_done_by FOREIGN KEY (done_by) REFERENCES member (id);
ALTER TABLE activism_task DROP CONSTRAINT IF EXISTS uq_activism_task_position;
ALTER TABLE activism_task ADD CONSTRAINT uq_activism_task_position UNIQUE (action_id, position);

CREATE TABLE IF NOT EXISTS activism_text_block (
    id UUID NOT NULL PRIMARY KEY,
    action_id UUID NOT NULL,
    position INTEGER NOT NULL,
    title VARCHAR(120) NOT NULL,
    body VARCHAR(4000) NOT NULL
);
ALTER TABLE activism_text_block DROP CONSTRAINT IF EXISTS fk_activism_text_block_action;
ALTER TABLE activism_text_block ADD CONSTRAINT fk_activism_text_block_action FOREIGN KEY (action_id) REFERENCES activism_action (id) ON DELETE CASCADE;
ALTER TABLE activism_text_block DROP CONSTRAINT IF EXISTS uq_activism_text_block_position;
ALTER TABLE activism_text_block ADD CONSTRAINT uq_activism_text_block_position UNIQUE (action_id, position);

CREATE TABLE IF NOT EXISTS activism_material_document (
    id UUID NOT NULL PRIMARY KEY,
    action_id UUID NOT NULL,
    document_id UUID NOT NULL,
    position INTEGER NOT NULL
);
ALTER TABLE activism_material_document DROP CONSTRAINT IF EXISTS fk_activism_material_action;
ALTER TABLE activism_material_document ADD CONSTRAINT fk_activism_material_action FOREIGN KEY (action_id) REFERENCES activism_action (id) ON DELETE CASCADE;
ALTER TABLE activism_material_document DROP CONSTRAINT IF EXISTS fk_activism_material_document;
ALTER TABLE activism_material_document ADD CONSTRAINT fk_activism_material_document FOREIGN KEY (document_id) REFERENCES document (id) ON DELETE CASCADE;
ALTER TABLE activism_material_document DROP CONSTRAINT IF EXISTS uq_activism_material_pair;
ALTER TABLE activism_material_document ADD CONSTRAINT uq_activism_material_pair UNIQUE (action_id, document_id);

CREATE TABLE IF NOT EXISTS activism_contribution (
    id UUID NOT NULL PRIMARY KEY,
    action_id UUID NOT NULL,
    member_id UUID NULL,
    quantity INTEGER NOT NULL,
    area VARCHAR(120) NULL,
    note VARCHAR(500) NULL,
    status VARCHAR(10) NOT NULL DEFAULT 'REPORTED',
    reject_reason VARCHAR(200) NULL,
    decided_by UUID NULL,
    decided_at TIMESTAMP NULL,
    reported_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
ALTER TABLE activism_contribution DROP CONSTRAINT IF EXISTS fk_activism_contribution_action;
ALTER TABLE activism_contribution ADD CONSTRAINT fk_activism_contribution_action FOREIGN KEY (action_id) REFERENCES activism_action (id) ON DELETE CASCADE;
ALTER TABLE activism_contribution DROP CONSTRAINT IF EXISTS fk_activism_contribution_member;
ALTER TABLE activism_contribution ADD CONSTRAINT fk_activism_contribution_member FOREIGN KEY (member_id) REFERENCES member (id);
ALTER TABLE activism_contribution DROP CONSTRAINT IF EXISTS fk_activism_contribution_decided_by;
ALTER TABLE activism_contribution ADD CONSTRAINT fk_activism_contribution_decided_by FOREIGN KEY (decided_by) REFERENCES member (id);
ALTER TABLE activism_contribution DROP CONSTRAINT IF EXISTS chk_activism_contribution_quantity;
ALTER TABLE activism_contribution ADD CONSTRAINT chk_activism_contribution_quantity CHECK (quantity >= 1 AND quantity <= 10000);
ALTER TABLE activism_contribution DROP CONSTRAINT IF EXISTS chk_activism_contribution_status;
ALTER TABLE activism_contribution ADD CONSTRAINT chk_activism_contribution_status CHECK (status IN ('REPORTED', 'CONFIRMED', 'REJECTED'));
CREATE INDEX IF NOT EXISTS idx_activism_contribution_member ON activism_contribution (action_id, member_id, reported_at);
CREATE INDEX IF NOT EXISTS idx_activism_contribution_status ON activism_contribution (action_id, status, id);

CREATE TABLE IF NOT EXISTS activism_signal (
    id UUID NOT NULL PRIMARY KEY,
    action_id UUID NOT NULL,
    member_id UUID NOT NULL
);
ALTER TABLE activism_signal DROP CONSTRAINT IF EXISTS fk_activism_signal_action;
ALTER TABLE activism_signal ADD CONSTRAINT fk_activism_signal_action FOREIGN KEY (action_id) REFERENCES activism_action (id) ON DELETE CASCADE;
ALTER TABLE activism_signal DROP CONSTRAINT IF EXISTS fk_activism_signal_member;
ALTER TABLE activism_signal ADD CONSTRAINT fk_activism_signal_member FOREIGN KEY (member_id) REFERENCES member (id);
ALTER TABLE activism_signal DROP CONSTRAINT IF EXISTS uq_activism_signal_pair;
ALTER TABLE activism_signal ADD CONSTRAINT uq_activism_signal_pair UNIQUE (action_id, member_id);
CREATE INDEX IF NOT EXISTS idx_activism_signal_member ON activism_signal (member_id);

CREATE TABLE IF NOT EXISTS activism_report (
    id UUID NOT NULL PRIMARY KEY,
    action_id UUID NOT NULL,
    reporter_member_id UUID NULL,
    reason VARCHAR(1000) NOT NULL,
    status VARCHAR(8) NOT NULL DEFAULT 'OPEN',
    created_at TIMESTAMP NOT NULL,
    resolved_at TIMESTAMP NULL,
    resolved_by UUID NULL
);
ALTER TABLE activism_report DROP CONSTRAINT IF EXISTS fk_activism_report_action;
ALTER TABLE activism_report ADD CONSTRAINT fk_activism_report_action FOREIGN KEY (action_id) REFERENCES activism_action (id) ON DELETE CASCADE;
ALTER TABLE activism_report DROP CONSTRAINT IF EXISTS fk_activism_report_reporter;
ALTER TABLE activism_report ADD CONSTRAINT fk_activism_report_reporter FOREIGN KEY (reporter_member_id) REFERENCES member (id);
ALTER TABLE activism_report DROP CONSTRAINT IF EXISTS fk_activism_report_resolved_by;
ALTER TABLE activism_report ADD CONSTRAINT fk_activism_report_resolved_by FOREIGN KEY (resolved_by) REFERENCES member (id);
ALTER TABLE activism_report DROP CONSTRAINT IF EXISTS chk_activism_report_status;
ALTER TABLE activism_report ADD CONSTRAINT chk_activism_report_status CHECK (status IN ('OPEN', 'RESOLVED'));
CREATE INDEX IF NOT EXISTS idx_activism_report_reporter ON activism_report (reporter_member_id, created_at);
CREATE INDEX IF NOT EXISTS idx_activism_report_status ON activism_report (status, created_at, id);

CREATE TABLE IF NOT EXISTS activism_notice_text (
    id UUID NOT NULL PRIMARY KEY,
    country_code VARCHAR(2) NOT NULL,
    kind VARCHAR(20) NOT NULL,
    body VARCHAR(2000) NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    updated_by UUID NULL
);
ALTER TABLE activism_notice_text DROP CONSTRAINT IF EXISTS fk_activism_notice_text_updated_by;
ALTER TABLE activism_notice_text ADD CONSTRAINT fk_activism_notice_text_updated_by FOREIGN KEY (updated_by) REFERENCES member (id);
ALTER TABLE activism_notice_text DROP CONSTRAINT IF EXISTS uq_activism_notice_text_key;
ALTER TABLE activism_notice_text ADD CONSTRAINT uq_activism_notice_text_key UNIQUE (country_code, kind);
ALTER TABLE activism_notice_text DROP CONSTRAINT IF EXISTS chk_activism_notice_text_kind;
ALTER TABLE activism_notice_text ADD CONSTRAINT chk_activism_notice_text_kind CHECK (kind IN (
    'INFO_STAND', 'POSTERING', 'SIGNATURES', 'DEMONSTRATION', 'ACTION_DAY', 'DOOR_TO_DOOR', 'ONLINE', 'OTHER'));

ALTER TABLE audit_log_entry
    DROP CONSTRAINT IF EXISTS chk_audit_log_entry_entity_type;
ALTER TABLE audit_log_entry
    ADD CONSTRAINT chk_audit_log_entry_entity_type
        CHECK (entity_type IN (
                                'JOURNAL_ENTRY', 'PARTY_DONATION_VERDICT', 'RESOLUTION', 'BOARD_MEMBERSHIP',
                                'CONFERENCE_RECORDING', 'CONFERENCE_STREAM', 'CONFERENCE_STREAM_DESTINATION',
                                'CONFERENCE_ROOM', 'SOCIAL_POST', 'ORGANIZATION_SETTINGS', 'SEPA_MANDATE',
                                'SEPA_DEBIT_BATCH', 'DUNNING_NOTICE', 'MEMBER', 'PAYMENT_TRANSACTION', 'API_KEY',
                                'WEBHOOK_ENDPOINT', 'BANK_STATEMENT_IMPORT', 'ACCOUNTING_EXPORT_CONNECTION',
                                'ACCOUNTING_EXPORT_RUN', 'ACCOUNTING_EXPORT_MAPPING', 'CONTRIBUTION_RELIEF_REQUEST',
                                'TRAVEL_EXPENSE_REPORT', 'VOLUNTEER_ALLOWANCE_PAYMENT', 'VOLUNTEER_DECLARATION',
                                'BANK_ACCOUNT', 'OPEN_ITEM', 'OPEN_ITEM_NETTING', 'RECEIVABLE_DUNNING_NOTICE',
                                'DOCUMENT', 'DOCUMENT_FOLDER', 'ARTICLE', 'REGIONAL_CHAPTER', 'REGIONAL_CHAPTER_OFFICER',
                                'MEMBERSHIP_TIER', 'POLL', 'ENCOUNTER_SPACE', 'EVENT', 'EVENT_IMPORT',
                                'COMMITTEE_MEMBERSHIP', 'ARTICLE_IMPORT', 'ARTICLE_BULK_APPROVED',
                                'ARTICLE_IMPORT_IMAGES_APPLIED', 'MAILING_LIST', 'MAILING_MESSAGE', 'COMMITTEE',
                                'TRAVEL_EXPENSE_SEPA_BATCH', 'CARPOOL_PARTICIPATION', 'VIDEO_OVERLAY', 'AGREEMENT_TEXT', 'PULPIT_RECORDING',
                                'EVENT_CAPTURE', 'TRAVEL_EXPENSE_DOCUMENT', 'TENDER', 'EVENT_REGISTRATION_EXPORT', 'ATTACHED_IMAGE',
                                'PARTG_REPORT_SHEET', 'PARTG_REPORT_POSITION', 'LUPABB_SYNC_RUN', 'LUPABB_SYNC_CONFIG', 'MEETING_EVENT_LINK',
                                'MEETING_SESSION', 'DIRECT_MESSAGE_GROUP_SEND', 'PARTNER_SERVER', 'PARTNER_BLOCKLIST',
                                'ACTIVISM_ACTION', 'ACTIVISM_CONTRIBUTION', 'ACTIVISM_REPORT', 'ACTIVISM_SETTINGS'
        ));
