package network.lapis.cloud.server.encounter

import network.lapis.cloud.shared.domain.ENCOUNTER_MAP_COLUMNS
import network.lapis.cloud.shared.domain.ENCOUNTER_MAP_INITIALS_MAX
import network.lapis.cloud.shared.domain.ENCOUNTER_MAP_MAX_PAYLOAD_BYTES
import network.lapis.cloud.shared.domain.ENCOUNTER_MAP_MAX_WALKERS
import network.lapis.cloud.shared.domain.EncounterMapAnchor
import network.lapis.cloud.shared.domain.EncounterMapFacing
import network.lapis.cloud.shared.domain.EncounterMapMove
import network.lapis.cloud.shared.domain.EncounterMapPacket
import network.lapis.cloud.shared.domain.EncounterMapPositionDto
import network.lapis.cloud.shared.domain.EncounterMapSelfDto
import network.lapis.cloud.shared.domain.EncounterMapSnapshotDto
import network.lapis.cloud.shared.domain.EncounterMapTemplate
import network.lapis.cloud.shared.domain.EncounterMapTemplates
import network.lapis.cloud.shared.domain.EncounterRoomLayout
import network.lapis.cloud.shared.domain.encounterMapNearestFree
import network.lapis.cloud.shared.domain.encounterMapPayload
import kotlin.uuid.Uuid

/**
 * Welle V1.9.166 (encounter room stage 3, wave 1) -- the ONLY position state of the grid map: which congregation person stands on which tile
 * and looks where. **Held in memory only, never persisted, never logged, never audited** -- a position in the database or in a log would be a
 * lasting record of who stood next to whom at a church service or a political assembly (Art. 9 GDPR). Consequences, documented in
 * `docs/architecture/encounter-space.adoc`: the state is lost on a server restart (a new [epoch] tells every client) and dropped when the session
 * ends ([clear], the poller). Per server instance. There is no history: only the current tile of a person who is on the map right now.
 *
 * Server-authoritative: a client sends a DIRECTION, never a coordinate. Bounded: [MAX_SESSIONS] sessions, [ENCOUNTER_MAP_MAX_WALKERS] walkers and
 * [MAX_VIEWERS] registered viewers per session.
 *
 * Thread-safe through a single monitor, exactly like [EncounterSeatState]: no suspend call, no logger, no transaction, no audit entry in this
 * class. LiveKit delivery is the job of `EncounterMapBroadcaster`, which only calls [drain].
 */
class EncounterMapState(
    private val monoMs: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    /** Changes with every process start: a client that sees another epoch drops everything it knows and fetches a snapshot. */
    val epoch: String =
        Uuid
            .random()
            .toString()
            .replace("-", "")
            .take(EPOCH_LENGTH),
) {
    private val lock = Any()
    private val sessions = HashMap<Uuid, Session>()
    private val sessionBySpace = HashMap<Uuid, Uuid>()
    private val pendingResets = ArrayDeque<Pending>()

    sealed interface EnterOutcome {
        data class Ok(
            val self: EncounterMapSelfDto,
        ) : EnterOutcome

        data object Full : EnterOutcome
    }

    sealed interface MoveOutcome {
        data class Done(
            val self: EncounterMapSelfDto,
        ) : MoveOutcome

        data object NotOnMap : MoveOutcome
    }

    /** One data-channel message to send: to [recipients] (member ids = LiveKit identities, members only) in [livekitRoom]. */
    data class Pending(
        val livekitRoom: String,
        val recipients: List<String>,
        val packet: EncounterMapPacket,
    )

    private class Walker(
        var tile: Int,
        var facing: EncounterMapFacing,
        val initials: String,
        var tokens: Int,
        var lastRefillMs: Long,
        var lastTeleportMs: Long?,
    )

    private class Session(
        val sessionRoomId: Uuid,
        val spaceId: Uuid,
        var livekitRoom: String,
        val layout: EncounterRoomLayout,
        val template: EncounterMapTemplate,
    ) {
        val walkers = LinkedHashMap<Uuid, Walker>()
        val occupied = HashMap<Int, Uuid>()
        val viewers = LinkedHashSet<Uuid>()
        val dirtyChanged = LinkedHashSet<Uuid>()
        val dirtyRemoved = LinkedHashSet<Uuid>()
        var seq: Long = 0
    }

    /**
     * Puts [memberId] on the nearest free tile to the entrance. Idempotent (a person who is on the map keeps the tile). [Full] = the map holds
     * [ENCOUNTER_MAP_MAX_WALKERS] people, no tile is free, the layout has no map or [MAX_SESSIONS] sessions exist.
     */
    fun enter(
        spaceId: Uuid,
        sessionRoomId: Uuid,
        livekitRoom: String,
        layout: EncounterRoomLayout,
        memberId: Uuid,
        initials: String,
    ): EnterOutcome =
        synchronized(lock) {
            val session =
                sessionLocked(spaceId = spaceId, sessionRoomId = sessionRoomId, livekitRoom = livekitRoom, layout = layout, create = true)
                    ?: return@synchronized EnterOutcome.Full
            val existing = session.walkers[memberId]
            if (existing != null) {
                addViewerLocked(session = session, memberId = memberId)
                return@synchronized EnterOutcome.Ok(selfOf(session = session, memberId = memberId))
            }
            if (session.walkers.size >= ENCOUNTER_MAP_MAX_WALKERS) return@synchronized EnterOutcome.Full
            val entrance = session.template.anchors.getValue(EncounterMapAnchor.ENTRANCE)
            val tile =
                encounterMapNearestFree(t = session.template, occupied = session.occupied.keys, from = entrance)
                    ?: return@synchronized EnterOutcome.Full
            if (!addViewerLocked(session = session, memberId = memberId)) return@synchronized EnterOutcome.Full
            val now = monoMs()
            session.walkers[memberId] =
                Walker(
                    tile = tile,
                    facing = EncounterMapFacing.NORTH,
                    initials = initials.take(ENCOUNTER_MAP_INITIALS_MAX),
                    tokens = BUCKET,
                    lastRefillMs = now,
                    lastTeleportMs = null,
                )
            session.occupied[tile] = memberId
            session.dirtyRemoved.remove(memberId)
            session.dirtyChanged.add(memberId)
            EnterOutcome.Ok(selfOf(session = session, memberId = memberId))
        }

    /** One step. The facing always changes; a wall, the edge or another person is [EncounterMapMove.BLOCKED]; no token is [EncounterMapMove.THROTTLED]. */
    fun step(
        spaceId: Uuid,
        memberId: Uuid,
        dir: EncounterMapFacing,
    ): MoveOutcome =
        synchronized(lock) {
            val session = sessionOfSpaceLocked(spaceId) ?: return@synchronized MoveOutcome.NotOnMap
            val walker = session.walkers[memberId] ?: return@synchronized MoveOutcome.NotOnMap
            if (!takeTokenLocked(
                    walker,
                )
            ) {
                return@synchronized MoveOutcome.Done(selfOf(session = session, memberId = memberId, move = EncounterMapMove.THROTTLED))
            }
            val target = session.template.neighbor(index = walker.tile, dir = dir)
            val blocked = target == null || !session.template.walkable(target) || session.occupied[target] != null
            var changed = false
            if (walker.facing != dir) {
                walker.facing = dir
                changed = true
            }
            if (!blocked && target != null) {
                session.occupied.remove(walker.tile)
                walker.tile = target
                session.occupied[target] = memberId
                changed = true
            }
            if (changed) session.dirtyChanged.add(memberId)
            MoveOutcome.Done(
                selfOf(session = session, memberId = memberId, move = if (blocked) EncounterMapMove.BLOCKED else EncounterMapMove.MOVED),
            )
        }

    /** Jumps to the nearest free tile of [anchor]; at most once every [TELEPORT_MS]. */
    fun teleport(
        spaceId: Uuid,
        memberId: Uuid,
        anchor: EncounterMapAnchor,
    ): MoveOutcome =
        synchronized(lock) {
            val session = sessionOfSpaceLocked(spaceId) ?: return@synchronized MoveOutcome.NotOnMap
            val walker = session.walkers[memberId] ?: return@synchronized MoveOutcome.NotOnMap
            val now = monoMs()
            val last = walker.lastTeleportMs
            if (last != null && now - last < TELEPORT_MS) {
                return@synchronized MoveOutcome.Done(selfOf(session = session, memberId = memberId, move = EncounterMapMove.THROTTLED))
            }
            val others = session.occupied.filterValues { it != memberId }.keys
            val target =
                encounterMapNearestFree(t = session.template, occupied = others, from = session.template.anchors.getValue(anchor))
                    ?: return@synchronized MoveOutcome.Done(selfOf(session = session, memberId = memberId, move = EncounterMapMove.BLOCKED))
            if (target != walker.tile) {
                walker.lastTeleportMs = now
                session.occupied.remove(walker.tile)
                walker.tile = target
                session.occupied[target] = memberId
                session.dirtyChanged.add(memberId)
            }
            MoveOutcome.Done(selfOf(session = session, memberId = memberId))
        }

    /**
     * Takes [memberId] off the map of [sessionRoomId] (idempotent). With [dropViewer] the person also stops receiving packets (the person left
     * the room or was removed); without it they stay a viewer (they merely sat down or left the map).
     */
    fun leave(
        sessionRoomId: Uuid,
        memberId: Uuid,
        dropViewer: Boolean = false,
    ) {
        synchronized(
            lock,
        ) { leaveLocked(session = sessions[sessionRoomId] ?: return@synchronized, memberId = memberId, dropViewer = dropViewer) }
    }

    fun leaveAll(
        sessionRoomId: Uuid,
        memberIds: Collection<Uuid>,
        dropViewer: Boolean = false,
    ) {
        synchronized(lock) {
            val session = sessions[sessionRoomId] ?: return@synchronized
            memberIds.forEach { leaveLocked(session = session, memberId = it, dropViewer = dropViewer) }
        }
    }

    /**
     * Registers [memberId] for the packets of the session (idempotent). Called ONLY after the service verified an ACTIVE member who is present --
     * a guest never gets here, so a guest can never be a recipient. `false` = [MAX_VIEWERS] or [MAX_SESSIONS] reached.
     */
    fun addViewer(
        spaceId: Uuid,
        sessionRoomId: Uuid,
        livekitRoom: String,
        layout: EncounterRoomLayout,
        memberId: Uuid,
    ): Boolean =
        synchronized(lock) {
            val session =
                sessionLocked(spaceId = spaceId, sessionRoomId = sessionRoomId, livekitRoom = livekitRoom, layout = layout, create = true)
                    ?: return@synchronized false
            addViewerLocked(session = session, memberId = memberId)
        }

    /** Everybody on the map plus [memberId]'s own position. An unknown session is an empty map. */
    fun snapshot(
        sessionRoomId: Uuid,
        memberId: Uuid,
    ): EncounterMapSnapshotDto =
        synchronized(lock) {
            val session = sessions[sessionRoomId]
            if (session == null) {
                return@synchronized EncounterMapSnapshotDto(
                    epoch = epoch,
                    seq = 0,
                    positions = emptyList(),
                    self = EncounterMapSelfDto(onMap = false),
                )
            }
            EncounterMapSnapshotDto(
                epoch = epoch,
                seq = session.seq,
                positions = session.walkers.keys.map { positionOf(session = session, memberId = it) },
                self = selfOf(session = session, memberId = memberId),
            )
        }

    /**
     * Collects what the broadcaster has to send now: the queued resets plus, per session with changes, ONE delta (or a reset if the delta would
     * not fit the packet limit). Recipients are the registered viewers only.
     */
    fun drain(): List<Pending> =
        synchronized(lock) {
            val out = ArrayList<Pending>()
            while (pendingResets.isNotEmpty()) out += pendingResets.removeFirst()
            for (session in sessions.values) {
                if (session.dirtyChanged.isEmpty() && session.dirtyRemoved.isEmpty()) continue
                val changed = session.dirtyChanged.filter { it in session.walkers }.map { positionOf(session = session, memberId = it) }
                val removed = session.dirtyRemoved.filter { it !in session.walkers }.map { it.toString() }
                session.dirtyChanged.clear()
                session.dirtyRemoved.clear()
                if (changed.isEmpty() && removed.isEmpty()) continue
                session.seq++
                var packet: EncounterMapPacket =
                    EncounterMapPacket.Delta(
                        epoch = epoch,
                        seq = session.seq,
                        changed = changed,
                        removed = removed,
                    )
                if (encounterMapPayload(packet).toByteArray(Charsets.UTF_8).size > ENCOUNTER_MAP_MAX_PAYLOAD_BYTES ||
                    changed.size > MAX_LIST ||
                    removed.size > MAX_LIST
                ) {
                    // Fail closed: a delta the receiver would drop becomes "fetch the snapshot again".
                    packet = EncounterMapPacket.Reset(epoch = epoch, seq = session.seq)
                }
                if (session.viewers.isNotEmpty()) {
                    out += Pending(livekitRoom = session.livekitRoom, recipients = session.viewers.map { it.toString() }, packet = packet)
                }
            }
            out
        }

    /**
     * Reconciles with the database (the reconciler reads it every few seconds): everybody not in [walkers] leaves the map, everybody not in
     * [viewers] stops receiving packets.
     */
    fun retain(
        sessionRoomId: Uuid,
        walkers: Set<Uuid>,
        viewers: Set<Uuid>,
    ) {
        synchronized(lock) {
            val session = sessions[sessionRoomId] ?: return@synchronized
            session.walkers.keys
                .filter { it !in walkers }
                .forEach { leaveLocked(session = session, memberId = it, dropViewer = false) }
            val dropped = session.viewers.filter { it !in viewers }
            session.viewers.retainAll(viewers)
            // A dropped viewer stays in the room but never gets another map packet: tell it to refetch, so its Forbidden snapshot clears the board.
            if (dropped.isNotEmpty() && pendingResets.size < MAX_SESSIONS) {
                pendingResets.addLast(
                    Pending(
                        livekitRoom = session.livekitRoom,
                        recipients = dropped.map { it.toString() },
                        packet = EncounterMapPacket.Reset(epoch = epoch, seq = session.seq + 1),
                    ),
                )
            }
            if (session.walkers.isEmpty() && session.viewers.isEmpty()) {
                sessions.remove(session.sessionRoomId)
                if (sessionBySpace[session.spaceId] == session.sessionRoomId) sessionBySpace.remove(session.spaceId)
            }
        }
    }

    /**
     * Drops everything known about [sessionRoomId] (session ended / map switched off) and queues a reset for its viewers. Returns the LiveKit
     * room the reset goes to, or `null` if nothing was tracked.
     */
    fun clear(sessionRoomId: Uuid): String? =
        synchronized(lock) {
            val session = sessions.remove(sessionRoomId) ?: return@synchronized null
            if (sessionBySpace[session.spaceId] == sessionRoomId) sessionBySpace.remove(session.spaceId)
            if (session.viewers.isNotEmpty() && pendingResets.size < MAX_SESSIONS) {
                pendingResets.addLast(
                    Pending(
                        livekitRoom = session.livekitRoom,
                        recipients = session.viewers.map { it.toString() },
                        packet =
                            EncounterMapPacket.Reset(
                                epoch = epoch,
                                seq =
                                    session.seq + 1,
                            ),
                    ),
                )
            }
            session.livekitRoom
        }

    /** Drops the state of every session NOT in [open]. Safety net against an `enter` that raced with the end of a session (Art. 9 data must not outlive it). */
    fun retainOnly(open: Set<Uuid>) {
        synchronized(lock) {
            sessions.keys.filter { it !in open }.forEach { id ->
                val session = sessions.remove(id) ?: return@forEach
                if (sessionBySpace[session.spaceId] == id) sessionBySpace.remove(session.spaceId)
            }
        }
    }

    /** Number of sessions with any state (test/diagnostic aid). */
    fun trackedSessions(): Int = synchronized(lock) { sessions.size }

    /** The session ids the reconciler has to check against the database. */
    fun sessionsForReconcile(): List<Uuid> = synchronized(lock) { sessions.keys.toList() }

    /** Number of people on the map of [sessionRoomId] (test aid). */
    fun walkerCount(sessionRoomId: Uuid): Int = synchronized(lock) { sessions[sessionRoomId]?.walkers?.size ?: 0 }

    // ── Internals (all under the monitor) ──────────────────────────────────

    private fun sessionLocked(
        spaceId: Uuid,
        sessionRoomId: Uuid,
        livekitRoom: String,
        layout: EncounterRoomLayout,
        create: Boolean,
    ): Session? {
        val existing = sessions[sessionRoomId]
        if (existing != null) {
            existing.livekitRoom = livekitRoom
            return existing
        }
        if (!create || sessions.size >= MAX_SESSIONS) return null
        val template = EncounterMapTemplates.of(layout) ?: return null
        // A stale session of the same space (an ended one that was not cleaned up yet) is replaced, never kept next to the new one.
        sessionBySpace[spaceId]?.let { stale -> sessions.remove(stale) }
        val created =
            Session(sessionRoomId = sessionRoomId, spaceId = spaceId, livekitRoom = livekitRoom, layout = layout, template = template)
        sessions[sessionRoomId] = created
        sessionBySpace[spaceId] = sessionRoomId
        return created
    }

    private fun sessionOfSpaceLocked(spaceId: Uuid): Session? = sessionBySpace[spaceId]?.let { sessions[it] }

    private fun addViewerLocked(
        session: Session,
        memberId: Uuid,
    ): Boolean {
        if (memberId in session.viewers) return true
        if (session.viewers.size >= MAX_VIEWERS) return false
        session.viewers += memberId
        return true
    }

    private fun leaveLocked(
        session: Session,
        memberId: Uuid,
        dropViewer: Boolean,
    ) {
        val walker = session.walkers.remove(memberId)
        if (walker != null) {
            session.occupied.remove(walker.tile)
            session.dirtyChanged.remove(memberId)
            // The removal is announced to the viewers who stay; nothing about the person is kept.
            session.dirtyRemoved.add(memberId)
        }
        if (dropViewer) session.viewers.remove(memberId)
        if (session.walkers.isEmpty() && session.viewers.isEmpty()) {
            sessions.remove(session.sessionRoomId)
            if (sessionBySpace[session.spaceId] == session.sessionRoomId) sessionBySpace.remove(session.spaceId)
        }
    }

    private fun takeTokenLocked(walker: Walker): Boolean {
        val now = monoMs()
        val elapsed = now - walker.lastRefillMs
        if (elapsed >= REFILL_MS) {
            val gained = (elapsed / REFILL_MS).coerceAtMost(BUCKET.toLong()).toInt()
            walker.tokens = minOf(BUCKET, walker.tokens + gained)
            walker.lastRefillMs = if (walker.tokens >= BUCKET) now else walker.lastRefillMs + gained * REFILL_MS
        }
        if (walker.tokens <= 0) return false
        walker.tokens--
        return true
    }

    private fun positionOf(
        session: Session,
        memberId: Uuid,
    ): EncounterMapPositionDto {
        val walker = session.walkers.getValue(memberId)
        return EncounterMapPositionDto(
            memberId = memberId.toString(),
            x = walker.tile % ENCOUNTER_MAP_COLUMNS,
            y = walker.tile / ENCOUNTER_MAP_COLUMNS,
            facing = walker.facing,
            initials = walker.initials,
        )
    }

    private fun selfOf(
        session: Session,
        memberId: Uuid,
        move: EncounterMapMove = EncounterMapMove.MOVED,
    ): EncounterMapSelfDto {
        val walker = session.walkers[memberId] ?: return EncounterMapSelfDto(onMap = false)
        return EncounterMapSelfDto(
            onMap = true,
            x = walker.tile % ENCOUNTER_MAP_COLUMNS,
            y = walker.tile / ENCOUNTER_MAP_COLUMNS,
            facing = walker.facing,
            move = move,
        )
    }

    companion object {
        const val MAX_SESSIONS = 64
        const val MAX_VIEWERS = 500
        const val BUCKET = 3
        const val REFILL_MS = 120L

        /**
         * Minimum gap between two jumps of one person: protects against teleport spam and against scanning the map for people.
         * At 1 s more than one jump per second is ruled out; the step rate (bucket of [BUCKET], [REFILL_MS]) is untouched.
         */
        const val TELEPORT_MS = 1_000L
        private const val MAX_LIST = 64
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val EPOCH_LENGTH = 8
    }
}
