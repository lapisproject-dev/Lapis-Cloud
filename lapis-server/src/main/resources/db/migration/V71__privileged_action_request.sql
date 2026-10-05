-- Welle V1.9.57 -- Admin-Peer-Schutz (Vier-Augen-Prinzip fuer Aktionen gegen ein ADMIN-Konto). V1..V70 untouched. Purely
-- additive, idempotent, H2 MODE=PostgreSQL- and Postgres-compatible (DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT,
-- no partial or expression indexes, no DO block). Existing rows stay byte-identical (no UPDATE here).
--
-- privileged_action_request holds a request of one ADMIN against ANOTHER ADMIN (temporary password, demotion, suspension)
-- from request to resolution; a second ADMIN must approve it. At most ONE open request per (target, action). H2 cannot
-- express a partial unique index, so the V70 substitute is used: open_target_member_id equals target_member_id while the
-- request is open (PENDING or APPROVED_WAITING) and is NULL otherwise, with a plain UNIQUE (open_target_member_id, action)
-- (several NULLs are legal on H2 and PostgreSQL). chk_privileged_action_request_open ties the column to the status, with
-- the explicit IS NOT NULL (a CHECK that evaluates to UNKNOWN passes).
--
-- veto_token_hash holds only a SHA-256 hex digest of the one-time objection link sent to the TARGET (temporary password only).
-- The audit log never carries the reason, a token or an address -- the reason lives only in this erasable table.
--
-- account.role_changed_at (Klasse A, UTC) is stamped by every writer of account.role; NULL = pre-existing / seeded account.
-- The approver tenure rule (an ADMIN may approve only after 7 days in the role) reads it.

CREATE TABLE IF NOT EXISTS privileged_action_request (
    id                      UUID          NOT NULL PRIMARY KEY,
    action                  VARCHAR(24)   NOT NULL,
    actor_member_id         UUID          NOT NULL,
    target_member_id        UUID          NOT NULL,
    open_target_member_id   UUID          NULL,
    target_role_at_request  VARCHAR(16)   NOT NULL,
    requested_role          VARCHAR(16)   NULL,
    requested_status        VARCHAR(24)   NULL,
    reason                  VARCHAR(500)  NOT NULL,
    status                  VARCHAR(20)   NOT NULL,
    approver_member_id      UUID          NULL,
    veto_token_hash         VARCHAR(64)   NULL,
    created_at              TIMESTAMP     NOT NULL,
    expires_at              TIMESTAMP     NOT NULL,
    not_before              TIMESTAMP     NULL,
    execute_until           TIMESTAMP     NULL,
    decided_at              TIMESTAMP     NULL,
    resolved_at             TIMESTAMP     NULL
);

ALTER TABLE privileged_action_request DROP CONSTRAINT IF EXISTS fk_privileged_action_request_actor;
ALTER TABLE privileged_action_request ADD CONSTRAINT fk_privileged_action_request_actor
    FOREIGN KEY (actor_member_id) REFERENCES member(id);

ALTER TABLE privileged_action_request DROP CONSTRAINT IF EXISTS fk_privileged_action_request_target;
ALTER TABLE privileged_action_request ADD CONSTRAINT fk_privileged_action_request_target
    FOREIGN KEY (target_member_id) REFERENCES member(id);

ALTER TABLE privileged_action_request DROP CONSTRAINT IF EXISTS fk_privileged_action_request_approver;
ALTER TABLE privileged_action_request ADD CONSTRAINT fk_privileged_action_request_approver
    FOREIGN KEY (approver_member_id) REFERENCES member(id);

ALTER TABLE privileged_action_request DROP CONSTRAINT IF EXISTS uq_privileged_action_request_open;
ALTER TABLE privileged_action_request ADD CONSTRAINT uq_privileged_action_request_open
    UNIQUE (open_target_member_id, action);

ALTER TABLE privileged_action_request DROP CONSTRAINT IF EXISTS uq_privileged_action_request_veto;
ALTER TABLE privileged_action_request ADD CONSTRAINT uq_privileged_action_request_veto UNIQUE (veto_token_hash);

ALTER TABLE privileged_action_request DROP CONSTRAINT IF EXISTS chk_privileged_action_request_action;
ALTER TABLE privileged_action_request ADD CONSTRAINT chk_privileged_action_request_action
    CHECK (action IN ('TEMP_PASSWORD', 'DEMOTE', 'SUSPEND'));

ALTER TABLE privileged_action_request DROP CONSTRAINT IF EXISTS chk_privileged_action_request_status;
ALTER TABLE privileged_action_request ADD CONSTRAINT chk_privileged_action_request_status
    CHECK (status IN ('PENDING', 'APPROVED_WAITING', 'EXECUTED', 'REJECTED', 'WITHDRAWN', 'VETOED', 'EXPIRED', 'INVALIDATED'));

ALTER TABLE privileged_action_request DROP CONSTRAINT IF EXISTS chk_privileged_action_request_target_role;
ALTER TABLE privileged_action_request ADD CONSTRAINT chk_privileged_action_request_target_role
    CHECK (target_role_at_request IN ('MEMBER', 'BOARD', 'TREASURER', 'ADMIN'));

ALTER TABLE privileged_action_request DROP CONSTRAINT IF EXISTS chk_privileged_action_request_requested_role;
ALTER TABLE privileged_action_request ADD CONSTRAINT chk_privileged_action_request_requested_role
    CHECK (requested_role IS NULL OR requested_role IN ('MEMBER', 'BOARD', 'TREASURER', 'ADMIN'));

ALTER TABLE privileged_action_request DROP CONSTRAINT IF EXISTS chk_privileged_action_request_requested_status;
ALTER TABLE privileged_action_request ADD CONSTRAINT chk_privileged_action_request_requested_status
    CHECK (requested_status IS NULL OR requested_status IN
        ('APPLICATION', 'ACTIVE', 'GUEST', 'WITHDRAWN', 'REJECTED', 'FRIEND', 'DONOR', 'DECEASED'));

ALTER TABLE privileged_action_request DROP CONSTRAINT IF EXISTS chk_privileged_action_request_demote_role;
ALTER TABLE privileged_action_request ADD CONSTRAINT chk_privileged_action_request_demote_role
    CHECK ((action = 'DEMOTE' AND requested_role IS NOT NULL) OR (action <> 'DEMOTE' AND requested_role IS NULL));

ALTER TABLE privileged_action_request DROP CONSTRAINT IF EXISTS chk_privileged_action_request_suspend_status;
ALTER TABLE privileged_action_request ADD CONSTRAINT chk_privileged_action_request_suspend_status
    CHECK ((action = 'SUSPEND' AND requested_status IS NOT NULL) OR (action <> 'SUSPEND' AND requested_status IS NULL));

ALTER TABLE privileged_action_request DROP CONSTRAINT IF EXISTS chk_privileged_action_request_open;
ALTER TABLE privileged_action_request ADD CONSTRAINT chk_privileged_action_request_open CHECK (
    (status IN ('PENDING', 'APPROVED_WAITING') AND open_target_member_id IS NOT NULL
        AND open_target_member_id = target_member_id)
    OR
    (status NOT IN ('PENDING', 'APPROVED_WAITING') AND open_target_member_id IS NULL)
);

CREATE INDEX IF NOT EXISTS idx_privileged_action_request_status_due ON privileged_action_request (status, expires_at);
CREATE INDEX IF NOT EXISTS idx_privileged_action_request_target ON privileged_action_request (target_member_id);
CREATE INDEX IF NOT EXISTS idx_privileged_action_request_actor ON privileged_action_request (actor_member_id);

ALTER TABLE account ADD COLUMN IF NOT EXISTS role_changed_at TIMESTAMP NULL;
