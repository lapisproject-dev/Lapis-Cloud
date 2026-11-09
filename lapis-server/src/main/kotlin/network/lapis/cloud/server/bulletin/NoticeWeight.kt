package network.lapis.cloud.server.bulletin

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.economy.WeightDecayClock
import network.lapis.cloud.server.rpc.SocialPostWeight
import java.math.BigDecimal

/**
 * V1.9.297 -- the weight of a bulletin-board notice. A THIN adapter with no mathematics of its own: a notice has no initial stake (base weight 0),
 * its weight is the sum of its boosts, each decaying from ITS OWN `boosted_at` with the social network's rule (10 % per 24 h, `0.9^days`),
 * rounded exactly once at the end. Everything is delegated to [SocialPostWeight] and [WeightDecayClock]; `NoticeWeightTripwireTest` forbids a
 * second copy of the decay in this package.
 */
internal object NoticeWeight {
    /** The decayed weight of a notice created at [createdAt] with [boosts], at [now]. Rounded to two places, never stored. */
    fun weightOf(
        boosts: List<SocialPostWeight.BoostContribution>,
        createdAt: LocalDateTime,
        now: LocalDateTime,
    ): BigDecimal =
        WeightDecayClock.round2(
            SocialPostWeight.ownWeightUnrounded(
                initialWeightLtr = BigDecimal.ZERO,
                publishedAt = createdAt,
                boosts = boosts,
                now = now,
            ),
        )

    /** At most two decimals, at least 0.01 LTR -- the social network's rule, shared (no copy). */
    fun normalizeAmount(
        amount: BigDecimal,
        field: String,
    ): BigDecimal = SocialPostWeight.normalizeLtrAmount(weight = amount, fieldName = field)
}
