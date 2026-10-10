package network.lapis.cloud.server.encounter

import network.lapis.cloud.shared.domain.ENCOUNTER_BLESSING_MIN_INTERVAL_MS
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Upper bound of tracked session rooms (V1.9.95). */
internal const val ENCOUNTER_BLESSING_RATE_MAP_MAX = 10_000

/**
 * V1.9.95 -- in-memory, per server instance: when the last blessing of a SESSION ROOM was sent. Holds room id -> instant only (no
 * member, no count). Entries older than the interval are pruned lazily; at most [maxEntries] (oldest dropped first); cleared when a
 * session closes. Never persisted: a lasting record of who blessed when would be a trace of the liturgy.
 */
class EncounterBlessingState(
    private val now: () -> Instant = { Clock.System.now() },
    private val minIntervalMs: Long = ENCOUNTER_BLESSING_MIN_INTERVAL_MS,
    private val maxEntries: Int = ENCOUNTER_BLESSING_RATE_MAP_MAX,
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
