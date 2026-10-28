package network.lapis.cloud.server.messaging

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** V1.9.237 -- at most this many send attempts per member within [DM_SEND_WINDOW]. */
internal const val DM_MAX_SENDS_PER_WINDOW = 30

/** V1.9.237 -- window of [DM_MAX_SENDS_PER_WINDOW]. */
internal val DM_SEND_WINDOW: Duration = 10.minutes

/** V1.9.237 -- at most this many RECIPIENTS (a group of 50 counts as 50) per member within [DM_RECIPIENT_WINDOW]. */
internal const val DM_MAX_RECIPIENTS_PER_WINDOW = 150

/** V1.9.237 -- window of [DM_MAX_RECIPIENTS_PER_WINDOW]. */
internal val DM_RECIPIENT_WINDOW: Duration = 1.hours

/** V1.9.237 -- at most this many recipient searches per member per minute. */
internal const val DM_MAX_SEARCHES_PER_MINUTE = 60

/** V1.9.237 -- at most this many block requests per member per hour. */
internal const val DM_MAX_BLOCKS_PER_HOUR = 30

/**
 * V1.9.237 -- sliding-window limits of "Neue Nachricht": sends (and recipients), recipient searches and blocks. Same shape as the AI question
 * limiter: in-process, bounded map, opportunistic eviction instead of a scheduler. **State is per JVM and resets on restart** -- a multi-instance
 * deployment would multiply the effective limits (this codebase targets a single instance).
 *
 * Every `checkAndRecord*` is atomic (check, then record, under one lock) and records an ALLOWED attempt the moment it is allowed -- a send that
 * the recipient rule refuses afterwards still counts, so retries cannot probe. A refused attempt is not recorded (the window drains normally).
 *
 * **Must never be called inside a `transaction {}`**: Exposed retries the block on an SQLException, which would count the attempt twice
 * (`DirectMessageComposeTripwireTest` pins this).
 */
class DirectMessageRateLimiter(
    private val maxTrackedMembers: Int = 100_000,
    private val now: () -> Instant = { Clock.System.now() },
) {
    private class Hit(
        val at: Instant,
        val weight: Int,
    )

    private val lock = Any()
    private val sends = HashMap<Uuid, ArrayDeque<Hit>>()
    private val searches = HashMap<Uuid, ArrayDeque<Hit>>()
    private val blocks = HashMap<Uuid, ArrayDeque<Hit>>()

    /** `true` and recorded if the member may send to [recipients] more people now; `false` (nothing recorded) otherwise. */
    fun checkAndRecordSend(
        memberId: Uuid,
        recipients: Int,
    ): Boolean =
        synchronized(lock) {
            val current = now()
            val window = sends.getOrPut(memberId) { ArrayDeque() }
            prune(window = window, cutoff = current - DM_RECIPIENT_WINDOW)
            val sendsInWindow = window.count { it.at > current - DM_SEND_WINDOW }
            val recipientsInWindow = window.sumOf { it.weight }
            if (sendsInWindow >= DM_MAX_SENDS_PER_WINDOW || recipientsInWindow + recipients > DM_MAX_RECIPIENTS_PER_WINDOW) {
                return@synchronized false
            }
            window.addLast(Hit(at = current, weight = recipients))
            evictIfOverCapacity(map = sends, current = current, retention = DM_RECIPIENT_WINDOW)
            true
        }

    fun checkAndRecordSearch(memberId: Uuid): Boolean =
        checkAndRecordCount(map = searches, memberId = memberId, span = 1.minutes, max = DM_MAX_SEARCHES_PER_MINUTE)

    fun checkAndRecordBlock(memberId: Uuid): Boolean =
        checkAndRecordCount(map = blocks, memberId = memberId, span = 1.hours, max = DM_MAX_BLOCKS_PER_HOUR)

    private fun checkAndRecordCount(
        map: HashMap<Uuid, ArrayDeque<Hit>>,
        memberId: Uuid,
        span: Duration,
        max: Int,
    ): Boolean =
        synchronized(lock) {
            val current = now()
            val window = map.getOrPut(memberId) { ArrayDeque() }
            prune(window = window, cutoff = current - span)
            if (window.size >= max) return@synchronized false
            window.addLast(Hit(at = current, weight = 1))
            evictIfOverCapacity(map = map, current = current, retention = span)
            true
        }

    private fun prune(
        window: ArrayDeque<Hit>,
        cutoff: Instant,
    ) {
        while (window.isNotEmpty() && window.first().at <= cutoff) window.removeFirst()
    }

    private fun evictIfOverCapacity(
        map: HashMap<Uuid, ArrayDeque<Hit>>,
        current: Instant,
        retention: Duration,
    ) {
        if (map.size <= maxTrackedMembers) return
        map.entries.removeIf { (_, window) ->
            prune(window = window, cutoff = current - retention)
            window.isEmpty()
        }
    }
}
