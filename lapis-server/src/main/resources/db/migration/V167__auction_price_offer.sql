-- Wave V1.9.321 "Preisvorschlag" (Fiat wave b). See 21-auction.kuml.kts and docs/architecture/auction-fiat-and-federation.adoc.
-- V1..V166 untouched (V166 may already be applied on instances, so this is a NEW migration).
-- Purely additive and repeatable, H2 MODE=PostgreSQL- and PostgreSQL-compatible: DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT,
-- CREATE TABLE/INDEX IF NOT EXISTS, no DO block, no partial index, no ON DELETE CASCADE, NO data change on existing rows (every existing
-- auction keeps offers_enabled = FALSE through the column default).
--
-- DECISION: a price offer is a negotiation record, never money. It binds the buyer only once the seller accepts (the award then follows the
-- Fiat path of V166: AWAITING_PAYMENT, payment directly between the parties). A price offer NEVER touches ltr_ledger_entry.
--
--   * auction.offers_enabled: set by the seller at creation, immutable afterwards (CHECK: only a Fiat auction with a Sofortkauf price).
--   * auction_offer: one row per proposal. A counter-offer is a NEW row (from_seller = TRUE, parent_offer_id = the buyer's proposal), the old
--     row becomes COUNTERED. At most ONE counter-offer per buyer proposal (unique parent_offer_id; several NULLs are allowed in H2 and PG).
--     "At most one open proposal per buyer and auction" and the limits (20 per auction, 3 declines per buyer) are enforced by the service under
--     the auction row lock (no partial index: H2 cannot).
--
-- ROLLBACK HAZARD / BACKUPS: a backup taken before V167 cannot be restored on V167 and the other way round (schema checksum differs).

ALTER TABLE auction ADD COLUMN IF NOT EXISTS offers_enabled BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE auction DROP CONSTRAINT IF EXISTS chk_auction_offers_enabled;
ALTER TABLE auction ADD CONSTRAINT chk_auction_offers_enabled
    CHECK (offers_enabled = FALSE OR (currency <> 'LTR' AND buy_now_price_minor IS NOT NULL));

CREATE TABLE IF NOT EXISTS auction_offer (
    id               UUID        NOT NULL PRIMARY KEY,
    auction_id       UUID        NOT NULL REFERENCES auction (id),
    buyer_member_id  UUID        NOT NULL REFERENCES member (id),
    from_seller      BOOLEAN     NOT NULL,
    parent_offer_id  UUID        NULL REFERENCES auction_offer (id),
    amount_minor     BIGINT      NOT NULL,
    status           VARCHAR(16) NOT NULL,
    created_at       TIMESTAMP   NOT NULL,
    expires_at       TIMESTAMP   NOT NULL,
    decided_at       TIMESTAMP   NULL,
    CONSTRAINT chk_auction_offer_status CHECK (status IN ('OFFERED', 'ACCEPTED', 'DECLINED', 'COUNTERED', 'EXPIRED', 'WITHDRAWN')),
    CONSTRAINT chk_auction_offer_amount CHECK (amount_minor > 0),
    CONSTRAINT chk_auction_offer_parent CHECK (
        (from_seller = FALSE AND parent_offer_id IS NULL) OR (from_seller = TRUE AND parent_offer_id IS NOT NULL)),
    CONSTRAINT chk_auction_offer_expiry CHECK (expires_at >= created_at)
);
CREATE INDEX IF NOT EXISTS idx_auction_offer_auction_status ON auction_offer (auction_id, status);
CREATE INDEX IF NOT EXISTS idx_auction_offer_buyer_status ON auction_offer (buyer_member_id, status);
CREATE UNIQUE INDEX IF NOT EXISTS uq_auction_offer_parent ON auction_offer (parent_offer_id);
