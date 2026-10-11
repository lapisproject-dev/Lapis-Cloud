-- Welle V1.9.103 "Gremien, Welle C" -- an election result becomes an office: the tally seats the winners and (policy REPLACE) ends the previous
-- holders of the role. V1..V83 untouched. Purely additive, H2 MODE=PostgreSQL- and Postgres-compatible (ADD COLUMN IF NOT EXISTS,
-- CREATE INDEX IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT, no DO block).
--
-- A dedicated V84 rather than an edit of V83: V83 is already applied on local H2/dev databases, an edit in place would break its checksum.
--
--   * election.incumbent_policy: NULL for every election opened before this wave and for YES_NO elections (old tally behaviour, unchanged).
--     REPLACE = the previous holders of the target role end with the tally ("Neuwahl"); KEEP = they stay ("Nachwahl"). Immutable after opening.
--   * committee_membership.started_by_election_id / ended_by_election_id: provenance of a seat that an election started / ended. NULL for all
--     existing rows (no backfill). A seat ended by an election never carries an auto_end_reason (different cause), and ended_by needs until.
--   * election_seat_outcome: one row per person and outcome of the succession of one election (append-only; the unique key makes a second
--     succession of the same election a programming error that rolls the transaction back).
--
-- The audit CHECK needs no change: COMMITTEE_MEMBERSHIP is in chk_audit_log_entry_entity_type since V83.

ALTER TABLE election ADD COLUMN IF NOT EXISTS incumbent_policy VARCHAR(8) NULL;

ALTER TABLE election
    DROP CONSTRAINT IF EXISTS chk_election_incumbent_policy;
ALTER TABLE election
    ADD CONSTRAINT chk_election_incumbent_policy
        CHECK (incumbent_policy IS NULL OR incumbent_policy IN ('REPLACE', 'KEEP'));

ALTER TABLE election
    DROP CONSTRAINT IF EXISTS chk_election_incumbent_policy_personnel;
ALTER TABLE election
    ADD CONSTRAINT chk_election_incumbent_policy_personnel
        CHECK (incumbent_policy IS NULL OR election_type IN ('SINGLE_CHOICE', 'MULTI_CHOICE'));

ALTER TABLE committee_membership ADD COLUMN IF NOT EXISTS started_by_election_id UUID NULL;
ALTER TABLE committee_membership ADD COLUMN IF NOT EXISTS ended_by_election_id UUID NULL;

ALTER TABLE committee_membership
    DROP CONSTRAINT IF EXISTS fk_cm_started_by_election;
ALTER TABLE committee_membership
    ADD CONSTRAINT fk_cm_started_by_election FOREIGN KEY (started_by_election_id) REFERENCES election (id);
ALTER TABLE committee_membership
    DROP CONSTRAINT IF EXISTS fk_cm_ended_by_election;
ALTER TABLE committee_membership
    ADD CONSTRAINT fk_cm_ended_by_election FOREIGN KEY (ended_by_election_id) REFERENCES election (id);

ALTER TABLE committee_membership
    DROP CONSTRAINT IF EXISTS chk_committee_membership_end_cause;
ALTER TABLE committee_membership
    ADD CONSTRAINT chk_committee_membership_end_cause
        CHECK (NOT (auto_end_reason IS NOT NULL AND ended_by_election_id IS NOT NULL));

ALTER TABLE committee_membership
    DROP CONSTRAINT IF EXISTS chk_committee_membership_ended_by_needs_until;
ALTER TABLE committee_membership
    ADD CONSTRAINT chk_committee_membership_ended_by_needs_until
        CHECK (ended_by_election_id IS NULL OR until IS NOT NULL);

CREATE INDEX IF NOT EXISTS idx_committee_membership_started_by_election ON committee_membership (started_by_election_id, since);
CREATE INDEX IF NOT EXISTS idx_committee_membership_ended_by_election ON committee_membership (ended_by_election_id, until);

CREATE TABLE IF NOT EXISTS election_seat_outcome
(
    id           UUID PRIMARY KEY,
    election_id  UUID        NOT NULL,
    member_id    UUID        NOT NULL,
    committee_id UUID        NOT NULL,
    role         VARCHAR(20) NOT NULL,
    outcome      VARCHAR(16) NOT NULL,
    reason       VARCHAR(24) NULL,
    effective_on DATE        NOT NULL,
    created_at   TIMESTAMP   NOT NULL,
    CONSTRAINT fk_eso_election FOREIGN KEY (election_id) REFERENCES election (id),
    CONSTRAINT fk_eso_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT fk_eso_committee FOREIGN KEY (committee_id) REFERENCES committee (id),
    CONSTRAINT chk_eso_outcome CHECK (outcome IN ('SEATED', 'CONTINUED', 'ENDED', 'SKIPPED', 'PREDECESSOR_KEPT')),
    CONSTRAINT chk_eso_reason CHECK (reason IS NULL OR reason IN ('MEMBER_NOT_ACTIVE', 'WINNER_SKIPPED', 'SEAT_STARTS_LATER')),
    CONSTRAINT chk_eso_reason_required CHECK ((outcome IN ('SKIPPED', 'PREDECESSOR_KEPT') AND reason IS NOT NULL)
        OR (outcome NOT IN ('SKIPPED', 'PREDECESSOR_KEPT') AND reason IS NULL)),
    CONSTRAINT uq_eso_election_member_outcome UNIQUE (election_id, member_id, outcome)
);
CREATE INDEX IF NOT EXISTS idx_election_seat_outcome_election ON election_seat_outcome (election_id);
CREATE INDEX IF NOT EXISTS idx_election_seat_outcome_created ON election_seat_outcome (created_at);
