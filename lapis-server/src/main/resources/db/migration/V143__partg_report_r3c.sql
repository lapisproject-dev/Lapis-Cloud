-- V1.9.287 "Rechenschaftsbericht (PartG), R3c": statement D (enterprise activity). See 76-partg-report.kuml.kts and
-- docs/features/partg-report.adoc. V1..V142 untouched (V142 shipped with 0.46.0).
-- Purely additive and repeatable: CREATE TABLE IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT, no partial index, no ON
-- DELETE CASCADE, H2 MODE=PostgreSQL- and PostgreSQL-compatible. While this wave is unreleased V143 may still be corrected in place on the
-- feature branch; after the release every correction is a NEW migration.
--
--   * partg_report_enterprise: statement D, one row per own business operation and annex (unit and year). Income and expenses are optional
--     (NULL = not entered, 0 = entered as zero); the result is COMPUTED, never stored. There is no link to partg_report_holding: holdings
--     belong to the organisation (notes sheet), enterprises to a unit.
--
-- NOT expressible in the ERM profile (SQL only): every CHECK below and the row limit (50 enterprises per annex), which the service checks
-- under the lock of the annex row (PartgAnnexWriter).
-- DSGVO: organisation data. created_by / updated_by are "changed by you" data.
-- audit_log_entry.entity_type: the existing PARTG_REPORT_ANNEX is reused with new change codes, so the CHECK list stays untouched.

CREATE TABLE IF NOT EXISTS partg_report_enterprise (
    id                  UUID          NOT NULL PRIMARY KEY,
    annex_id            UUID          NOT NULL REFERENCES partg_report_annex (id),
    name                VARCHAR(200)  NOT NULL,
    legal_form          VARCHAR(60)   NULL,
    seat                VARCHAR(100)  NULL,
    share_basis_points  INTEGER       NULL,
    income_cents        BIGINT        NULL,
    expenses_cents      BIGINT        NULL,
    created_at          TIMESTAMP     NOT NULL,
    created_by          UUID          NOT NULL REFERENCES member (id),
    updated_at          TIMESTAMP     NOT NULL,
    updated_by          UUID          NOT NULL REFERENCES member (id)
);

ALTER TABLE partg_report_enterprise DROP CONSTRAINT IF EXISTS chk_partg_enterprise_name_len;
ALTER TABLE partg_report_enterprise ADD CONSTRAINT chk_partg_enterprise_name_len CHECK (CHAR_LENGTH(name) BETWEEN 1 AND 200);
ALTER TABLE partg_report_enterprise DROP CONSTRAINT IF EXISTS chk_partg_enterprise_legal_form_len;
ALTER TABLE partg_report_enterprise ADD CONSTRAINT chk_partg_enterprise_legal_form_len
    CHECK (legal_form IS NULL OR CHAR_LENGTH(legal_form) BETWEEN 1 AND 60);
ALTER TABLE partg_report_enterprise DROP CONSTRAINT IF EXISTS chk_partg_enterprise_seat_len;
ALTER TABLE partg_report_enterprise ADD CONSTRAINT chk_partg_enterprise_seat_len
    CHECK (seat IS NULL OR CHAR_LENGTH(seat) BETWEEN 1 AND 100);
ALTER TABLE partg_report_enterprise DROP CONSTRAINT IF EXISTS chk_partg_enterprise_share;
ALTER TABLE partg_report_enterprise ADD CONSTRAINT chk_partg_enterprise_share
    CHECK (share_basis_points IS NULL OR share_basis_points BETWEEN 0 AND 10000);
ALTER TABLE partg_report_enterprise DROP CONSTRAINT IF EXISTS chk_partg_enterprise_income;
ALTER TABLE partg_report_enterprise ADD CONSTRAINT chk_partg_enterprise_income
    CHECK (income_cents IS NULL OR income_cents BETWEEN 0 AND 99999999999999);
ALTER TABLE partg_report_enterprise DROP CONSTRAINT IF EXISTS chk_partg_enterprise_expenses;
ALTER TABLE partg_report_enterprise ADD CONSTRAINT chk_partg_enterprise_expenses
    CHECK (expenses_cents IS NULL OR expenses_cents BETWEEN 0 AND 99999999999999);

CREATE INDEX IF NOT EXISTS ix_partg_report_enterprise_annex ON partg_report_enterprise (annex_id);
CREATE INDEX IF NOT EXISTS ix_partg_report_enterprise_created_by ON partg_report_enterprise (created_by);
CREATE INDEX IF NOT EXISTS ix_partg_report_enterprise_updated_by ON partg_report_enterprise (updated_by);
