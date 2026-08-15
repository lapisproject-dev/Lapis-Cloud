package network.lapis.cloud.server.rpc

import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Pure monthly EUR-pool apportionment for Internes Crowdfunding (V0.6.1) -- extracted so it is
 * unit-testable without a database, same "pure logic extracted to a sibling file" idiom as
 * [CrowdfundingWeightDecay]/[PartyDonationComplianceCalculator]. Only ever invoked by
 * `CrowdfundingService.computeMonthlyDistribution`.
 *
 * Deliberately unaware of LTR, member ids, or dates -- takes only each project's already-computed
 * basket total (`max(0, likeCount - dislikeCount)`, the democratic, LTR-**unweighted**
 * Verteilungs-Korb -- see `17-crowdfunding.kuml.kts` file header point 2) and the pool to split,
 * delegating the actual rounding-critical apportionment to [LargestRemainderApportionment] --
 * the same algorithm `VoteSettlement` uses for its Vickrey-settlement charges, reused here rather
 * than re-implemented so both call sites share one tested source of rounding truth.
 */
internal object CrowdfundingDistributionCalculator {
    /**
     * Splits [poolEur] proportionally across every project in [projectBaskets] whose basket total
     * is strictly positive -- projects with a basket of `0` receive nothing and are dropped from
     * the result entirely (not present with a zero share), per the concept document ("Projekte
     * mit Korb = 0 bekommen nichts"). Guarantees `Σ result.values == poolEur` exactly when the
     * result is non-empty. Returns an empty map if [projectBaskets] has no strictly-positive
     * entry, or if [poolEur] is zero.
     */
    fun allocate(
        projectBaskets: Map<Uuid, Int>,
        poolEur: BigDecimal,
    ): Map<Uuid, BigDecimal> {
        if (poolEur.signum() == 0) return emptyMap()
        val positiveBaskets = projectBaskets.filterValues { it > 0 }
        if (positiveBaskets.isEmpty()) return emptyMap()
        val weights = positiveBaskets.mapValues { (_, basket) -> BigDecimal(basket).setScale(2) }
        return LargestRemainderApportionment.apportion(weights = weights, pool = poolEur)
    }
}
