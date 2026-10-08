package network.lapis.cloud.server.encounter

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Welle V1.9.61 -- the ONLY moderation state of an encounter session: who was removed (blocked from re-entering) and who was silenced
 * (re-enters without a data channel). **Held in memory only, never persisted** -- a block list in the database would be a lasting
 * record about a person in connection with a church service (Art. 9 GDPR). Consequences, documented in
 * `docs/architecture/encounter-space.adoc`: the state is lost on a server restart, and it is dropped when the session ends
 * ([clear], called by close and by the poller).
 *
 * Bounded: at most [MAX_SESSIONS] sessions and [MAX_ENTRIES_PER_SESSION] entries per list; a full list REFUSES the entry (office holders stop at the cap, BOARD/ADMIN have a small reserve above it) (the
 * caller turns that into a conflict) instead of evicting an older one, so a block can never silently disappear.
 * Thread-safe (a single monitor; the lists are tiny and touched rarely).
 */
class EncounterModerationState {
    private val lock = Any()
    private val removed = HashMap<Uuid, MutableSet<Uuid>>()
    private val silenced = HashMap<Uuid, MutableSet<Uuid>>()
    private val tableBans = HashMap<Uuid, MutableMap<Uuid, Instant>>()

    /** Blocks [memberId] from re-entering [sessionRoomId]. `false` iff the state is full (nothing recorded). */
    fun block(
        sessionRoomId: Uuid,
        memberId: Uuid,
        privileged: Boolean = false,
    ): Boolean = add(target = removed, sessionRoomId = sessionRoomId, memberId = memberId, privileged = privileged)

    fun isBlocked(
        sessionRoomId: Uuid,
        memberId: Uuid,
    ): Boolean = synchronized(lock) { removed[sessionRoomId]?.contains(memberId) == true }

    /** Marks [memberId] as silenced in [sessionRoomId]. `false` iff the state is full (nothing recorded). */
    fun silence(
        sessionRoomId: Uuid,
        memberId: Uuid,
        privileged: Boolean = false,
    ): Boolean = add(target = silenced, sessionRoomId = sessionRoomId, memberId = memberId, privileged = privileged)

    fun isSilenced(
        sessionRoomId: Uuid,
        memberId: Uuid,
    ): Boolean = synchronized(lock) { silenced[sessionRoomId]?.contains(memberId) == true }

    /**
     * Keeps [memberId] away from the tables of [sessionRoomId] until [until] (sent back to the plenum by a moderator: without this they
     * could sit down again two seconds later). Expired entries are pruned on the way. `false` iff the state is full (nothing recorded).
     */
    fun banFromTables(
        sessionRoomId: Uuid,
        memberId: Uuid,
        until: Instant,
        now: Instant,
        privileged: Boolean = false,
    ): Boolean =
        synchronized(lock) {
            val map = tableBans[sessionRoomId]
            if (map == null) {
                if (sessionKeys().size >= MAX_SESSIONS) return false
                tableBans[sessionRoomId] = mutableMapOf(memberId to until)
                return true
            }
            map.values.removeAll { it <= now }
            if (memberId !in map && map.size >= MAX_ENTRIES_PER_SESSION + (if (privileged) PRIVILEGED_RESERVE else 0)) return false
            map[memberId] = until
            true
        }

    fun isTableBanned(
        sessionRoomId: Uuid,
        memberId: Uuid,
        now: Instant,
    ): Boolean = synchronized(lock) { tableBans[sessionRoomId]?.get(memberId)?.let { it > now } == true }

    /** The identities (member UUID strings) currently blocked in [sessionRoomId] -- the poller kicks those that are still connected. */
    fun blockedIdentities(sessionRoomId: Uuid): Set<String> =
        synchronized(lock) { removed[sessionRoomId].orEmpty().map { it.toString() }.toSet() }

    /** The identities (member UUID strings) currently silenced in [sessionRoomId] -- the poller disconnects those that still hold a data-channel grant. */
    fun silencedIdentities(sessionRoomId: Uuid): Set<String> =
        synchronized(lock) { silenced[sessionRoomId].orEmpty().map { it.toString() }.toSet() }

    /** Drops everything known about [sessionRoomId] (session ended). */
    fun clear(sessionRoomId: Uuid) {
        synchronized(lock) {
            removed.remove(sessionRoomId)
            silenced.remove(sessionRoomId)
            tableBans.remove(sessionRoomId)
        }
    }

    /** Number of sessions with any state (test/diagnostic aid). */
    fun trackedSessions(): Int = synchronized(lock) { sessionKeys().size }

    private fun sessionKeys(): Set<Uuid> = removed.keys + silenced.keys + tableBans.keys

    private fun add(
        target: MutableMap<Uuid, MutableSet<Uuid>>,
        sessionRoomId: Uuid,
        memberId: Uuid,
        privileged: Boolean,
    ): Boolean =
        synchronized(lock) {
            val set = target[sessionRoomId]
            if (set == null) {
                if (sessionKeys().size >= MAX_SESSIONS) return false
                target[sessionRoomId] = mutableSetOf(memberId)
                true
            } else if (memberId in set) {
                true
            } else if (set.size >= MAX_ENTRIES_PER_SESSION + (if (privileged) PRIVILEGED_RESERVE else 0)) {
                false
            } else {
                set.add(memberId)
                true
            }
        }

    companion object {
        const val MAX_SESSIONS = 64
        const val MAX_ENTRIES_PER_SESSION = 200

        /** Extra room per list that only BOARD/ADMIN may use, so a flooded list can never lock them out of removing a real disruptor. */
        const val PRIVILEGED_RESERVE = 20
    }
}
