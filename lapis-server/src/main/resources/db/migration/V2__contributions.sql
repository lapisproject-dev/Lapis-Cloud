-- Beitragsverwaltung — Basis (siehe docs/architecture/domain-model.adoc). Bewusst
-- ausgeschlossen: SEPA-Mandat-Entitaet, Mahnstufen-Automatik, Zahlungsdienstleister-Integration.

CREATE TABLE membership_tier (
    id                   UUID PRIMARY KEY,
    name                 VARCHAR(100)   NOT NULL,
    description          VARCHAR(1000)  NOT NULL,
    contribution_amount  DECIMAL(12, 2) NOT NULL,
    billing_interval     VARCHAR(20)    NOT NULL,
    active               BOOLEAN        NOT NULL DEFAULT TRUE
);

-- Forward reference from V1's member table: which tier a member is currently assigned to.
-- Nullable — a member without an assigned tier is skipped by generateContributionsForPeriod.
-- Individual contribution rows keep their own membership_tier_id below regardless of any
-- later reassignment, so historical billing is unaffected by a member switching tiers.
ALTER TABLE member ADD COLUMN membership_tier_id UUID REFERENCES membership_tier (id);

CREATE TABLE contribution (
    id                  UUID PRIMARY KEY,
    member_id           UUID           NOT NULL REFERENCES member (id),
    membership_tier_id  UUID           NOT NULL REFERENCES membership_tier (id),
    period_start        DATE           NOT NULL,
    period_end          DATE           NOT NULL,
    amount_due          DECIMAL(12, 2) NOT NULL,
    status              VARCHAR(20)    NOT NULL,
    paid_at             TIMESTAMP,
    paid_amount         DECIMAL(12, 2),
    note                VARCHAR(1000),
    created_at          TIMESTAMP      NOT NULL,
    -- Idempotenz-Garantie fuer generateContributionsForPeriod: ein Mitglied bekommt pro
    -- Beitragsstufe+Periode hoechstens eine Buchung.
    CONSTRAINT uq_contribution_member_tier_period UNIQUE (member_id, membership_tier_id, period_start, period_end)
);

CREATE INDEX idx_contribution_member ON contribution (member_id);
CREATE INDEX idx_contribution_status ON contribution (status);
