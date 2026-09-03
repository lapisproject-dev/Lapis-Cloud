-- Welle V1.4.1b "Öffentliche Website-Integration -- anonymer Spenden-Pfad".
-- Idempotent by construction (IF NOT EXISTS / dual-DROP-then-ADD), same discipline as V2-V15.
-- V13__psp_checkout.sql is NOT touched (released, checksum consumed).

-- 1. Spender-Identität: bisher immer ein Mitglied, ab jetzt entweder Mitglied ODER external_donor.
ALTER TABLE payment_checkout_session ALTER COLUMN member_id DROP NOT NULL;

ALTER TABLE payment_checkout_session ADD COLUMN IF NOT EXISTS external_donor_id UUID         NULL;
ALTER TABLE payment_checkout_session ADD COLUMN IF NOT EXISTS embed_origin      VARCHAR(255) NULL;

ALTER TABLE payment_checkout_session DROP CONSTRAINT IF EXISTS fk_payment_checkout_session_external_donor_id;
ALTER TABLE payment_checkout_session ADD CONSTRAINT fk_payment_checkout_session_external_donor_id
    FOREIGN KEY (external_donor_id) REFERENCES external_donor(id);

-- 2. Genau EINE Spender-Identität -- nie beide, nie keine. Der harte Kern dieser Migration.
ALTER TABLE payment_checkout_session DROP CONSTRAINT IF EXISTS chk_payment_checkout_session_donor_identity;
ALTER TABLE payment_checkout_session ADD CONSTRAINT chk_payment_checkout_session_donor_identity
    CHECK ((member_id IS NULL) <> (external_donor_id IS NULL));

-- 3. embed_origin gibt es nur auf dem externen Pfad ...
ALTER TABLE payment_checkout_session DROP CONSTRAINT IF EXISTS chk_payment_checkout_session_embed_origin_external;
ALTER TABLE payment_checkout_session ADD CONSTRAINT chk_payment_checkout_session_embed_origin_external
    CHECK (embed_origin IS NULL OR external_donor_id IS NOT NULL);

-- 4. ... und trägt dort immer ANONYMOUS (Widget kennt keine andere Kategorie, siehe embed-widgets.adoc).
ALTER TABLE payment_checkout_session DROP CONSTRAINT IF EXISTS chk_payment_checkout_session_embed_anonymous;
ALTER TABLE payment_checkout_session ADD CONSTRAINT chk_payment_checkout_session_embed_anonymous
    CHECK (embed_origin IS NULL OR donor_category = 'ANONYMOUS');

CREATE INDEX IF NOT EXISTS idx_payment_checkout_session_external_donor
    ON payment_checkout_session (external_donor_id);
