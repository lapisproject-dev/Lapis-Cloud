package network.lapis.cloud.server.dashboard

import network.lapis.cloud.server.time.ServerClock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * V1.9.224 -- a tiny bounded cache with a time to live and least-recently-used eviction (no new dependency; Caffeine is not on the class
 * path). Synchronized: every access is a short map operation, never a database call.
 *
 * [get] returns a value only while it is younger than [ttl]. [getStale] returns the last stored value up to [maxAge] old, for the
 * rate-limited path that prefers an old answer to no answer. The clock is injectable so a test needs no sleeping.
 */
internal class TtlCache<K : Any, V : Any>(
    private val ttl: Duration,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val now: () -> Instant = ServerClock::nowInstant,
) {
    private class Entry<V>(
        val value: V,
        val storedAt: Instant,
    )

    private val map: LinkedHashMap<K, Entry<V>> =
        object : LinkedHashMap<K, Entry<V>>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>): Boolean = size > maxEntries
        }

    @Synchronized
    fun get(key: K): V? {
        val entry = map[key] ?: return null
        return if (now() - entry.storedAt < ttl) entry.value else null
    }

    @Synchronized
    fun getStale(
        key: K,
        maxAge: Duration,
    ): V? {
        val entry = map[key] ?: return null
        return if (now() - entry.storedAt < maxAge) entry.value else null
    }

    @Synchronized
    fun put(
        key: K,
        value: V,
    ) {
        map[key] = Entry(value = value, storedAt = now())
    }

    @Synchronized
    fun size(): Int = map.size

    companion object {
        const val DEFAULT_MAX_ENTRIES = 10_000
        private const val INITIAL_CAPACITY = 16
        private const val LOAD_FACTOR = 0.75f
    }
}
