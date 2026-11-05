-- V1.9.283 "Rechenschaftsbericht (PartG), R3b": statement C (sponsoring) and F (youth organisations). See 76-partg-report.kuml.kts and
-- docs/features/partg-report.adoc. V1..V141 untouched.
-- Purely additive and repeatable: CREATE TABLE IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT, no partial index, no ON
-- DELETE CASCADE, H2 MODE=PostgreSQL- and PostgreSQL-compatible. While this wave is unreleased V142 may still be corrected in place on the
-- feature branch; after the release every correction is a NEW migration. V139 is on master and is NOT edited.
--
--   * partg_report_sponsor: statement C, one row per sponsor and annex (unit and year). ALL sponsors are stored, also those under both limits:
--     the yearly sum of 6,000.00 EUR cannot be computed otherwise. Whether a sponsor is part of the report is COMPUTED (PartgStatementRules),
--     never enforced on save, so there is deliberately no amount CHECK for the limits.
--   * partg_report_sponsoring_entry: one service of a sponsor (date, amount, purpose). No annex_id on purpose: a second assignment could
--     contradict the one of the sponsor.
--   * partg_report_youth_org: statement F, minimal model (name, relationship to the party, income, expenses; the result is computed).
--
-- NOT expressible in the ERM profile (SQL only): every CHECK below and the row limits (500 sponsors, 100 entries per sponsor, 2000 entries per
-- annex, 20 youth organisations), which the service checks under the lock of the annex row (PartgAnnexWriter). Also checked only in the service:
-- the date of an entry lies in the report year (the year is stored in another table) and an entry belongs to the sponsor of the same annex.
-- DSGVO: partg_report_sponsor and partg_report_sponsoring_entry (purpose as free text, amount) hold PERSONAL DATA OF THIRD PARTIES; they have
-- no member foreign key and are registered in PersonalDataRegistry.knownUncoveredSubjectRoots. partg_report_youth_org holds organisation data.
-- created_by / updated_by of all three tables are "changed by you" data.
-- audit_log_entry.entity_type: the existing PARTG_REPORT_ANNEX is reused with new change codes, so the CHECK list stays untouched.

CREATE TABLE IF NOT EXISTS partg_report_sponsor (
    id           UUID          NOT NULL PRIMARY KEY,
    annex_id     UUID          NOT NULL REFERENCES partg_report_annex (id),
    person_kind  VARCHAR(7)    NOT NULL,
    name         VARCHAR(200)  NOT NULL,
    street       VARCHAR(200)  NULL,
    postal_code  VARCHAR(10)   NULL,
    city         VARCHAR(100)  NULL,
    country      VARCHAR(2)    NOT NULL DEFAULT 'DE',
    created_at   TIMESTAMP     NOT NULL,
    created_by   UUID          NOT NULL REFERENCES member (id),
    updated_at   TIMESTAMP     NOT NULL,
    updated_by   UUID          NOT NULL REFERENCES member (id)
);

ALTER TABLE partg_report_sponsor DROP CONSTRAINT IF EXISTS chk_partg_sponsor_person_kind;
ALTER TABLE partg_report_sponsor ADD CONSTRAINT chk_partg_sponsor_person_kind CHECK (person_kind IN ('NATURAL', 'LEGAL'));
ALTER TABLE partg_report_sponsor DROP CONSTRAINT IF EXISTS chk_partg_sponsor_name_len;
ALTER TABLE partg_report_sponsor ADD CONSTRAINT chk_partg_sponsor_name_len CHECK (CHAR_LENGTH(name) BETWEEN 1 AND 200);
ALTER TABLE partg_report_sponsor DROP CONSTRAINT IF EXISTS chk_partg_sponsor_country;
ALTER TABLE partg_report_sponsor ADD CONSTRAINT chk_partg_sponsor_country CHECK (CHAR_LENGTH(country) = 2);

CREATE INDEX IF NOT EXISTS ix_partg_report_sponsor_annex ON partg_report_sponsor (annex_id);
CREATE INDEX IF NOT EXISTS ix_partg_report_sponsor_created_by ON partg_report_sponsor (created_by);
CREATE INDEX IF NOT EXISTS ix_partg_report_sponsor_updated_by ON partg_report_sponsor (updated_by);

CREATE TABLE IF NOT EXISTS partg_report_sponsoring_entry (
    id            UUID          NOT NULL PRIMARY KEY,
    sponsor_id    UUID          NOT NULL REFERENCES partg_report_sponsor (id),
    sponsored_on  DATE          NOT NULL,
    amount_cents  BIGINT        NOT NULL,
    purpose       VARCHAR(500)  NULL,
    created_at    TIMESTAMP     NOT NULL,
    created_by    UUID          NOT NULL REFERENCES member (id),
    updated_at    TIMESTAMP     NOT NULL,
    updated_by    UUID          NOT NULL REFERENCES member (id)
);

ALTER TABLE partg_report_sponsoring_entry DROP CONSTRAINT IF EXISTS chk_partg_sponsoring_amount;
ALTER TABLE partg_report_sponsoring_entry ADD CONSTRAINT chk_partg_sponsoring_amount CHECK (amount_cents > 0 AND amount_cents <= 99999999999999);
ALTER TABLE partg_report_sponsoring_entry DROP CONSTRAINT IF EXISTS chk_partg_sponsoring_purpose_len;
ALTER TABLE partg_report_sponsoring_entry ADD CONSTRAINT chk_partg_sponsoring_purpose_len
    CHECK (purpose IS NULL OR CHAR_LENGTH(purpose) BETWEEN 1 AND 500);

CREATE INDEX IF NOT EXISTS ix_partg_report_sponsoring_entry_sponsor ON partg_report_sponsoring_entry (sponsor_id);
CREATE INDEX IF NOT EXISTS ix_partg_report_sponsoring_entry_created_by ON partg_report_sponsoring_entry (created_by);
CREATE INDEX IF NOT EXISTS ix_partg_report_sponsoring_entry_updated_by ON partg_report_sponsoring_entry (updated_by);

CREATE TABLE IF NOT EXISTS partg_report_youth_org (
    id            UUID          NOT NULL PRIMARY KEY,
    annex_id      UUID          NOT NULL REFERENCES partg_report_annex (id),
    name          VARCHAR(200)  NOT NULL,
    relationship  VARCHAR(200)  NULL,
    income_cents  BIGINT        NULL,
    expenses_cents BIGINT       NULL,
    created_at    TIMESTAMP     NOT NULL,
    created_by    UUID          NOT NULL REFERENCES member (id),
    updated_at    TIMESTAMP     NOT NULL,
    updated_by    UUID          NOT NULL REFERENCES member (id)
);

ALTER TABLE partg_report_youth_org DROP CONSTRAINT IF EXISTS chk_partg_youth_name_len;
ALTER TABLE partg_report_youth_org ADD CONSTRAINT chk_partg_youth_name_len CHECK (CHAR_LENGTH(name) BETWEEN 1 AND 200);
ALTER TABLE partg_report_youth_org DROP CONSTRAINT IF EXISTS chk_partg_youth_relationship_len;
ALTER TABLE partg_report_youth_org ADD CONSTRAINT chk_partg_youth_relationship_len
    CHECK (relationship IS NULL OR CHAR_LENGTH(relationship) BETWEEN 1 AND 200);
ALTER TABLE partg_report_youth_org DROP CONSTRAINT IF EXISTS chk_partg_youth_income;
ALTER TABLE partg_report_youth_org ADD CONSTRAINT chk_partg_youth_income
    CHECK (income_cents IS NULL OR income_cents BETWEEN 0 AND 99999999999999);
ALTER TABLE partg_report_youth_org DROP CONSTRAINT IF EXISTS chk_partg_youth_expenses;
ALTER TABLE partg_report_youth_org ADD CONSTRAINT chk_partg_youth_expenses
    CHECK (expenses_cents IS NULL OR expenses_cents BETWEEN 0 AND 99999999999999);

CREATE INDEX IF NOT EXISTS ix_partg_report_youth_org_annex ON partg_report_youth_org (annex_id);
CREATE INDEX IF NOT EXISTS ix_partg_report_youth_org_created_by ON partg_report_youth_org (created_by);
CREATE INDEX IF NOT EXISTS ix_partg_report_youth_org_updated_by ON partg_report_youth_org (updated_by);
