-- Welle V1.3.2 "Webhooks" (ausgehend) -- see 37-webhook.kuml.kts as well as
-- network.lapis.cloud.server.webhook.* for the signature-, delivery- and retry mechanics these two
-- tables back.

CREATE TABLE IF NOT EXISTS webhook_endpoint (
    id                        UUID          NOT NULL PRIMARY KEY,
    api_key_id                UUID          NOT NULL REFERENCES api_key(id),
    url                       VARCHAR(2048) NOT NULL,
    secret_sealed             VARCHAR(512)  NOT NULL,
    secret_prefix             VARCHAR(24)   NOT NULL,
    active                    BOOLEAN       NOT NULL,
    created_at                TIMESTAMP     NOT NULL,
    created_by_member_id      UUID          NOT NULL REFERENCES member(id),
    updated_at                TIMESTAMP     NULL,
    updated_by_member_id      UUID          NULL REFERENCES member(id),
    deactivated_at            TIMESTAMP     NULL,
    deactivated_by_member_id  UUID          NULL REFERENCES member(id),
    deactivation_reason       VARCHAR(32)   NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_webhook_endpoint_api_key ON webhook_endpoint(api_key_id);
CREATE INDEX IF NOT EXISTS idx_webhook_endpoint_active ON webhook_endpoint(active);

CREATE TABLE IF NOT EXISTS webhook_delivery (
    id                UUID          NOT NULL PRIMARY KEY,
    endpoint_id       UUID          NOT NULL REFERENCES webhook_endpoint(id),
    event_id          UUID          NOT NULL,
    event_type        VARCHAR(48)   NOT NULL,
    entity_id         UUID          NOT NULL,
    occurred_at       TIMESTAMP     NOT NULL,
    payload           TEXT          NOT NULL,
    status            VARCHAR(16)   NOT NULL,
    attempt_count     INT           NOT NULL,
    next_attempt_at   TIMESTAMP     NULL,
    last_attempt_at   TIMESTAMP     NULL,
    last_http_status  INT           NULL,
    last_error        VARCHAR(200)  NULL,
    created_at        TIMESTAMP     NOT NULL,
    delivered_at      TIMESTAMP     NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_webhook_delivery_event ON webhook_delivery(endpoint_id, event_id);
CREATE INDEX IF NOT EXISTS idx_webhook_delivery_due ON webhook_delivery(status, next_attempt_at);
CREATE INDEX IF NOT EXISTS idx_webhook_delivery_endpoint ON webhook_delivery(endpoint_id, created_at);

-- audit_log_entry.entity_type CHECK-Verbreiterung um 'WEBHOOK_ENDPOINT' -- dual-DROP-Muster wie
-- V6/V7/V8/V11/V14 (siehe deren Kommentare): H2 hat kein ALTER ... ADD VALUE-Aequivalent fuer den
-- CHECK, daher muss die VOLLE Literalliste wiederholt werden -- exakt V14's Liste plus
-- 'WEBHOOK_ENDPOINT' am Ende. Diese NAMED Table-Level-Variante ist die, die auf einer bereits
-- migrierten echten Instanz (pdv2/ELB) tatsaechlich existiert und dort gepflegt werden muss --
-- siehe ZUSAETZLICH V1__baseline.sql's eigenen inline-CHECK, der auf jeder FRISCHEN/Test-Datenbank
-- die tatsaechlich wirksame Constraint ist und ebenfalls (in place) um 'WEBHOOK_ENDPOINT' erweitert
-- wurde -- Flyway repair auf pdv2/ELB noetig, gleiches Vorgehen wie bei den V1.2.12/V1.2.8/V1.3.1-
-- Praezedenzfaellen (siehe deren Kommentare in V1__baseline.sql).
ALTER TABLE audit_log_entry DROP CONSTRAINT IF EXISTS audit_log_entry_entity_type_check;
ALTER TABLE audit_log_entry DROP CONSTRAINT IF EXISTS chk_audit_log_entry_entity_type;
ALTER TABLE audit_log_entry ADD CONSTRAINT chk_audit_log_entry_entity_type
    CHECK (entity_type IN (
        'JOURNAL_ENTRY', 'PARTY_DONATION_VERDICT', 'RESOLUTION', 'BOARD_MEMBERSHIP',
        'CONFERENCE_RECORDING', 'CONFERENCE_STREAM', 'CONFERENCE_STREAM_DESTINATION',
        'CONFERENCE_ROOM', 'SOCIAL_POST', 'ORGANIZATION_SETTINGS', 'SEPA_MANDATE',
        'SEPA_DEBIT_BATCH', 'DUNNING_NOTICE', 'MEMBER', 'PAYMENT_TRANSACTION', 'API_KEY',
        'WEBHOOK_ENDPOINT'
    ));
