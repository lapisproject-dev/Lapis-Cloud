-- V1.9.35 -- see 39-events.kuml.kts header addendum. V1..V65 untouched.
-- Records that the board paid a refund OUTSIDE Lapis Cloud for a paid-but-withdrawn event
-- registration. Moves no money, posts no booking. Idempotent (IF NOT EXISTS / DROP-then-ADD),
-- H2-portable (no partial index, no ON DELETE CASCADE).

ALTER TABLE event_registration ADD COLUMN IF NOT EXISTS refund_marked_at TIMESTAMP NULL;
ALTER TABLE event_registration ADD COLUMN IF NOT EXISTS refund_marked_by UUID NULL;

ALTER TABLE event_registration DROP CONSTRAINT IF EXISTS fk_event_registration_refund_marked_by;
ALTER TABLE event_registration ADD CONSTRAINT fk_event_registration_refund_marked_by
    FOREIGN KEY (refund_marked_by) REFERENCES member(id);

ALTER TABLE event_registration DROP CONSTRAINT IF EXISTS chk_event_registration_refund_marked_pair;
ALTER TABLE event_registration ADD CONSTRAINT chk_event_registration_refund_marked_pair
    CHECK ((refund_marked_at IS NULL) = (refund_marked_by IS NULL));

-- Defensive second line: a marker may only sit on an inactive registration.
ALTER TABLE event_registration DROP CONSTRAINT IF EXISTS chk_event_registration_refund_marked_status;
ALTER TABLE event_registration ADD CONSTRAINT chk_event_registration_refund_marked_status
    CHECK (refund_marked_at IS NULL OR status IN ('CANCELLED', 'EXPIRED'));
