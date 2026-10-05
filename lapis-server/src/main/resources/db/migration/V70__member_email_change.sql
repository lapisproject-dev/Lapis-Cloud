-- Welle V1.9.56 -- Absicherung der E-Mail-Aenderung (Kontouebernahme-Schutz). V1..V69 untouched. Purely additive,
-- idempotent, H2 MODE=PostgreSQL- and Postgres-compatible (DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT, no
-- partial or expression indexes, no DO block). Existing member.email values stay byte-identical (no UPDATE here).
--
-- member_email_change holds at most ONE open ("PENDING") change per member. H2 cannot express the partial unique
-- index "UNIQUE (member_id) WHERE status = 'PENDING'" (see V12, V18, V43, V44), so the standard substitute is used,
-- the same one uq_bank_account_default (V32) uses: open_member_id equals member_id while the change is PENDING and is
-- NULL otherwise, and a plain UNIQUE constraint on open_member_id (several NULLs are allowed on H2 and PostgreSQL)
-- enforces "at most one open change per member". chk_member_email_change_open ties the two columns to the status so
-- the substitute cannot drift. NOTE the explicit IS NOT NULL: a CHECK that evaluates to UNKNOWN PASSES, so without it a
-- PENDING row with open_member_id NULL (NULL = member_id is UNKNOWN) would slip through AND bypass the uniqueness above.
--
-- Token columns hold only SHA-256 hashes (hex). confirm_token_hash goes to the NEW address, revoke_token_hash to the
-- OLD address. The audit log never carries an address (hash chain, not erasable) -- the addresses live only in this
-- erasable table.

CREATE TABLE IF NOT EXISTS member_email_change (
    id                     UUID          NOT NULL PRIMARY KEY,
    member_id              UUID          NOT NULL,
    open_member_id         UUID          NULL,
    pending_email          VARCHAR(320)  NOT NULL,
    kind                   VARCHAR(24)   NOT NULL,
    requested_by           UUID          NULL,
    reason                 VARCHAR(500)  NULL,
    confirm_token_hash     VARCHAR(64)   NULL,
    revoke_token_hash      VARCHAR(64)   NULL,
    status                 VARCHAR(16)   NOT NULL,
    created_at             TIMESTAMP     NOT NULL,
    expires_at             TIMESTAMP     NOT NULL,
    effective_at           TIMESTAMP     NULL,
    new_email_confirmed_at TIMESTAMP     NULL,
    resolved_at            TIMESTAMP     NULL
);

ALTER TABLE member_email_change DROP CONSTRAINT IF EXISTS fk_member_email_change_member;
ALTER TABLE member_email_change ADD CONSTRAINT fk_member_email_change_member
    FOREIGN KEY (member_id) REFERENCES member(id);

ALTER TABLE member_email_change DROP CONSTRAINT IF EXISTS fk_member_email_change_requested_by;
ALTER TABLE member_email_change ADD CONSTRAINT fk_member_email_change_requested_by
    FOREIGN KEY (requested_by) REFERENCES member(id);

ALTER TABLE member_email_change DROP CONSTRAINT IF EXISTS uq_member_email_change_open;
ALTER TABLE member_email_change ADD CONSTRAINT uq_member_email_change_open UNIQUE (open_member_id);

ALTER TABLE member_email_change DROP CONSTRAINT IF EXISTS uq_member_email_change_confirm;
ALTER TABLE member_email_change ADD CONSTRAINT uq_member_email_change_confirm UNIQUE (confirm_token_hash);

ALTER TABLE member_email_change DROP CONSTRAINT IF EXISTS uq_member_email_change_revoke;
ALTER TABLE member_email_change ADD CONSTRAINT uq_member_email_change_revoke UNIQUE (revoke_token_hash);

ALTER TABLE member_email_change DROP CONSTRAINT IF EXISTS chk_member_email_change_kind;
ALTER TABLE member_email_change ADD CONSTRAINT chk_member_email_change_kind
    CHECK (kind IN ('SELF', 'PROPOSAL', 'PROPOSAL_NO_ACCOUNT', 'ADMIN_OVERRIDE'));

ALTER TABLE member_email_change DROP CONSTRAINT IF EXISTS chk_member_email_change_status;
ALTER TABLE member_email_change ADD CONSTRAINT chk_member_email_change_status
    CHECK (status IN ('PENDING', 'APPLIED', 'REVOKED', 'WITHDRAWN', 'EXPIRED', 'SUPERSEDED', 'CONFLICT'));

ALTER TABLE member_email_change DROP CONSTRAINT IF EXISTS chk_member_email_change_open;
ALTER TABLE member_email_change ADD CONSTRAINT chk_member_email_change_open CHECK (
    (status = 'PENDING' AND open_member_id IS NOT NULL AND open_member_id = member_id)
    OR
    (status <> 'PENDING' AND open_member_id IS NULL)
);

CREATE INDEX IF NOT EXISTS idx_member_email_change_member ON member_email_change (member_id);
CREATE INDEX IF NOT EXISTS idx_member_email_change_status_due ON member_email_change (status, effective_at);
