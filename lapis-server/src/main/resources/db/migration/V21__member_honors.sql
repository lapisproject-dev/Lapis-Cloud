-- Welle V1.4.4.3 "Mitgliederlebenszyklus: Ehrungsverwaltung" -- see 41-member-honor.kuml.kts.
-- V20__bank_statement_import.sql wird NICHT angefasst (deployed, Checksum verbraucht) -- gleiche
-- Disziplin, die V20 selbst gegenueber V19 dokumentiert.

CREATE TABLE IF NOT EXISTS member_honor (
    id            UUID          NOT NULL PRIMARY KEY,
    member_id     UUID          NOT NULL,
    category      VARCHAR(19)   NOT NULL,
    title         VARCHAR(200)  NOT NULL,
    awarded_at    DATE          NOT NULL,
    awarded_by    VARCHAR(200)  NULL,
    note          VARCHAR(4000) NULL,
    recorded_by   UUID          NOT NULL,
    recorded_at   TIMESTAMP     NOT NULL
);

ALTER TABLE member_honor DROP CONSTRAINT IF EXISTS fk_member_honor_member;
ALTER TABLE member_honor ADD CONSTRAINT fk_member_honor_member
    FOREIGN KEY (member_id) REFERENCES member(id);

ALTER TABLE member_honor DROP CONSTRAINT IF EXISTS fk_member_honor_recorded_by;
ALTER TABLE member_honor ADD CONSTRAINT fk_member_honor_recorded_by
    FOREIGN KEY (recorded_by) REFERENCES member(id);

ALTER TABLE member_honor DROP CONSTRAINT IF EXISTS chk_member_honor_category;
ALTER TABLE member_honor ADD CONSTRAINT chk_member_honor_category
    CHECK (category IN ('HONORARY_MEMBERSHIP','SERVICE_AWARD','LOYALTY_AWARD','OTHER'));

CREATE INDEX IF NOT EXISTS idx_member_honor_member ON member_honor (member_id, awarded_at);
CREATE INDEX IF NOT EXISTS idx_member_honor_awarded_at ON member_honor (awarded_at);
CREATE INDEX IF NOT EXISTS idx_member_honor_category ON member_honor (category);
