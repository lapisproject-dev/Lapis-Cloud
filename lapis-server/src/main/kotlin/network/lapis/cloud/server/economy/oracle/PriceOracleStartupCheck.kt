package network.lapis.cloud.server.economy.oracle

import io.github.oshai.kotlinlogging.KotlinLogging
import network.lapis.cloud.server.db.generated.PriceOracleConfigTable
import network.lapis.cloud.server.rpc.PRICE_ORACLE_CONFIG_ID
import network.lapis.cloud.shared.domain.AnchorAsset
import network.lapis.cloud.shared.domain.AnchorPolicy
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private val logger = KotlinLogging.logger {}

/** [AlphaVantageGoldPriceSource] itself, the two natively-EUR gold sources its own KDoc recommends pairing with instead -- see [warnIfGoldRiskyAlphaVantagePairing]. */
private const val ALPHA_VANTAGE_SOURCE_ID = "alphavantage"
private val NATIVELY_EUR_GOLD_SOURCE_IDS = setOf("goldapi", "metalpriceapi")

/**
 * Non-fatal startup surface for the "anchor configured, but its sources are not" misconfiguration
 * -- V0.6.6. Deliberately does NOT throw -- the anchor lives in a DB row, not an env var, so
 * `Application.module` cannot treat it as a hard precondition (the exact reasoning
 * `network.lapis.cloud.server.payment.sepa.SepaConfig`'s own KDoc gives for its DB-flag-gated
 * feature). The hard rejection lives at the operation boundary
 * (`PriceOracleService.validateConfigInput`); this only makes an ALREADY-persisted bad state loud at
 * boot instead of at the first donation conversion.
 */
object PriceOracleStartupCheck {
    /** INFO-level inventory of every anchor's configured source count/ids -- never logs an API key. Also runs [warnIfGoldRiskyAlphaVantagePairing] (Review Round 1 / MINOR-3). */
    fun logSourceInventory(orchestrator: PriceOracleOrchestrator) {
        AnchorAsset.entries.forEach { anchor ->
            logger.info {
                "Price-Oracle sources for $anchor: ${orchestrator.configuredSourceIds(anchor)} " +
                    "(quorum floor ${AnchorPolicy.quorumFloor(anchor)})"
            }
        }
        warnIfGoldRiskyAlphaVantagePairing(orchestrator)
    }

    /**
     * Review Round 1 / MINOR-3: [AlphaVantageGoldPriceSource]'s own KDoc already recommends that "a
     * gold deployment configuring only two keys should prefer the two natively-EUR sources
     * (`LAPIS_ORACLE_GOLDAPI_KEY` + `LAPIS_ORACLE_METALPRICEAPI_KEY`)" over any pairing that includes
     * Alpha Vantage, because Alpha Vantage carries an extra ECB-conversion-leg dependency with no
     * third source left to absorb its loss. Nothing previously surfaced that recommendation anywhere
     * a deploying operator would see it -- this makes the riskier pairing a loud, non-fatal `WARN` at
     * startup, matching the inventory log this function already emits. Deliberately not a hard
     * rejection: the configuration is still perfectly valid (quorum floor 2 is met), just riskier
     * than the alternative -- the same "warn, don't block" posture as
     * [warnIfActiveAnchorUnderprovisioned].
     *
     * **This warning covers OUTAGE risk, not MANIPULATION risk** (Security-Audit-Runde 1 / S5,
     * documentation-only -- the underlying math is a pre-existing, correct property of the design,
     * not a bug): at exactly [AnchorPolicy.quorumFloor]`(GOLD_XAU)` == 2 configured sources,
     * `median([a, b]) == (a + b) / 2`, so both sources' deviations from that provisional median are
     * ALWAYS identical -- the outlier-rejection step in [PriceOracleOrchestrator] can only
     * accept-both or reject-both, it can never discriminate the bad one from the good one. With the
     * seeded 300bps default `outlierThresholdBps`, a single malicious or buggy source among exactly 2
     * can skew the final reported price by up to roughly `min(2 x outlierThresholdBps, maxSpreadBps) /
     * 2` -- about 3% at the seeded defaults -- while the quote STILL reports [PriceStatus.LIVE] (both
     * sources "survive" the outlier check, since their computed deviation is within threshold). This
     * is true of ANY 2-source pairing (`goldapi + metalpriceapi` included), not just an
     * Alpha-Vantage-inclusive one -- exactly 2 sources buys AVAILABILITY redundancy (one source may be
     * down and gold still works) but NOT outlier/manipulation resilience. Configuring all THREE gold
     * sources is the genuine security baseline, not merely the outage-avoidance one -- see
     * `deploy/production/README.adoc`'s gold-anchor section, updated alongside this KDoc to say the
     * same thing where an operator will actually read it before deploying.
     */
    private fun warnIfGoldRiskyAlphaVantagePairing(orchestrator: PriceOracleOrchestrator) {
        val goldSourceIds = orchestrator.configuredSourceIds(AnchorAsset.GOLD_XAU).toSet()
        if (ALPHA_VANTAGE_SOURCE_ID !in goldSourceIds) return
        if (goldSourceIds.containsAll(NATIVELY_EUR_GOLD_SOURCE_IDS)) return
        logger.warn {
            "Price-Oracle: GOLD_XAU source set $goldSourceIds includes '$ALPHA_VANTAGE_SOURCE_ID' without BOTH " +
                "natively-EUR sources ($NATIVELY_EUR_GOLD_SOURCE_IDS) -- this is the riskiest 2-of-3 key " +
                "configuration (extra ECB-conversion-leg dependency, no third source left to absorb its loss if " +
                "it fails). Still a valid configuration, but prefer configuring LAPIS_ORACLE_GOLDAPI_KEY + " +
                "LAPIS_ORACLE_METALPRICEAPI_KEY instead -- see AlphaVantageGoldPriceSource KDoc."
        }
    }

    /**
     * Reads the single seeded `price_oracle_config` row and, if the currently-persisted
     * `anchorAsset` has fewer configured sources than its [AnchorPolicy.quorumFloor], logs an
     * `ERROR` naming the anchor and the missing source count -- every `convertDonationToLtr`/
     * `previewCurrentPrice` call against that anchor will `Halt` until the deployment is fixed
     * (either by configuring the missing `LAPIS_ORACLE_*` keys, or by an ADMIN switching the anchor
     * back via `updateOracleConfig`).
     */
    fun warnIfActiveAnchorUnderprovisioned(orchestrator: PriceOracleOrchestrator) {
        val activeAnchor =
            transaction {
                PriceOracleConfigTable
                    .selectAll()
                    .where { PriceOracleConfigTable.id eq PRICE_ORACLE_CONFIG_ID }
                    .singleOrNull()
                    ?.get(PriceOracleConfigTable.anchorAsset)
            } ?: return
        val configured = orchestrator.configuredSourceCount(activeAnchor)
        val floor = AnchorPolicy.quorumFloor(activeAnchor)
        if (configured < floor) {
            logger.error {
                "Price-Oracle: active anchor $activeAnchor has only $configured configured price " +
                    "source(s), below its required minimum of $floor -- every donation conversion for " +
                    "this anchor will HALT until this is fixed (configure the missing LAPIS_ORACLE_* " +
                    "env vars, or switch the anchor back)"
            }
        }
    }
}
