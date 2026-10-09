package network.lapis.cloud.server.mail.budget

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.mail.plusDuration
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The two budget lanes: a SYSTEM mail may use the whole hourly [MailBudget] maximum, a BULK mail (mailing-list send) only what the reserve leaves. */
enum class MailLane { SYSTEM, BULK }

sealed interface BudgetDecision {
    data object Allowed : BudgetDecision

    /** No slot before [at] (class A, UTC). */
    data class WaitUntil(
        val at: LocalDateTime,
    ) : BudgetDecision
}

/**
 * Welle V1.9.81 -- the pure decision function of the hourly send budget (no I/O; the database part is [MailBudgetStore]).
 *
 * The window is a sliding, half-open interval `(now - 3600 s, now]`: a slot reserved at exactly `now - 3600 s` is already outside.
 * Every slot counts for BOTH lanes (the maximum is a property of the mailbox, not of a lane); the lanes differ only in the limit
 * a new reservation is checked against: SYSTEM `max`, BULK `max - reserve`.
 */
object MailBudget {
    val WINDOW: Duration = 3600.seconds

    /** Retry delay when a reservation could not be decided (lock contention, database error) -- never blocks the caller. */
    val RECHECK: Duration = 5.seconds

    fun limitFor(
        lane: MailLane,
        max: Int,
        reserve: Int,
    ): Int = if (lane == MailLane.SYSTEM) max else max - reserve

    /**
     * [usedInWindow] = all slots in `(now - WINDOW, now]`. [nthOldestForRelease] = the slot with ascending index `usedInWindow - limit`
     * (only needed, and only loaded, when the budget is exhausted; so at most ONE timestamp is ever read, never up to 10 000).
     * A configuration lowered while slots exist (`used > max`) simply yields a later release slot (index > 0).
     */
    fun decide(
        lane: MailLane,
        usedInWindow: Int,
        max: Int,
        reserve: Int,
        nthOldestForRelease: LocalDateTime?,
        now: LocalDateTime,
    ): BudgetDecision {
        val limit = limitFor(lane = lane, max = max, reserve = reserve)
        if (usedInWindow < limit) return BudgetDecision.Allowed
        // Without the release slot (inconsistent input, e.g. a concurrent purge) re-evaluate shortly: never an optimistic Allowed.
        val release = nthOldestForRelease ?: return BudgetDecision.WaitUntil(now.plusDuration(RECHECK))
        return BudgetDecision.WaitUntil(release.plusDuration(WINDOW))
    }
}
