package network.lapis.cloud.server.economy.oracle

import network.lapis.cloud.shared.domain.AnchorAsset
import kotlin.time.Clock

/**
 * The single [AnchorAsset.FIAT] source -- a thin wrapper over the shared [EcbReferenceRateClient]
 * (see that class's own KDoc for the XXE hardening, response cap, allowlist check and XML parse,
 * all implemented exactly once and shared with `AlphaVantageGoldPriceSource`'s conversion leg).
 *
 * **The FIAT anchor unit is code-fixed to exactly one EUR** (a deliberate scope-cut -- no
 * `anchor_fiat_currency` column exists, see `network.lapis.cloud.shared.rpc.IPriceOracleService`
 * KDoc). [PriceOracleSource.fetchPrice] asks for "the price of one anchor unit in the donation
 * currency" -- i.e. one EUR's price in [fetchPrice]'s `donationCurrency` argument. For a EUR
 * donation that is trivially `1` (**zero HTTP requests** -- a EUR-anchored, EUR-donation deployment
 * has no exchange risk and no external dependency at all). For any other supported donation
 * currency (currently only `USD`) it is [EcbReferenceRateClient.ratePerEur] read verbatim: e.g.
 * `rate='1.1605'` for USD means 1 EUR = 1.1605 USD, so `1.1605` is the answer -- **do not invert**.
 * The generic "ECB rates are EUR-based, so invert them" reflex is wrong here, and MetalpriceAPI's
 * `base=` convention (in `GoldPriceSources.kt`) running the opposite way makes this asymmetry look
 * like a bug to a future reader; it is not one.
 */
class EcbFiatPriceSource(
    private val ecbRates: EcbRateSource,
    override val id: String = "ecb",
) : PriceOracleSource {
    override val anchor: AnchorAsset = AnchorAsset.FIAT

    override suspend fun fetchPrice(donationCurrency: String): SourcePriceResult? {
        val rate = ecbRates.ratePerEur(donationCurrency) ?: return null
        if (rate.signum() <= 0) return null
        return SourcePriceResult(sourceId = id, price = rate, observedAt = Clock.System.now())
    }
}

/** The one FIAT source, sharing [ecbRates] with the GOLD anchor's Alpha Vantage conversion leg -- see `network.lapis.cloud.server.economy.oracle.defaultOracleSources`. */
fun defaultFiatOracleSources(ecbRates: EcbRateSource): List<PriceOracleSource> = listOf(EcbFiatPriceSource(ecbRates = ecbRates))
