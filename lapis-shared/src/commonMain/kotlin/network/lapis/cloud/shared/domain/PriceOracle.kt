package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.Decimal
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * The real-world asset a Libertaler (LTR) is pegged to -- see
 * `network.lapis.cloud.server.economy.oracle.PriceOracleSource` KDoc and
 * `lapis-server/src/main/kuml/19-price-oracle.kuml.kts` file header for the full fachlich model.
 * As of V0.6.6 "Price-Oracle: Gold- und Fiat-Anker" all three literals have real, wired price
 * sources: [BITCOIN_BTC] keeps its three independent, free, no-API-key public endpoints
 * (Coinbase/Kraken/Bitstamp); [GOLD_XAU] gains three key-gated sources (GoldAPI.io, MetalpriceAPI,
 * Alpha Vantage `GOLD_SILVER_SPOT`) of which a deployment must configure at least
 * [AnchorPolicy.quorumFloor] (2 of 3); [FIAT] gains one source, the ECB daily reference-rate feed,
 * with its unit fixed to exactly one EUR (a deliberate scope-cut -- no `anchor_fiat_currency`
 * column exists, see [network.lapis.cloud.shared.rpc.IPriceOracleService.updateOracleConfig]
 * KDoc). Additively extensible -- literal order is pinned by `PriceOracleSchemaDriftTest`.
 */
@Serializable
enum class AnchorAsset { BITCOIN_BTC, GOLD_XAU, FIAT }

/**
 * Code-fixed, per-anchor oracle policy that is deliberately NOT part of [PriceOracleConfigDto] --
 * an ADMIN tunes staleness tolerance and outlier/spread thresholds, but can never lower a quorum
 * floor below the level its anchor's financial integrity depends on, and can never shorten the
 * refresh interval that keeps the request-budgeted gold sources inside their free-tier ceilings
 * (see `GoldPriceSources.kt` KDoc "Free-tier request budget"). Lives in `commonMain` so the client
 * form validates against the SAME numbers the server enforces -- never a duplicated constant
 * (`PriceOracleScreen.kt` KDoc's own "never duplicate a server constant" rule).
 */
object AnchorPolicy {
    /**
     * The minimum number of INDEPENDENT surviving sources a quote for this anchor must be built
     * from. `PriceOracleOrchestrator` clamps `PriceOracleConfigDto.minQuorum` UP to this value --
     * so a `minQuorum` of 1 persisted for BITCOIN_BTC/GOLD_XAU (by a direct SQL edit, a restored
     * backup, or a future validation bug) can never actually produce a single-source quote.
     * [AnchorAsset.FIAT] is deliberately 1: its only authoritative free source is the ECB reference
     * feed, and a single source structurally has no spread to check.
     *
     * **[AnchorAsset.GOLD_XAU]'s floor of 2 provides availability redundancy, not outlier resilience**
     * (Security-Audit-Runde 1 / S5, documented here since this is where the floor value itself lives):
     * at exactly 2 configured sources, the median of two values is always their average, so both
     * sources deviate from it by construction-identical amounts -- outlier rejection can only
     * accept-both or reject-both, never single out the bad one. A single compromised/buggy source
     * among exactly 2 can still skew the reported price while the quote reports LIVE (see
     * `PriceOracleStartupCheck.warnIfGoldRiskyAlphaVantagePairing` KDoc for the concrete bps math and
     * operator guidance). This is true for ANY 2-of-3 gold source pairing, not specific to one -- 3
     * configured sources is the genuine outlier-resilience baseline, 2 is outage-tolerance only.
     */
    fun quorumFloor(anchor: AnchorAsset): Int =
        when (anchor) {
            AnchorAsset.BITCOIN_BTC -> 2
            AnchorAsset.GOLD_XAU -> 2
            AnchorAsset.FIAT -> 1
        }

    /**
     * How long a successful quote for this anchor is served straight from cache WITHOUT re-querying
     * any source. `0` for [AnchorAsset.BITCOIN_BTC] -- every call fans out, exactly as before this
     * wave (the zero-regression property). See `GoldPriceSources.kt` KDoc for the free-tier
     * arithmetic behind 12 h (43 200 s).
     */
    fun refreshIntervalSeconds(anchor: AnchorAsset): Int =
        when (anchor) {
            AnchorAsset.BITCOIN_BTC -> 0
            AnchorAsset.GOLD_XAU -> 43_200
            AnchorAsset.FIAT -> 43_200
        }

    /**
     * Advisory only -- shown in the ADMIN form, never enforced beyond the `>= refreshIntervalSeconds`
     * floor. Tracks the volatility table in the concept document ("Meritokratisches System und
     * Libertaler.md", § Anker-spezifische Eigenschaften).
     */
    fun recommendedCacheTtlSeconds(anchor: AnchorAsset): Int =
        when (anchor) {
            AnchorAsset.BITCOIN_BTC -> 300
            AnchorAsset.GOLD_XAU -> 172_800
            AnchorAsset.FIAT -> 172_800
        }
}

/**
 * The trustworthiness of a [OraclePriceStatusDto]/[PriceOracleConversionDto] price quote --
 * [LIVE] (every configured source for the active anchor agreed within the outlier threshold),
 * [DEGRADED] (at least [PriceOracleConfigDto.minQuorum] sources survived, but fewer than every
 * configured source), or [CACHED] (live quorum could not be reached, but a still-fresh cached
 * price -- within [PriceOracleConfigDto.cacheTtlSeconds] -- was used instead). There is
 * deliberately no HALT literal here: a halted quote mints nothing and writes no
 * [PriceOracleConversionDto] row at all, so HALT never needs to be a stored value -- see
 * `network.lapis.cloud.server.rpc.PriceOracleService.convertDonationToLtr` KDoc.
 *
 * [DEFERRED] is reserved-but-unused (no code path ever writes it) -- see
 * `19-price-oracle.kuml.kts` file header "Scope-cut: no persistent halt-queue" for why the
 * concept document's persistent halt-queue/deferred-retroactive-pricing mechanism is not built
 * this wave, and why the literal is nonetheless defined now (a later wave can add the queue
 * without an enum-literal-order-breaking re-model). Additively extensible -- literal order is
 * pinned by `PriceOracleSchemaDriftTest`.
 */
@Serializable
enum class PriceStatus { LIVE, DEGRADED, CACHED, DEFERRED }

/**
 * Single-row, ADMIN-tunable oracle policy -- see `19-price-oracle.kuml.kts` file header. No
 * create/delete RPC, only
 * [network.lapis.cloud.shared.rpc.IPriceOracleService.getOracleConfig]/
 * [network.lapis.cloud.shared.rpc.IPriceOracleService.updateOracleConfig], both always targeting
 * the one seeded row. **SSRF invariant**: this DTO exposes no URL/host/source field on purpose --
 * price sources stay a code-fixed, allowlisted set
 * (`network.lapis.cloud.server.economy.oracle.defaultBitcoinOracleSources`); an ADMIN tunes only
 * scalar policy (which anchor/currency, the peg, cache TTL, quorum, outlier/spread thresholds).
 * [anchorUnitsPerLtr] is the peg (how many [anchorAsset] units back exactly one LTR) -- a much
 * higher-precision decimal than every other money field in this codebase, since one LTR is
 * expected to be worth a tiny fraction of one BTC.
 */
@Serializable
data class PriceOracleConfigDto(
    val id: String,
    val anchorAsset: AnchorAsset,
    val donationCurrency: String,
    val anchorUnitsPerLtr: Decimal,
    val cacheTtlSeconds: Int,
    val minQuorum: Int,
    val outlierThresholdBps: Int,
    val maxSpreadBps: Int,
    val updatedAt: LocalDateTime,
)

/** Replaces every field of the single [PriceOracleConfigDto] row wholesale (no partial update). Role: ADMIN only -- see [PriceOracleConfigDto] KDoc "SSRF invariant". */
@Serializable
data class PriceOracleConfigInput(
    val anchorAsset: AnchorAsset,
    val donationCurrency: String,
    val anchorUnitsPerLtr: Decimal,
    val cacheTtlSeconds: Int,
    val minQuorum: Int,
    val outlierThresholdBps: Int,
    val maxSpreadBps: Int,
)

/**
 * A diagnostic read of the oracle's CURRENT price quote -- never mints anything (see
 * [network.lapis.cloud.shared.rpc.IPriceOracleService.previewCurrentPrice]). Exactly one of
 * ([status]/[medianPrice]/[priceTimestamp] non-null, [halted] == false) or ([halted] == true,
 * [haltReason] non-null, the other three null) holds.
 */
@Serializable
data class OraclePriceStatusDto(
    val status: PriceStatus?,
    val halted: Boolean,
    val haltReason: String?,
    val medianPrice: Decimal?,
    val sourceIds: List<String>,
    val priceTimestamp: LocalDateTime?,
)

/**
 * Role: TREASURER/BOARD/ADMIN (same tier as [MintLtrInput] -- a real payment-intake webhook is
 * out of scope this wave, this is the operator-triggered booking of an already-received
 * donation). [donationAmount] must be strictly positive with at most 2 decimal places, denominated
 * in [PriceOracleConfigDto.donationCurrency].
 */
@Serializable
data class DonationConversionInput(
    val memberId: String,
    val donationAmount: Decimal,
    val note: String? = null,
)

/**
 * The permanent provenance record of one donation -> LTR conversion -- see
 * `19-price-oracle.kuml.kts` file header "Trust und Auditierbarkeit" paragraph.
 * [ltrLedgerEntryId] points at the `MINT` [LtrLedgerEntryDto] this conversion caused (written in
 * the SAME transaction). [priceStatus] is never a halt-representing value -- a halted quote mints
 * nothing and this row is never written for it.
 */
@Serializable
data class PriceOracleConversionDto(
    val id: String,
    val memberId: String,
    val donationAmount: Decimal,
    val donationCurrency: String,
    val anchorAsset: AnchorAsset,
    val anchorPrice: Decimal,
    val anchorUnitsPerLtr: Decimal,
    val ltrMinted: Decimal,
    val priceStatus: PriceStatus,
    val sourceCount: Int,
    val sourcesUsed: String,
    val priceTimestamp: LocalDateTime,
    val ltrLedgerEntryId: String,
    val createdById: String?,
    val createdAt: LocalDateTime,
)
