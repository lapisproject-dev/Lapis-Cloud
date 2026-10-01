-- V1.9.23: server-side integrity of the democratic elections. Additive, no baseline change.
--
-- OPERATOR NOTE: run the pre-deploy checks from CHANGELOG.md first. This migration FAILS (and rolls
-- back) if the data already violates a new constraint, namely two non-aborted elections for one
-- motion, or duplicate option positions within one election.

-- (1) "At most one non-aborted election per motion". A partial unique index (WHERE ...) is not
-- available on H2, so the application maintains this shadow column: it equals motion_id for every
-- election that is not ABORTED and is NULL for ABORTED ones. A unique index ignores NULLs.
ALTER TABLE election ADD COLUMN active_motion_id UUID NULL;
UPDATE election SET active_motion_id = motion_id WHERE status <> 'ABORTED';
CREATE UNIQUE INDEX uq_election_active_motion ON election (active_motion_id);
ALTER TABLE election ADD CONSTRAINT ck_election_active_motion
    CHECK (active_motion_id IS NULL OR active_motion_id = motion_id);
ALTER TABLE election ADD CONSTRAINT ck_election_active_motion_status
    CHECK ((status = 'ABORTED' AND active_motion_id IS NULL) OR (status <> 'ABORTED' AND active_motion_id IS NOT NULL));

-- (2) Required majority as an exact fraction (both NULL = legacy percent path).
ALTER TABLE election ADD COLUMN required_majority_numerator INTEGER NULL;
ALTER TABLE election ADD COLUMN required_majority_denominator INTEGER NULL;
ALTER TABLE election ADD CONSTRAINT ck_election_majority_fraction CHECK (
    (required_majority_numerator IS NULL AND required_majority_denominator IS NULL) OR
    (required_majority_numerator IS NOT NULL AND required_majority_denominator IS NOT NULL
     AND required_majority_numerator >= 1 AND required_majority_denominator <= 100
     AND required_majority_numerator <= required_majority_denominator
     AND 2 * required_majority_numerator >= required_majority_denominator));

-- (3) One tally approval must never be enough. Only elections that are still running are raised;
-- finished ones keep their historical threshold.
UPDATE election SET tally_threshold = 2 WHERE tally_threshold < 2 AND status NOT IN ('TALLIED', 'ABORTED');
ALTER TABLE election ADD CONSTRAINT ck_election_tally_threshold
    CHECK (tally_threshold >= 2 OR status IN ('TALLIED', 'ABORTED'));

-- (4) Secret ballots: a constant time reference, so cast_at cannot be correlated with anything.
UPDATE election_ballot SET cast_at = (
    SELECT COALESCE(e.voting_opened_at, e.opened_at) FROM election e WHERE e.id = election_ballot.election_id)
WHERE election_id IN (SELECT id FROM election WHERE secret = TRUE);

-- (5) A candidate list can only be released once: option positions are unique within an election.
CREATE UNIQUE INDEX uq_election_option_position ON election_option (election_id, position);
