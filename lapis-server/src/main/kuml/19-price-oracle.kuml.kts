// Price-Oracle-fuer-die-Anker-Bindung domain (V0.6.5) -- see the concept document
// ("03 Bereiche/Lapis Cloud/Meritokratisches System und Libertaler.md", "Price-Oracle fuer die
// Anker-Bindung" section, vault) for the full fachlich specification this implements.
//
// **Why this wave exists at all**: before V0.6.5 there was no money -> LTR conversion path
// anywhere in this codebase. `ltr_ledger_entry`'s only credit-writing RPC (`mintLtr`,
// 08-ltr-balance.kuml.kts) takes an already-decided, explicit LTR amount -- nobody computes an
// LTR amount from a real-world donation via an exchange rate. This wave adds exactly that
// boundary: `IPriceOracleService.convertDonationToLtr` MINTs LTR from a donation amount using an
// oracle-derived anchor price, and writes a permanent provenance record
// (`price_oracle_conversion`) per the concept's "Trust und Auditierbarkeit" section -- otherwise
// the oracle would be an unused standalone module with nothing load-bearing depending on it.
//
// **Scope-cut: no persistent halt-queue.** The concept document's step-5 describes a persistent
// halt-queue with deferred/retroactive-vs-forward pricing once a trustworthy price returns. This
// wave does NOT build that queue. Instead, HALT means `convertDonationToLtr` REJECTS the request
// (throws, mints nothing) -- fully satisfying "conversions must halt rather than silently using a
// stale/unreliable price" without inventing a queueing/retro-booking mechanism this wave has no
// budget to get right. `PriceStatus.DEFERRED` is still DEFINED below (reserved-but-unused, no
// code path ever writes it) -- same reserved-literal idiom `LtrLedgerEntryType.PROJECT_STAKE_RELEASE`
// already established -- so a later wave can add the queue without an enum-literal-order-breaking
// re-model.
//
// **V0.6.6 "Price-Oracle: Gold- und Fiat-Anker": all three anchors now wired.** `AnchorAsset`
// defines three literals (BITCOIN_BTC/GOLD_XAU/FIAT), and as of V0.6.6 all three have real price
// sources: BITCOIN_BTC keeps its three free, no-API-key public endpoints
// (network.lapis.cloud.server.economy.oracle.defaultBitcoinOracleSources: Coinbase/Kraken/
// Bitstamp); GOLD_XAU gains three key-gated sources (defaultGoldOracleSources: GoldAPI.io,
// MetalpriceAPI, Alpha Vantage `GOLD_SILVER_SPOT`), of which a deployment must configure at least
// `AnchorPolicy.quorumFloor(GOLD_XAU)` (2 of 3, via the optional `LAPIS_ORACLE_GOLDAPI_KEY`/
// `LAPIS_ORACLE_METALPRICEAPI_KEY`/`LAPIS_ORACLE_ALPHAVANTAGE_KEY` env vars,
// network.lapis.cloud.server.economy.oracle.OracleSourceConfig); FIAT gains one source
// (defaultFiatOracleSources: the ECB daily reference-rate feed, keyless), its anchor unit
// code-fixed to exactly one EUR (a deliberate scope-cut -- no `anchor_fiat_currency` column, so an
// arbitrary fiat anchor currency is a later wave's addition). `IPriceOracleService.updateOracleConfig`
// still rejects an anchor switch a given DEPLOYMENT cannot serve (fewer configured sources than the
// anchor's quorum floor) with a clear error naming the missing env vars, rather than silently
// accepting a config with insufficient sources behind it. The `PriceOracleSource` interface itself
// stayed anchor-agnostic throughout -- wiring the gold/fiat source sets was an additive change, not
// a re-model of this one. `PriceOracleOrchestrator` itself gained two prerequisite fixes this wave:
// it now queries only the ACTIVE anchor's sources (previously it queried every configured source
// regardless of anchor) and its cache is now keyed by (anchor, donationCurrency) (previously a
// single unkeyed slot) -- both were latent bugs that would have let e.g. a BTC price be served as a
// gold price the moment a second anchor existed.
//
// **price_oracle_config is a single-row, ADMIN-tunable policy row** -- same "exactly one row by
// convention, seeded once with a fixed sentinel id" idiom `organization_settings`
// (11-organization-settings.kuml.kts)/`crowdfunding_submission_gate` (17-crowdfunding.kuml.kts)
// already establish. NO member FK at all (pure scalar policy: which anchor, which donation
// currency, the peg itself, cache TTL, quorum, outlier/spread thresholds) -- see
// PersonalDataRegistry.noPersonalDataAllowlist for the corresponding entry. `anchorUnitsPerLtr`
// is the peg (how many anchor-asset units back one LTR) -- DECIMAL(38,18), a much higher scale
// than every other money column in this codebase (DECIMAL(18,2)) because one LTR is expected to
// be worth a tiny fraction of one BTC, and a 2-decimal peg would round to zero.
//
// **price_oracle_conversion is the permanent provenance record** the concept's "Trust und
// Auditierbarkeit" section calls for -- one row per donation->LTR conversion, snapshotting
// everything needed to reconstruct after the fact WHY a given LTR amount was minted: the donation
// amount/currency, which anchor and which median price (in donationCurrency) were used, the peg
// snapshot, the resulting LTR amount, the oracle's status (LIVE/DEGRADED/CACHED -- never HALT,
// since a HALTed quote mints nothing and therefore writes no row here at all), how many sources
// contributed and which ones (`sourcesUsed`, a comma-joined audit trail, NOT a FK -- sources are a
// small, code-fixed set, not a first-class entity table), and the price's OWN timestamp
// (`priceTimestamp`, which may predate the conversion transaction itself, especially for a CACHED
// quote -- concept document line ~101).
//
// **`ltrLedgerEntryId` is a plain, non-FK «Column» UUID** pointing at the `MINT` `ltr_ledger_entry`
// row this conversion caused -- deliberately NOT a real FK. Same polymorphic-pointer idiom
// `ltr_ledger_entry.reference_id` itself already uses (see 08-ltr-balance.kuml.kts): adding a real
// FK here would mean either giving this file its own `LtrLedgerEntry` stub (redundant, since this
// file already needs a `Member` stub) or reaching across domains for a target entity id that
// `UmlToErmTransformer` cannot resolve from a single-file evaluation anyway. The
// `PriceOracleConversionTable`/`LtrLedgerEntryTable` pairing is instead verified at the service
// layer (`PriceOracleService.convertDonationToLtr` writes both rows in the SAME transaction, the
// conversion row's `ltrLedgerEntryId` always equal to the MINT row's real `id`).
//
// **Two separate member FKs on price_oracle_conversion, two different naming mechanisms**:
// `memberId` (the LTR recipient -- the donor being credited) is modelled as a genuine UML
// association, because the association-derived default column name ("member_id") is exactly what
// this column is already called -- same "association default already matches" case
// `crowdfunding_reaction.member_id` documents in 17-crowdfunding.kuml.kts. `createdById` (the
// acting TREASURER/BOARD/ADMIN who triggered the conversion, nullable) is instead a plain
// «Column» UUID attribute with «Column».fkEntity="Member", because a SECOND association to the
// same target class would either collide with the first association's class-derived default or
// require a `role` override this codebase has never actually exercised for renaming purposes (see
// 18-peer-transfer.kuml.kts's own "FK-naming choice" paragraph for the identical reasoning,
// applied there to three FKs instead of two).
//
// This file, like every other domain file with an FK to Member, carries a minimal id-only Member
// stub (owned by Foundation) purely so UmlToErmTransformer can resolve the association and the
// «Column».fkEntity override within this single-file evaluation.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "PriceOracle") {
    applyProfile(ermMappingProfile)

    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Literal order is load-bearing: PriceOracleSchemaDriftTest asserts ErmDataType.Enum.values in
    // exactly this order, matching network.lapis.cloud.shared.domain.AnchorAsset. Only BITCOIN_BTC
    // has real wired sources this wave -- see file header "Scope-cut: Bitcoin-only anchor".
    val anchorAssetEnum = enumOf(name = "AnchorAsset") {
        literal(name = "BITCOIN_BTC")
        literal(name = "GOLD_XAU")
        literal(name = "FIAT")
    }

    // Literal order is load-bearing, same reason as above -- matches
    // network.lapis.cloud.shared.domain.PriceStatus. DEFERRED is reserved-but-unused -- see file
    // header "Scope-cut: no persistent halt-queue".
    val priceStatusEnum = enumOf(name = "PriceStatus") {
        literal(name = "LIVE")
        literal(name = "DEGRADED")
        literal(name = "CACHED")
        literal(name = "DEFERRED")
    }

    // Single-row, ADMIN-tunable oracle policy -- see file header. No FK to Member at all.
    val priceOracleConfig = classOf(name = "PriceOracleConfig") {
        stereotype("Entity") { "tableName" to "price_oracle_config"; "kotlinObjectName" to "PriceOracleConfigTable" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "anchorAsset", type = anchorAssetEnum) {
            stereotype("Column") {
                "columnName" to "anchor_asset"
                "enumType" to "network.lapis.cloud.shared.domain.AnchorAsset"
            }
        }
        attribute(name = "donationCurrency", type = "String") {
            stereotype("Column") { "columnName" to "donation_currency"; "sqlType" to "VARCHAR(3)" }
        }
        // The peg: how many anchor-asset units back exactly one LTR. High scale (DECIMAL(38,18),
        // not this codebase's usual DECIMAL(18,2)) because one LTR is expected to be worth a tiny
        // fraction of one BTC -- see file header.
        attribute(name = "anchorUnitsPerLtr", type = "BigDecimal") {
            stereotype("Column") { "columnName" to "anchor_units_per_ltr"; "sqlType" to "DECIMAL(38,18)" }
        }
        attribute(name = "cacheTtlSeconds", type = "Int") {
            stereotype("Column") { "columnName" to "cache_ttl_seconds" }
        }
        attribute(name = "minQuorum", type = "Int") {
            stereotype("Column") { "columnName" to "min_quorum" }
        }
        attribute(name = "outlierThresholdBps", type = "Int") {
            stereotype("Column") { "columnName" to "outlier_threshold_bps" }
        }
        attribute(name = "maxSpreadBps", type = "Int") {
            stereotype("Column") { "columnName" to "max_spread_bps" }
        }
        attribute(name = "updatedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "updated_at" }
        }
    }

    // Permanent provenance record for one donation -> LTR conversion -- see file header "Trust
    // und Auditierbarkeit" paragraph.
    val priceOracleConversion = classOf(name = "PriceOracleConversion") {
        stereotype("Entity") { "tableName" to "price_oracle_conversion"; "kotlinObjectName" to "PriceOracleConversionTable" }
        stereotype("Index") { "columns" to listOf("member_id"); "name" to "idx_price_oracle_conversion_member" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "donationAmount", type = "BigDecimal") {
            stereotype("Column") { "columnName" to "donation_amount"; "sqlType" to "DECIMAL(18,2)" }
        }
        // Snapshot at conversion time -- may differ from a later-reconfigured
        // price_oracle_config.donation_currency.
        attribute(name = "donationCurrency", type = "String") {
            stereotype("Column") { "columnName" to "donation_currency"; "sqlType" to "VARCHAR(3)" }
        }
        attribute(name = "anchorAsset", type = anchorAssetEnum) {
            stereotype("Column") {
                "columnName" to "anchor_asset"
                "enumType" to "network.lapis.cloud.shared.domain.AnchorAsset"
            }
        }
        // The median anchor price (in donationCurrency) actually used -- see
        // network.lapis.cloud.server.economy.oracle.PriceOracleOrchestrator.
        attribute(name = "anchorPrice", type = "BigDecimal") {
            stereotype("Column") { "columnName" to "anchor_price"; "sqlType" to "DECIMAL(38,18)" }
        }
        // Peg snapshot at conversion time -- may differ from a later-reconfigured
        // price_oracle_config.anchor_units_per_ltr.
        attribute(name = "anchorUnitsPerLtr", type = "BigDecimal") {
            stereotype("Column") { "columnName" to "anchor_units_per_ltr"; "sqlType" to "DECIMAL(38,18)" }
        }
        attribute(name = "ltrMinted", type = "BigDecimal") {
            stereotype("Column") { "columnName" to "ltr_minted"; "sqlType" to "DECIMAL(18,2)" }
        }
        attribute(name = "priceStatus", type = priceStatusEnum) {
            stereotype("Column") {
                "columnName" to "price_status"
                "enumType" to "network.lapis.cloud.shared.domain.PriceStatus"
            }
        }
        attribute(name = "sourceCount", type = "Int") {
            stereotype("Column") { "columnName" to "source_count" }
        }
        // Comma-joined source ids (e.g. "coinbase,kraken,bitstamp") -- an audit trail, NOT a FK.
        // Sources are a small, code-fixed set, never a first-class entity table -- see file header.
        attribute(name = "sourcesUsed", type = "String") {
            stereotype("Column") { "columnName" to "sources_used"; "sqlType" to "VARCHAR(500)" }
        }
        // The price's OWN timestamp -- may predate this row's createdAt, especially for a CACHED
        // quote. See file header.
        attribute(name = "priceTimestamp", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "price_timestamp" }
        }
        // Plain, non-FK «Column» UUID pointing at the MINT ltr_ledger_entry row this conversion
        // caused -- see file header "ltrLedgerEntryId is a plain, non-FK Column" paragraph.
        attribute(name = "ltrLedgerEntryId", type = "UUID") {
            stereotype("Column") { "columnName" to "ltr_ledger_entry_id" }
        }
        // Real FK -> member (id), nullable -- the acting TREASURER/BOARD/ADMIN who triggered the
        // conversion. Plain «Column» attribute, not a second association -- see file header "Two
        // separate member FKs" paragraph.
        attribute(name = "createdById", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "created_by"; "fkEntity" to "Member" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
    }

    // price_oracle_conversion.member_id -> member (id): association-derived default matches
    // ("member_id") -- the LTR recipient. Safe despite this entity ALSO having a second member FK
    // (createdById), because that one is modelled as a plain «Column» attribute, never as a
    // competing association -- see file header.
    association(source = member, target = priceOracleConversion, id = "assoc-member-price_oracle_conversion") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "memberId" }
    }
}
