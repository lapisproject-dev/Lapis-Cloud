-- V1.2.7 "Automatisiertes Mahnwesen" -- see lapis-server/src/main/kuml/34-dunning.kuml.kts for the
-- full domain model. Vault plan: "Umsetzungsplan V1.2.7 -- Automatisiertes Mahnwesen".
--
-- Idempotent by construction (IF NOT EXISTS / dual-DROP-then-ADD), same discipline as V2-V8.
--
-- Aufteilungsregel: the TWO in-place V1__baseline.sql edits this migration mirrors are
-- (1) organization_settings.dunning_enabled, (2) audit_log_entry.entity_type-CHECK widening for
-- DUNNING_NOTICE. Both are repeated here, idempotently, so a fresh DB (every test run) and the
-- already-migrated pdv2/ELB instances reach the same end state. The THREE new tables live ONLY
-- here, never in V1__baseline.sql (V4-V8 precedent).
--
-- WHY V9 AND NOT AN EXTENSION OF V8: V8 is merged and rolled out on pdv2 (>= V1.2.6); its checksum
-- is consumed. An in-place edit would fail flyway migrate there hard (validateOnMigrate = true,
-- DatabaseConfig.kt).
--
-- OPERATOR NOTE: this migration edits V1__baseline.sql in place (again) -- run `flyway repair`
-- BEFORE the next deploy on BOTH pdv2 AND the ELB instance (two co-located production instances
-- since V1.2.6).

-- ---------------------------------------------------------------------------
-- dunning_level -- the configurable escalation ladder. Deliberately NOT seeded with default rows:
-- an empty table is one of the five independent safeguards before a real dunning letter ever
-- leaves the house (see DunningPoller class KDoc "Phase B" / DunningConfig KDoc).
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS dunning_level (
    id            UUID          NOT NULL PRIMARY KEY,
    level_number  INT           NOT NULL,
    name          VARCHAR(100)  NOT NULL,
    grace_days    INT           NOT NULL,
    response_days INT           NOT NULL,
    fee_amount    DECIMAL(12,2) NULL,
    active        BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP     NOT NULL
);

ALTER TABLE dunning_level DROP CONSTRAINT IF EXISTS chk_dunning_level_grace_days;
ALTER TABLE dunning_level ADD CONSTRAINT chk_dunning_level_grace_days
    CHECK (grace_days >= 1 AND grace_days <= 365);
ALTER TABLE dunning_level DROP CONSTRAINT IF EXISTS chk_dunning_level_response_days;
ALTER TABLE dunning_level ADD CONSTRAINT chk_dunning_level_response_days
    CHECK (response_days >= 1 AND response_days <= 365);
ALTER TABLE dunning_level DROP CONSTRAINT IF EXISTS chk_dunning_level_fee_amount;
ALTER TABLE dunning_level ADD CONSTRAINT chk_dunning_level_fee_amount
    CHECK (fee_amount IS NULL OR (fee_amount >= 0 AND fee_amount <= 25.00));

CREATE UNIQUE INDEX IF NOT EXISTS uq_dunning_level_number ON dunning_level (level_number);

-- ---------------------------------------------------------------------------
-- dunning_notice -- one row per issued/skipped/cancelled escalation step. uq_dunning_notice_slot
-- is the idempotency anchor: at most one notice per (contribution, cycle, level). See
-- DunningIssuance KDoc "cycle_number" for why a cycle counter is used instead of a partial index
-- (H2's MODE=PostgreSQL compatibility layer this codebase's test suite runs against does not
-- support `CREATE UNIQUE INDEX ... WHERE`, same limitation SepaMandateTable's own migration
-- comment already documents).
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS dunning_notice (
    id                     UUID          NOT NULL PRIMARY KEY,
    contribution_id        UUID          NOT NULL,
    dunning_level_id       UUID          NOT NULL,
    cycle_number           INT           NOT NULL DEFAULT 1,
    level_number           INT           NOT NULL,
    level_name             VARCHAR(100)  NOT NULL,
    fee_amount             DECIMAL(12,2) NULL,
    amount_due             DECIMAL(12,2) NOT NULL,
    status                 VARCHAR(9)    NOT NULL,
    issued_at              TIMESTAMP     NOT NULL,
    respond_by             DATE          NOT NULL,
    document_id            UUID          NULL,
    postal_delivery_log_id UUID          NULL,
    created_by             UUID          NULL,
    cancelled_at           TIMESTAMP     NULL,
    cancellation_reason    VARCHAR(500)  NULL
);

ALTER TABLE dunning_notice DROP CONSTRAINT IF EXISTS chk_dunning_notice_status;
ALTER TABLE dunning_notice ADD CONSTRAINT chk_dunning_notice_status
    CHECK (status IN ('ISSUED', 'SKIPPED', 'CANCELLED'));

ALTER TABLE dunning_notice DROP CONSTRAINT IF EXISTS fk_dunning_notice_contribution_id;
ALTER TABLE dunning_notice ADD CONSTRAINT fk_dunning_notice_contribution_id
    FOREIGN KEY (contribution_id) REFERENCES contribution(id);
ALTER TABLE dunning_notice DROP CONSTRAINT IF EXISTS fk_dunning_notice_dunning_level_id;
ALTER TABLE dunning_notice ADD CONSTRAINT fk_dunning_notice_dunning_level_id
    FOREIGN KEY (dunning_level_id) REFERENCES dunning_level(id);
ALTER TABLE dunning_notice DROP CONSTRAINT IF EXISTS fk_dunning_notice_document_id;
ALTER TABLE dunning_notice ADD CONSTRAINT fk_dunning_notice_document_id
    FOREIGN KEY (document_id) REFERENCES document(id);
ALTER TABLE dunning_notice DROP CONSTRAINT IF EXISTS fk_dunning_notice_postal_delivery_log_id;
ALTER TABLE dunning_notice ADD CONSTRAINT fk_dunning_notice_postal_delivery_log_id
    FOREIGN KEY (postal_delivery_log_id) REFERENCES postal_delivery_log(id);
ALTER TABLE dunning_notice DROP CONSTRAINT IF EXISTS fk_dunning_notice_created_by;
ALTER TABLE dunning_notice ADD CONSTRAINT fk_dunning_notice_created_by
    FOREIGN KEY (created_by) REFERENCES member(id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_dunning_notice_slot
    ON dunning_notice (contribution_id, cycle_number, level_number);
CREATE INDEX IF NOT EXISTS idx_dunning_notice_contribution ON dunning_notice (contribution_id);
CREATE INDEX IF NOT EXISTS idx_dunning_notice_status ON dunning_notice (status);
CREATE INDEX IF NOT EXISTS idx_dunning_notice_issued_at ON dunning_notice (issued_at);

-- ---------------------------------------------------------------------------
-- dunning_compliance_acknowledgment -- exact structural mirror of
-- sepa_compliance_acknowledgment (V7__payments.sql).
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS dunning_compliance_acknowledgment (
    id                        UUID         NOT NULL PRIMARY KEY,
    acknowledged_by_member_id UUID         NOT NULL,
    acknowledged_at           TIMESTAMP    NOT NULL,
    disclaimer_version        VARCHAR(20)  NOT NULL,
    disclaimer_sha256         VARCHAR(64)  NOT NULL
);

ALTER TABLE dunning_compliance_acknowledgment DROP CONSTRAINT IF EXISTS fk_dunning_compliance_acknowledgment_member_id;
ALTER TABLE dunning_compliance_acknowledgment ADD CONSTRAINT fk_dunning_compliance_acknowledgment_member_id
    FOREIGN KEY (acknowledged_by_member_id) REFERENCES member(id);

-- ---------------------------------------------------------------------------
-- organization_settings.dunning_enabled -- the DB-level opt-in gate, settable ONLY via
-- IDunningService.enableDunning/disableDunning, never via updateOrganizationSettings. This is the
-- SECOND of five independent safeguards (see DunningPoller/DunningConfig KDoc) -- default FALSE.
-- ---------------------------------------------------------------------------
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS dunning_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- ---------------------------------------------------------------------------
-- audit_log_entry.entity_type CHECK widening for DUNNING_NOTICE. Dual-DROP pattern as V4/V6/V7/V8.
-- Longest new literal DUNNING_NOTICE (14) < CONFERENCE_STREAM_DESTINATION (29) -- no column-width
-- change. VERIFY WITH `\d audit_log_entry` ON pdv2/ELB BEFORE DEPLOY.
-- ---------------------------------------------------------------------------
ALTER TABLE audit_log_entry DROP CONSTRAINT IF EXISTS audit_log_entry_entity_type_check;
ALTER TABLE audit_log_entry DROP CONSTRAINT IF EXISTS chk_audit_log_entry_entity_type;
ALTER TABLE audit_log_entry ADD CONSTRAINT chk_audit_log_entry_entity_type
    CHECK (entity_type IN (
        'JOURNAL_ENTRY', 'PARTY_DONATION_VERDICT', 'RESOLUTION', 'BOARD_MEMBERSHIP',
        'CONFERENCE_RECORDING', 'CONFERENCE_STREAM', 'CONFERENCE_STREAM_DESTINATION', 'CONFERENCE_ROOM',
        'SOCIAL_POST', 'ORGANIZATION_SETTINGS', 'SEPA_MANDATE', 'SEPA_DEBIT_BATCH', 'DUNNING_NOTICE'
    ));
