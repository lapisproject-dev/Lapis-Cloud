// LTR-Auktion domain (V0.6.2) -- see the concept document ("03 Bereiche/Lapis Cloud/
// Meritokratisches System und Libertaler.md", "Auktion – Marktplatz fuer LTR-Inhaber" section,
// vault) for the full fachlich specification this implements.
//
// **Englische Proxy-Bid-Auktion, Second-Price-Zuschlag, LTR-only.** A seller creates a listing
// (title/description/startingBidLtr/optional buyNowPriceLtr/durationHours). Bidders submit a
// PROXY maximum (`auction_bid.max_bid_ltr`) -- the server derives the current asking price and the
// leader purely from the set of standing maxima, never storing a "current price" column (see
// network.lapis.cloud.server.rpc.computeAuctionOutcome, a pure function with zero DB access, unit-
// property-tested). At settlement the leader wins and pays the SECOND-highest maximum (bumped by
// one minimum increment, capped at their own maximum) -- eBay/Vickrey-style, conceptually related
// to but NOT reusing network.lapis.cloud.server.rpc.computeVickreySettlement (that function
// apportions a second price across MULTIPLE simultaneous winning ballots in a basket vote; an
// auction has exactly one winning slot, no apportionment needed).
//
// **THIS FILE'S auction_bid IS AN UPSERT TABLE, ONE ROW PER (auction, bidder) -- NOT an append-
// only bid history.** `uq_auction_bid_auction_bidder` enforces at most one standing maximum per
// bidder per auction; raising your own bid UPDATES that same row (`max_bid_ltr`/`created_at`
// overwritten), it never inserts a second row. This is a deliberate implementation decision (not
// explicitly fixed by the wave's concept document), chosen because it mirrors this codebase's own
// established "one row per (parent, member), enforced by a unique index, upsert on repeat action"
// idiom already used for `crowdfunding_reaction`/`politician_reaction` (see 17-crowdfunding.kuml.kts/
// 20-politician.kuml.kts) -- and because computeAuctionOutcome's leader/second-price math needs
// exactly "each bidder's current standing maximum", which this shape gives for free with no extra
// group-by-then-take-latest step server-side. `created_at`, despite the name, is therefore this
// row's LAST bid time (re-stamped on every raise) -- same "single timestamp column reused as last-
// action time" idiom `crowdfunding_reaction.cast_at` already establishes.
//
// **Reservation model -- ONLY the current leader holds a `ltr_ledger_entry` hold.** Every bid is
// checked for LTR coverage at bid time (a bidder can never submit a maxBidLtr they could not
// actually back), but a bid that does not (yet) take the lead reserves nothing net -- it is
// checked, then legitimately just sets the second price. The moment a HIGHER bid takes the lead,
// the former leader's hold is released (`AUCTION_HOLD_RELEASE`) and the new leader's hold is
// booked (`AUCTION_HOLD`) for their own maxBidLtr, atomically, in the same transaction. This is a
// deliberate architecture decision (see the V0.6.2 planning discussion): real, signed
// `ltr_ledger_entry` rows, NOT a value derived purely from live open bids -- because every OTHER
// LTR-debiting call site (`PeerTransferService.transferLtr`, `GovernanceService.castVoteBallot`,
// `CrowdfundingService.submitProject`) reads `LtrBalanceProvider.freeBalance` = `SUM(ltr_ledger_
// entry.amount_ltr)` and would otherwise be BLIND to an open auction hold -- exactly the
// V0.6.1 security gap `08-ltr-balance.kuml.kts`'s own header documents for the original
// `castVoteBallot`/stake omission. A real ledger hold composes for free with every existing and
// future debit path; a live-derived-only reservation would require re-auditing every debit call
// site whenever a new one is added. See 08-ltr-balance.kuml.kts's own header for the five new,
// additively-appended `LtrLedgerEntryType` literals this wave introduces
// (AUCTION_LISTING_FEE/AUCTION_HOLD/AUCTION_HOLD_RELEASE/AUCTION_SALE_OUT/AUCTION_SALE_IN) and the
// new `AUCTION` `LtrLedgerReferenceType` literal (`referenceId` always points at `auction.id`,
// never at `auction_bid.id`).
//
// **Lazy-Close, no scheduler.** This codebase has no scheduler/cron-job infrastructure anywhere
// (verified, same absence 17-crowdfunding.kuml.kts's own header documents for its Silence-is-
// Approval window). An auction whose `ends_at` has passed is settled on the next call that
// actually touches it (getAuction/placeBid/buyNow/settleAuction) -- `status` stays OPEN in the
// database until that happens; `listAuctions` computes an `effectiveStatus` on read without ever
// writing it, same "computed view over a persisted PENDING state" idiom `CrowdfundingProjectDto
// .effectiveStatus`/`CrowdfundingWeightDecay.isAutoApproved` already use.
//
// **Split from ltr_ledger_entry, not a widening of it**: same "business table carries fachlich
// detail, ltr_ledger_entry carries only the generic signed booking effect" split
// `crowdfunding_project`/`PROJECT_STAKE` and `peer_transfer`/`PEER_TRANSFER_OUT`/`PEER_TRANSFER_IN`
// already establish (see 17-crowdfunding.kuml.kts/18-peer-transfer.kuml.kts).
//
// **`auction_max_value_ltr` cap (11-organization-settings.kuml.kts, this wave's own two-column
// addition there)**: enforced ONLY at `createListing` against `starting_bid_ltr`/
// `buy_now_price_ltr` -- never against an individual bid. The cap governs what a seller may LIST,
// not what a bidder may offer (a bidder legitimately exceeding a since-lowered cap on an
// already-open auction is not retroactively invalidated). Deliberately NOT derived from the
// Price-Oracle (19-price-oracle.kuml.kts) -- the ADMIN sets the LTR figure directly, no Oracle
// dependency in auction core validation. See that column's own comment in
// 11-organization-settings.kuml.kts.
//
// **`auctionEnabled` gate + auditable acknowledgment (`auction_compliance_acknowledgment`)**: see
// 11-organization-settings.kuml.kts's own header for the `auction_enabled` column, and
// network.lapis.cloud.server.rpc.AuctionComplianceDisclaimer/AuctionService KDoc for the full
// versioned-disclaimer-hash acknowledgment mechanism this table backs. Deliberately NOT a
// genesis-singleton lock row (unlike crowdfunding_submission_gate) -- this table is meant to
// accumulate ONE row per enableAuction call (an append-only audit trail of who acknowledged which
// disclaimer version and when), never locked/read-then-decided against for a business invariant.
//
// **No genesis-singleton gate row for auction listing itself** (unlike
// `crowdfunding_submission_gate`) -- there is no cross-auction global invariant analogous to
// Crowdfunding's decaying entry hurdle; every listing is independent, and the one genuinely
// cross-request invariant this domain has (a bidder's aggregate LTR exposure) is already covered
// by the existing per-member `LtrBalanceProvider.lockForDebit` row lock -- no dedicated singleton
// lock row is needed.
//
// **Fiat path (V166, Wave V1.9.320 "Auktionen in Euro mit Direktzahlung") -- do not "repair" any of these decisions**:
//  - `auction.currency` is 'LTR' (default, every pre-V166 row) or a Fiat code ('EUR' is the only one the server accepts today). A Fiat auction
//    carries its amounts as LONG MINOR UNITS (`*_minor`, cents) in `starting_price_minor`/`buy_now_price_minor`/`final_price_minor`, and its
//    bidders' maxima in `auction_bid.max_bid_minor`; the LTR columns of such a row are NULL (CHECK `chk_auction_amount_columns` pins exactly one of
//    the two shapes), which is why `starting_bid_ltr` and `auction_bid.max_bid_ltr` are nullable since V166.
//  - A Fiat auction NEVER writes `ltr_ledger_entry`: no listing fee (`listing_fee_ltr` = 0.00), no reservation/hold, no sale booking. Lapis only
//    brokers the contract; seller and winner settle the money directly between themselves, outside the platform.
//  - Status flow: OPEN -> AWAITING_PAYMENT (an award, by time or by Sofortkauf) -> PAID (the seller confirmed the money arrived) -> COMPLETED (the
//    winner also confirmed the goods arrived); OPEN -> CLOSED_NO_SALE without a bid. SETTLED is LTR-only (CHECK `chk_auction_status_by_currency`).
//    The two confirmations are independent: `payment_confirmed_at` is set by the seller, `delivery_confirmed_at` by the winner; COMPLETED = both.
//  - `auction_payment_details` holds the seller's holder name/IBAN/link as ONE SecretBox-sealed JSON blob in a SEPARATE table, so that no listing
//    query can ever select it. Only the winner and the seller may open it, only after the award; the retention poller deletes it.
//  - `auction_fiat_acknowledgment` is append-only: OPERATOR = an administrator switching `organization_settings.auction_fiat_enabled` on (OFF by
//    default), SELLER = a member before listing in Euro. An auction keeps the `seller_disclaimer_version` it was created under.
//
// **FK-naming choice**: EVERY member-referencing and auction-referencing FK in this file
// (`seller_member_id`, `winner_member_id`, `auction_id`, `bidder_member_id`,
// `acknowledged_by_member_id`) is modelled as a plain «Column» UUID attribute with
// «Column».fkEntity, NEVER a UML association -- consistently, even where an association's
// class-derived default would happen to already match (e.g. `auction_bid.auction_id`). This
// deliberately avoids the exact footgun that caused the V0.6.4 `politician_reaction
// .rater_member_id` bug (a UML association's `role` override does NOT rename the generated
// column -- see Commit 9171e95's post-mortem, and the CLAUDE.md "kUML-Repo-Konventionen" rule this
// wave follows): a domain-wide "always plain «Column», never association-with-role" policy is
// simpler to hold in mind and audit than "association except where the name would collide".
//
// **Scope-cuts (deliberate, documented so a reviewer does not mistake these for gaps)**:
//  - Member-only. No Gast/guest identity model exists in this codebase yet (same V0.7-dependent
//    cut 18-peer-transfer.kuml.kts/20-politician.kuml.kts already document) -- `seller_member_id`/
//    `bidder_member_id`/`winner_member_id` are real, appropriately-NOT-NULL member FKs.
//  - NO central arbitration/Kaeuferschutz/chargeback table or column anywhere in this domain --
//    unlike `peer_transfer.initiated_by`'s privileged-correction path, an auction dispute has NO
//    analogous mechanism, and none is planned. The concept document deliberately withholds a
//    content-arbitration role from the platform here; reputation (LTR-balance visibility, social
//    ratings) is the only protection. This is an intentional, permanent design decision, not a
//    missing feature the reviewer should flag.
//  - No automatic EUR/Fiat CONVERSION anywhere in this domain (see `auction_max_value_ltr` above). Since V166 (Wave V1.9.320) an auction may,
//    however, be run in EUR with DIRECT PAYMENT between seller and winner -- see "Fiat path" below. Lapis never moves that money.
//  - `auction_bid.max_bid_ltr` of any member OTHER than the caller is never exposed by any DTO --
//    proxy-auction integrity (`listAuctions`/`getAuction` only ever expose the DERIVED
//    `currentPriceLtr`, never a raw maximum). Enforced at the service layer, not by this model.
// **Price offers (V167, Fiat wave b).** A seller may allow `auction.offers_enabled` (only a Fiat auction with a Sofortkauf price, fixed at
// creation). A buyer proposes a price below the Sofortkauf price (`auction_offer`, from_seller = false); the seller accepts (the auction is
// awarded at that price: AWAITING_PAYMENT), declines or counters ONCE (a new row, from_seller = true, parent = the proposal); the buyer accepts or
// declines the counter. A proposal never extends the auction and expires at the latest at the auction end. NOT legally reviewed.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "Auction") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub — id-only, mirrors every other domain's own Member stub. Resolves
    // seller_member_id/winner_member_id/bidder_member_id/acknowledged_by_member_id's
    // «Column».fkEntity overrides within this single-file evaluation.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Literal order is load-bearing: AuctionSchemaDriftTest asserts ErmDataType.Enum.values in
    // exactly this order, matching network.lapis.cloud.shared.domain.AuctionStatus.
    val auctionStatus = enumOf(name = "AuctionStatus") {
        literal(name = "OPEN")
        literal(name = "SETTLED")
        literal(name = "CLOSED_NO_SALE")
        literal(name = "AWAITING_PAYMENT")
        literal(name = "PAID")
        literal(name = "COMPLETED")
    }

    val auction = classOf(name = "Auction") {
        stereotype("Entity") { "tableName" to "auction"; "kotlinObjectName" to "AuctionTable" }
        stereotype("Index") { "columns" to listOf("status"); "name" to "idx_auction_status" }
        stereotype("Index") { "columns" to listOf("seller_member_id"); "name" to "idx_auction_seller" }
        stereotype("Index") { "columns" to listOf("ends_at"); "name" to "idx_auction_ends_at" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "title", type = "String") {
            stereotype("Column") { "columnName" to "title"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "description", type = "String") {
            stereotype("Column") { "columnName" to "description"; "sqlType" to "VARCHAR(4000)" }
        }
        // NULL on a Fiat auction (V166) -- see file header "Fiat path"; NOT NULL on an LTR auction (CHECK chk_auction_amount_columns).
        attribute(name = "startingBidLtr", type = "BigDecimal") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "starting_bid_ltr"; "sqlType" to "DECIMAL(18,2)" }
        }
        // Nullable -- an auction may run to endsAt with no Sofortkauf option at all.
        attribute(name = "buyNowPriceLtr", type = "BigDecimal") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "buy_now_price_ltr"; "sqlType" to "DECIMAL(18,2)" }
        }
        attribute(name = "status", type = auctionStatus) {
            defaultValue = "OPEN"
            stereotype("Column") { "columnName" to "status"; "enumType" to "network.lapis.cloud.shared.domain.AuctionStatus" }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute -- see file header
        // "FK-naming choice".
        attribute(name = "sellerMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "seller_member_id"; "fkEntity" to "Member" }
        }
        // Real FK -> member (id), nullable (unset until SETTLED). Plain «Column» UUID attribute --
        // see file header "FK-naming choice" and "Scope-cut: member-only".
        attribute(name = "winnerMemberId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "winner_member_id"; "fkEntity" to "Member" }
        }
        // NOT NULL iff status == SETTLED -- cross-column invariant enforced only at the service
        // layer, same class of gap 17-crowdfunding.kuml.kts's own rejection_reason comment documents.
        attribute(name = "finalPriceLtr", type = "BigDecimal") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "final_price_ltr"; "sqlType" to "DECIMAL(18,2)" }
        }
        // Flat spam-guard fee actually debited at createListing -- see file header. Denormalized
        // onto this row (rather than only ever existing as the AUCTION_LISTING_FEE ledger entry)
        // so a read of one auction never needs a second ledger lookup to show what its own listing
        // cost the seller.
        attribute(name = "listingFeeLtr", type = "BigDecimal") {
            stereotype("Column") { "columnName" to "listing_fee_ltr"; "sqlType" to "DECIMAL(18,2)" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        attribute(name = "endsAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "ends_at" }
        }
        // Set the instant the lazy-close (or explicit buyNow/settleAuction) actually settles this
        // row -- null while OPEN, regardless of whether endsAt has already passed (see file header
        // "Lazy-Close").
        attribute(name = "settledAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "settled_at" }
        }
        // ---- V166 Fiat path (see file header) ----
        // 'LTR' for every pre-V166 row; a Fiat code ('EUR') otherwise. The server whitelist (AuctionCurrencies.SUPPORTED) is the real gate.
        attribute(name = "currency", type = "String") {
            defaultValue = "LTR"
            stereotype("Column") { "columnName" to "currency"; "sqlType" to "VARCHAR(3)" }
        }
        // Minor units (cents) of a Fiat auction; NULL on an LTR auction.
        attribute(name = "startingPriceMinor", type = "Long") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "starting_price_minor" }
        }
        attribute(name = "buyNowPriceMinor", type = "Long") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "buy_now_price_minor" }
        }
        attribute(name = "finalPriceMinor", type = "Long") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "final_price_minor" }
        }
        // The auction_fiat_acknowledgment version the seller acknowledged when listing; NULL on an LTR auction.
        attribute(name = "sellerDisclaimerVersion", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "seller_disclaimer_version"; "sqlType" to "VARCHAR(50)" }
        }
        // Set by the SELLER (money arrived) / by the WINNER (goods arrived); both set = COMPLETED.
        attribute(name = "paymentConfirmedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "payment_confirmed_at" }
        }
        attribute(name = "deliveryConfirmedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "delivery_confirmed_at" }
        }
        // ---- V167 Price offers (wave b, see "Price offers" in the file header): fixed at creation, never changed. ----
        attribute(name = "offersEnabled", type = "Boolean") {
            defaultValue = "false"
            stereotype("Column") { "columnName" to "offers_enabled" }
        }
        // ---- V169 Federation visibility (wave c): written ONLY by AuctionFederationStore. ----
        attribute(name = "federationOptIn", type = "Boolean") {
            defaultValue = "false"
            stereotype("Column") { "columnName" to "federation_opt_in" }
        }
        attribute(name = "federationOptInAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "federation_opt_in_at" }
        }
    }

    val auctionBid = classOf(name = "AuctionBid") {
        stereotype("Entity") { "tableName" to "auction_bid"; "kotlinObjectName" to "AuctionBidTable" }
        stereotype("Index") {
            "columns" to listOf("auction_id", "bidder_member_id")
            "unique" to true
            "name" to "uq_auction_bid_auction_bidder"
        }
        stereotype("Index") { "columns" to listOf("auction_id"); "name" to "idx_auction_bid_auction" }
        stereotype("Index") { "columns" to listOf("bidder_member_id"); "name" to "idx_auction_bid_bidder" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // Real FK -> auction (id), NOT NULL. Plain «Column» UUID attribute -- see file header
        // "FK-naming choice" (deliberately plain even though the association-derived default would
        // already match, for a single, exceptionless domain-wide rule).
        attribute(name = "auctionId", type = "UUID") {
            stereotype("Column") { "columnName" to "auction_id"; "fkEntity" to "Auction" }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute -- see file header.
        attribute(name = "bidderMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "bidder_member_id"; "fkEntity" to "Member" }
        }
        // NULL on a bid of a Fiat auction (V166) -- exactly one of maxBidLtr/maxBidMinor is set (CHECK chk_auction_bid_amount_columns).
        attribute(name = "maxBidLtr", type = "BigDecimal") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "max_bid_ltr"; "sqlType" to "DECIMAL(18,2)" }
        }
        attribute(name = "maxBidMinor", type = "Long") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "max_bid_minor" }
        }
        // This bidder's LAST bid time on this auction, re-stamped on every raise -- see file
        // header "auction_bid is an upsert table".
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
    }

    val auctionComplianceAcknowledgment = classOf(name = "AuctionComplianceAcknowledgment") {
        stereotype("Entity") {
            "tableName" to "auction_compliance_acknowledgment"
            "kotlinObjectName" to "AuctionComplianceAcknowledgmentTable"
        }
        stereotype("Index") { "columns" to listOf("acknowledged_at"); "name" to "idx_auction_compliance_ack_at" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // Real FK -> member (id), NOT NULL -- always an ADMIN at the time of acknowledgment (role
        // is not itself persisted here, only the actor's identity). Plain «Column» UUID attribute
        // -- see file header "FK-naming choice".
        attribute(name = "acknowledgedByMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "acknowledged_by_member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "acknowledgedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "acknowledged_at" }
        }
        // Echoes network.lapis.cloud.server.rpc.AuctionComplianceDisclaimer.VERSION at the moment
        // of acknowledgment -- see that object's KDoc.
        attribute(name = "disclaimerVersion", type = "String") {
            stereotype("Column") { "columnName" to "disclaimer_version"; "sqlType" to "VARCHAR(50)" }
        }
        // Echoes AuctionComplianceDisclaimer.SHA256 at the moment of acknowledgment -- a SHA-256
        // digest is always exactly 64 lowercase hex characters.
        attribute(name = "disclaimerSha256", type = "String") {
            stereotype("Column") { "columnName" to "disclaimer_sha256"; "sqlType" to "VARCHAR(64)" }
        }
    }
    // V166: the seller's payment details, ONE sealed JSON blob in its own table (see file header "Fiat path"). PK = FK -> auction.
    val auctionPaymentDetails = classOf(name = "AuctionPaymentDetails") {
        stereotype("Entity") { "tableName" to "auction_payment_details"; "kotlinObjectName" to "AuctionPaymentDetailsTable" }

        attribute(name = "auctionId", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "auction_id"; "fkEntity" to "Auction" }
        }
        // SecretBox "v1:<iv>:<ct>" over {accountHolder, iban, paymentLink}, aad = auction id. Never exported, never logged.
        attribute(name = "detailsCiphertext", type = "String") {
            stereotype("Column") { "columnName" to "details_ciphertext"; "sqlType" to "VARCHAR(2000)" }
        }
        attribute(name = "hasIban", type = "Boolean") {
            stereotype("Column") { "columnName" to "has_iban" }
        }
        attribute(name = "hasPaymentLink", type = "Boolean") {
            stereotype("Column") { "columnName" to "has_payment_link" }
        }
        attribute(name = "updatedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "updated_at" }
        }
    }

    // V166: append-only record of who acknowledged which Fiat disclaimer version (OPERATOR = administrator, SELLER = listing member).
    val auctionFiatAcknowledgment = classOf(name = "AuctionFiatAcknowledgment") {
        stereotype("Entity") { "tableName" to "auction_fiat_acknowledgment"; "kotlinObjectName" to "AuctionFiatAcknowledgmentTable" }
        stereotype("Index") {
            "columns" to listOf("acknowledged_by_member_id", "kind", "disclaimer_version")
            "name" to "idx_auction_fiat_ack_member_kind"
        }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "kind", type = "String") {
            stereotype("Column") { "columnName" to "kind"; "sqlType" to "VARCHAR(8)" }
        }
        attribute(name = "acknowledgedByMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "acknowledged_by_member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "acknowledgedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "acknowledged_at" }
        }
        attribute(name = "disclaimerVersion", type = "String") {
            stereotype("Column") { "columnName" to "disclaimer_version"; "sqlType" to "VARCHAR(50)" }
        }
        attribute(name = "disclaimerSha256", type = "String") {
            stereotype("Column") { "columnName" to "disclaimer_sha256"; "sqlType" to "VARCHAR(64)" }
        }
    }

    // Literal order is load-bearing (AuctionSchemaDriftTest) and matches network.lapis.cloud.shared.domain.AuctionOfferStatus. Append only.
    val auctionOfferStatus = enumOf(name = "AuctionOfferStatus") {
        literal(name = "OFFERED")
        literal(name = "ACCEPTED")
        literal(name = "DECLINED")
        literal(name = "COUNTERED")
        literal(name = "EXPIRED")
        literal(name = "WITHDRAWN")
    }

    // V167: ONE row per price proposal. A counter-offer is a NEW row (from_seller = true, parent = the buyer's proposal); the old row becomes
    // COUNTERED. parent_offer_id is genuinely SELF-referential: plain nullable UUID column without fkEntity (same precedent as
    // document_folder.parent_folder_id), the real FK exists only in V167.
    val auctionOffer = classOf(name = "AuctionOffer") {
        stereotype("Entity") { "tableName" to "auction_offer"; "kotlinObjectName" to "AuctionOfferTable" }
        stereotype("Index") { "columns" to listOf("auction_id", "status"); "name" to "idx_auction_offer_auction_status" }
        stereotype("Index") { "columns" to listOf("buyer_member_id", "status"); "name" to "idx_auction_offer_buyer_status" }
        stereotype("Index") { "columns" to listOf("parent_offer_id"); "unique" to true; "name" to "uq_auction_offer_parent" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "auctionId", type = "UUID") {
            stereotype("Column") { "columnName" to "auction_id"; "fkEntity" to "Auction" }
        }
        // The buyer of the negotiation: also on a counter-offer row (it is addressed to this member).
        attribute(name = "buyerMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "buyer_member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "fromSeller", type = "Boolean") {
            stereotype("Column") { "columnName" to "from_seller" }
        }
        attribute(name = "parentOfferId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "parent_offer_id" }
        }
        attribute(name = "amountMinor", type = "Long") {
            stereotype("Column") { "columnName" to "amount_minor" }
        }
        attribute(name = "status", type = auctionOfferStatus) {
            stereotype("Column") { "columnName" to "status"; "enumType" to "network.lapis.cloud.shared.domain.AuctionOfferStatus" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        attribute(name = "expiresAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "expires_at" }
        }
        attribute(name = "decidedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "decided_at" }
        }
        // V170: the notice the AUTHOR was shown (version, language, SHA-256 of "version\ntext"). NULL only on rows from before V170.
        attribute(name = "noticeVersion", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "notice_version"; "sqlType" to "VARCHAR(32)" }
        }
        attribute(name = "noticeLang", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "notice_lang"; "sqlType" to "VARCHAR(5)" }
        }
        attribute(name = "noticeSha256", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "notice_sha256"; "sqlType" to "VARCHAR(64)" }
        }
    }

    // V170: the decline counter of the current cycle and the end of the decline block, ONE row per (auction, buyer). Written ONLY by
    // AuctionOfferRestrictionStore and ONLY under the auction row lock. Surrogate id plus a UNIQUE pair (no composite primary key in the ERM profile).
    val auctionOfferBuyerRestriction = classOf(name = "AuctionOfferBuyerRestriction") {
        stereotype("Entity") { "tableName" to "auction_offer_buyer_restriction"; "kotlinObjectName" to "AuctionOfferBuyerRestrictionTable" }
        stereotype("Index") { "columns" to listOf("auction_id", "buyer_member_id"); "unique" to true; "name" to "uq_aobr_auction_buyer" }
        stereotype("Index") { "columns" to listOf("buyer_member_id"); "name" to "idx_aobr_buyer" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "auctionId", type = "UUID") {
            stereotype("Column") { "columnName" to "auction_id"; "fkEntity" to "Auction" }
        }
        attribute(name = "buyerMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "buyer_member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "declinesInCycle", type = "Int") {
            stereotype("Column") { "columnName" to "declines_in_cycle" }
        }
        attribute(name = "blockedUntil", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "blocked_until" }
        }
        attribute(name = "updatedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "updated_at" }
        }
    }

    // ---- V169 Federation visibility (wave c). id-only stub so the received/tombstone tables can name their partner foreign keys. ----
    val partnerServer =
        classOf(name = "PartnerServer") {
            stereotype("Entity") { "tableName" to "partner_server"; "kotlinObjectName" to "PartnerServerTable" }
            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
        }

    // What THIS server last told its partners about one shared auction (revision + the throttled price). Written ONLY by AuctionFederationStore.
    // Excluded from the organization backup (a restore resets it).
    val auctionTeaserSnapshot =
        classOf(name = "AuctionTeaserSnapshot") {
            stereotype("Entity") { "tableName" to "auction_teaser_snapshot"; "kotlinObjectName" to "AuctionTeaserSnapshotTable" }
            attribute(name = "auctionId", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "auction_id"; "fkEntity" to "Auction" }
            }
            attribute(name = "teaserId", type = "String") {
                stereotype("Column") { "columnName" to "teaser_id"; "sqlType" to "VARCHAR(64)" }
            }
            attribute(name = "revision", type = "Long") {
                stereotype("Column") { "columnName" to "revision" }
            }
            attribute(name = "priceKind", type = "String") {
                stereotype("Column") { "columnName" to "price_kind"; "sqlType" to "VARCHAR(8)" }
            }
            attribute(name = "priceMinor", type = "Long") {
                stereotype("Column") { "columnName" to "price_minor" }
            }
            attribute(name = "priceAsOf", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "price_as_of" }
            }
            attribute(name = "contentSha256", type = "String") {
                stereotype("Column") { "columnName" to "content_sha256"; "sqlType" to "CHAR(64)" }
            }
            attribute(name = "updatedAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "updated_at" }
            }
        }

    // The tombstone of a withdrawn hint (own or passed on). Written ONLY by AuctionFederationStore. Stays in the backup. The FK to partner_server is
    // ON DELETE CASCADE in the real schema; the generated table cannot express that, so the store deletes the rows of a removed partner explicitly.
    val auctionTeaserTombstone =
        classOf(name = "AuctionTeaserTombstone") {
            stereotype("Entity") { "tableName" to "auction_teaser_tombstone"; "kotlinObjectName" to "AuctionTeaserTombstoneTable" }
            stereotype("Index") { "columns" to listOf("teaser_id"); "name" to "idx_auction_teaser_tombstone_teaser" }
            stereotype("Index") { "columns" to listOf("expires_at"); "name" to "idx_auction_teaser_tombstone_expires" }
            stereotype("Index") { "columns" to listOf("partner_id"); "name" to "idx_auction_teaser_tombstone_partner" }
            stereotype("Index") { "columns" to listOf("relay_reporter_partner_id"); "name" to "idx_auction_teaser_tombstone_relay_reporter" }
            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
            attribute(name = "teaserId", type = "String") {
                stereotype("Column") { "columnName" to "teaser_id"; "sqlType" to "VARCHAR(64)" }
            }
            attribute(name = "partnerId", type = "UUID") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "partner_id"; "fkEntity" to "PartnerServer" }
            }
            attribute(name = "createdAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "created_at" }
            }
            attribute(name = "expiresAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "expires_at" }
            }
            attribute(name = "relayOriginHost", type = "String") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "relay_origin_host"; "sqlType" to "VARCHAR(253)" }
            }
            attribute(name = "relayReporterPartnerId", type = "UUID") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "relay_reporter_partner_id" }
            }
        }

    // A hint of ANOTHER server. Written and read ONLY by AuctionReceivedStore. Excluded from the backup. FK ON DELETE CASCADE in the real schema.
    val auctionReceivedHint =
        classOf(name = "AuctionReceivedHint") {
            stereotype("Entity") { "tableName" to "auction_received_hint"; "kotlinObjectName" to "AuctionReceivedHintTable" }
            stereotype("Index") { "columns" to listOf("origin_host", "teaser_id"); "unique" to true; "name" to "uq_auction_received_hint_origin_teaser" }
            stereotype("Index") { "columns" to listOf("source_partner_id"); "name" to "idx_auction_received_hint_source" }
            stereotype("Index") { "columns" to listOf("expires_at"); "name" to "idx_auction_received_hint_expires" }
            stereotype("Index") { "columns" to listOf("teaser_id"); "name" to "idx_auction_received_hint_teaser" }
            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
            attribute(name = "teaserId", type = "String") {
                stereotype("Column") { "columnName" to "teaser_id"; "sqlType" to "VARCHAR(64)" }
            }
            attribute(name = "originHost", type = "String") {
                stereotype("Column") { "columnName" to "origin_host"; "sqlType" to "VARCHAR(253)" }
            }
            attribute(name = "hops", type = "Int") {
                stereotype("Column") { "columnName" to "hops"; "sqlType" to "SMALLINT" }
            }
            attribute(name = "maxHops", type = "Int") {
                stereotype("Column") { "columnName" to "max_hops"; "sqlType" to "SMALLINT" }
            }
            attribute(name = "viaHosts", type = "String") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "via_hosts"; "sqlType" to "VARCHAR(800)" }
            }
            attribute(name = "sourcePartnerId", type = "UUID") {
                stereotype("Column") { "columnName" to "source_partner_id"; "fkEntity" to "PartnerServer" }
            }
            attribute(name = "revision", type = "Long") {
                stereotype("Column") { "columnName" to "revision" }
            }
            attribute(name = "title", type = "String") {
                stereotype("Column") { "columnName" to "title"; "sqlType" to "VARCHAR(80)" }
            }
            attribute(name = "currency", type = "String") {
                stereotype("Column") { "columnName" to "currency"; "sqlType" to "VARCHAR(3)" }
            }
            attribute(name = "priceKind", type = "String") {
                stereotype("Column") { "columnName" to "price_kind"; "sqlType" to "VARCHAR(8)" }
            }
            attribute(name = "priceMinor", type = "Long") {
                stereotype("Column") { "columnName" to "price_minor" }
            }
            attribute(name = "buyNowMinor", type = "Long") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "buy_now_minor" }
            }
            attribute(name = "offersPossible", type = "Boolean") {
                stereotype("Column") { "columnName" to "offers_possible" }
            }
            attribute(name = "endsAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "ends_at" }
            }
            attribute(name = "priceAsOf", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "price_as_of" }
            }
            attribute(name = "originSignature", type = "String") {
                stereotype("Column") { "columnName" to "origin_signature"; "sqlType" to "VARCHAR(1024)" }
            }
            attribute(name = "receivedAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "received_at" }
            }
            attribute(name = "lastSeenAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "last_seen_at" }
            }
            attribute(name = "expiresAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "expires_at" }
            }
        }

    // A LOCAL block (WITHDRAWN = the origin withdrew it, HIDDEN = an administrator hid it). Never sent anywhere. Excluded from the backup.
    val auctionReceivedTombstone =
        classOf(name = "AuctionReceivedTombstone") {
            stereotype("Entity") { "tableName" to "auction_received_tombstone"; "kotlinObjectName" to "AuctionReceivedTombstoneTable" }
            stereotype("Index") { "columns" to listOf("origin_host", "teaser_id"); "unique" to true; "name" to "uq_auction_received_tombstone_origin_teaser" }
            stereotype("Index") { "columns" to listOf("expires_at"); "name" to "idx_auction_received_tombstone_expires" }
            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
            attribute(name = "teaserId", type = "String") {
                stereotype("Column") { "columnName" to "teaser_id"; "sqlType" to "VARCHAR(64)" }
            }
            attribute(name = "originHost", type = "String") {
                stereotype("Column") { "columnName" to "origin_host"; "sqlType" to "VARCHAR(253)" }
            }
            attribute(name = "kind", type = "String") {
                stereotype("Column") { "columnName" to "kind"; "sqlType" to "VARCHAR(16)" }
            }
            attribute(name = "reportedByPartnerId", type = "UUID") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "reported_by_partner_id"; "fkEntity" to "PartnerServer" }
            }
            attribute(name = "createdAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "created_at" }
            }
            attribute(name = "expiresAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "expires_at" }
            }
        }
}
