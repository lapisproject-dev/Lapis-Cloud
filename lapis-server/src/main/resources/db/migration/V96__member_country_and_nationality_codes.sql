-- Welle V1.9.121 -- country and nationality as selection lists. V1..V95 untouched.
-- Purely additive, no data is rewritten, H2 MODE=PostgreSQL- and Postgres-compatible.
--
-- A dedicated V96: V95 is the highest migration and is already part of squashed master commits (and applied in local/dev/deployed
-- databases), so changing it would break the Flyway checksum there (same reasoning as the V95 header). V96 itself is not rolled out
-- anywhere yet.
--
--   * member.country_code: ISO 3166-1 alpha-2 (or XK for Kosovo) chosen from the country list; NULL while only the legacy free text
--     member.country exists. The legacy column stays (read-only legacy value) and is cleared when a code is set by a human.
--   * member_nationality: 0..10 rows per member (multiple nationalities). Besides country codes the internal values XS (stateless,
--     ICAO XXA) and ZZ (unclear) are allowed; XS/ZZ exclusivity and the 10-row cap are enforced by the application
--     (MemberCountryStore, under the member row lock) -- a trigger or CHECK with a subquery is not portable between H2 and Postgres.
--     The DB CHECKs below only pin the SHAPE (two upper-case characters); the full list check is server-side.
--   * No ON DELETE CASCADE (house convention, see V29/V30/V32/V62): rows are removed by MemberNationalityPersonalData on erasure.
--   * The legacy columns are NOT migrated: the bulk reconciliation is an explicit board/admin action (dry run + apply).

ALTER TABLE member ADD COLUMN IF NOT EXISTS country_code CHAR(2) NULL;

ALTER TABLE member DROP CONSTRAINT IF EXISTS chk_member_country_code_shape;
ALTER TABLE member ADD CONSTRAINT chk_member_country_code_shape
    CHECK (country_code IS NULL OR (country_code = UPPER(country_code) AND LENGTH(country_code) = 2));

CREATE TABLE IF NOT EXISTS member_nationality (
    member_id    UUID    NOT NULL,
    country_code CHAR(2) NOT NULL,
    CONSTRAINT pk_member_nationality PRIMARY KEY (member_id, country_code)
);

ALTER TABLE member_nationality DROP CONSTRAINT IF EXISTS fk_member_nationality_member;
ALTER TABLE member_nationality ADD CONSTRAINT fk_member_nationality_member
    FOREIGN KEY (member_id) REFERENCES member(id);

ALTER TABLE member_nationality DROP CONSTRAINT IF EXISTS chk_member_nationality_code_shape;
ALTER TABLE member_nationality ADD CONSTRAINT chk_member_nationality_code_shape
    CHECK (country_code = UPPER(country_code) AND LENGTH(country_code) = 2);

CREATE INDEX IF NOT EXISTS idx_member_nationality_country ON member_nationality (country_code);
