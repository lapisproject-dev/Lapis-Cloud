package network.lapis.cloud.server.economy.oracle

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode

/**
 * Exercises [OracleSourceConfig.load] -- follows `SepaConfigTest`'s own "inject an env lambda, never
 * touch real `System.getenv`" idiom.
 */
class OracleSourceConfigTest :
    FunSpec({
        test("no keys in the injected env -> defaultGoldOracleSources is empty, load() never throws") {
            val config = OracleSourceConfig.load(env = { null })

            config.goldApiKey shouldBe null
            config.metalPriceApiKey shouldBe null
            config.alphaVantageKey shouldBe null

            val sources =
                defaultGoldOracleSources(
                    config = config,
                    ecbRates = EcbReferenceRateClient(httpClient = FAKE_HTTP_CLIENT),
                    httpClient = FAKE_HTTP_CLIENT,
                )
            sources.isEmpty() shouldBe true
        }

        test("each key alone yields exactly one gold source with the matching id") {
            val goldapiOnly = OracleSourceConfig.load(env = { name -> if (name == "LAPIS_ORACLE_GOLDAPI_KEY") "k" else null })
            defaultGoldOracleSources(
                config = goldapiOnly,
                ecbRates = EcbReferenceRateClient(httpClient = FAKE_HTTP_CLIENT),
                httpClient = FAKE_HTTP_CLIENT,
            ).map { it.id } shouldBe listOf("goldapi")

            val metalOnly = OracleSourceConfig.load(env = { name -> if (name == "LAPIS_ORACLE_METALPRICEAPI_KEY") "k" else null })
            defaultGoldOracleSources(
                config = metalOnly,
                ecbRates = EcbReferenceRateClient(httpClient = FAKE_HTTP_CLIENT),
                httpClient = FAKE_HTTP_CLIENT,
            ).map { it.id } shouldBe listOf("metalpriceapi")

            val alphaOnly = OracleSourceConfig.load(env = { name -> if (name == "LAPIS_ORACLE_ALPHAVANTAGE_KEY") "k" else null })
            defaultGoldOracleSources(
                config = alphaOnly,
                ecbRates = EcbReferenceRateClient(httpClient = FAKE_HTTP_CLIENT),
                httpClient = FAKE_HTTP_CLIENT,
            ).map { it.id } shouldBe listOf("alphavantage")
        }

        test("any two keys yield exactly two gold sources") {
            val goldapiAndMetal =
                OracleSourceConfig.load(
                    env = { name ->
                        when (name) {
                            "LAPIS_ORACLE_GOLDAPI_KEY", "LAPIS_ORACLE_METALPRICEAPI_KEY" -> "k"
                            else -> null
                        }
                    },
                )
            defaultGoldOracleSources(
                config = goldapiAndMetal,
                ecbRates = EcbReferenceRateClient(httpClient = FAKE_HTTP_CLIENT),
                httpClient = FAKE_HTTP_CLIENT,
            ).size shouldBe 2
        }

        test("all three keys yield exactly three gold sources") {
            val allThree = OracleSourceConfig.load(env = { "k" })
            defaultGoldOracleSources(
                config = allThree,
                ecbRates = EcbReferenceRateClient(httpClient = FAKE_HTTP_CLIENT),
                httpClient = FAKE_HTTP_CLIENT,
            ).size shouldBe 3
        }

        test("a blank or whitespace-only key is treated as unset") {
            val blank =
                OracleSourceConfig.load(
                    env = { name -> if (name == "LAPIS_ORACLE_GOLDAPI_KEY") "   " else null },
                )
            blank.goldApiKey shouldBe null

            val empty =
                OracleSourceConfig.load(
                    env = { name -> if (name == "LAPIS_ORACLE_METALPRICEAPI_KEY") "" else null },
                )
            empty.metalPriceApiKey shouldBe null
        }

        test("a key with surrounding whitespace is trimmed") {
            val padded =
                OracleSourceConfig.load(
                    env = { name -> if (name == "LAPIS_ORACLE_ALPHAVANTAGE_KEY") "  actual-key  " else null },
                )
            padded.alphaVantageKey shouldBe "actual-key"
        }

        test("toString() contains none of the three key values, only redaction markers") {
            val config =
                OracleSourceConfig.load(
                    env = { name ->
                        when (name) {
                            "LAPIS_ORACLE_GOLDAPI_KEY" -> "goldapi-secret-value"
                            "LAPIS_ORACLE_METALPRICEAPI_KEY" -> "metalpriceapi-secret-value"
                            "LAPIS_ORACLE_ALPHAVANTAGE_KEY" -> "alphavantage-secret-value"
                            else -> null
                        }
                    },
                )
            val text = config.toString()
            text.contains("goldapi-secret-value") shouldBe false
            text.contains("metalpriceapi-secret-value") shouldBe false
            text.contains("alphavantage-secret-value") shouldBe false
            text.contains("<redacted, 20 chars>") shouldBe true
        }
    })

/** Never actually reached by these tests -- no source list construction here ever calls `fetchPrice`. */
private val FAKE_HTTP_CLIENT: HttpClient by lazy {
    HttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) })
}
