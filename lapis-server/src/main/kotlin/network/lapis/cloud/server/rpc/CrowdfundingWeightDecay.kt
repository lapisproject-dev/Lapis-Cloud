package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.time.Duration.Companion.days

/**
 * Pure Sichtbarkeits-Gewicht ("visibility weight") clock for Internes Crowdfunding (V0.6.1) --
 * extracted so it is unit-testable without a database, same "pure logic extracted to a sibling
 * file" idiom as [PartyDonationComplianceCalculator]/[BreachDeadlineCalculator]. Only ever
 * invoked by `CrowdfundingService` to compute
 * [network.lapis.cloud.shared.domain.CrowdfundingProjectDto.currentWeightLtr]/`.effectiveStatus`/
 * `.isAutoApproved` at read time -- neither value is ever persisted, see `17-crowdfunding.kuml.kts`
 * file header.
 *
 * **This is a starting point, not a settled fachlich constant** -- [DECAY_KEEP_RATE_PER_DAY] (the
 * concept document's "10% Zerfall pro Tag") and [BOARD_REVIEW_WINDOW_DAYS] (its "14-Tage-Frist")
 * are the current understanding of that document at the time this wave was written; both are
 * named constants specifically so they are trivial to find and adjust once confirmed, same
 * "current-understanding, verify" disclaimer class as
 * [PartyDonationComplianceCalculator]/`14-audit-log.kuml.kts`'s own top-of-file notes (here the
 * open question is product/governance policy, not statute law, but the "flag it, don't silently
 * assume it's final" discipline is the same).
 *
 * **Recursive comment-weight extension (explicitly NOT implemented this wave)**: the concept
 * document describes a project's total Sichtbarkeits-Gewicht as its own decaying Eigengewicht
 * PLUS the recursive sum of every comment's own decaying weight. No comment/discussion feature
 * exists anywhere in this codebase yet (Crowdfunding has no `crowdfunding_comment` table), so
 * [currentWeight] computes ONLY the Eigengewicht term -- the recursive extension is a genuinely
 * separate, later feature, not something to simulate or approximate here.
 */
object CrowdfundingWeightDecay {
    /**
     * Fraction of a project's weight retained per elapsed day (10%/day decay = 90% kept) --
     * current understanding of the concept document, verify with the product/governance owner
     * before treating this as final.
     */
    val DECAY_KEEP_RATE_PER_DAY: BigDecimal = BigDecimal("0.9")

    /** Board silence-is-approval window -- current understanding, verify (see class KDoc). */
    const val BOARD_REVIEW_WINDOW_DAYS: Long = 14

    /** Internal computation precision for [BigDecimal.pow] -- display values are always rounded to 2dp by [currentWeight] afterward. */
    private val DECAY_MATH_CONTEXT = MathContext(20)

    /**
     * [initialWeightLtr] decayed by [DECAY_KEEP_RATE_PER_DAY] raised to the whole number of days
     * elapsed between [submittedAt] and [now] (24h-rolling via [daysElapsed], never calendar-day
     * subtraction -- see that function's KDoc). Rounded to 2 decimal places (matching
     * `ltr_ledger_entry.amount_ltr`'s own scale) via [RoundingMode.HALF_UP] -- this is a DISPLAY/
     * comparison value, never itself persisted, so a rounding choice here has no bookkeeping
     * consequence the way it would for an actual ledger write.
     */
    fun currentWeight(
        initialWeightLtr: BigDecimal,
        submittedAt: LocalDateTime,
        now: LocalDateTime,
    ): BigDecimal {
        val days = daysElapsed(from = submittedAt, now = now)
        val decayFactor = DECAY_KEEP_RATE_PER_DAY.pow(days.toInt(), DECAY_MATH_CONTEXT)
        return (initialWeightLtr * decayFactor).setScale(2, RoundingMode.HALF_UP)
    }

    /**
     * `true` once at least [BOARD_REVIEW_WINDOW_DAYS] * 24h have elapsed between [submittedAt]
     * and [now] (inclusive -- exactly 14*24h counts as auto-approved, matching a ">=" boundary,
     * not ">"). Purely a read-time computation; the persisted `status` column is never written by
     * this function or as a side effect of calling it -- see class KDoc / `CrowdfundingService`
     * for the explicit board-action-vs-silence distinction.
     */
    fun isAutoApproved(
        submittedAt: LocalDateTime,
        now: LocalDateTime,
    ): Boolean = elapsedDuration(from = submittedAt, now = now) >= BOARD_REVIEW_WINDOW_DAYS.days

    /**
     * Whole number of complete 24h periods between [from] and [now], via [kotlin.time.Duration]
     * (`Instant` subtraction against a fixed [TimeZone.UTC] reference), NOT calendar-day
     * subtraction -- two [LocalDateTime]s 23h59m apart but crossing a local midnight must count
     * as 0 elapsed days, not 1, so DST/timezone artifacts never distort the decay curve or the
     * silence-is-approval boundary. Negative-appearing input (`now` before `from`, which should
     * never happen in practice) floors to 0 rather than throwing, since a decay curve going
     * "into the future" relative to its own submission has no sensible negative-days meaning.
     */
    internal fun daysElapsed(
        from: LocalDateTime,
        now: LocalDateTime,
    ): Long = elapsedDuration(from = from, now = now).inWholeDays.coerceAtLeast(0)

    private fun elapsedDuration(
        from: LocalDateTime,
        now: LocalDateTime,
    ) = now.toInstant(TimeZone.UTC) - from.toInstant(TimeZone.UTC)
}
