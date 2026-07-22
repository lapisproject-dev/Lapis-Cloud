package network.lapis.cloud.server.rpc

import network.lapis.cloud.shared.domain.PoliticianReactionValue
import java.math.BigDecimal
import kotlin.uuid.Uuid

private val ZERO_2DP: BigDecimal = BigDecimal.ZERO.setScale(2)

/**
 * Pure LTR-weight-pool calculation for Politiker-Profile und Politiker-Ranking (V0.6.4) --
 * extracted so it is unit-testable without a database, same "pure logic extracted to a sibling
 * file" idiom as [CrowdfundingWeightDecay]/[CrowdfundingDistributionCalculator]. Only ever invoked
 * by [PoliticianService].
 *
 * ## Why a single shared pool, split by ratio -- not N isolated per-politician sums
 *
 * The concept document ("Der LTR-Gewichts-Pool wird gebildet aus den aktuellen LTR-Bestaenden
 * aller Bewerter... Der Pool wird proportional zu den Korb-Inhalten auf die Politiker verteilt")
 * describes exactly the same shape [CrowdfundingDistributionCalculator]'s monthly EUR pool already
 * has -- one pool, apportioned across every participant's basket total by
 * [LargestRemainderApportionment], not a per-participant direct sum computed in isolation. A
 * direct-sum reading ("each politician's weight = sum of THEIR OWN raters' balances") would mean a
 * rater who never voted for politician X could never move X's number -- but the concept text's
 * plural "aendert sich automatisch der Pool und damit das aggregierte Vertrauensgewicht DER
 * POLITIKER mit" (every politician's number moves together) only makes sense under the shared-pool
 * reading, so that is what this function implements.
 *
 * ## Recompute-on-read, not incremental
 *
 * Because the pool is shared and proportionally redistributed, there is no valid incremental
 * update for a single rater's balance change -- it would ripple into every active politician's
 * weight simultaneously, including ones that rater never rated. This function is therefore always
 * called fresh against the CURRENT reaction rows and CURRENT LTR balances -- same "derive, don't
 * cache" idiom [network.lapis.cloud.server.economy.LedgerBackedLtrBalanceProvider]/
 * [CrowdfundingWeightDecay.currentWeight] already establish for their own live-computed numbers.
 */
internal object PoliticianTrustWeightCalculator {
    data class TrustWeightResult(
        val memberLikeCount: Int,
        val memberDislikeCount: Int,
        val memberTrustWeight: BigDecimal,
    )

    /**
     * [reactionsByProfile] must contain one entry per ACTIVE politician profile the caller wants a
     * result for -- INCLUDING profiles with an empty reaction list (a profile absent from this map
     * entirely is simply absent from the result; this function has no other way to know the full
     * "active politician" set). Each value is that profile's raw `(raterMemberId, value)` pairs,
     * one per `politician_reaction` row.
     *
     * [raterBalances] should map every rater id appearing anywhere in [reactionsByProfile]'s
     * values to their current free LTR balance (see [network.lapis.cloud.server.economy
     * .LtrBalanceProvider.freeBalances]) -- a rater id missing here is treated as
     * [BigDecimal.ZERO] (defensive only; should not happen when the caller resolves
     * [raterBalances] over the exact same distinct-rater set this function would derive from
     * [reactionsByProfile]).
     *
     * Returns one [TrustWeightResult] per key of [reactionsByProfile] -- a politician with an
     * empty/all-cancelling-out reaction list (Korb == 0) is still present, with
     * `memberTrustWeight == ZERO`, per "Politiker mit Korb-Inhalt 0 bekommen kein
     * Vertrauensgewicht" (they are represented, not omitted).
     */
    fun computeMemberTrustWeights(
        reactionsByProfile: Map<Uuid, List<Pair<Uuid, PoliticianReactionValue>>>,
        raterBalances: Map<Uuid, BigDecimal>,
    ): Map<Uuid, TrustWeightResult> {
        if (reactionsByProfile.isEmpty()) return emptyMap()

        val countsByProfile: Map<Uuid, Pair<Int, Int>> =
            reactionsByProfile.mapValues { (_, reactions) ->
                val likes = reactions.count { (_, value) -> value == PoliticianReactionValue.LIKE }
                val dislikes = reactions.count { (_, value) -> value == PoliticianReactionValue.DISLIKE }
                likes to dislikes
            }

        val korbByProfile: Map<Uuid, BigDecimal> =
            countsByProfile.mapValues { (_, counts) ->
                val (likes, dislikes) = counts
                BigDecimal((likes - dislikes).coerceAtLeast(0)).setScale(2)
            }

        // Distinct raters across ALL active politicians combined, counted once per person -- not
        // once per vote (see class KDoc "single shared pool").
        val distinctRaters: Set<Uuid> =
            reactionsByProfile.values
                .flatten()
                .map { (raterId, _) -> raterId }
                .toSet()
        val pool: BigDecimal =
            distinctRaters.fold(ZERO_2DP) { acc, raterId -> acc + (raterBalances[raterId] ?: ZERO_2DP) }

        val weightsByProfile = LargestRemainderApportionment.apportion(korbByProfile, pool)

        return reactionsByProfile.keys.associateWith { profileId ->
            val (likes, dislikes) = countsByProfile.getValue(profileId)
            TrustWeightResult(
                memberLikeCount = likes,
                memberDislikeCount = dislikes,
                memberTrustWeight = weightsByProfile[profileId] ?: ZERO_2DP,
            )
        }
    }
}
