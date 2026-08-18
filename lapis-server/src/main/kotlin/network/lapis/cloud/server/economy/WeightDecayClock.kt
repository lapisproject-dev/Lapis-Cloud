package network.lapis.cloud.server.economy

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * The shared, reine (DB-freie) Zerfalls-Primitive for every LTR-gewichtetes Sichtbarkeits-Konzept
 * in this codebase -- extracted (V1.1.1, Soziales Netzwerk) from
 * [network.lapis.cloud.server.rpc.CrowdfundingWeightDecay] (V0.6.1). [CrowdfundingWeightDecay]'s
 * public SIGNATURE is unchanged by this refactor -- it now delegates here. Its rounded OUTPUT is
 * numerically indistinguishable from before for every realistic LTR amount (see "Precision caveat"
 * below for the precise, corrected reasoning -- an earlier revision of this paragraph claimed
 * "byte-for-byte"/"same code, same [MC], just moved", which overstated it: the delegation
 * introduces one additional [MC]-bounded rounding step versus the original's exact `multiply`, with
 * zero practical effect at this system's scale), verified by the pre-existing
 * `CrowdfundingWeightDecayTest` regression suite passing unchanged; see that object's KDoc for the
 * still-current "Aktuelle Annahme, vor Produktiveinsatz zu verifizieren" disclaimer on
 * [KEEP_RATE_PER_DAY]'s value.
 *
 * **40-Jahre-Stabilitätsanforderung** (see the vault's "Soziales Netzwerk"/"Meritokratisches
 * System und Libertaler" concept notes): [decayedUnrounded] returns the UNROUNDED decayed value --
 * "unrounded" meaning it skips [round2], NOT that it is exact/infinite-precision arithmetic (see
 * the precision caveat below) -- rounding a per-contribution value BEFORE summing many
 * contributions (a post's own weight plus, from Welle V1.1.2 onward, every comment's/boost's own
 * decaying weight) accumulates systematic rounding drift and can flip an otherwise-stable ranking
 * the moment a new comment arrives. [round2] is the ONE rounding step, applied exactly once, at the
 * end of a complete aggregation -- never per-contribution. [CrowdfundingWeightDecay.currentWeight]
 * itself has no aggregation to protect (a Crowdfunding project's weight is Eigengewicht-only, see
 * that object's KDoc "Recursive comment-weight extension"), so it is safe for it to keep rounding
 * immediately, exactly as before.
 *
 * **Precision caveat (corrected 2026-08-18, Review-Fund G1 -- earlier revisions of this KDoc
 * overstated this as "full-precision"; corrected AGAIN 2026-08-18, Review-Fund NEU-3 -- that first
 * correction still attributed the rounding solely to `KEEP_RATE_PER_DAY.pow(...)`, when
 * [decayedUnrounded] passes [MC] to BOTH `BigDecimal` operations, and picked magnitude thresholds
 * -- "10^12"/"10^15 LTR" -- that do not match how [MathContext] precision actually works)**:
 * [decayedUnrounded] is NOT literally exact (unlimited-precision) arithmetic. [MC]'s 20
 * significant-digit [MathContext] applies to EVERY `BigDecimal` operation it is passed to here --
 * both the `KEEP_RATE_PER_DAY.pow(...)` decay-factor computation (`BigDecimal.pow` requires SOME
 * `MathContext` once the exponent is large -- see [daysElapsed] KDoc below for why -- an exact
 * `0.9^14610` would need a BigDecimal with thousands of digits) AND the final
 * `amountLtr.multiply(decayFactor, MC)`. What [MC] actually guarantees is 20 significant digits of
 * accuracy per operation, i.e. a RELATIVE error on the order of 10^-20 -- independent of the
 * operands' magnitude, not tied to a fixed absolute threshold like "10^12" or "10^15". What matters
 * in practice is where that relative error becomes visible AFTER [round2]'s 2-decimal-place
 * rounding: a ~10^-20 relative error only shifts the second decimal place once the amount itself
 * approaches roughly 10^18 LTR (10^-20 × 10^18 ≈ 10^-2). No realistic LTR balance in this
 * codebase's domain (an internal participation currency for a members' association) comes remotely
 * close to that magnitude, so this remains a theoretical caveat, not a practical one -- [MC]
 * delivers DETERMINISM (the same inputs always decay to the same output, forever) and, for every
 * amount this system will ever see, a result indistinguishable from unlimited-precision arithmetic
 * after rounding.
 *
 * [daysElapsed] is `Instant`-difference against a fixed [TimeZone.UTC] reference, NEVER
 * calendar-day subtraction, so DST/timezone artifacts never distort the decay curve -- same
 * discipline the pre-existing `CrowdfundingWeightDecay.daysElapsed` already established. Negative
 * elapsed time (`now` before `since`) floors to 0; correctness for large positive spans (up to and
 * beyond 40 years = 14 610 days) is covered by [MC]'s 20-digit precision on [BigDecimal.pow] --
 * without it, `0.9^14610` (≈ 1e-669) would otherwise need a BigDecimal with thousands of digits.
 */
object WeightDecayClock {
    /** Fraction of a contribution's weight retained per elapsed day (10%/day decay = 90% kept). */
    val KEEP_RATE_PER_DAY: BigDecimal = BigDecimal("0.9")

    /** Precision for [BigDecimal.pow] -- large day counts (decades) need this to avoid an unbounded-digit result. */
    private val MC = MathContext(20)

    /**
     * Whole number of complete 24h periods between [from] and [now], via [kotlin.time.Duration]
     * (`Instant` subtraction against [TimeZone.UTC]), never calendar-day subtraction -- two
     * [LocalDateTime]s 23h59m apart but crossing a local midnight count as 0 elapsed days, not 1.
     * Floors at 0 for a negative-appearing span (`now` before `from`).
     */
    fun daysElapsed(
        from: LocalDateTime,
        now: LocalDateTime,
    ): Long = (now.toInstant(TimeZone.UTC) - from.toInstant(TimeZone.UTC)).inWholeDays.coerceAtLeast(0)

    /**
     * [amountLtr] decayed by [KEEP_RATE_PER_DAY] raised to the whole number of days elapsed
     * between [since] and [now] (via [daysElapsed]). Returns the UNROUNDED result (no [round2]
     * applied) -- see class KDoc "40-Jahre-Stabilitätsanforderung" for why, and "Precision caveat"
     * for why "unrounded" is not the same claim as "exact/infinite-precision".
     */
    fun decayedUnrounded(
        amountLtr: BigDecimal,
        since: LocalDateTime,
        now: LocalDateTime,
    ): BigDecimal {
        val decayFactor = KEEP_RATE_PER_DAY.pow(daysElapsed(from = since, now = now).toInt(), MC)
        return amountLtr.multiply(decayFactor, MC)
    }

    /** The ONE rounding step (2dp, `HALF_UP`, matching `ltr_ledger_entry.amount_ltr`'s own scale) -- apply exactly once, at the end of a complete aggregation. See class KDoc. */
    fun round2(value: BigDecimal): BigDecimal = value.setScale(2, RoundingMode.HALF_UP)
}
