package network.lapis.cloud.server.encounter

import kotlin.uuid.Uuid

/**
 * Welle V1.9.79 (Begegnungsraum Stufe 2a) -- the ONLY seat state of an encounter session: which congregation person chose which seat.
 * **Held in memory only, never persisted, never logged, never audited** -- a seat plan in the database would be a lasting record of who
 * sat where at a church service (Art. 9 GDPR). Consequences, documented in `docs/architecture/encounter-space.adoc`: the state is lost on
 * a server restart and it is dropped when the session ends ([clear], called by close and by the poller). Per server instance.
 *
 * Server-authoritative: the server never assigns a seat on its own, a seat exists only because its occupant chose it. One seat per
 * person. Bounded: at most [MAX_SESSIONS] sessions (a new session beyond that yields [Outcome.Full]); the seat range itself is
 * enforced by the caller (`0 until ENCOUNTER_SEAT_MAX`), so a session holds at most that many entries.
 *
 * Thread-safe through a single monitor. [select] checks "does the seat belong to somebody else who is present?", releases the old seat
 * and sets the new one inside ONE `synchronized` block: whoever enters the block first wins. No suspend call may ever sit inside it.
 */
class EncounterSeatState {
    private val lock = Any()
    private val seatsBySession = HashMap<Uuid, HashMap<Int, Uuid>>()
    private val byMember = HashMap<Uuid, HashMap<Uuid, Int>>()

    sealed interface Outcome {
        data object Ok : Outcome

        data object Taken : Outcome

        data object Full : Outcome
    }

    /**
     * Atomic. [seat] `null` releases [memberId]'s seat. A seat whose occupant is not in [present] counts as free (stale entry).
     * [present] is the set of members currently present in the session (read by the caller in the same transaction).
     */
    fun select(
        sessionRoomId: Uuid,
        memberId: Uuid,
        seat: Int?,
        present: Set<Uuid>,
    ): Outcome =
        synchronized(lock) {
            if (seat == null) {
                releaseLocked(sessionRoomId = sessionRoomId, memberId = memberId)
                return@synchronized Outcome.Ok
            }
            val seats = seatsBySession[sessionRoomId]
            if (seats == null && seatsBySession.size >= MAX_SESSIONS) return@synchronized Outcome.Full
            val occupant = seats?.get(seat)
            if (occupant != null && occupant != memberId && occupant in present) return@synchronized Outcome.Taken
            if (occupant != null && occupant != memberId) releaseLocked(sessionRoomId = sessionRoomId, memberId = occupant)
            releaseLocked(sessionRoomId = sessionRoomId, memberId = memberId)
            val target = seatsBySession.getOrPut(sessionRoomId) { HashMap() }
            target[seat] = memberId
            byMember.getOrPut(sessionRoomId) { HashMap() }[memberId] = seat
            Outcome.Ok
        }

    /** The seats of the members in [present] only (member -> seat); everybody else is pruned as a side effect. */
    fun snapshot(
        sessionRoomId: Uuid,
        present: Set<Uuid>,
    ): Map<Uuid, Int> =
        synchronized(lock) {
            val members = byMember[sessionRoomId] ?: return@synchronized emptyMap()
            val stale = members.keys.filter { it !in present }
            stale.forEach { releaseLocked(sessionRoomId = sessionRoomId, memberId = it) }
            (byMember[sessionRoomId] ?: emptyMap<Uuid, Int>()).toMap()
        }

    fun release(
        sessionRoomId: Uuid,
        memberId: Uuid,
    ) {
        synchronized(lock) { releaseLocked(sessionRoomId = sessionRoomId, memberId = memberId) }
    }

    fun releaseAll(
        sessionRoomId: Uuid,
        memberIds: Collection<Uuid>,
    ) {
        synchronized(lock) { memberIds.forEach { releaseLocked(sessionRoomId = sessionRoomId, memberId = it) } }
    }

    /** Drops everything known about [sessionRoomId] (session ended). */
    fun clear(sessionRoomId: Uuid) {
        synchronized(lock) {
            seatsBySession.remove(sessionRoomId)
            byMember.remove(sessionRoomId)
        }
    }

    /**
     * Drops the state of every session NOT in [openSessionRoomIds]. Safety net against a `select` that raced with the end of a session
     * and re-created state after [clear] (Art. 9 data must not outlive the session; orphans would also exhaust [MAX_SESSIONS]).
     */
    fun retainOnly(openSessionRoomIds: Set<Uuid>) {
        synchronized(lock) {
            seatsBySession.keys.retainAll(openSessionRoomIds)
            byMember.keys.retainAll(openSessionRoomIds)
        }
    }

    /** Number of sessions with any state (test/diagnostic aid). */
    fun trackedSessions(): Int = synchronized(lock) { seatsBySession.size }

    private fun releaseLocked(
        sessionRoomId: Uuid,
        memberId: Uuid,
    ) {
        val members = byMember[sessionRoomId] ?: return
        val seat = members.remove(memberId) ?: return
        val seats = seatsBySession[sessionRoomId]
        if (seats != null && seats[seat] == memberId) seats.remove(seat)
        if (members.isEmpty()) byMember.remove(sessionRoomId)
        if (seats != null && seats.isEmpty()) seatsBySession.remove(sessionRoomId)
    }

    companion object {
        const val MAX_SESSIONS = 64
    }
}
