-- Welle V1.9.144 "Mitglieder-Finder" (server) -- see 72-member-finder.kuml.kts. V1..V106 untouched.
-- Purely additive, H2 MODE=PostgreSQL- and Postgres-compatible (IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT,
-- no DO block, no partial index, no ON DELETE CASCADE).
--
-- A dedicated V107 rather than an edit of V106: V106 is already on origin/master and migrated on running instances (staging, forks), so
-- changing it would break the Flyway checksum there.
--
--   * organization_settings: the instance switch (default OFF) and the version of the operator notice the admin acknowledged when
--     switching it on. Both are NOT part of the generic updateOrganizationSettings write-set.
--   * agreement_text_version / agreement_text_draft: one further kind FINDER (6 chars); VARCHAR(10) is unchanged, only the two CHECKs grow.
--   * member_finder_consent_event: append-only proof of consent (GRANT / REVOKE). It carries NO region: the region is derived from the
--     postal code on every query and never stored next to a person.
--   * member_finder_request: a request from a member to ONE randomly chosen counterpart. to_member_id is NULL for a PHANTOM request (nobody
--     was found) and for a request whose counterpart link was detached after the retention period. No region column here either.
--   * member_finder_block: blocks set by a member. Only these rows name two members.
--   * member_finder_region_band: the daily aggregate (region key + coarse band). No member column at all.
--   * No audit_log_entry change: opt-in, opt-out, requests and blocks are deliberately NOT audited (the audit chain cannot be erased).

ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS member_finder_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS member_finder_notice_version VARCHAR(16) NULL;

ALTER TABLE agreement_text_version DROP CONSTRAINT IF EXISTS chk_agreement_text_version_kind;
ALTER TABLE agreement_text_version ADD CONSTRAINT chk_agreement_text_version_kind
    CHECK (kind IN ('MEMBERSHIP', 'FRIEND', 'REC_ROOM', 'REC_PULPIT', 'FINDER'));

ALTER TABLE agreement_text_draft DROP CONSTRAINT IF EXISTS chk_agreement_text_draft_kind;
ALTER TABLE agreement_text_draft ADD CONSTRAINT chk_agreement_text_draft_kind
    CHECK (kind IN ('MEMBERSHIP', 'FRIEND', 'REC_ROOM', 'REC_PULPIT', 'FINDER'));

CREATE TABLE IF NOT EXISTS member_finder_consent_event (
    id               UUID        NOT NULL PRIMARY KEY,
    member_id        UUID        NOT NULL,
    event_type       VARCHAR(6)  NOT NULL,
    occurred_at      TIMESTAMP   NOT NULL,
    superseded_at    TIMESTAMP   NULL,
    consent_version  VARCHAR(50) NULL,
    consent_sha256   VARCHAR(64) NULL,
    interest         VARCHAR(14) NULL,
    adult_confirmed  BOOLEAN     NOT NULL DEFAULT FALSE,
    revoke_reason    VARCHAR(12) NULL
);

ALTER TABLE member_finder_consent_event DROP CONSTRAINT IF EXISTS chk_member_finder_consent_event_type;
ALTER TABLE member_finder_consent_event ADD CONSTRAINT chk_member_finder_consent_event_type CHECK (event_type IN ('GRANT', 'REVOKE'));

ALTER TABLE member_finder_consent_event DROP CONSTRAINT IF EXISTS chk_member_finder_consent_event_interest;
ALTER TABLE member_finder_consent_event ADD CONSTRAINT chk_member_finder_consent_event_interest
    CHECK (interest IS NULL OR interest IN ('GROUP_FOUNDING', 'MEETUP', 'BOTH'));

ALTER TABLE member_finder_consent_event DROP CONSTRAINT IF EXISTS chk_member_finder_consent_event_reason;
ALTER TABLE member_finder_consent_event ADD CONSTRAINT chk_member_finder_consent_event_reason
    CHECK (revoke_reason IS NULL OR revoke_reason IN ('MEMBER', 'STATUS_LOST', 'INSTANCE_OFF'));

ALTER TABLE member_finder_consent_event DROP CONSTRAINT IF EXISTS chk_member_finder_consent_event_shape;
ALTER TABLE member_finder_consent_event ADD CONSTRAINT chk_member_finder_consent_event_shape
    CHECK (
        (event_type = 'GRANT' AND consent_version IS NOT NULL AND consent_sha256 IS NOT NULL AND CHAR_LENGTH(consent_sha256) = 64
            AND interest IS NOT NULL AND adult_confirmed = TRUE AND revoke_reason IS NULL)
        OR
        (event_type = 'REVOKE' AND interest IS NULL AND revoke_reason IS NOT NULL)
    );

ALTER TABLE member_finder_consent_event DROP CONSTRAINT IF EXISTS fk_member_finder_consent_event_member;
ALTER TABLE member_finder_consent_event ADD CONSTRAINT fk_member_finder_consent_event_member FOREIGN KEY (member_id) REFERENCES member (id);

CREATE INDEX IF NOT EXISTS idx_member_finder_consent_event_member ON member_finder_consent_event (member_id, superseded_at);

CREATE TABLE IF NOT EXISTS member_finder_request (
    id              UUID         NOT NULL PRIMARY KEY,
    from_member_id  UUID         NOT NULL,
    to_member_id    UUID         NULL,
    purpose         VARCHAR(14)  NOT NULL,
    note            VARCHAR(200) NULL,
    status          VARCHAR(9)   NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    expires_at      TIMESTAMP    NOT NULL,
    decided_at      TIMESTAMP    NULL,
    sender_withdrawn_at TIMESTAMP NULL
);

ALTER TABLE member_finder_request DROP CONSTRAINT IF EXISTS chk_member_finder_request_purpose;
ALTER TABLE member_finder_request ADD CONSTRAINT chk_member_finder_request_purpose CHECK (purpose IN ('GROUP_FOUNDING', 'MEETUP'));

ALTER TABLE member_finder_request DROP CONSTRAINT IF EXISTS chk_member_finder_request_status;
ALTER TABLE member_finder_request ADD CONSTRAINT chk_member_finder_request_status
    CHECK (status IN ('OPEN', 'ACCEPTED', 'DECLINED', 'WITHDRAWN', 'EXPIRED'));

ALTER TABLE member_finder_request DROP CONSTRAINT IF EXISTS chk_member_finder_request_decided;
ALTER TABLE member_finder_request ADD CONSTRAINT chk_member_finder_request_decided
    CHECK ((status = 'OPEN' AND decided_at IS NULL) OR (status <> 'OPEN' AND decided_at IS NOT NULL));

ALTER TABLE member_finder_request DROP CONSTRAINT IF EXISTS chk_member_finder_request_not_self;
ALTER TABLE member_finder_request ADD CONSTRAINT chk_member_finder_request_not_self CHECK (to_member_id IS NULL OR to_member_id <> from_member_id);

ALTER TABLE member_finder_request DROP CONSTRAINT IF EXISTS chk_member_finder_request_accepted_target;
ALTER TABLE member_finder_request ADD CONSTRAINT chk_member_finder_request_accepted_target CHECK (status <> 'ACCEPTED' OR to_member_id IS NOT NULL);

ALTER TABLE member_finder_request DROP CONSTRAINT IF EXISTS chk_member_finder_request_expiry;
ALTER TABLE member_finder_request ADD CONSTRAINT chk_member_finder_request_expiry CHECK (expires_at > created_at);

ALTER TABLE member_finder_request DROP CONSTRAINT IF EXISTS fk_member_finder_request_from;
ALTER TABLE member_finder_request ADD CONSTRAINT fk_member_finder_request_from FOREIGN KEY (from_member_id) REFERENCES member (id);

ALTER TABLE member_finder_request DROP CONSTRAINT IF EXISTS fk_member_finder_request_to;
ALTER TABLE member_finder_request ADD CONSTRAINT fk_member_finder_request_to FOREIGN KEY (to_member_id) REFERENCES member (id);

CREATE INDEX IF NOT EXISTS idx_member_finder_request_to ON member_finder_request (to_member_id, status);
CREATE INDEX IF NOT EXISTS idx_member_finder_request_from ON member_finder_request (from_member_id, status);
CREATE INDEX IF NOT EXISTS idx_member_finder_request_expiry ON member_finder_request (status, expires_at);

CREATE TABLE IF NOT EXISTS member_finder_block (
    id                 UUID      NOT NULL PRIMARY KEY,
    blocker_member_id  UUID      NOT NULL,
    blocked_member_id  UUID      NOT NULL,
    created_at         TIMESTAMP NOT NULL
);

ALTER TABLE member_finder_block DROP CONSTRAINT IF EXISTS uq_member_finder_block_pair;
ALTER TABLE member_finder_block ADD CONSTRAINT uq_member_finder_block_pair UNIQUE (blocker_member_id, blocked_member_id);

ALTER TABLE member_finder_block DROP CONSTRAINT IF EXISTS chk_member_finder_block_not_self;
ALTER TABLE member_finder_block ADD CONSTRAINT chk_member_finder_block_not_self CHECK (blocker_member_id <> blocked_member_id);

ALTER TABLE member_finder_block DROP CONSTRAINT IF EXISTS fk_member_finder_block_blocker;
ALTER TABLE member_finder_block ADD CONSTRAINT fk_member_finder_block_blocker FOREIGN KEY (blocker_member_id) REFERENCES member (id);

ALTER TABLE member_finder_block DROP CONSTRAINT IF EXISTS fk_member_finder_block_blocked;
ALTER TABLE member_finder_block ADD CONSTRAINT fk_member_finder_block_blocked FOREIGN KEY (blocked_member_id) REFERENCES member (id);

CREATE INDEX IF NOT EXISTS idx_member_finder_block_blocked ON member_finder_block (blocked_member_id);

CREATE TABLE IF NOT EXISTS member_finder_region_band (
    region_key   VARCHAR(8) NOT NULL PRIMARY KEY,
    band         VARCHAR(8) NOT NULL,
    computed_at  TIMESTAMP  NOT NULL
);

ALTER TABLE member_finder_region_band DROP CONSTRAINT IF EXISTS chk_member_finder_region_band_band;
ALTER TABLE member_finder_region_band ADD CONSTRAINT chk_member_finder_region_band_band CHECK (band IN ('B5_9', 'B10_19', 'B20_PLUS'));
