-- Welle V1.3.1 "API-Fundament, lesend" -- api_key table (see 36-api-key.kuml.kts) + widening the
-- audit_log_entry.entity_type CHECK constraint to admit 'API_KEY'.

CREATE TABLE IF NOT EXISTS api_key (
    id                     UUID          NOT NULL PRIMARY KEY,
    label                  VARCHAR(100)  NOT NULL,
    token_hash             VARCHAR(64)   NOT NULL,
    key_prefix             VARCHAR(16)   NOT NULL,
    created_at             TIMESTAMP     NOT NULL,
    created_by_member_id   UUID          NOT NULL REFERENCES member(id),
    expires_at             TIMESTAMP     NULL,
    revoked_at             TIMESTAMP     NULL,
    revoked_by_member_id   UUID          NULL REFERENCES member(id),
    last_used_at           TIMESTAMP     NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_api_key_token_hash ON api_key(token_hash);
CREATE INDEX IF NOT EXISTS idx_api_key_active ON api_key(revoked_at, expires_at);

-- audit_log_entry.entity_type CHECK-Verbreiterung um 'API_KEY' -- dual-DROP-Muster wie V6/V7/V8/V11
-- (siehe deren Kommentare): H2 hat kein ALTER ... ADD VALUE-Aequivalent fuer den CHECK, daher muss
-- die VOLLE Literalliste wiederholt werden -- exakt V1__baseline.sql's Liste plus 'API_KEY' am Ende.
ALTER TABLE audit_log_entry DROP CONSTRAINT IF EXISTS audit_log_entry_entity_type_check;
ALTER TABLE audit_log_entry DROP CONSTRAINT IF EXISTS chk_audit_log_entry_entity_type;
ALTER TABLE audit_log_entry ADD CONSTRAINT chk_audit_log_entry_entity_type
    CHECK (entity_type IN (
        'JOURNAL_ENTRY', 'PARTY_DONATION_VERDICT', 'RESOLUTION', 'BOARD_MEMBERSHIP',
        'CONFERENCE_RECORDING', 'CONFERENCE_STREAM', 'CONFERENCE_STREAM_DESTINATION',
        'CONFERENCE_ROOM', 'SOCIAL_POST', 'ORGANIZATION_SETTINGS', 'SEPA_MANDATE',
        'SEPA_DEBIT_BATCH', 'DUNNING_NOTICE', 'MEMBER', 'PAYMENT_TRANSACTION', 'API_KEY'
    ));
