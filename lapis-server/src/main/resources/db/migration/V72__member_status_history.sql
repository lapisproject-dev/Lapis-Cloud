-- Welle V1.9.59 -- Mitgliederzahlen ueber Zeit (historisch korrekt). V1..V71 untouched. Purely additive, H2 MODE=PostgreSQL- and
-- Postgres-compatible (CREATE ... IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT, no DO block, no partial or
-- expression indexes, no JSON functions, no gen_random_uuid()/RANDOM_UUID()). The member table stays byte-identical (no UPDATE on it);
-- audit_log_entry, membership_agreement_acknowledgment and friend_terms_acknowledgment are only READ.
--
-- member_status_history is an append-only log of status changes: one row per change of member.status. Primary key is
-- (member_id, effective_from), no surrogate id. previous_status is the status of the member's previous row (NULL = the first row), so a
-- count at an instant is the sum over all rows before that instant of (+1 for status, -1 for previous_status) -- a single GROUP BY, no
-- window function. effective_from is a class-A timestamp (UTC): the moment the change was RECORDED, not necessarily its legal effect.
-- recorded_at is NULL exactly for rows reconstructed by this migration (source BACKFILL_*), set for live rows (LIVE/IMPORT/SEED).
--
-- Backfill (below, fixed order, each INSERT guarded by NOT EXISTS on the primary key, so the order is the priority):
--   B1 audit-log status changes, B2/B3 friend terms acknowledgment / friend_since, B4 membership agreement acknowledgment,
--   B5 reviewed_at (decision on an application), B6 date_of_death, B7 anchor rows, B8 members without any evidence,
--   B9 closing row if the latest row differs from member.status, B10 previous_status recomputed from the real predecessor,
--   B11 rows that do not change the status are dropped.
-- Date sources (joined_at, friend_since, date_of_death) are DATEs: noon UTC (CAST(d AS TIMESTAMP) + 12 HOUR) is used -- pure
-- arithmetic, independent of the process zone, and the same calendar day for every organization zone from UTC-12 to UTC+11.
-- Nothing is guessed: a status that cannot be extracted from an audit snapshot is skipped. The voluntary withdrawal
-- (leaveMembership) leaves neither audit entry nor date, so its instant is not recoverable (it lands right behind the last evidence).

CREATE TABLE IF NOT EXISTS member_status_history (
    member_id        UUID         NOT NULL,
    effective_from   TIMESTAMP    NOT NULL,
    status           VARCHAR(11)  NOT NULL,
    previous_status  VARCHAR(11)  NULL,
    source           VARCHAR(16)  NOT NULL,
    recorded_at      TIMESTAMP    NULL,
    CONSTRAINT pk_member_status_history PRIMARY KEY (member_id, effective_from)
);

ALTER TABLE member_status_history DROP CONSTRAINT IF EXISTS fk_member_status_history_member;
ALTER TABLE member_status_history ADD CONSTRAINT fk_member_status_history_member
    FOREIGN KEY (member_id) REFERENCES member(id);

ALTER TABLE member_status_history DROP CONSTRAINT IF EXISTS chk_member_status_history_status;
ALTER TABLE member_status_history ADD CONSTRAINT chk_member_status_history_status
    CHECK (status IN ('APPLICATION', 'ACTIVE', 'GUEST', 'WITHDRAWN', 'REJECTED', 'FRIEND', 'DONOR', 'DECEASED'));

ALTER TABLE member_status_history DROP CONSTRAINT IF EXISTS chk_member_status_history_previous;
ALTER TABLE member_status_history ADD CONSTRAINT chk_member_status_history_previous
    CHECK (previous_status IS NULL OR previous_status IN ('APPLICATION', 'ACTIVE', 'GUEST', 'WITHDRAWN', 'REJECTED', 'FRIEND', 'DONOR', 'DECEASED'));

ALTER TABLE member_status_history DROP CONSTRAINT IF EXISTS chk_member_status_history_source;
ALTER TABLE member_status_history ADD CONSTRAINT chk_member_status_history_source
    CHECK (source IN ('LIVE', 'IMPORT', 'SEED', 'BACKFILL_AUDIT', 'BACKFILL_RECORD', 'BACKFILL_ASSUMED'));

-- The explicit IS NULL / IS NOT NULL on both branches (a CHECK that evaluates to UNKNOWN passes).
ALTER TABLE member_status_history DROP CONSTRAINT IF EXISTS chk_member_status_history_recorded;
ALTER TABLE member_status_history ADD CONSTRAINT chk_member_status_history_recorded CHECK (
    (source IN ('BACKFILL_AUDIT', 'BACKFILL_RECORD', 'BACKFILL_ASSUMED') AND recorded_at IS NULL)
    OR
    (source IN ('LIVE', 'IMPORT', 'SEED') AND recorded_at IS NOT NULL)
);

-- (member_id, effective_from) is already indexed by the primary key; this one serves the time-range aggregation.
CREATE INDEX IF NOT EXISTS idx_member_status_history_effective_from ON member_status_history (effective_from);

-- B1: status changes recorded in the hash-chained audit log (only MemberChangeSnapshot carries a "status" key; a "status" inside
-- free text is JSON-escaped as \"status\" and cannot match the pattern). A CREATE has no before snapshot (previous_status NULL).
INSERT INTO member_status_history (member_id, effective_from, status, previous_status, source, recorded_at)
SELECT s.entity_id, s.occurred_at, MAX(s.after_status), MAX(s.before_status), 'BACKFILL_AUDIT', NULL
FROM (
    SELECT a.entity_id AS entity_id, a.occurred_at AS occurred_at,
        CASE
            WHEN a.after_snapshot LIKE '%"status":"APPLICATION"%' OR a.after_snapshot LIKE '%"status": "APPLICATION"%' THEN 'APPLICATION'
            WHEN a.after_snapshot LIKE '%"status":"ACTIVE"%' OR a.after_snapshot LIKE '%"status": "ACTIVE"%' THEN 'ACTIVE'
            WHEN a.after_snapshot LIKE '%"status":"GUEST"%' OR a.after_snapshot LIKE '%"status": "GUEST"%' THEN 'GUEST'
            WHEN a.after_snapshot LIKE '%"status":"WITHDRAWN"%' OR a.after_snapshot LIKE '%"status": "WITHDRAWN"%' THEN 'WITHDRAWN'
            WHEN a.after_snapshot LIKE '%"status":"REJECTED"%' OR a.after_snapshot LIKE '%"status": "REJECTED"%' THEN 'REJECTED'
            WHEN a.after_snapshot LIKE '%"status":"FRIEND"%' OR a.after_snapshot LIKE '%"status": "FRIEND"%' THEN 'FRIEND'
            WHEN a.after_snapshot LIKE '%"status":"DONOR"%' OR a.after_snapshot LIKE '%"status": "DONOR"%' THEN 'DONOR'
            WHEN a.after_snapshot LIKE '%"status":"DECEASED"%' OR a.after_snapshot LIKE '%"status": "DECEASED"%' THEN 'DECEASED'
            ELSE NULL END AS after_status,
        CASE
            WHEN a.before_snapshot LIKE '%"status":"APPLICATION"%' OR a.before_snapshot LIKE '%"status": "APPLICATION"%' THEN 'APPLICATION'
            WHEN a.before_snapshot LIKE '%"status":"ACTIVE"%' OR a.before_snapshot LIKE '%"status": "ACTIVE"%' THEN 'ACTIVE'
            WHEN a.before_snapshot LIKE '%"status":"GUEST"%' OR a.before_snapshot LIKE '%"status": "GUEST"%' THEN 'GUEST'
            WHEN a.before_snapshot LIKE '%"status":"WITHDRAWN"%' OR a.before_snapshot LIKE '%"status": "WITHDRAWN"%' THEN 'WITHDRAWN'
            WHEN a.before_snapshot LIKE '%"status":"REJECTED"%' OR a.before_snapshot LIKE '%"status": "REJECTED"%' THEN 'REJECTED'
            WHEN a.before_snapshot LIKE '%"status":"FRIEND"%' OR a.before_snapshot LIKE '%"status": "FRIEND"%' THEN 'FRIEND'
            WHEN a.before_snapshot LIKE '%"status":"DONOR"%' OR a.before_snapshot LIKE '%"status": "DONOR"%' THEN 'DONOR'
            WHEN a.before_snapshot LIKE '%"status":"DECEASED"%' OR a.before_snapshot LIKE '%"status": "DECEASED"%' THEN 'DECEASED'
            ELSE NULL END AS before_status
    FROM audit_log_entry a
    WHERE a.entity_type = 'MEMBER' AND a.action IN ('UPDATE', 'CREATE')
) s
JOIN member m ON m.id = s.entity_id
WHERE s.after_status IS NOT NULL
  AND (s.before_status IS NULL OR s.before_status <> s.after_status)
GROUP BY s.entity_id, s.occurred_at;

-- B2: first friend terms acknowledgment = the member became a FRIEND.
INSERT INTO member_status_history (member_id, effective_from, status, previous_status, source, recorded_at)
SELECT x.member_id, x.first_at, 'FRIEND', NULL, 'BACKFILL_RECORD', NULL
FROM (
    SELECT f.member_id AS member_id, MIN(f.acknowledged_at) AS first_at
    FROM friend_terms_acknowledgment f
    JOIN member m ON m.id = f.member_id
    GROUP BY f.member_id
) x
WHERE NOT EXISTS (
    SELECT 1 FROM member_status_history h WHERE h.member_id = x.member_id AND h.effective_from = x.first_at
);

-- B3: friend_since where no acknowledgment exists (noon UTC).
INSERT INTO member_status_history (member_id, effective_from, status, previous_status, source, recorded_at)
SELECT m.id, CAST(m.friend_since AS TIMESTAMP) + INTERVAL '12' HOUR, 'FRIEND', NULL, 'BACKFILL_RECORD', NULL
FROM member m
WHERE m.friend_since IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM friend_terms_acknowledgment f WHERE f.member_id = m.id)
  AND NOT EXISTS (
      SELECT 1 FROM member_status_history h
      WHERE h.member_id = m.id AND h.effective_from = CAST(m.friend_since AS TIMESTAMP) + INTERVAL '12' HOUR
  );

-- B4: first membership agreement acknowledgment = the member applied.
INSERT INTO member_status_history (member_id, effective_from, status, previous_status, source, recorded_at)
SELECT x.member_id, x.first_at, 'APPLICATION', NULL, 'BACKFILL_RECORD', NULL
FROM (
    SELECT g.member_id AS member_id, MIN(g.acknowledged_at) AS first_at
    FROM membership_agreement_acknowledgment g
    JOIN member m ON m.id = g.member_id
    GROUP BY g.member_id
) x
WHERE NOT EXISTS (
    SELECT 1 FROM member_status_history h WHERE h.member_id = x.member_id AND h.effective_from = x.first_at
);

-- B5: reviewed_at = the decision on an application: REJECTED / FRIEND (rejection with fall-back) / otherwise ACTIVE (approval).
-- Members whose current status is APPLICATION or GUEST are skipped: the decision they carry may belong to an earlier application.
INSERT INTO member_status_history (member_id, effective_from, status, previous_status, source, recorded_at)
SELECT m.id, m.reviewed_at,
       CASE WHEN m.status = 'REJECTED' THEN 'REJECTED' WHEN m.status = 'FRIEND' THEN 'FRIEND' ELSE 'ACTIVE' END,
       NULL, 'BACKFILL_RECORD', NULL
FROM member m
WHERE m.reviewed_at IS NOT NULL
  AND m.status IN ('ACTIVE', 'WITHDRAWN', 'DONOR', 'DECEASED', 'REJECTED', 'FRIEND')
  AND NOT EXISTS (
      SELECT 1 FROM member_status_history h WHERE h.member_id = m.id AND h.effective_from = m.reviewed_at
  );

-- B6: date_of_death of a DECEASED member (noon UTC).
INSERT INTO member_status_history (member_id, effective_from, status, previous_status, source, recorded_at)
SELECT m.id, CAST(m.date_of_death AS TIMESTAMP) + INTERVAL '12' HOUR, 'DECEASED', NULL, 'BACKFILL_RECORD', NULL
FROM member m
WHERE m.status = 'DECEASED' AND m.date_of_death IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM member_status_history h
      WHERE h.member_id = m.id AND h.effective_from = CAST(m.date_of_death AS TIMESTAMP) + INTERVAL '12' HOUR
  );

-- B7 case a: the first row is an audit change with a known before status -> that before status is the anchor (proven).
-- The anchor is placed at the earlier of joined_at (noon UTC) and one second before the first evidence.
INSERT INTO member_status_history (member_id, effective_from, status, previous_status, source, recorded_at)
SELECT h.member_id,
       CASE WHEN CAST(m.joined_at AS TIMESTAMP) + INTERVAL '12' HOUR < h.effective_from
            THEN CAST(m.joined_at AS TIMESTAMP) + INTERVAL '12' HOUR
            ELSE h.effective_from - INTERVAL '1' SECOND END,
       h.previous_status, NULL, 'BACKFILL_AUDIT', NULL
FROM member_status_history h
JOIN member m ON m.id = h.member_id
WHERE h.source = 'BACKFILL_AUDIT' AND h.previous_status IS NOT NULL
  AND h.effective_from = (SELECT MIN(h2.effective_from) FROM member_status_history h2 WHERE h2.member_id = h.member_id);

-- B7 case c: the first row is the decision on an application and no acknowledgment exists -> APPLICATION is proven (approving and
-- rejecting are only possible from APPLICATION).
INSERT INTO member_status_history (member_id, effective_from, status, previous_status, source, recorded_at)
SELECT h.member_id,
       CASE WHEN CAST(m.joined_at AS TIMESTAMP) + INTERVAL '12' HOUR < h.effective_from
            THEN CAST(m.joined_at AS TIMESTAMP) + INTERVAL '12' HOUR
            ELSE h.effective_from - INTERVAL '1' SECOND END,
       'APPLICATION', NULL, 'BACKFILL_RECORD', NULL
FROM member_status_history h
JOIN member m ON m.id = h.member_id
WHERE h.source = 'BACKFILL_RECORD' AND h.effective_from = m.reviewed_at
  AND h.effective_from = (SELECT MIN(h2.effective_from) FROM member_status_history h2 WHERE h2.member_id = h.member_id);

-- B8: members without any evidence: the current status counts from the earliest date that proves this status (DECEASED from
-- date_of_death, FRIEND from friend_since, everything else from joined_at); an assumption (BACKFILL_ASSUMED).
INSERT INTO member_status_history (member_id, effective_from, status, previous_status, source, recorded_at)
SELECT m.id,
       CASE WHEN m.status = 'DECEASED' AND m.date_of_death IS NOT NULL THEN CAST(m.date_of_death AS TIMESTAMP) + INTERVAL '12' HOUR
            WHEN m.status = 'FRIEND' AND m.friend_since IS NOT NULL THEN CAST(m.friend_since AS TIMESTAMP) + INTERVAL '12' HOUR
            ELSE CAST(m.joined_at AS TIMESTAMP) + INTERVAL '12' HOUR END,
       m.status, NULL, 'BACKFILL_ASSUMED', NULL
FROM member m
WHERE NOT EXISTS (SELECT 1 FROM member_status_history h WHERE h.member_id = m.id);

-- B9: the latest row differs from the current status (e.g. a voluntary withdrawal, which leaves no trace): close the chain one
-- second after the last evidence.
INSERT INTO member_status_history (member_id, effective_from, status, previous_status, source, recorded_at)
SELECT h.member_id, h.effective_from + INTERVAL '1' SECOND, m.status, NULL, 'BACKFILL_ASSUMED', NULL
FROM member_status_history h
JOIN member m ON m.id = h.member_id
WHERE h.effective_from = (SELECT MAX(h2.effective_from) FROM member_status_history h2 WHERE h2.member_id = h.member_id)
  AND h.status <> m.status;

-- B10: previous_status of EVERY row from its real predecessor (NULL for the first row).
UPDATE member_status_history h
SET previous_status = (
    SELECT p.status FROM member_status_history p
    WHERE p.member_id = h.member_id
      AND p.effective_from = (
          SELECT MAX(q.effective_from) FROM member_status_history q
          WHERE q.member_id = h.member_id AND q.effective_from < h.effective_from
      )
);

-- B11: a row that repeats its predecessor's status is no change. The successor already carries the same previous_status.
DELETE FROM member_status_history WHERE previous_status = status;
