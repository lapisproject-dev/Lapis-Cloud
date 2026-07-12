-- Kommunikation — Basis: Mailinglisten, Direktnachrichten (siehe
-- docs/architecture/domain-model.adoc). unsubscribed_at statt Hard-Delete der Subscription
-- (DSGVO-Audit-Trail des Opt-outs).

CREATE TABLE mailing_list (
    id          UUID PRIMARY KEY,
    name        VARCHAR(200)  NOT NULL,
    description VARCHAR(1000),
    created_by  UUID          NOT NULL REFERENCES member (id)
);

CREATE TABLE mailing_list_subscription (
    id               UUID       PRIMARY KEY,
    mailing_list_id  UUID       NOT NULL REFERENCES mailing_list (id),
    member_id        UUID       NOT NULL REFERENCES member (id),
    subscribed_at    TIMESTAMP  NOT NULL,
    unsubscribed_at  TIMESTAMP,
    CONSTRAINT uq_mailing_subscription_list_member UNIQUE (mailing_list_id, member_id)
);

CREATE TABLE mailing_message (
    id               UUID          PRIMARY KEY,
    mailing_list_id  UUID          NOT NULL REFERENCES mailing_list (id),
    subject          VARCHAR(300)  NOT NULL,
    body_text        VARCHAR(20000) NOT NULL,
    sent_by          UUID          NOT NULL REFERENCES member (id),
    sent_at          TIMESTAMP,
    status           VARCHAR(20)   NOT NULL
);

CREATE TABLE mailing_delivery_log (
    id                  UUID       PRIMARY KEY,
    mailing_message_id  UUID       NOT NULL REFERENCES mailing_message (id),
    member_id           UUID       NOT NULL REFERENCES member (id),
    delivered_at        TIMESTAMP  NOT NULL,
    delivery_status     VARCHAR(30) NOT NULL
);

CREATE TABLE direct_message (
    id            UUID           PRIMARY KEY,
    sender_id     UUID           NOT NULL REFERENCES member (id),
    recipient_id  UUID           NOT NULL REFERENCES member (id),
    body          VARCHAR(10000) NOT NULL,
    sent_at       TIMESTAMP      NOT NULL,
    read_at       TIMESTAMP
);

CREATE INDEX idx_mailing_subscription_list ON mailing_list_subscription (mailing_list_id);
CREATE INDEX idx_mailing_message_list ON mailing_message (mailing_list_id);
CREATE INDEX idx_direct_message_recipient ON direct_message (recipient_id);
CREATE INDEX idx_direct_message_sender ON direct_message (sender_id);
