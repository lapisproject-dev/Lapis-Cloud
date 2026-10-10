package network.lapis.cloud.server.encounter

import network.lapis.cloud.shared.domain.ENCOUNTER_BELL_MIN_INTERVAL_MS
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Upper bound of tracked session rooms (V1.9.96). */
internal const val ENCOUNTER_BELL_RATE_MAP_MAX = 10_000

/**
 * V1.9.96 -- in-memory, per server instance: when the last bell of a SESSION ROOM was rung. Holds room id -> instant only (no member,
 * no count). Entries older than the interval are pruned lazily; at most [maxEntries] (oldest dropped first); cleared when a session
 * closes. Never persisted: a lasting record of who rang when would be a trace of the liturgy. Deliberately separate from the blessing
 * state so the blessing stays untouched.
 */
class EncounterBellState(
    private val now: () -> Instant = { Clock.System.now() },
    private val minIntervalMs: Long = ENCOUNTER_BELL_MIN_INTERVAL_MS,
    private val maxEntries: Int = ENCOUNTER_BELL_RATE_MAP_MAX,
) {
    private val lock = Any()
    private val last = LinkedHashMap<Uuid, Instant>()

    /** `true` = send now (and remember); `false` = within the interval, swallow. */
    fun tryAcquire(sessionRoomId: Uuid): Boolean =
        synchronized(lock) {
            val t = now()
            val cutoff = t.toEpochMilliseconds() - minIntervalMs
            last.values.removeAll { it.toEpochMilliseconds() <= cutoff }
            val previous = last[sessionRoomId]
            if (previous != null && t.toEpochMilliseconds() - previous.toEpochMilliseconds() < minIntervalMs) return false
            last.remove(sessionRoomId)
            while (last.size >= maxEntries) last.remove(last.keys.first())
            last[sessionRoomId] = t
            true
        }

    fun clear(sessionRoomId: Uuid) {
        synchronized(lock) { last.remove(sessionRoomId) }
    }

    internal fun size(): Int = synchronized(lock) { last.size }
}
