-- Welle V1.4.3.1 "Veranstaltungen: Kernschleife + Anmeldegebuehren-Zahlung" -- see 39-events.kuml.kts
-- for the entity rationale. Idempotent by construction (IF NOT EXISTS / dual-DROP-then-ADD), same
-- discipline as V2-V17. V17__crm_contacts.sql is NOT touched (released, checksum consumed).
--
-- Deviation from the original design-team plan (documented here, not silently applied): the plan's
-- own step 2.1(a) proposed widening payment_transaction.intent/payment_checkout_session.intent from
-- VARCHAR(12) to VARCHAR(24) "for a hypothetical fourth variant". EVENT_FEE (9 characters) already
-- fits inside the EXISTING VARCHAR(12) (CONTRIBUTION, 12 characters, is still the longest literal)
-- -- so that ALTER COLUMN step is skipped entirely here, per the plan's own explicitly-permitted
-- fallback ("Verbreiterung ersatzlos streichen, EVENT_FEE passt in VARCHAR(12)"). Only the CHECK
-- constraints below are widened to allow the new literal. This also sidesteps the H2-vs-Postgres
-- ALTER COLUMN TYPE syntax risk the plan flagged entirely.
--
-- Table creation order matters: `event` must exist before `event_registration` (FK), and
-- `event_registration` must exist before payment_checkout_session.event_registration_id (FK) is
-- added below -- see plan Stolperfalle 4.

-- ---------------------------------------------------------------------------
-- event
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS event (
    id                      UUID          NOT NULL PRIMARY KEY,
    slug                    VARCHAR(120)  NOT NULL,
    title                   VARCHAR(200)  NOT NULL,
    description             VARCHAR(8000) NOT NULL,
    location_text           VARCHAR(500)  NULL,
    online_url              VARCHAR(2048) NULL,
    starts_at               TIMESTAMP     NOT NULL,
    ends_at                 TIMESTAMP     NOT NULL,
    capacity                INT           NULL,
    fee_amount              DECIMAL(12,2) NOT NULL DEFAULT 0,
    fee_currency            VARCHAR(3)    NOT NULL DEFAULT 'EUR',
    status                  VARCHAR(9)    NOT NULL,
    visibility              VARCHAR(12)   NOT NULL,
    registration_closes_at  TIMESTAMP     NULL,
    created_at              TIMESTAMP     NOT NULL,
    created_by              UUID          NOT NULL,
    cancelled_at            TIMESTAMP     NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_event_slug ON event (slug);
CREATE INDEX IF NOT EXISTS idx_event_status_starts_at ON event (status, starts_at);

ALTER TABLE event DROP CONSTRAINT IF EXISTS fk_event_created_by;
ALTER TABLE event ADD CONSTRAINT fk_event_created_by FOREIGN KEY (created_by) REFERENCES member(id);

-- At least one venue; hybrid (both) is allowed.
ALTER TABLE event DROP CONSTRAINT IF EXISTS chk_event_venue;
ALTER TABLE event ADD CONSTRAINT chk_event_venue CHECK (location_text IS NOT NULL OR online_url IS NOT NULL);

ALTER TABLE event DROP CONSTRAINT IF EXISTS chk_event_time_order;
ALTER TABLE event ADD CONSTRAINT chk_event_time_order CHECK (ends_at >= starts_at);

ALTER TABLE event DROP CONSTRAINT IF EXISTS chk_event_capacity;
ALTER TABLE event ADD CONSTRAINT chk_event_capacity CHECK (capacity IS NULL OR capacity > 0);

ALTER TABLE event DROP CONSTRAINT IF EXISTS chk_event_fee;
ALTER TABLE event ADD CONSTRAINT chk_event_fee CHECK (fee_amount >= 0);

-- status: longest literal PUBLISHED (9) -> VARCHAR(9).
ALTER TABLE event DROP CONSTRAINT IF EXISTS chk_event_status;
ALTER TABLE event ADD CONSTRAINT chk_event_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'CANCELLED'));

-- visibility: longest literal MEMBERS_ONLY (12) -> VARCHAR(12).
ALTER TABLE event DROP CONSTRAINT IF EXISTS chk_event_visibility;
ALTER TABLE event ADD CONSTRAINT chk_event_visibility CHECK (visibility IN ('MEMBERS_ONLY', 'PUBLIC'));

-- ---------------------------------------------------------------------------
-- event_registration
--
-- active_participant_key (see file header §0.1 of the design-team plan): H2's MODE=PostgreSQL --
-- the mode the WHOLE test suite runs against (DatabaseConfig.kt) -- rejects a partial unique index
-- (`CREATE UNIQUE INDEX ... WHERE`, verified empirically in V8__sepa_mandates.sql/V9__dunning.sql)
-- and rejects the generated-column cross-dialect workaround too (Postgres requires STORED, H2
-- rejects STORED). This application-maintained shadow column is the portable alternative: NULL
-- once a registration no longer holds a seat (CANCELLED/EXPIRED), set to a normalized
-- "m:<memberId>" / "g:<lowercased email>" key otherwise. A plain UNIQUE index on (event_id,
-- active_participant_key) then enforces "at most one ACTIVE registration per event per person" --
-- multiple NULLs are allowed under a unique index on both H2 and Postgres (same property
-- uq_crm_contact_email already relies on). Written EXCLUSIVELY by EventStore.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS event_registration (
    id                      UUID          NOT NULL PRIMARY KEY,
    event_id                UUID          NOT NULL,
    member_id               UUID          NULL,
    guest_name              VARCHAR(300)  NULL,
    guest_email             VARCHAR(320)  NULL,
    active_participant_key  VARCHAR(320)  NULL,
    status                  VARCHAR(15)   NOT NULL,
    fee_amount              DECIMAL(12,2) NOT NULL,
    hold_expires_at         TIMESTAMP     NULL,
    waitlist_position       INT           NULL,
    cancel_token_sha256     VARCHAR(64)   NULL,
    registered_at           TIMESTAMP     NOT NULL,
    confirmed_at            TIMESTAMP     NULL,
    cancelled_at            TIMESTAMP     NULL,
    waitlist_offered_at     TIMESTAMP     NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_event_registration_active_participant
    ON event_registration (event_id, active_participant_key);
CREATE INDEX IF NOT EXISTS idx_event_registration_event_status ON event_registration (event_id, status);
CREATE INDEX IF NOT EXISTS idx_event_registration_member       ON event_registration (member_id);
CREATE INDEX IF NOT EXISTS idx_event_registration_waitlist     ON event_registration (event_id, waitlist_position);

ALTER TABLE event_registration DROP CONSTRAINT IF EXISTS fk_event_registration_event;
ALTER TABLE event_registration ADD CONSTRAINT fk_event_registration_event
    FOREIGN KEY (event_id) REFERENCES event(id);

ALTER TABLE event_registration DROP CONSTRAINT IF EXISTS fk_event_registration_member;
ALTER TABLE event_registration ADD CONSTRAINT fk_event_registration_member
    FOREIGN KEY (member_id) REFERENCES member(id);

-- Exactly one identity: a member XOR a (name, email) guest pair -- never both, never neither.
ALTER TABLE event_registration DROP CONSTRAINT IF EXISTS chk_event_registration_identity;
ALTER TABLE event_registration ADD CONSTRAINT chk_event_registration_identity
    CHECK ((member_id IS NOT NULL AND guest_name IS NULL AND guest_email IS NULL)
        OR (member_id IS NULL AND guest_name IS NOT NULL AND guest_email IS NOT NULL));

-- status: longest literal PENDING_PAYMENT (15) -> VARCHAR(15).
ALTER TABLE event_registration DROP CONSTRAINT IF EXISTS chk_event_registration_status;
ALTER TABLE event_registration ADD CONSTRAINT chk_event_registration_status
    CHECK (status IN ('PENDING_PAYMENT', 'CONFIRMED', 'WAITLISTED', 'CANCELLED', 'EXPIRED'));

-- DB-level backstop for the application-maintained shadow column: a CANCELLED/EXPIRED row must
-- never keep a key (it would forever block that participant from re-registering), and every other
-- status must carry one (it is what makes the row count as occupying a seat).
ALTER TABLE event_registration DROP CONSTRAINT IF EXISTS chk_event_registration_active_key;
ALTER TABLE event_registration ADD CONSTRAINT chk_event_registration_active_key
    CHECK ((status IN ('CANCELLED', 'EXPIRED') AND active_participant_key IS NULL)
        OR (status NOT IN ('CANCELLED', 'EXPIRED') AND active_participant_key IS NOT NULL));

ALTER TABLE event_registration DROP CONSTRAINT IF EXISTS chk_event_registration_hold;
ALTER TABLE event_registration ADD CONSTRAINT chk_event_registration_hold
    CHECK (status <> 'PENDING_PAYMENT' OR hold_expires_at IS NOT NULL);

ALTER TABLE event_registration DROP CONSTRAINT IF EXISTS chk_event_registration_fee;
ALTER TABLE event_registration ADD CONSTRAINT chk_event_registration_fee CHECK (fee_amount >= 0);

-- ---------------------------------------------------------------------------
-- payment_checkout_session -- a third payer identity (event_registration_id), alongside the
-- existing member_id/external_donor_id pair (V16__embed_anonymous_donation.sql). The XOR CHECK
-- widens to a "exactly one of three" CHECK -- the old two-way constraint name is dropped so it can
-- never linger active alongside the new one (plan Stolperfalle 5: leaving both active would block
-- every EVENT_FEE insert).
-- ---------------------------------------------------------------------------
ALTER TABLE payment_checkout_session ADD COLUMN IF NOT EXISTS event_registration_id UUID NULL;

ALTER TABLE payment_checkout_session DROP CONSTRAINT IF EXISTS fk_payment_checkout_session_event_registration_id;
ALTER TABLE payment_checkout_session ADD CONSTRAINT fk_payment_checkout_session_event_registration_id
    FOREIGN KEY (event_registration_id) REFERENCES event_registration(id);

CREATE INDEX IF NOT EXISTS idx_payment_checkout_session_event_registration
    ON payment_checkout_session (event_registration_id);

-- intent CHECK widened for the new EVENT_FEE literal (fits the existing VARCHAR(12), see file
-- header deviation note above).
ALTER TABLE payment_checkout_session DROP CONSTRAINT IF EXISTS chk_payment_checkout_session_intent;
ALTER TABLE payment_checkout_session ADD CONSTRAINT chk_payment_checkout_session_intent
    CHECK (intent IN ('CONTRIBUTION', 'DONATION', 'EVENT_FEE'));

-- Exactly ONE payer identity -- never two, never none. Replaces
-- chk_payment_checkout_session_donor_identity (dropped, see header note above).
ALTER TABLE payment_checkout_session DROP CONSTRAINT IF EXISTS chk_payment_checkout_session_donor_identity;
ALTER TABLE payment_checkout_session DROP CONSTRAINT IF EXISTS chk_payment_checkout_session_payer_identity;
ALTER TABLE payment_checkout_session ADD CONSTRAINT chk_payment_checkout_session_payer_identity
    CHECK ((CASE WHEN member_id             IS NOT NULL THEN 1 ELSE 0 END)
         + (CASE WHEN external_donor_id     IS NOT NULL THEN 1 ELSE 0 END)
         + (CASE WHEN event_registration_id IS NOT NULL THEN 1 ELSE 0 END) = 1);
-- Note: `(x IS NOT NULL)::int` (Postgres cast syntax) is NOT portable to H2 -- CASE WHEN is used
-- instead, same reasoning V16's own header documents for every cross-dialect CHECK in this repo.

-- embed_origin's ANONYMOUS coupling only ever made sense for a DONATION -- narrowed here rather
-- than left to (wrongly) also gate an EVENT_FEE embed path, which has no donor_category at all.
ALTER TABLE payment_checkout_session DROP CONSTRAINT IF EXISTS chk_payment_checkout_session_embed_anonymous;
ALTER TABLE payment_checkout_session ADD CONSTRAINT chk_payment_checkout_session_embed_anonymous
    CHECK (embed_origin IS NULL OR intent <> 'DONATION' OR donor_category = 'ANONYMOUS');

-- embed_origin may now also travel alongside an event_registration_id, not only external_donor_id
-- (a future embed-widget registration path, see 39-events.kuml.kts file header OF-3).
ALTER TABLE payment_checkout_session DROP CONSTRAINT IF EXISTS chk_payment_checkout_session_embed_origin_external;
ALTER TABLE payment_checkout_session ADD CONSTRAINT chk_payment_checkout_session_embed_origin_external
    CHECK (embed_origin IS NULL OR external_donor_id IS NOT NULL OR event_registration_id IS NOT NULL);

-- ---------------------------------------------------------------------------
-- payment_transaction -- intent CHECK widened, same reasoning as payment_checkout_session above.
-- No column-width change (EVENT_FEE fits the existing VARCHAR(12)).
-- ---------------------------------------------------------------------------
ALTER TABLE payment_transaction DROP CONSTRAINT IF EXISTS chk_payment_transaction_intent;
ALTER TABLE payment_transaction ADD CONSTRAINT chk_payment_transaction_intent
    CHECK (intent IN ('CONTRIBUTION', 'DONATION', 'EVENT_FEE'));

-- ---------------------------------------------------------------------------
-- organization_settings -- where a confirmed participation-fee payment's brutto amount is booked
-- as income (see EventFeePostingBridge), and under which of the four §§51-68 AO Gemeinnuetzigkeit
-- spheres. Default ZWECKBETRIEB (a satzungsgemaesse Bildungs-/Informationsveranstaltung) -- see
-- 39-events.kuml.kts file header OF-1 for why this is an organization-wide default, not a
-- per-event field, in this wave.
-- ---------------------------------------------------------------------------
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS event_income_account_id UUID NULL;

ALTER TABLE organization_settings DROP CONSTRAINT IF EXISTS fk_organization_settings_event_income_account_id;
ALTER TABLE organization_settings ADD CONSTRAINT fk_organization_settings_event_income_account_id
    FOREIGN KEY (event_income_account_id) REFERENCES ledger_account(id);

-- event_income_sphere reuses the existing GemeinnuetzigkeitSphere literal set (10-accounting.kuml.kts)
-- -- longest literal WIRTSCHAFTLICHER_GESCHAEFTSBETRIEB (34 chars) -> VARCHAR(34).
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS event_income_sphere VARCHAR(34) NOT NULL DEFAULT 'ZWECKBETRIEB';

ALTER TABLE organization_settings DROP CONSTRAINT IF EXISTS chk_organization_settings_event_income_sphere;
ALTER TABLE organization_settings ADD CONSTRAINT chk_organization_settings_event_income_sphere
    CHECK (event_income_sphere IN (
        'IDEELLER_BEREICH', 'VERMOEGENSVERWALTUNG', 'ZWECKBETRIEB', 'WIRTSCHAFTLICHER_GESCHAEFTSBETRIEB'
    ));
