-- V1.9.41 -- Konsensieren-Modi in Umfragen (poll kinds). V1..V68 untouched. Purely additive, idempotent, H2
-- MODE=PostgreSQL- and Postgres-compatible (DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT, no partial or
-- expression indexes, no DO block).
--
-- ANONYMITY MODEL (do NOT "repair"): poll_participation says WHO answered, poll_response says THAT (and for the
-- classic kind WHAT) was answered, poll_response_rating says HOW MUCH resistance was rated per option. The rating
-- table has deliberately NO member_id and NO time column, random UUIDv4 ids. For the consensus kinds a response row
-- carries no option and weight 0 (no LTR is involved).

ALTER TABLE poll ADD COLUMN IF NOT EXISTS kind VARCHAR(20) DEFAULT 'SINGLE_CHOICE' NOT NULL;
ALTER TABLE poll DROP CONSTRAINT IF EXISTS chk_poll_kind;
ALTER TABLE poll ADD CONSTRAINT chk_poll_kind CHECK (kind IN ('SINGLE_CHOICE', 'SK_DECISION', 'SK_PRIORITY'));

ALTER TABLE poll_option ADD COLUMN IF NOT EXISTS explanation VARCHAR(1000) NULL;
ALTER TABLE poll_option ADD COLUMN IF NOT EXISTS is_passive BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE poll_option DROP CONSTRAINT IF EXISTS chk_poll_option_position;
ALTER TABLE poll_option ADD CONSTRAINT chk_poll_option_position
    CHECK ((is_passive = FALSE AND position BETWEEN 0 AND 9) OR (is_passive = TRUE AND position = 10));
ALTER TABLE poll_option DROP CONSTRAINT IF EXISTS chk_poll_option_passive_no_explanation;
ALTER TABLE poll_option ADD CONSTRAINT chk_poll_option_passive_no_explanation CHECK (is_passive = FALSE OR explanation IS NULL);
-- uq_poll_option_position (poll_id, position) stays -> at most ONE passive option per poll, enforced by the database.

ALTER TABLE poll_response ALTER COLUMN option_id DROP NOT NULL;
ALTER TABLE poll_response DROP CONSTRAINT IF EXISTS chk_poll_response_option_or_unweighted;
ALTER TABLE poll_response ADD CONSTRAINT chk_poll_response_option_or_unweighted CHECK (option_id IS NOT NULL OR weight_ltr = 0);

CREATE TABLE IF NOT EXISTS poll_response_rating (
    id           UUID NOT NULL PRIMARY KEY,
    response_id  UUID NOT NULL,
    option_id    UUID NOT NULL,
    resistance   INT  NOT NULL
);

ALTER TABLE poll_response_rating DROP CONSTRAINT IF EXISTS fk_poll_response_rating_response;
ALTER TABLE poll_response_rating ADD CONSTRAINT fk_poll_response_rating_response FOREIGN KEY (response_id) REFERENCES poll_response(id);

ALTER TABLE poll_response_rating DROP CONSTRAINT IF EXISTS fk_poll_response_rating_option;
ALTER TABLE poll_response_rating ADD CONSTRAINT fk_poll_response_rating_option FOREIGN KEY (option_id) REFERENCES poll_option(id);

ALTER TABLE poll_response_rating DROP CONSTRAINT IF EXISTS chk_poll_response_rating_resistance;
ALTER TABLE poll_response_rating ADD CONSTRAINT chk_poll_response_rating_resistance CHECK (resistance BETWEEN 0 AND 10);

CREATE UNIQUE INDEX IF NOT EXISTS uq_poll_response_rating_response_option ON poll_response_rating (response_id, option_id);
CREATE INDEX IF NOT EXISTS idx_poll_response_rating_option ON poll_response_rating (option_id);
