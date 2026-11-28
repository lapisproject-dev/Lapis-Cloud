package network.lapis.cloud.server.translation

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Daily character budget for translation calls: a sliding 24-hour window of `(instant, chars)`. A reservation is made BEFORE the model call
 * and is not given back on failure (a failing provider must not be an unbounded cost source, same stance as `AiQuestionRateLimiter`).
 *
 * Scope cut, documented: the state is per JVM and in memory -- a restart resets it, and a multi-instance deployment would multiply it.
 */
internal class TranslationDailyBudget(
    private val dailyChars: Int,
    private val now: () -> Instant = { Clock.System.now() },
) {
    private val lock = Any()
    private val window = ArrayDeque<Pair<Instant, Int>>()
    private var used = 0L

    fun tryReserve(chars: Int): Boolean =
        synchronized(lock) {
            prune()
            if (chars < 0 || used + chars > dailyChars) return@synchronized false
            window.addLast(now() to chars)
            used += chars
            true
        }

    fun remaining(): Int =
        synchronized(lock) {
            prune()
            (dailyChars - used).coerceAtLeast(0).toInt()
        }

    private fun prune() {
        val cutoff = now() - 1.days
        while (window.isNotEmpty() && window.first().first <= cutoff) used -= window.removeFirst().second
    }
}

/**
 * Sliding one-minute window per client key (an IP string). Bounded map, opportunistic eviction (no scheduler), like `AiQuestionRateLimiter`.
 * Built and tested in T1; wired to the public endpoint in T2.
 */
internal class TranslationIpRateLimiter(
    private val perMinute: Int,
    private val maxTrackedKeys: Int = 100_000,
    private val now: () -> Instant = { Clock.System.now() },
) {
    private val lock = Any()
    private val windows = HashMap<String, ArrayDeque<Instant>>()

    /** `true` and records the request if the key is below its budget. */
    fun checkAndRecord(ip: String): Boolean =
        synchronized(lock) {
            val current = now()
            val window = windows.getOrPut(ip) { ArrayDeque() }
            while (window.isNotEmpty() && window.first() <= current - WINDOW) window.removeFirst()
            if (window.size >= perMinute) return@synchronized false
            window.addLast(current)
            if (windows.size > maxTrackedKeys) evict(current)
            true
        }

    private fun evict(current: Instant) {
        windows.entries.removeAll { (_, w) -> w.isEmpty() || w.last() <= current - WINDOW }
        // Still over capacity: drop the oldest-idle keys until under the cap (a flood of distinct keys never grows the map unbounded).
        if (windows.size > maxTrackedKeys) {
            val victims = windows.entries.sortedBy { it.value.lastOrNull() ?: Instant.DISTANT_PAST }.take(windows.size - maxTrackedKeys)
            victims.forEach { windows.remove(it.key) }
        }
    }

    private companion object {
        val WINDOW: Duration = 1.minutes
    }
}
