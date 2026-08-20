package network.lapis.cloud.server.economy.oracle

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import network.lapis.cloud.shared.domain.AnchorAsset
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Clock

/**
 * The three [AnchorAsset.GOLD_XAU] price sources (V0.6.6 "Price-Oracle: Gold- und Fiat-Anker"),
 * named per this codebase's real convention (`<Provider><Asset>PriceSource`, as in
 * [CoinbaseBitcoinPriceSource]). `AnchorPolicy.quorumFloor(GOLD_XAU) == 2` out of these 3 available
 * sources, so a gold-anchored deployment must configure **at least two of the three**
 * `LAPIS_ORACLE_*` keys (see [OracleSourceConfig]) -- configuring all three buys the same
 * one-source-may-fail redundancy the [AnchorAsset.BITCOIN_BTC] set already has.
 *
 * ## Free-tier request budget
 *
 * Sources are only ever queried while `GOLD_XAU` is the *active* anchor (see
 * [PriceOracleOrchestrator]'s anchor-keyed source selection), and then at most once per
 * `AnchorPolicy.refreshIntervalSeconds(GOLD_XAU)` = 12h regardless of how many operators hit
 * `previewCurrentPrice`/`convertDonationToLtr`.
 * - 12h => <=2 fan-outs/day => **<=62 requests/month/source** (31-day month).
 * - GoldAPI.io free tier **100/month** => 62% consumed, **38 spare** (each JVM restart empties the
 *   in-memory cache and costs one extra fan-out, so ~38 restarts/month of headroom).
 * - MetalpriceAPI free tier **100/month** => identical.
 * - Alpha Vantage free tier **25 requests/day, 5/min** => 2/day consumed, **8%**. Its per-minute cap
 *   is the one to watch on restart storms, not its daily cap.
 * - The derived EUR conversion ([AlphaVantageGoldPriceSource]) adds <=2 ECB fetches/day -- keyless,
 *   unmetered, negligible.
 *
 * Rejected alternatives: 6h => 124 requests/month/source, **over budget on the two 100/month
 * sources**. 24h => 31/month (69 spare) -- the conservative fallback if a deployment restarts
 * often, and it matches MetalpriceAPI's own *daily* refresh cadence exactly; change
 * `AnchorPolicy.refreshIntervalSeconds(GOLD_XAU)` to `86_400` if ever needed
 * (`recommendedCacheTtlSeconds` stays valid). 12h is chosen over 24h because GoldAPI.io updates
 * every 2s, so the extra daily fetch buys real freshness at an affordable 62% of budget. The knob
 * is deliberately code-fixed, not ADMIN-tunable: a mis-set interval does not merely degrade the
 * service, it can get the organization's API key rate-limited or banned.
 *
 * **Worst case under repeated admin config saves (Security-Audit-Runde 2 / S9)**: the 12h cadence
 * above is the *normal-operation* rate, not a hard ceiling by itself -- a rapid sequence of genuinely
 * different `updateOracleConfig` changes can each force a fresh fan-out (see
 * `PriceOracleOrchestrator.invalidateReplayState` KDoc). `PriceOracleOrchestrator`'s
 * `HARD_FLOOR_FANOUT_DIVISOR` bounds that worst case to at most one real fan-out per
 * `refreshIntervalSeconds(GOLD_XAU) / 3` = 4h, i.e. **at most 6 fan-outs/day/key** even under
 * continuous adversarial config-save abuse -- a 3x multiple of this section's `<=2`/day budget
 * assumption, not an unbounded or effectively-unbounded rate. See `PriceOracleOrchestrator.lastFanoutAt`
 * KDoc for the full derivation.
 *
 * **Cadence-mismatch note for operators**: GoldAPI.io is live (2s) and MetalpriceAPI refreshes once
 * a day, and the Alpha Vantage leg carries a daily-fixed ECB conversion, so gold's sources routinely
 * disagree by more than two BTC exchanges would. The seeded 300bps outlier / 1000bps spread
 * defaults absorb gold's ~1%/day drift comfortably; an ADMIN tightening them below ~150bps should
 * expect spurious halts.
 */
private val logger = KotlinLogging.logger {}

/** The unmistakable signature of a metals-API base/quote inversion (see [MetalPriceApiGoldPriceSource] KDoc "TRAP 1") -- a price this small for one troy ounce of gold in any real currency is never legitimate. */
private val METALPRICEAPI_INVERSION_CEILING = BigDecimal("0.1")

/**
 * `GET https://www.goldapi.io/api/XAU/{currency}`, auth via the `x-access-token` header (never the
 * URL). **VERIFIED**: the route is live and this file's happy-path test fixture is the *verbatim*
 * captured response from the old PZB sister-project's own test suite
 * (`.../PDV/PZB/Common/src/test/kotlin/de/pdv/pzb/common/SerializationTest.kt`, `test()`), still
 * shape-identical today. `price` is per troy ounce (confirmed arithmetically against that fixture's
 * own `price_gram_24k` field: `1763.64 / 56.7023 g` ~= 31.1 g = one troy ounce).
 *
 * **Auth failures** arrive as HTTP **403** with a bare `{"error":"..."}"` (their own OpenAPI spec's
 * `{error, message, statusCode}` at 401/400/404/429 is aspirational, not what the live API actually
 * returns) -- the existing `if (!response.status.isSuccess()) return null` already covers this, no
 * 401-specific branch needed.
 *
 * **Stays on the legacy `/api/{metal}/{currency}` route deliberately**, not the newer
 * `/api/price/{metal}/{currency}` v2 route (which renames several fields) -- the legacy route is
 * live, stable, and matches this class's verified fixture.
 *
 * Free tier ("Sandbox"): 100 requests/month, no card, XAU/XAG/XPT/XPD, 72 currencies incl. EUR, 2s
 * update interval. See [GoldPriceSources.kt][network.lapis.cloud.server.economy.oracle] file KDoc
 * "Free-tier request budget" for the full arithmetic.
 */
class GoldApiIoGoldPriceSource(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = "https://www.goldapi.io",
    override val id: String = "goldapi",
) : PriceOracleSource {
    override val anchor: AnchorAsset = AnchorAsset.GOLD_XAU

    override suspend fun fetchPrice(donationCurrency: String): SourcePriceResult? {
        val currency = donationCurrency.uppercase()
        val url = "$baseUrl/api/XAU/$currency"
        return try {
            requireAllowlistedHttpsUrl(url)
            val response = httpClient.get(url) { header("x-access-token", apiKey) }
            if (!response.status.isSuccess()) return null
            val bytes = response.readCappedBodyOrNull() ?: return null
            val json = ORACLE_JSON.parseToJsonElement(bytes.decodeToString()).jsonObject
            val metal = (json["metal"] as? JsonPrimitive)?.content
            if (metal != "XAU") return null
            val responseCurrency = (json["currency"] as? JsonPrimitive)?.content
            if (responseCurrency != currency) return null
            val price = json.decimalOrNull("price") ?: return null
            if (price.signum() <= 0) return null
            SourcePriceResult(sourceId = id, price = price, observedAt = Clock.System.now())
        } catch (e: Exception) {
            logSourceFailure(sourceId = id, cause = e)
            null
        }
    }
}

/**
 * `GET https://api.metalpriceapi.com/v1/latest?api_key={key}&base=XAU&currencies={currency}` -- the
 * API key is unavoidably in the query string (mitigated: [requireAllowlistedHttpsUrl]'s failure
 * message never echoes the URL, [logSourceFailure] logs only the exception class name,
 * [oracleHttpClient] never installs Ktor's `Logging` plugin). Response shape:
 * `{"success":true,"base":"XAU","timestamp":...,"rates":{...}}`. There is no `unit` field in the
 * response (`unit` is a request parameter, paid-plan-only) -- metals are per troy ounce by default.
 *
 * **TRAP 1 -- the concatenated key is the inverse.** `rates` contains BOTH plain keys and
 * `<BASE><QUOTE>` keys, and they are reciprocals of each other (documented example: `"XAU":
 * 0.00053853` alongside `"USDXAU": 1856.906765`). With `base=XAU&currencies={currency}` this class
 * reads **`rates[currency]`** (e.g. `rates["EUR"]` ~= 3800) and **NEVER** `rates["XAU$currency"]`
 * (~= 0.00026) -- picking the wrong key is silently wrong by ~7 orders of magnitude. As a second
 * line of defence (not a substitute for reading the right key), a result below
 * [METALPRICEAPI_INVERSION_CEILING] is treated as the unmistakable inversion signature, logged, and
 * dropped -- see D8's plausibility-band guard in [PriceOracleOrchestrator] for the general version
 * of this same defence.
 *
 * **TRAP 2 -- the documented error shape is wrong.** Docs claim
 * `{"success":false,"error":{"code":...,"info":...}}"`; the live API instead returns
 * `{"success":false,"error":{"statusCode":101,"message":"..."}}"` at HTTP **200**. This class
 * simply gates on `success == true`, which is sufficient for both spellings without parsing the
 * error body at all.
 *
 * **UNVERIFIED operationally**: whether `base=XAU` is permitted on the *free* tier (error code
 * `201` "invalid Base Currency" exists in their docs, free-plan capability wording is vague) --
 * verify on the first real keyed call in a gold-anchored deployment. If rejected, this class would
 * need `base={currency}&currencies=XAU` with an explicit
 * `BigDecimal.ONE.divide(rate, ORACLE_MATH_SCALE, RoundingMode.HALF_UP)` inversion instead (a
 * known-direction conversion, categorically different from the silent "correction" of an
 * *unexpected* implausible number D8 forbids) -- not implemented here because the documented
 * `base=XAU` request is expected to work and this class should not guess at an untested fallback.
 *
 * Free tier: 100 requests/month, daily refresh. See file KDoc "Free-tier request budget"/
 * "Cadence-mismatch note".
 */
class MetalPriceApiGoldPriceSource(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = "https://api.metalpriceapi.com",
    override val id: String = "metalpriceapi",
) : PriceOracleSource {
    override val anchor: AnchorAsset = AnchorAsset.GOLD_XAU

    override suspend fun fetchPrice(donationCurrency: String): SourcePriceResult? {
        val currency = donationCurrency.uppercase()
        val url = "$baseUrl/v1/latest?api_key=$apiKey&base=XAU&currencies=$currency"
        return try {
            requireAllowlistedHttpsUrl(url)
            val response = httpClient.get(url)
            if (!response.status.isSuccess()) return null
            val bytes = response.readCappedBodyOrNull() ?: return null
            val json = ORACLE_JSON.parseToJsonElement(bytes.decodeToString()).jsonObject
            val success = (json["success"] as? JsonPrimitive)?.booleanOrNull ?: false
            if (!success) return null
            val rates = json["rates"] as? JsonObject ?: return null
            // TRAP 1 (class KDoc): rates[currency], NEVER rates["XAU$currency"] (the reciprocal).
            val price = rates.decimalOrNull(currency) ?: return null
            if (price.signum() <= 0) return null
            if (price < METALPRICEAPI_INVERSION_CEILING) {
                logger.warn {
                    "Oracle source '$id' returned a suspiciously small $currency price $price for one troy ounce of " +
                        "XAU -- likely the reciprocal 'XAU$currency' key was read instead of '$currency' (see class " +
                        "KDoc TRAP 1), dropping"
                }
                return null
            }
            SourcePriceResult(sourceId = id, price = price, observedAt = Clock.System.now())
        } catch (e: Exception) {
            logSourceFailure(sourceId = id, cause = e)
            null
        }
    }
}

/**
 * `GET https://www.alphavantage.co/query?function=GOLD_SILVER_SPOT&symbol=XAU&apikey={key}` --
 * Alpha Vantage's dedicated precious-metals spot endpoint (tagged "Trending", free tier: 25
 * requests/day, 5/min, no credit card). A **separate** endpoint from `CURRENCY_EXCHANGE_RATE`,
 * which cannot serve gold at all (Alpha Vantage's fiat/digital-currency lists contain no `XAU`
 * instrument). Alpha Vantage is a long-established financial-data company with no corporate or
 * upstream relationship to GoldAPI.io or MetalpriceAPI, so it is a genuine third quorum member, not
 * a mirror -- exactly the independence [PriceOracleSource]'s own KDoc calls for.
 *
 * **Wire format -- verified live 2026-08-20**, but only via this endpoint's SILVER path
 * (`symbol=SILVER`/`XAG`), which alone accepts Alpha Vantage's public `apikey=demo` --
 * `symbol=GOLD`/`XAU` returned the standard "demo key" rejection body even though the documented
 * example URL uses `apikey=demo` for silver. The confirmed live response shape (for
 * `symbol=SILVER`):
 * ```json
 * {"nominal": "XAGUSD", "timestamp": "2026-08-20 12:22:12", "price": "66.037130301"}
 * ```
 * `price` is a JSON **string**; `nominal` is `"<SYMBOL>USD"`. The endpoint's official documentation
 * confirms `symbol` accepts `GOLD`/`XAU` for gold with the exact same three parameters
 * (`function`/`symbol`/`apikey`) and **no currency/market parameter of any kind** -- confirmed by
 * reading `alphavantage.co/documentation/` directly (the "Gold & Silver Spot Prices" section lists
 * only those three), which resolves this wave's own "first implementation step": there is no native
 * currency conversion, so the ECB conversion leg below is required, not optional. The gold response
 * is inferred with very high confidence to mirror the silver shape exactly (same endpoint,
 * `nominal` mechanically built from `symbol` + quote currency) -- specifically `{"nominal":
 * "XAUUSD", "timestamp": "...", "price": "..."}` -- but this inference (gold path itself, as
 * opposed to the endpoint's parameter contract) was **not** directly observed with a real key. The
 * parser therefore stays defensive exactly as if the shape were still fully unverified: fields are
 * read by name, `nominal` is checked to equal `"XAUUSD"` (not merely "looks numeric"), and any
 * unexpected/renamed shape or missing field yields `null`, never a throw. Errors/rate-limits are
 * expected (Alpha Vantage's house convention across every other function, confirmed live for THIS
 * exact endpoint via the "demo key" rejection body observed above) at HTTP **200** under
 * `"Information"`/`"Note"`/`"Error Message"` -- a body missing all of `nominal`/`price` already maps
 * to `null` regardless, but these keys are checked explicitly first for a clearer log line.
 *
 * ## Currency conversion (USD -> donationCurrency)
 *
 * `GOLD_SILVER_SPOT` quotes **USD per troy ounce** with no native EUR variant (see above). Feeding a
 * USD number into a median of EUR numbers would corrupt the outlier/spread machinery outright (a
 * ~16% apparent deviation would either evict the honest source or blow the spread cap), so the
 * conversion happens **inside this source**, before the price is ever returned to the orchestrator
 * -- the [PriceOracleSource] contract is "price of one anchor unit in the donation currency", and
 * honouring it here keeps the orchestrator anchor-agnostic.
 *
 * `donationCurrency == "USD"`: the spot price is returned **verbatim**, no ECB call at all. Any
 * other currency: `price = usdPerOunce * ecbRates.ratePerEur(donationCurrency) /
 * ecbRates.ratePerEur("USD")` (dimensionally: `USD/oz * donationUnits/EUR / USD/EUR =
 * donationUnits/oz`). Worked example (EUR): `4000 USD/oz * 1 (EUR/EUR) / 1.1605 (USD/EUR) ~=
 * 3446.79 EUR/oz`. **If the ECB leg is unavailable (either rate `null` or non-positive), this
 * source returns `null` and drops out of the quorum -- it must NEVER fall back to the raw USD
 * figure**, which would be a wrong-by-double-digit-percent number reaching `convertDonationToLtr`.
 * The converted value stays the minority leg against two natively-EUR sources
 * ([GoldApiIoGoldPriceSource]/[MetalPriceApiGoldPriceSource]), so the orchestrator's existing median
 * + outlier rejection remain the primary defence against a conversion error dominating the result.
 *
 * A gold deployment configuring only two keys should prefer the two natively-EUR sources
 * (`LAPIS_ORACLE_GOLDAPI_KEY` + `LAPIS_ORACLE_METALPRICEAPI_KEY`), because a two-key set that
 * includes this source carries the extra ECB dependency with no third source to absorb its loss --
 * see `deploy/production/README.adoc`. Request cost of the conversion leg: one extra keyless ECB
 * fetch per gold fan-out, at most 2/day (see file KDoc "Free-tier request budget").
 */
class AlphaVantageGoldPriceSource(
    private val httpClient: HttpClient,
    private val ecbRates: EcbRateSource,
    private val apiKey: String,
    private val baseUrl: String = "https://www.alphavantage.co",
    override val id: String = "alphavantage",
) : PriceOracleSource {
    override val anchor: AnchorAsset = AnchorAsset.GOLD_XAU

    override suspend fun fetchPrice(donationCurrency: String): SourcePriceResult? {
        val currency = donationCurrency.uppercase()
        val url = "$baseUrl/query?function=GOLD_SILVER_SPOT&symbol=XAU&apikey=$apiKey"
        return try {
            requireAllowlistedHttpsUrl(url)
            val response = httpClient.get(url)
            if (!response.status.isSuccess()) return null
            val bytes = response.readCappedBodyOrNull() ?: return null
            val json = ORACLE_JSON.parseToJsonElement(bytes.decodeToString()).jsonObject
            if (json.containsKey("Information") || json.containsKey("Note") || json.containsKey("Error Message")) {
                return null
            }
            val nominal = (json["nominal"] as? JsonPrimitive)?.content
            if (nominal == null || !nominal.equals("XAUUSD", ignoreCase = true)) return null
            val usdPerOunce = json.decimalOrNull("price") ?: return null
            if (usdPerOunce.signum() <= 0) return null

            if (currency == "USD") {
                return SourcePriceResult(sourceId = id, price = usdPerOunce, observedAt = Clock.System.now())
            }

            val donationPerEur = ecbRates.ratePerEur(currency) ?: return null
            val usdPerEur = ecbRates.ratePerEur("USD") ?: return null
            if (donationPerEur.signum() <= 0 || usdPerEur.signum() <= 0) return null
            val price = usdPerOunce.multiply(donationPerEur).divide(usdPerEur, ORACLE_MATH_SCALE, RoundingMode.HALF_UP)
            if (price.signum() <= 0) return null
            SourcePriceResult(sourceId = id, price = price, observedAt = Clock.System.now())
        } catch (e: Exception) {
            logSourceFailure(sourceId = id, cause = e)
            null
        }
    }
}

/**
 * The gold sources this deployment has keys for -- `buildList`, so a missing/blank key simply omits
 * that source, never throws. The resulting list length is exactly what
 * [PriceOracleOrchestrator.configuredSourceCount] reports for [AnchorAsset.GOLD_XAU], which is what
 * `PriceOracleService.validateConfigInput`'s generic `>= quorumFloor` check compares against -- no
 * per-anchor arithmetic hardcoded anywhere else.
 */
fun defaultGoldOracleSources(
    config: OracleSourceConfig,
    ecbRates: EcbReferenceRateClient,
    httpClient: HttpClient = oracleHttpClient(),
): List<PriceOracleSource> =
    buildList {
        config.goldApiKey?.let { add(GoldApiIoGoldPriceSource(httpClient = httpClient, apiKey = it)) }
        config.metalPriceApiKey?.let { add(MetalPriceApiGoldPriceSource(httpClient = httpClient, apiKey = it)) }
        config.alphaVantageKey?.let {
            add(AlphaVantageGoldPriceSource(httpClient = httpClient, ecbRates = ecbRates, apiKey = it))
        }
    }
