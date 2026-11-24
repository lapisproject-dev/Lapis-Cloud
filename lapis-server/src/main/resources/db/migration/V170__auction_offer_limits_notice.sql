-- Wave V1.9.323 "Preisvorschlag: Limits, Sperre, Rueckzug, Hinweisfassung" (Fiat wave b2). See 21-auction.kuml.kts and
-- docs/architecture/auction-fiat-and-federation.adoc. V1..V169 untouched (V167 may already be applied on an instance, so this is a NEW migration).
-- Purely additive and repeatable, H2 MODE=PostgreSQL- and PostgreSQL-compatible: DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT,
-- ADD COLUMN / CREATE TABLE / CREATE INDEX IF NOT EXISTS, no DO block, no partial index, no ON DELETE CASCADE, NO data change on existing rows.
--
--   * auction_offer.notice_*: the version, language and SHA-256 of the notice text the AUTHOR was shown when he made the offer (the hash proves
--     WHICH wording he saw). All three are NULL on rows from before this migration (only V167 rows, never shipped) and all three are set on
--     every new row; the CHECK refuses a mixed state.
--   * auction_offer_buyer_restriction: ONE row per (auction, buyer) with the decline counter of the CURRENT cycle and, once three proposals were
--     declined, the end of the block (blocked_until, UTC). After blocked_until a NEW cycle starts with a counter of zero. A surrogate id plus a
--     UNIQUE pair (the ERM profile has no composite primary key, same shape as the other pair tables). Written ONLY by
--     AuctionOfferRestrictionStore, ONLY under the auction row lock.
--   * "1 open proposal per buyer" and "10 open proposals per auction" are enforced by the service under the auction row lock (no partial index).
--
-- ROLLBACK HAZARD / BACKUPS: a backup taken before V170 cannot be restored on V170 and the other way round (schema checksum differs).

ALTER TABLE auction_offer ADD COLUMN IF NOT EXISTS notice_version VARCHAR(32) NULL;
ALTER TABLE auction_offer ADD COLUMN IF NOT EXISTS notice_lang    VARCHAR(5)  NULL;
ALTER TABLE auction_offer ADD COLUMN IF NOT EXISTS notice_sha256  VARCHAR(64) NULL;

ALTER TABLE auction_offer DROP CONSTRAINT IF EXISTS chk_auction_offer_notice;
ALTER TABLE auction_offer ADD CONSTRAINT chk_auction_offer_notice CHECK (
    (notice_version IS NULL AND notice_lang IS NULL AND notice_sha256 IS NULL) OR
    (notice_version IS NOT NULL AND notice_lang IN ('de', 'en') AND notice_sha256 IS NOT NULL AND CHAR_LENGTH(notice_sha256) = 64));

CREATE TABLE IF NOT EXISTS auction_offer_buyer_restriction (
    id                UUID      NOT NULL PRIMARY KEY,
    auction_id        UUID      NOT NULL REFERENCES auction (id),
    buyer_member_id   UUID      NOT NULL REFERENCES member (id),
    declines_in_cycle INT       NOT NULL DEFAULT 0,
    blocked_until     TIMESTAMP NULL,
    updated_at        TIMESTAMP NOT NULL,
    CONSTRAINT chk_aobr_declines CHECK (declines_in_cycle >= 0 AND declines_in_cycle < 3)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_aobr_auction_buyer ON auction_offer_buyer_restriction (auction_id, buyer_member_id);
CREATE INDEX IF NOT EXISTS idx_aobr_buyer ON auction_offer_buyer_restriction (buyer_member_id);
