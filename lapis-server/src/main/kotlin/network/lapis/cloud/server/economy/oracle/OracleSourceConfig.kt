package network.lapis.cloud.server.economy.oracle

/**
 * V0.6.6 "Price-Oracle: Gold- und Fiat-Anker". Deployment-supplied API keys for the three key-gated
 * [network.lapis.cloud.shared.domain.AnchorAsset.GOLD_XAU] sources -- follows
 * `network.lapis.cloud.server.payment.sepa.SepaConfig` **exactly**: a private constructor, a
 * [load] factory taking an injectable `env` lambda (`System.getenv` cannot be mutated per test), and
 * a redacting [toString].
 *
 * **Never fails fast.** Same posture (and same reasoning) as `SepaConfig`: the feature is gated by a
 * **DB flag** (`price_oracle_config.anchor_asset`), not by an env var, so [load] cannot know at
 * startup whether gold is actually in use. A missing/blank key simply means that one source does not
 * participate in [defaultGoldOracleSources] -- never a startup crash. The error is raised at the
 * operation boundary (`PriceOracleService.updateOracleConfig`, via
 * `PriceOracleOrchestrator.configuredSourceCount`) and, if the row was already gold, surfaced as a
 * startup `WARN`/`ERROR` by [PriceOracleStartupCheck] and a runtime `Halt`.
 */
class OracleSourceConfig private constructor(
    /** `LAPIS_ORACLE_GOLDAPI_KEY` -- GoldAPI.io `x-access-token`. `null` iff unset/blank: that source simply does not participate. Never logged, never in [toString], never in a DTO or exception message. */
    val goldApiKey: String?,
    /** `LAPIS_ORACLE_METALPRICEAPI_KEY`. Same discipline as [goldApiKey]. */
    val metalPriceApiKey: String?,
    /** `LAPIS_ORACLE_ALPHAVANTAGE_KEY` -- Alpha Vantage `GOLD_SILVER_SPOT`. Same discipline as [goldApiKey]. */
    val alphaVantageKey: String?,
) {
    override fun toString(): String =
        "OracleSourceConfig(goldApiKey=${redact(goldApiKey)}, metalPriceApiKey=${redact(metalPriceApiKey)}, " +
            "alphaVantageKey=${redact(alphaVantageKey)})"

    companion object {
        fun load(env: (String) -> String? = System::getenv): OracleSourceConfig =
            OracleSourceConfig(
                goldApiKey = env("LAPIS_ORACLE_GOLDAPI_KEY")?.trim()?.takeUnless { it.isBlank() },
                metalPriceApiKey = env("LAPIS_ORACLE_METALPRICEAPI_KEY")?.trim()?.takeUnless { it.isBlank() },
                alphaVantageKey = env("LAPIS_ORACLE_ALPHAVANTAGE_KEY")?.trim()?.takeUnless { it.isBlank() },
            )

        private fun redact(v: String?) = if (v == null) "<unset>" else "<redacted, ${v.length} chars>"
    }
}
