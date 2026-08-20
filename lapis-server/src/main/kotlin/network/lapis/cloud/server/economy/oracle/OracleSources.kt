package network.lapis.cloud.server.economy.oracle

import io.ktor.client.HttpClient

/**
 * Every price source across all three anchors, sharing exactly ONE [oracleHttpClient] instance and
 * ONE [EcbReferenceRateClient] instance -- the list actually passed to the singleton
 * [PriceOracleOrchestrator] constructed once by `Application.module`. [EcbReferenceRateClient] is
 * shared between the FIAT anchor's own source and the GOLD anchor's Alpha Vantage conversion leg
 * (see that class's own KDoc for why this is one hardened implementation, not two).
 */
fun defaultOracleSources(
    config: OracleSourceConfig = OracleSourceConfig.load(),
    httpClient: HttpClient = oracleHttpClient(),
): List<PriceOracleSource> {
    val ecbRates = EcbReferenceRateClient(httpClient = httpClient)
    return defaultBitcoinOracleSources(httpClient) +
        defaultGoldOracleSources(config = config, ecbRates = ecbRates, httpClient = httpClient) +
        defaultFiatOracleSources(ecbRates)
}
