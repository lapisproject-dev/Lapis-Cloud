-- V1.2.8 "PSP-Checkout (Stripe)" -- GitHub Issue #6. See lapis-server/src/main/kuml/33-payments.kuml.kts
-- (and the V1.2.8 addendum in 11-organization-settings.kuml.kts) for the full fachlich model.
--
-- Idempotent by construction (IF NOT EXISTS / dual-DROP-then-ADD), same discipline as V2-V12.
--
-- Supersedes the "V1.2.4" placeholder name several V1.2.1-era KDocs used for this work -- V1.2.4
-- was renumbered away before this wave started; this migration and everything it touches is V1.2.8.
-- V7__payments.sql itself is NOT touched (released, checksum consumed) -- its `payment_transaction`
-- table is extended here via ALTER TABLE instead.

-- ---------------------------------------------------------------------------
-- payment_checkout_session -- the server-authoritative record of what a member was SUPPOSED to pay,
-- created before any redirect to Stripe. The anchor against amount/currency tampering at webhook
-- time (see PspWebhookIngestion KDoc) -- the webhook's own numbers are only ever compared against
-- this row, never trusted as the posting basis.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS payment_checkout_session (
    id                          UUID          NOT NULL PRIMARY KEY,
    provider                    VARCHAR(8)    NOT NULL,
    provider_session_id         VARCHAR(255)  NOT NULL,
    status                      VARCHAR(9)    NOT NULL,
    intent                      VARCHAR(12)   NOT NULL,
    contribution_id             UUID          NULL,
    member_id                   UUID          NOT NULL,
    amount                      DECIMAL(14,2) NOT NULL,
    currency                    VARCHAR(3)    NOT NULL,
    donor_category              VARCHAR(41)   NULL,
    purpose                     VARCHAR(200)  NULL,
    created_at                  TIMESTAMP     NOT NULL,
    expires_at                  TIMESTAMP     NOT NULL,
    completed_at                TIMESTAMP     NULL,
    provider_idempotency_key    VARCHAR(64)   NOT NULL,
    -- Deviation from the original plan's column list (implementation-time addition, not a security-
    -- or accounting-relevant field -- a Stripe-hosted checkout URL, not a secret): needed so
    -- createContributionCheckout's documented session-REUSE path (an existing non-expired CREATED
    -- session for the same contribution is reused rather than minting a second Stripe session) can
    -- still hand the client a redirect target without a second outbound Stripe call. NULL once the
    -- session is no longer CREATED (see CheckoutSessionDto.redirectUrl KDoc).
    redirect_url                VARCHAR(2048) NULL
);

ALTER TABLE payment_checkout_session DROP CONSTRAINT IF EXISTS chk_payment_checkout_session_provider;
ALTER TABLE payment_checkout_session ADD CONSTRAINT chk_payment_checkout_session_provider
    CHECK (provider IN ('PAYPAL', 'STRIPE', 'MANUAL'));

-- status: longest literal COMPLETED (9) -> VARCHAR(9).
ALTER TABLE payment_checkout_session DROP CONSTRAINT IF EXISTS chk_payment_checkout_session_status;
ALTER TABLE payment_checkout_session ADD CONSTRAINT chk_payment_checkout_session_status
    CHECK (status IN ('CREATED', 'COMPLETED', 'EXPIRED', 'FAILED'));

-- intent: longest literal CONTRIBUTION (12) -> VARCHAR(12), same as payment_transaction.intent.
ALTER TABLE payment_checkout_session DROP CONSTRAINT IF EXISTS chk_payment_checkout_session_intent;
ALTER TABLE payment_checkout_session ADD CONSTRAINT chk_payment_checkout_session_intent
    CHECK (intent IN ('CONTRIBUTION', 'DONATION'));

-- donor_category: literal set + VARCHAR(41) width copied verbatim from V1__baseline.sql's
-- external_donor.donor_category / journal_entry.donor_category CHECK constraints.
ALTER TABLE payment_checkout_session DROP CONSTRAINT IF EXISTS chk_payment_checkout_session_donor_category;
ALTER TABLE payment_checkout_session ADD CONSTRAINT chk_payment_checkout_session_donor_category
    CHECK (donor_category IS NULL OR donor_category IN (
        'GERMAN_NATURAL_PERSON', 'EU_NATURAL_PERSON', 'NON_EU_FOREIGN_NATURAL_PERSON',
        'GERMAN_COMPANY_OR_ORGANIZATION', 'PUBLIC_LAW_CORPORATION', 'OVER_25_PERCENT_STATE_OWNED_COMPANY',
        'OTHER_PARTY_OR_PARLIAMENTARY_GROUP_ENTITY', 'PROFESSIONAL_OR_TRADE_ASSOCIATION', 'ANONYMOUS'
    ));

ALTER TABLE payment_checkout_session DROP CONSTRAINT IF EXISTS fk_payment_checkout_session_member_id;
ALTER TABLE payment_checkout_session ADD CONSTRAINT fk_payment_checkout_session_member_id
    FOREIGN KEY (member_id) REFERENCES member(id);

ALTER TABLE payment_checkout_session DROP CONSTRAINT IF EXISTS fk_payment_checkout_session_contribution_id;
ALTER TABLE payment_checkout_session ADD CONSTRAINT fk_payment_checkout_session_contribution_id
    FOREIGN KEY (contribution_id) REFERENCES contribution(id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_checkout_session_provider_session
    ON payment_checkout_session (provider, provider_session_id);
CREATE INDEX IF NOT EXISTS idx_payment_checkout_session_member       ON payment_checkout_session (member_id);
CREATE INDEX IF NOT EXISTS idx_payment_checkout_session_contribution ON payment_checkout_session (contribution_id);
CREATE INDEX IF NOT EXISTS idx_payment_checkout_session_status       ON payment_checkout_session (status);

-- ---------------------------------------------------------------------------
-- payment_transaction: two new columns. checkout_session_id is a deliberately ONE-DIRECTIONAL FK
-- (payment_transaction -> payment_checkout_session); NOT mirrored back onto
-- payment_checkout_session, same reasoning that keeps sepa_debit_batch's two document FKs
-- one-directional (see SepaRoutes.kt NIT-5) -- a bidirectional pair would force a nullable-then-
-- update dance for no benefit here.
-- ---------------------------------------------------------------------------
ALTER TABLE payment_transaction ADD COLUMN IF NOT EXISTS checkout_session_id UUID        NULL;
ALTER TABLE payment_transaction ADD COLUMN IF NOT EXISTS donor_category      VARCHAR(41) NULL;

ALTER TABLE payment_transaction DROP CONSTRAINT IF EXISTS fk_payment_transaction_checkout_session_id;
ALTER TABLE payment_transaction ADD CONSTRAINT fk_payment_transaction_checkout_session_id
    FOREIGN KEY (checkout_session_id) REFERENCES payment_checkout_session(id);

ALTER TABLE payment_transaction DROP CONSTRAINT IF EXISTS chk_payment_transaction_donor_category;
ALTER TABLE payment_transaction ADD CONSTRAINT chk_payment_transaction_donor_category
    CHECK (donor_category IS NULL OR donor_category IN (
        'GERMAN_NATURAL_PERSON', 'EU_NATURAL_PERSON', 'NON_EU_FOREIGN_NATURAL_PERSON',
        'GERMAN_COMPANY_OR_ORGANIZATION', 'PUBLIC_LAW_CORPORATION', 'OVER_25_PERCENT_STATE_OWNED_COMPANY',
        'OTHER_PARTY_OR_PARLIAMENTARY_GROUP_ENTITY', 'PROFESSIONAL_OR_TRADE_ASSOCIATION', 'ANONYMOUS'
    ));

-- ---------------------------------------------------------------------------
-- psp_webhook_event -- forensic log, direct analogue of federation_inbox_delivery_log: one row per
-- DELIVERY ATTEMPT, verified or not, accepted or rejected. Deliberately NO unique constraint --
-- repeated deliveries must each leave a trace. Idempotency lives exclusively on
-- uq_payment_transaction_provider_event (see payment_transaction above / PspWebhookIngestion KDoc).
-- No FK into member -- needs no PersonalDataContributor coverage.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS psp_webhook_event (
    id                     UUID         NOT NULL PRIMARY KEY,
    provider               VARCHAR(8)   NOT NULL,
    provider_event_id      VARCHAR(255) NULL,
    event_type             VARCHAR(100) NULL,
    received_at            TIMESTAMP    NOT NULL,
    signature_verified     BOOLEAN      NOT NULL,
    reject_reason          VARCHAR(40)  NULL,
    outcome                VARCHAR(20)  NOT NULL,
    payment_transaction_id UUID         NULL,
    body_sha256            VARCHAR(64)  NOT NULL,
    body_byte_size         INT          NOT NULL
);

ALTER TABLE psp_webhook_event DROP CONSTRAINT IF EXISTS chk_psp_webhook_event_provider;
ALTER TABLE psp_webhook_event ADD CONSTRAINT chk_psp_webhook_event_provider
    CHECK (provider IN ('PAYPAL', 'STRIPE', 'MANUAL'));

ALTER TABLE psp_webhook_event DROP CONSTRAINT IF EXISTS chk_psp_webhook_event_outcome;
ALTER TABLE psp_webhook_event ADD CONSTRAINT chk_psp_webhook_event_outcome
    CHECK (outcome IN ('REJECTED', 'IGNORED', 'DUPLICATE', 'PROCESSED', 'UNPOSTED'));

ALTER TABLE psp_webhook_event DROP CONSTRAINT IF EXISTS fk_psp_webhook_event_payment_transaction_id;
ALTER TABLE psp_webhook_event ADD CONSTRAINT fk_psp_webhook_event_payment_transaction_id
    FOREIGN KEY (payment_transaction_id) REFERENCES payment_transaction(id);

CREATE INDEX IF NOT EXISTS idx_psp_webhook_event_received_at ON psp_webhook_event (received_at);
CREATE INDEX IF NOT EXISTS idx_psp_webhook_event_provider_event ON psp_webhook_event (provider, provider_event_id);

-- ---------------------------------------------------------------------------
-- organization_settings.donation_income_account_id -- fourth ledger-account mapping, where a
-- gateway donation's brutto amount is booked as income. Nullable, same "unconfigured degrades to
-- no-op" treatment as the three V1.2.1 mapping columns (see DonationPostingBridge KDoc).
-- ---------------------------------------------------------------------------
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS donation_income_account_id UUID NULL;

ALTER TABLE organization_settings DROP CONSTRAINT IF EXISTS fk_organization_settings_donation_income_account_id;
ALTER TABLE organization_settings ADD CONSTRAINT fk_organization_settings_donation_income_account_id
    FOREIGN KEY (donation_income_account_id) REFERENCES ledger_account(id);

-- ---------------------------------------------------------------------------
-- audit_log_entry.entity_type CHECK widening for PAYMENT_TRANSACTION (Welle V1.2.8) -- dual-DROP
-- pattern like every prior migration touching this constraint (V6-V11). Literal list copied from
-- V11__member_administration.sql's version (the latest before this one) plus the one new literal.
-- PAYMENT_TRANSACTION (19 chars) fits the existing VARCHAR(29) width (CONFERENCE_STREAM_DESTINATION,
-- 29 chars, is still the longest literal) -- no column-width change needed.
-- ---------------------------------------------------------------------------
ALTER TABLE audit_log_entry DROP CONSTRAINT IF EXISTS audit_log_entry_entity_type_check;
ALTER TABLE audit_log_entry DROP CONSTRAINT IF EXISTS chk_audit_log_entry_entity_type;
ALTER TABLE audit_log_entry ADD CONSTRAINT chk_audit_log_entry_entity_type
    CHECK (entity_type IN (
        'JOURNAL_ENTRY', 'PARTY_DONATION_VERDICT', 'RESOLUTION', 'BOARD_MEMBERSHIP',
        'CONFERENCE_RECORDING', 'CONFERENCE_STREAM', 'CONFERENCE_STREAM_DESTINATION', 'CONFERENCE_ROOM',
        'SOCIAL_POST', 'ORGANIZATION_SETTINGS', 'SEPA_MANDATE', 'SEPA_DEBIT_BATCH', 'DUNNING_NOTICE',
        'MEMBER', 'PAYMENT_TRANSACTION'
    ));
