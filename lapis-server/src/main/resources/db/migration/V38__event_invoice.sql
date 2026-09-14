-- Welle V1.4.3.6 "Externe Rechnungsstellung für Veranstaltungen" -- see 39-events.kuml.kts file
-- header addendum for the full entity rationale. Idempotent by construction (IF NOT EXISTS /
-- dual-DROP-then-ADD), same discipline as V2-V37. V35__open_items.sql/V37__event_catering.sql are
-- NOT touched -- committet auf master, Checksummen verbraucht.
--
-- Portability note: H2 MODE=PostgreSQL (die gesamte Testsuite, DatabaseConfig.kt). KEIN partieller
-- Unique-Index, DROP CONSTRAINT IF EXISTS vor jedem ADD CONSTRAINT, CREATE TABLE/INDEX IF NOT
-- EXISTS, KEIN ON DELETE CASCADE.
--
-- Adds a billing address + `open_item` link to `event_registration` -- an admin (TREASURER/ADMIN)
-- can issue an external invoice instead of routing the fee through Stripe (see
-- `network.lapis.cloud.server.rpc.EventService.issueEventInvoice` KDoc). No new booking logic:
-- reuses the existing `open_item`/`OpenItemPostingBridge` debtor bookkeeping from
-- V1.4.15 ("Kreditoren-/Debitorenbuchhaltung").

ALTER TABLE event_registration ADD COLUMN IF NOT EXISTS billing_street VARCHAR(200) NULL;
ALTER TABLE event_registration ADD COLUMN IF NOT EXISTS billing_postal_code VARCHAR(20) NULL;
ALTER TABLE event_registration ADD COLUMN IF NOT EXISTS billing_city VARCHAR(200) NULL;
ALTER TABLE event_registration ADD COLUMN IF NOT EXISTS billing_country VARCHAR(100) NULL;
ALTER TABLE event_registration ADD COLUMN IF NOT EXISTS open_item_id UUID NULL;
ALTER TABLE event_registration ADD COLUMN IF NOT EXISTS invoice_issued_at TIMESTAMP NULL;
ALTER TABLE event_registration ADD COLUMN IF NOT EXISTS invoice_issued_by UUID NULL;

ALTER TABLE event_registration DROP CONSTRAINT IF EXISTS fk_event_registration_open_item;
ALTER TABLE event_registration ADD CONSTRAINT fk_event_registration_open_item
    FOREIGN KEY (open_item_id) REFERENCES open_item(id);

ALTER TABLE event_registration DROP CONSTRAINT IF EXISTS fk_event_registration_invoice_issued_by;
ALTER TABLE event_registration ADD CONSTRAINT fk_event_registration_invoice_issued_by
    FOREIGN KEY (invoice_issued_by) REFERENCES member(id);

-- Both columns are set together (or neither) -- `EventService.issueEventInvoice` writes them in
-- the same UPDATE right after `OpenItemService`'s creation booking succeeds.
ALTER TABLE event_registration DROP CONSTRAINT IF EXISTS chk_event_registration_invoice_consistency;
ALTER TABLE event_registration ADD CONSTRAINT chk_event_registration_invoice_consistency
    CHECK ((open_item_id IS NULL) = (invoice_issued_at IS NULL));

CREATE INDEX IF NOT EXISTS idx_event_registration_open_item ON event_registration (open_item_id);
