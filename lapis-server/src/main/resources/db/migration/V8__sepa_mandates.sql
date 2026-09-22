-- V1.2.2 "SEPA-Lastschriftmandate" -- see lapis-server/src/main/kuml/33-payments.kuml.kts
-- (V1.2.2 section) for the full domain model. Vault plan: sepa_v1.2.2_plan.md.
--
-- Idempotent by construction (IF NOT EXISTS / dual-DROP-then-ADD), same discipline as V2-V7.
--
-- Aufteilungsregel: the THREE in-place V1__baseline.sql edits this migration mirrors are
-- (1) contribution.sepa_mandate_id, (2) organization_settings' three new sepa_*-Spalten,
-- (3) audit_log_entry.entity_type-CHECK um SEPA_MANDATE/SEPA_DEBIT_BATCH. All three are repeated
-- here, idempotently, so a fresh DB (every test run) and the already-migrated pdv2 instance reach
-- the same end state. The FOUR new tables live ONLY here, never in V1__baseline.sql (V4-V7
-- precedent).
--
-- WHY V8 AND NOT AN EXTENSION OF V7: V7 is merged (33ef637) and rolled out on pdv2; its checksum
-- is consumed. An in-place edit would fail flyway migrate there hard (validateOnMigrate = true,
-- DatabaseConfig.kt). This is different from V7's own Security-Round-1 edit, which was still
-- pre-release.

-- ---------------------------------------------------------------------------
-- sepa_mandate
-- The IBAN lives EXCLUSIVELY SecretBox-sealed (AES-256-GCM, AAD = sepa_mandate.id) in
-- debtor_iban_ciphertext. Column-pair convention exactly matches
-- conference_stream_destination.stream_key_ciphertext/stream_key_set_at (V1__baseline.sql:1723).
-- debtor_iban_last4 is a pure display fragment ("DE.....1234") -- the full IBAN is never returned
-- again after capture, not even to the member themselves.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sepa_mandate (
    id                      UUID          NOT NULL PRIMARY KEY,
    member_id               UUID          NOT NULL,
    mandate_reference       VARCHAR(35)   NOT NULL,
    debtor_name             VARCHAR(70)   NOT NULL,
    debtor_iban_ciphertext  VARCHAR(1024) NOT NULL,
    debtor_iban_set_at      TIMESTAMP     NOT NULL,
    debtor_iban_last4       VARCHAR(4)    NOT NULL,
    debtor_bic              VARCHAR(11)   NULL,
    signature_date          DATE          NOT NULL,
    sequence_type           VARCHAR(4)    NOT NULL,
    status                  VARCHAR(7)    NOT NULL,
    granted_at              TIMESTAMP     NOT NULL,
    revoked_at              TIMESTAMP     NULL,
    revoked_by              UUID          NULL,
    revocation_reason       VARCHAR(500)  NULL,
    last_used_at            DATE          NULL,
    last_debited_amount     DECIMAL(12,2) NULL,
    created_by              UUID          NOT NULL
);

ALTER TABLE sepa_mandate DROP CONSTRAINT IF EXISTS chk_sepa_mandate_status;
ALTER TABLE sepa_mandate ADD CONSTRAINT chk_sepa_mandate_status
    CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED'));
ALTER TABLE sepa_mandate DROP CONSTRAINT IF EXISTS chk_sepa_mandate_sequence_type;
ALTER TABLE sepa_mandate ADD CONSTRAINT chk_sepa_mandate_sequence_type
    CHECK (sequence_type IN ('FRST', 'RCUR', 'OOFF', 'FNAL'));

ALTER TABLE sepa_mandate DROP CONSTRAINT IF EXISTS fk_sepa_mandate_member_id;
ALTER TABLE sepa_mandate ADD CONSTRAINT fk_sepa_mandate_member_id
    FOREIGN KEY (member_id) REFERENCES member(id);
ALTER TABLE sepa_mandate DROP CONSTRAINT IF EXISTS fk_sepa_mandate_revoked_by;
ALTER TABLE sepa_mandate ADD CONSTRAINT fk_sepa_mandate_revoked_by
    FOREIGN KEY (revoked_by) REFERENCES member(id);
ALTER TABLE sepa_mandate DROP CONSTRAINT IF EXISTS fk_sepa_mandate_created_by;
ALTER TABLE sepa_mandate ADD CONSTRAINT fk_sepa_mandate_created_by
    FOREIGN KEY (created_by) REFERENCES member(id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_sepa_mandate_reference ON sepa_mandate (mandate_reference);
CREATE INDEX IF NOT EXISTS idx_sepa_mandate_member ON sepa_mandate (member_id);
CREATE INDEX IF NOT EXISTS idx_sepa_mandate_status ON sepa_mandate (status);

-- Review Round 1 (2026-08-19, MINOR) -- INVESTIGATED, DEFERRED (not silently skipped): a DB-level
-- backup for the "one ACTIVE mandate per member" invariant grantMandate already enforces at the
-- application level (SELECT ... FOR UPDATE + ConflictException, see SepaService.grantMandate
-- KDoc; exercised by a real concurrent-thread regression test, SepaServiceTest "concurrent-grant
-- guard"). Two DB-level approaches were tried and BOTH fail cross-dialect:
--   1. Postgres' native partial-index syntax (`CREATE UNIQUE INDEX ... ON t(col) WHERE cond`) --
--      rejected by H2's MODE=PostgreSQL compatibility layer this codebase's whole test suite runs
--      against (verified empirically: syntax error at the WHERE keyword).
--   2. A generated/computed column (NULL unless status='ACTIVE') + an ordinary UNIQUE index on
--      that column -- Postgres REQUIRES the `STORED` keyword on GENERATED ALWAYS AS (...) (it has
--      no virtual/non-stored generated column support at all), while H2 REJECTS `STORED` outright
--      (verified empirically) -- mutually exclusive, no single statement satisfies both engines.
-- A third option -- an application-maintained plain shadow column (no generated-column magic),
-- updated at every status-transition write site (grantMandate/revokeMandate/SepaBatchPoller Phase
-- A+B/recordReturn) -- IS portable, but SepaMandateTable.kt is kuml-codegen-generated from
-- `33-payments.kuml.kts` ("do not edit manually"); adding a column that only exists in the
-- migration/hand-edited Exposed table, never in the kUML model, would drift from
-- PaymentsSchemaDriftTest's schema-vs-model consistency check. Widening the actual kUML model is a
-- larger, cross-cutting change (model + codegen regen + five write-site updates) that does not fit
-- safely within this review round for a MINOR finding -- tracked as a genuine follow-up, not a
-- silent omission. The application-level guard remains the sole current enforcement.

-- ---------------------------------------------------------------------------
-- sepa_debit_batch
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sepa_debit_batch (
    id                            UUID          NOT NULL PRIMARY KEY,
    message_id                    VARCHAR(35)   NOT NULL,
    payment_info_id                VARCHAR(35)   NOT NULL,
    requested_collection_date      DATE          NOT NULL,
    sequence_type                  VARCHAR(4)    NOT NULL,
    status                         VARCHAR(9)    NOT NULL,
    item_count                     INT           NOT NULL,
    total_amount                   DECIMAL(14,2) NOT NULL,
    created_by                     UUID          NOT NULL,
    created_at                     TIMESTAMP     NOT NULL,
    notified_at                    TIMESTAMP     NULL,
    required_notice_days           INT           NULL,
    generated_at                   TIMESTAMP     NULL,
    generated_document_id          UUID          NULL,
    prenotification_document_id    UUID          NULL,
    submitted_at                   TIMESTAMP     NULL,
    submitted_note                 VARCHAR(1000) NULL,
    settled_at                     TIMESTAMP     NULL,
    cancelled_at                   TIMESTAMP     NULL,
    cancellation_reason            VARCHAR(500)  NULL,
    creditor_id                    VARCHAR(35)   NULL,
    creditor_name                  VARCHAR(70)   NULL,
    creditor_iban                  VARCHAR(34)   NULL,
    creditor_bic                   VARCHAR(11)   NULL
);

-- Security Round 1 (2026-08-20, MAJOR-4) -- added in-place to this still-pre-release V8 migration
-- (master has not moved past 33ef637 since this branch diverged, same "extend in place" convention
-- V7's own Security-Round-1 edit already established for a pre-release migration). ADD COLUMN IF NOT
-- EXISTS so this is idempotent for anyone who already ran an earlier version of V8 in a local/CI DB.
-- See the sepa_debit_batch table definition above and SepaService.createDebitBatch/generateBatchFile/
-- listMyPrenotifications KDoc for why: the organization's creditor id/name/IBAN/BIC are snapshotted
-- onto the batch at creation time instead of being read LIVE at generation/pre-notification time, so
-- an in-flight batch cannot silently diverge from what its members were pre-notified about.
ALTER TABLE sepa_debit_batch ADD COLUMN IF NOT EXISTS creditor_id   VARCHAR(35) NULL;
ALTER TABLE sepa_debit_batch ADD COLUMN IF NOT EXISTS creditor_name VARCHAR(70) NULL;
ALTER TABLE sepa_debit_batch ADD COLUMN IF NOT EXISTS creditor_iban VARCHAR(34) NULL;
ALTER TABLE sepa_debit_batch ADD COLUMN IF NOT EXISTS creditor_bic  VARCHAR(11) NULL;

ALTER TABLE sepa_debit_batch DROP CONSTRAINT IF EXISTS chk_sepa_debit_batch_status;
ALTER TABLE sepa_debit_batch ADD CONSTRAINT chk_sepa_debit_batch_status
    CHECK (status IN ('DRAFT', 'NOTIFIED', 'GENERATED', 'SUBMITTED', 'SETTLED', 'CANCELLED'));
ALTER TABLE sepa_debit_batch DROP CONSTRAINT IF EXISTS chk_sepa_debit_batch_sequence_type;
ALTER TABLE sepa_debit_batch ADD CONSTRAINT chk_sepa_debit_batch_sequence_type
    CHECK (sequence_type IN ('FRST', 'RCUR', 'OOFF', 'FNAL'));

ALTER TABLE sepa_debit_batch DROP CONSTRAINT IF EXISTS fk_sepa_debit_batch_created_by;
ALTER TABLE sepa_debit_batch ADD CONSTRAINT fk_sepa_debit_batch_created_by
    FOREIGN KEY (created_by) REFERENCES member(id);
ALTER TABLE sepa_debit_batch DROP CONSTRAINT IF EXISTS fk_sepa_debit_batch_generated_document_id;
ALTER TABLE sepa_debit_batch ADD CONSTRAINT fk_sepa_debit_batch_generated_document_id
    FOREIGN KEY (generated_document_id) REFERENCES document(id);
ALTER TABLE sepa_debit_batch DROP CONSTRAINT IF EXISTS fk_sepa_debit_batch_prenotification_document_id;
ALTER TABLE sepa_debit_batch ADD CONSTRAINT fk_sepa_debit_batch_prenotification_document_id
    FOREIGN KEY (prenotification_document_id) REFERENCES document(id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_sepa_debit_batch_message_id ON sepa_debit_batch (message_id);
CREATE INDEX IF NOT EXISTS idx_sepa_debit_batch_status ON sepa_debit_batch (status);
CREATE INDEX IF NOT EXISTS idx_sepa_debit_batch_created_at ON sepa_debit_batch (created_at);

-- ---------------------------------------------------------------------------
-- sepa_debit_item
-- uq_sepa_debit_item_batch_contribution prevents the SAME contribution TWICE in the SAME batch. It
-- does NOT prevent the same contribution in two SIMULTANEOUSLY open batches -- that is the
-- SELECT ... FOR UPDATE + ContributionStatusSets.DEBIT_IN_FLIGHT check in createDebitBatch. A DB
-- constraint cannot express that.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sepa_debit_item (
    id                     UUID          NOT NULL PRIMARY KEY,
    batch_id               UUID          NOT NULL,
    contribution_id        UUID          NOT NULL,
    mandate_id             UUID          NOT NULL,
    end_to_end_id          VARCHAR(35)   NOT NULL,
    amount                 DECIMAL(12,2) NOT NULL,
    remittance_information VARCHAR(140)  NOT NULL,
    status                 VARCHAR(10)   NOT NULL,
    settleable_at          DATE          NULL,
    journal_entry_id       UUID          NULL
);

ALTER TABLE sepa_debit_item DROP CONSTRAINT IF EXISTS chk_sepa_debit_item_status;
ALTER TABLE sepa_debit_item ADD CONSTRAINT chk_sepa_debit_item_status
    CHECK (status IN ('PENDING', 'SETTLEABLE', 'SETTLED', 'RETURNED', 'CANCELLED'));
ALTER TABLE sepa_debit_item DROP CONSTRAINT IF EXISTS chk_sepa_debit_item_amount_positive;
ALTER TABLE sepa_debit_item ADD CONSTRAINT chk_sepa_debit_item_amount_positive
    CHECK (amount > 0);

ALTER TABLE sepa_debit_item DROP CONSTRAINT IF EXISTS fk_sepa_debit_item_batch_id;
ALTER TABLE sepa_debit_item ADD CONSTRAINT fk_sepa_debit_item_batch_id
    FOREIGN KEY (batch_id) REFERENCES sepa_debit_batch(id);
ALTER TABLE sepa_debit_item DROP CONSTRAINT IF EXISTS fk_sepa_debit_item_contribution_id;
ALTER TABLE sepa_debit_item ADD CONSTRAINT fk_sepa_debit_item_contribution_id
    FOREIGN KEY (contribution_id) REFERENCES contribution(id);
ALTER TABLE sepa_debit_item DROP CONSTRAINT IF EXISTS fk_sepa_debit_item_mandate_id;
ALTER TABLE sepa_debit_item ADD CONSTRAINT fk_sepa_debit_item_mandate_id
    FOREIGN KEY (mandate_id) REFERENCES sepa_mandate(id);
ALTER TABLE sepa_debit_item DROP CONSTRAINT IF EXISTS fk_sepa_debit_item_journal_entry_id;
ALTER TABLE sepa_debit_item ADD CONSTRAINT fk_sepa_debit_item_journal_entry_id
    FOREIGN KEY (journal_entry_id) REFERENCES journal_entry(id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_sepa_debit_item_batch_contribution
    ON sepa_debit_item (batch_id, contribution_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_sepa_debit_item_batch_e2e
    ON sepa_debit_item (batch_id, end_to_end_id);
CREATE INDEX IF NOT EXISTS idx_sepa_debit_item_status ON sepa_debit_item (status);
CREATE INDEX IF NOT EXISTS idx_sepa_debit_item_mandate ON sepa_debit_item (mandate_id);
CREATE INDEX IF NOT EXISTS idx_sepa_debit_item_contribution ON sepa_debit_item (contribution_id);

-- ---------------------------------------------------------------------------
-- sepa_return
-- uq_sepa_return_debit_item: exactly ONE return per item. The idempotency anchor against a double
-- recordReturn -- a DB constraint, not only a service-level check.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sepa_return (
    id               UUID          NOT NULL PRIMARY KEY,
    debit_item_id    UUID          NOT NULL,
    returned_at      DATE          NOT NULL,
    reason_code      VARCHAR(5)    NOT NULL,
    reason_text      VARCHAR(500)  NULL,
    return_fee       DECIMAL(12,2) NULL,
    recorded_by      UUID          NOT NULL,
    recorded_at      TIMESTAMP     NOT NULL
);

ALTER TABLE sepa_return DROP CONSTRAINT IF EXISTS chk_sepa_return_reason_code;
ALTER TABLE sepa_return ADD CONSTRAINT chk_sepa_return_reason_code
    CHECK (reason_code IN ('AC01','AC04','AC06','AC13','AG01','AM04','MD01','MD06','MD07','MS02','MS03','SL01','OTHER'));

ALTER TABLE sepa_return DROP CONSTRAINT IF EXISTS fk_sepa_return_debit_item_id;
ALTER TABLE sepa_return ADD CONSTRAINT fk_sepa_return_debit_item_id
    FOREIGN KEY (debit_item_id) REFERENCES sepa_debit_item(id);
ALTER TABLE sepa_return DROP CONSTRAINT IF EXISTS fk_sepa_return_recorded_by;
ALTER TABLE sepa_return ADD CONSTRAINT fk_sepa_return_recorded_by
    FOREIGN KEY (recorded_by) REFERENCES member(id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_sepa_return_debit_item ON sepa_return (debit_item_id);
CREATE INDEX IF NOT EXISTS idx_sepa_return_returned_at ON sepa_return (returned_at);

-- ---------------------------------------------------------------------------
-- contribution.sepa_mandate_id -- mirrors the V1__baseline.sql in-place edit. The FK is
-- DELIBERATELY set only here (not in V7), because sepa_mandate did not exist yet.
-- ---------------------------------------------------------------------------
ALTER TABLE contribution ADD COLUMN IF NOT EXISTS sepa_mandate_id UUID NULL;
ALTER TABLE contribution DROP CONSTRAINT IF EXISTS fk_contribution_sepa_mandate_id;
ALTER TABLE contribution ADD CONSTRAINT fk_contribution_sepa_mandate_id
    FOREIGN KEY (sepa_mandate_id) REFERENCES sepa_mandate(id);

-- ---------------------------------------------------------------------------
-- organization_settings -- the three SEPA configuration values V1.2.1 deliberately deferred (see
-- that release's CHANGELOG "Deviations"). sepa_creditor_id is NULL until the Gläubiger-
-- Identifikationsnummer is applied for at the Deutsche Bundesbank (E-11) -- generateBatchFile then
-- refuses with an actionable message, everything else works. Settable ONLY via
-- ISepaService.updateSepaCreditorSettings, NEVER via IOrganizationSettingsService
-- .updateOrganizationSettings -- same carve-out as auction_enabled/sepa_debit_enabled.
-- ---------------------------------------------------------------------------
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS sepa_creditor_id          VARCHAR(35) NULL;
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS sepa_creditor_name        VARCHAR(70) NULL;
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS sepa_prenotification_days INT NOT NULL DEFAULT 14;

ALTER TABLE organization_settings DROP CONSTRAINT IF EXISTS chk_organization_settings_sepa_prenotification_days;
ALTER TABLE organization_settings ADD CONSTRAINT chk_organization_settings_sepa_prenotification_days
    CHECK (sepa_prenotification_days >= 1 AND sepa_prenotification_days <= 30);

-- ---------------------------------------------------------------------------
-- audit_log_entry.entity_type CHECK widening for SEPA_MANDATE/SEPA_DEBIT_BATCH. Dual-DROP pattern
-- as V4/V6/V7. Longest new literal SEPA_DEBIT_BATCH (16) < CONFERENCE_STREAM_DESTINATION (29) =
-- VARCHAR(29) -- no column-width change. VERIFY WITH `\d audit_log_entry` ON pdv2 BEFORE DEPLOY.
-- ---------------------------------------------------------------------------
ALTER TABLE audit_log_entry DROP CONSTRAINT IF EXISTS audit_log_entry_entity_type_check;
ALTER TABLE audit_log_entry DROP CONSTRAINT IF EXISTS chk_audit_log_entry_entity_type;
ALTER TABLE audit_log_entry ADD CONSTRAINT chk_audit_log_entry_entity_type
    CHECK (entity_type IN (
        'JOURNAL_ENTRY', 'PARTY_DONATION_VERDICT', 'RESOLUTION', 'BOARD_MEMBERSHIP',
        'CONFERENCE_RECORDING', 'CONFERENCE_STREAM', 'CONFERENCE_STREAM_DESTINATION', 'CONFERENCE_ROOM',
        'SOCIAL_POST', 'ORGANIZATION_SETTINGS', 'SEPA_MANDATE', 'SEPA_DEBIT_BATCH'
    ));
