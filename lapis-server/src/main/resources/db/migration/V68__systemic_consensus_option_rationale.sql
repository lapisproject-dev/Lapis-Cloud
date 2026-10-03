-- V1.9.39 -- see 09-systemic-consensus.kuml.kts addendum. V1..V67 untouched. Optional free-text rationale of a proposal,
-- written only in COLLECTION by its proposer or a manager; NULL = none. Idempotent and H2-portable.
ALTER TABLE systemic_consensus_option ADD COLUMN IF NOT EXISTS rationale VARCHAR(1000) NULL;
