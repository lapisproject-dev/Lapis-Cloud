package network.lapis.cloud.server.encounter

import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Prefix of every LiveKit room that carries the audio of ONE table. Opaque: no space id, no table number (V1.9.80). */
const val ENCOUNTER_TABLE_ROOM_PREFIX = "lc-et-"

/**
 * Welle V1.9.80 (Begegnungsraum Stufe 2b) -- the ONLY table state of an encounter session: which congregation person sits at which
 * table seat, which LiveKit room carries which table, and which tables are quieted. **Held in memory only, never persisted, never
 * logged, never audited** -- a table plan in the database would be a lasting record of who talked with whom at a gathering (Art. 9 GDPR).
 * Consequences, documented in `docs/architecture/encounter-space.adoc`: the state is lost on a server restart, it is dropped when the
 * session ends ([clear], [retainOnly]) and it lives per server instance. Same shape as [EncounterSeatState].
 *
 * **Room rotation (the security model).** Each table owns an opaque LiveKit room `lc-et-<uuid>`. LiveKit silently refreshes the tokens of
 * connected clients, so a short token TTL is no revocation. Therefore every time somebody leaves a table (or is removed, or the table is
 * quieted or released) the table gets a NEW room name and the old room is deleted (which disconnects everybody at once). Whoever still
 * belongs at the table fetches a fresh token for the new name; a stale token only points at the deleted name and, at worst, re-creates
 * an empty room that nobody listens in and the reconciler deletes again. Every mutation therefore returns the [Rotation]s the caller has
 * to carry out at LiveKit **after** its transaction (no suspend call may ever sit inside the monitor).
 *
 * Server-authoritative: the server never seats anybody on its own. One table seat per person. Bounded: at most [MAX_SESSIONS] sessions;
 * table and seat ranges are enforced by the caller (the room configuration), so a session holds at most 12 x 8 entries.
 */
open class EncounterTableState {
    private val lock = Any()
    private val sessions = HashMap<Uuid, SessionTables>()

    /**
     * What a caller has to do at LiveKit: create [newRoom] (if not `null`, with at most [maxParticipants] participants), then delete
     * [oldRoom] (if not `null`). [newRoom] `null` = the table became empty. [generation] orders the rotations of one table.
     */
    data class Rotation(
        val table: Int,
        val oldRoom: String?,
        val newRoom: String?,
        val generation: Long,
        val maxParticipants: Int,
    )

    /** Where a member sits and which room/generation carries the table right now. */
    data class Assignment(
        val table: Int,
        val seat: Int,
        val room: String,
        val generation: Long,
        val quieted: Boolean,
    )

    sealed interface JoinOutcome {
        data class Ok(
            val assignment: Assignment,
            val rotations: List<Rotation>,
        ) : JoinOutcome

        data object Taken : JoinOutcome

        data object Full : JoinOutcome

        /** The person rotated a shared table too recently ([ROTATION_COOLDOWN]); nothing was changed. */
        data object TooFast : JoinOutcome
    }

    /** Result of [leaveSelf]: [throttled] = nothing was changed because the person disturbed a shared table too recently. */
    data class SelfLeave(
        val rotation: Rotation?,
        val throttled: Boolean,
    )

    /** The members that may be in one table room and whether they may publish -- what the reconciler compares LiveKit against. */
    data class ActiveRoom(
        val sessionRoomId: Uuid,
        val identities: Set<String>,
        val publishAllowed: Boolean,
    )

    /** Seats per table (table -> occupied seat numbers), the position of every present sitter and the quieted tables. */
    data class Snapshot(
        val occupied: Map<Int, Set<Int>>,
        val positions: Map<Uuid, Pair<Int, Int>>,
        val quieted: Set<Int>,
        val rotations: List<Rotation>,
    )

    private class TableData {
        val seats = HashMap<Int, Uuid>()
        var room: String? = null
        var generation = 0L
        var quietUntil: Instant? = null
    }

    private class SessionTables(
        var seatsPerTable: Int,
    ) {
        val tables = HashMap<Int, TableData>()
        val byMember = HashMap<Uuid, Pair<Int, Int>>()

        /** When a person last rotated a table that other people still sat at by leaving it by their own choice. */
        val lastDisturb = HashMap<Uuid, Instant>()
    }

    /**
     * Atomic. A seat whose occupant is not in [present] counts as free (stale entry; the stale occupant may still be connected, so the
     * table rotates). Moving to another seat of the SAME table does not rotate (nobody loses anything); leaving another table does.
     */
    @Suppress("LongParameterList")
    fun join(
        sessionRoomId: Uuid,
        memberId: Uuid,
        table: Int,
        seat: Int,
        present: Set<Uuid>,
        seatsPerTable: Int,
        now: Instant,
    ): JoinOutcome =
        synchronized(lock) {
            val existing = sessions[sessionRoomId]
            if (existing == null && sessions.size >= MAX_SESSIONS) return@synchronized JoinOutcome.Full
            val sess = existing ?: SessionTables(seatsPerTable).also { sessions[sessionRoomId] = it }
            sess.seatsPerTable = seatsPerTable
            val target = sess.tables[table]
            val occupant = target?.seats?.get(seat)
            if (occupant != null && occupant != memberId && occupant in present) {
                dropIfEmpty(sessionRoomId = sessionRoomId, sess = sess)
                return@synchronized JoinOutcome.Taken
            }
            val previous = sess.byMember[memberId]
            // Griefing guard: leaving a table that others still sit at rotates it (deletes the room, disconnects everybody). Switching
            // tables over and over would make a table unusable, so such a departure is limited to one per ROTATION_COOLDOWN and person.
            if (previous != null && previous.first != table && hasOthersLocked(sess = sess, table = previous.first, memberId = memberId)) {
                if (disturbThrottledLocked(sess = sess, memberId = memberId, now = now)) return@synchronized JoinOutcome.TooFast
                sess.lastDisturb[memberId] = now
            }
            val rotations = mutableListOf<Rotation>()
            if (previous != null) {
                if (previous.first == table) {
                    sess.tables[table]?.seats?.remove(previous.second)
                    sess.byMember.remove(memberId)
                } else {
                    releaseLocked(sess = sess, memberId = memberId)?.let { rotations += it }
                }
            }
            var staleRemoved = false
            if (occupant != null && occupant != memberId) {
                sess.byMember.remove(occupant)
                sess.tables[table]?.seats?.remove(seat)
                staleRemoved = true
            }
            val data = sess.tables.getOrPut(table) { TableData() }
            data.seats[seat] = memberId
            sess.byMember[memberId] = table to seat
            if (data.room == null) {
                data.generation++
                data.room = newRoomName()
                rotations +=
                    Rotation(
                        table = table,
                        oldRoom = null,
                        newRoom = data.room,
                        generation = data.generation,
                        maxParticipants = sess.seatsPerTable,
                    )
            } else if (staleRemoved) {
                rotations += rotateLocked(sess = sess, index = table, data = data)
            }
            JoinOutcome.Ok(assignment = assignmentLocked(sess = sess, memberId = memberId, now = now)!!, rotations = rotations)
        }

    /** Removes [memberId] from its table (idempotent). The returned rotation, if any, must be carried out after the commit. */
    fun leave(
        sessionRoomId: Uuid,
        memberId: Uuid,
    ): Rotation? =
        synchronized(lock) {
            val sess = sessions[sessionRoomId] ?: return@synchronized null
            val rotation = releaseLocked(sess = sess, memberId = memberId)
            dropIfEmpty(sessionRoomId = sessionRoomId, sess = sess)
            rotation
        }

    /**
     * The person's OWN departure ([leave] stays unthrottled for moderation, removal and housekeeping): refused (`throttled`, nothing
     * changed) when it would rotate a table that others still sit at and the person did so less than [ROTATION_COOLDOWN] ago.
     */
    fun leaveSelf(
        sessionRoomId: Uuid,
        memberId: Uuid,
        now: Instant,
    ): SelfLeave =
        synchronized(lock) {
            val sess = sessions[sessionRoomId] ?: return@synchronized SelfLeave(rotation = null, throttled = false)
            val at = sess.byMember[memberId] ?: return@synchronized SelfLeave(rotation = null, throttled = false)
            if (hasOthersLocked(sess = sess, table = at.first, memberId = memberId)) {
                if (disturbThrottledLocked(sess = sess, memberId = memberId, now = now)) {
                    return@synchronized SelfLeave(rotation = null, throttled = true)
                }
                sess.lastDisturb[memberId] = now
            }
            val rotation = releaseLocked(sess = sess, memberId = memberId)
            dropIfEmpty(sessionRoomId = sessionRoomId, sess = sess)
            SelfLeave(rotation = rotation, throttled = false)
        }

    private fun hasOthersLocked(
        sess: SessionTables,
        table: Int,
        memberId: Uuid,
    ): Boolean =
        sess.tables[table]
            ?.seats
            ?.values
            ?.any { it != memberId } == true

    private fun disturbThrottledLocked(
        sess: SessionTables,
        memberId: Uuid,
        now: Instant,
    ): Boolean {
        if (sess.lastDisturb.size >= MAX_TRACKED_DISTURBERS) sess.lastDisturb.values.removeAll { it + ROTATION_COOLDOWN <= now }
        val last = sess.lastDisturb[memberId] ?: return false
        return now < last + ROTATION_COOLDOWN
    }

    fun leaveAll(
        sessionRoomId: Uuid,
        memberIds: Collection<Uuid>,
    ): List<Rotation> =
        synchronized(lock) {
            val sess = sessions[sessionRoomId] ?: return@synchronized emptyList()
            val rotations = releaseManyLocked(sess = sess, memberIds = memberIds)
            dropIfEmpty(sessionRoomId = sessionRoomId, sess = sess)
            rotations
        }

    /**
     * Quiets ([until] non-null) or releases ([until] `null`) a table. A change rotates the table so that the new tokens carry the new
     * grant; extending an already (still running) quieted table only moves the end. `null` = nothing to do (nobody sits there / no change).
     */
    fun quiet(
        sessionRoomId: Uuid,
        table: Int,
        until: Instant?,
        now: Instant,
    ): Rotation? =
        synchronized(lock) {
            val sess = sessions[sessionRoomId] ?: return@synchronized null
            val data = sess.tables[table] ?: return@synchronized null
            // An expired quiet that expireQuiet has not lifted yet is already "not quiet" for assignmentOf: a new quiet must rotate then.
            val wasQuiet = data.quietUntil?.let { it > now } == true
            // A stale (expired, not yet lifted) quiet is a pending rotation: overwriting quietUntil here would lose expireQuiet's rotation.
            val stale = data.quietUntil != null && !wasQuiet
            data.quietUntil = until
            if (!stale && wasQuiet == (until != null)) null else rotateLocked(sess = sess, index = table, data = data)
        }

    open fun assignmentOf(
        sessionRoomId: Uuid,
        memberId: Uuid,
        now: Instant,
    ): Assignment? =
        synchronized(lock) {
            val sess = sessions[sessionRoomId] ?: return@synchronized null
            assignmentLocked(sess = sess, memberId = memberId, now = now)
        }

    /** Prunes everybody who is not in [present] (gone, or an office holder by now) and reports the rest. */
    fun snapshot(
        sessionRoomId: Uuid,
        present: Set<Uuid>,
        now: Instant,
    ): Snapshot =
        synchronized(lock) {
            val sess =
                sessions[sessionRoomId]
                    ?: return@synchronized Snapshot(
                        occupied = emptyMap(),
                        positions = emptyMap(),
                        quieted = emptySet(),
                        rotations = emptyList(),
                    )
            val stale = sess.byMember.keys.filter { it !in present }
            val rotations = releaseManyLocked(sess = sess, memberIds = stale)
            val snapshot =
                Snapshot(
                    occupied = sess.tables.mapValues { (_, d) -> d.seats.keys.toSet() },
                    positions = sess.byMember.toMap(),
                    quieted =
                        sess.tables
                            .filterValues { d -> d.quietUntil?.let { it > now } == true }
                            .keys
                            .toSet(),
                    rotations = rotations,
                )
            dropIfEmpty(sessionRoomId = sessionRoomId, sess = sess)
            snapshot
        }

    /** Every table room that is currently allowed to exist, with who may be in it. */
    fun activeRooms(now: Instant): Map<String, ActiveRoom> =
        synchronized(lock) {
            buildMap {
                sessions.forEach { (sessionRoomId, sess) ->
                    sess.tables.values.forEach { data ->
                        val room = data.room ?: return@forEach
                        val quiet = data.quietUntil?.let { it > now } == true
                        put(
                            room,
                            ActiveRoom(
                                sessionRoomId = sessionRoomId,
                                identities =
                                    data.seats.values
                                        .map { it.toString() }
                                        .toSet(),
                                publishAllowed = !quiet,
                            ),
                        )
                    }
                }
            }
        }

    /** Rotates the table that currently owns [roomName] (the reconciler found a violation there). `null` = not a current table room. */
    fun rotateRoom(roomName: String): Rotation? =
        synchronized(lock) {
            sessions.values.forEach { sess ->
                sess.tables.forEach { (index, data) ->
                    if (data.room == roomName) return@synchronized rotateLocked(sess = sess, index = index, data = data)
                }
            }
            null
        }

    /** Lifts every quiet whose time is up and rotates those tables so that the members get a publishing token again. */
    fun expireQuiet(now: Instant): List<Rotation> =
        synchronized(lock) {
            val rotations = mutableListOf<Rotation>()
            sessions.values.forEach { sess ->
                sess.tables.forEach { (index, data) ->
                    val until = data.quietUntil
                    if (until != null && until <= now) {
                        data.quietUntil = null
                        rotations += rotateLocked(sess = sess, index = index, data = data)
                    }
                }
            }
            rotations
        }

    /** Drops everything known about [sessionRoomId] (session ended); returns the LiveKit rooms to delete. */
    fun clear(sessionRoomId: Uuid): List<String> =
        synchronized(lock) {
            val sess = sessions.remove(sessionRoomId) ?: return@synchronized emptyList()
            sess.tables.values.mapNotNull { it.room }
        }

    /** Drops the state of every session NOT in [openSessionRoomIds] (safety net against a join that raced with the end); returns the rooms. */
    fun retainOnly(openSessionRoomIds: Set<Uuid>): List<String> =
        synchronized(lock) {
            val gone = sessions.keys.filter { it !in openSessionRoomIds }
            gone.flatMap { id ->
                sessions
                    .remove(id)!!
                    .tables.values
                    .mapNotNull { it.room }
            }
        }

    /** Number of sessions with any state (test/diagnostic aid). */
    fun trackedSessions(): Int = synchronized(lock) { sessions.size }

    private fun assignmentLocked(
        sess: SessionTables,
        memberId: Uuid,
        now: Instant,
    ): Assignment? {
        val (table, seat) = sess.byMember[memberId] ?: return null
        val data = sess.tables[table] ?: return null
        val room = data.room ?: return null
        return Assignment(
            table = table,
            seat = seat,
            room = room,
            generation = data.generation,
            quieted = data.quietUntil?.let { it > now } == true,
        )
    }

    /** Removes one member and returns the rotation of the table it left (delete if it is empty now), or `null` if it sat nowhere. */
    private fun releaseLocked(
        sess: SessionTables,
        memberId: Uuid,
    ): Rotation? = releaseManyLocked(sess = sess, memberIds = listOf(memberId)).firstOrNull()

    /** Removes the members and rotates every affected table ONCE. */
    private fun releaseManyLocked(
        sess: SessionTables,
        memberIds: Collection<Uuid>,
    ): List<Rotation> {
        val touched = linkedSetOf<Int>()
        memberIds.forEach { id ->
            val (table, seat) = sess.byMember.remove(id) ?: return@forEach
            sess.tables[table]?.let { data ->
                if (data.seats[seat] == id) data.seats.remove(seat)
                touched += table
            }
        }
        return touched.mapNotNull { index ->
            val data = sess.tables[index] ?: return@mapNotNull null
            rotateLocked(sess = sess, index = index, data = data)
        }
    }

    /** A new room name (the table has members) or the deletion of the old one (it is empty -- the table is forgotten). */
    private fun rotateLocked(
        sess: SessionTables,
        index: Int,
        data: TableData,
    ): Rotation {
        val old = data.room
        data.generation++
        if (data.seats.isEmpty()) {
            sess.tables.remove(index)
            return Rotation(
                table = index,
                oldRoom = old,
                newRoom = null,
                generation = data.generation,
                maxParticipants = sess.seatsPerTable,
            )
        }
        data.room = newRoomName()
        return Rotation(
            table = index,
            oldRoom = old,
            newRoom = data.room,
            generation = data.generation,
            maxParticipants = sess.seatsPerTable,
        )
    }

    private fun dropIfEmpty(
        sessionRoomId: Uuid,
        sess: SessionTables,
    ) {
        if (sess.byMember.isEmpty() && sess.tables.isEmpty()) sessions.remove(sessionRoomId)
    }

    private fun newRoomName(): String = "$ENCOUNTER_TABLE_ROOM_PREFIX${Uuid.random()}"

    companion object {
        const val MAX_SESSIONS = 64
        private const val MAX_TRACKED_DISTURBERS = 256

        /** Minimum time between two departures of one person from a table that others still sit at. */
        val ROTATION_COOLDOWN = 10.seconds
    }
}
