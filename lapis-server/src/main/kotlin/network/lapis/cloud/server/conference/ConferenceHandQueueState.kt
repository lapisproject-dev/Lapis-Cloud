package network.lapis.cloud.server.conference

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.CONFERENCE_HANDS_MAX_PAYLOAD_BYTES
import network.lapis.cloud.shared.domain.CONFERENCE_HAND_QUEUE_MAX
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val HAND_UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"

/**
 * The identities a raised hand may carry: a member UUID, `evg-<uuid>` or a ticket guest's `evg-<32 hex>` (lower case); nothing else
 * ever reaches a packet.
 */
internal val HAND_IDENTITY_REGEX = Regex("^(?:$HAND_UUID_PATTERN|evg-(?:$HAND_UUID_PATTERN|[0-9a-f]{32}))\$")

/** Thrown by [ConferenceHandQueueState.raise] when a room already holds [CONFERENCE_HAND_QUEUE_MAX] raised hands. */
class HandQueueFullException : Exception("The raised-hand queue of the room is full")

/**
 * V1.9.127 -- the order of the raised hands of every active conference room. **In memory on purpose** (design option "c with epoch"): no
 * database row, no stored time, no history, no audit. Per room it keeps the order, a monotonic [Snapshot.seq] (raised on every real
 * change) and, for the presence grace, when each person was first missing from LiveKit. [epoch] is drawn at random once per process:
 * after a restart the queue is empty and `seq` starts over, and a client tells "a moderator lowered my hand" (same epoch, hand missing)
 * from "the server restarted" (new epoch, so it re-raises on its own).
 *
 * A process-wide singleton wired in `Application.module` (the services are built per RPC call). Thread safety: every access to one
 * room's data synchronises on that room's object; [withRoomLock] additionally serialises "mutate + build the snapshot" of the RPC
 * service and the reconciler's pass over one room (like [ConferenceAudioGrantState.withRoomLock]); it is NOT reentrant.
 *
 * Privacy: nothing in here is ever logged -- not an identity, not a room id; `missingSince` never leaves this class.
 */
class ConferenceHandQueueState(
    private val maxPerRoom: Int = CONFERENCE_HAND_QUEUE_MAX,
    private val nowMillis: () -> Long = { ServerClock.nowInstant().toEpochMilliseconds() },
    val epoch: String = randomEpoch(),
) {
    /** One consistent view of a room's queue: [identities] is the order, first hand first. */
    data class Snapshot(
        val epoch: String,
        val seq: Long,
        val identities: List<String>,
    )

    /** What a mutation did: nothing, or a new [Snapshot] that has to be broadcast. */
    sealed interface Change {
        data object None : Change

        data class Changed(
            val snapshot: Snapshot,
        ) : Change
    }

    /** Result of [observePresence]: the removals it made and the identities seen for the first time in this room. */
    data class PresenceResult(
        val change: Change,
        val newlySeen: Set<String>,
    )

    private class Room {
        val order = LinkedHashSet<String>()
        var seq = 0L
        val missingSince = HashMap<String, Long>()
        var lastSeen: Set<String> = emptySet()
    }

    private val rooms = ConcurrentHashMap<Uuid, Room>()
    private val roomLocks = ConcurrentHashMap<Uuid, Mutex>()

    /** Serialises "mutate + build the snapshot" per room. NOT reentrant -- [block] must not call [withRoomLock] for the same room. */
    suspend fun <T> withRoomLock(
        roomId: Uuid,
        block: suspend () -> T,
    ): T = roomLocks.computeIfAbsent(roomId) { Mutex() }.withLock { block() }

    /** Raises [identity]'s hand; idempotent (the position is kept, [Change.None]). A full queue throws [HandQueueFullException]. */
    fun raise(
        roomId: Uuid,
        identity: String,
    ): Change {
        require(HAND_IDENTITY_REGEX.matches(identity)) { "not a hand identity" }
        return room(roomId).let { room ->
            synchronized(room) {
                if (identity in room.order) return Change.None
                if (room.order.size >= maxPerRoom) throw HandQueueFullException()
                room.order += identity
                room.missingSince.remove(identity)
                changed(room)
            }
        }
    }

    /** Lowers [identity]'s hand ([Change.None] if it was not raised). Raising again later puts the person at the end. */
    fun lower(
        roomId: Uuid,
        identity: String,
    ): Change =
        room(roomId).let { room ->
            synchronized(room) {
                if (!room.order.remove(identity)) return Change.None
                room.missingSince.remove(identity)
                changed(room)
            }
        }

    /** Lowers every hand of the room. */
    fun lowerAll(roomId: Uuid): Change =
        room(roomId).let { room ->
            synchronized(room) {
                if (room.order.isEmpty()) return Change.None
                room.order.clear()
                room.missingSince.clear()
                changed(room)
            }
        }

    fun snapshot(roomId: Uuid): Snapshot = room(roomId).let { room -> synchronized(room) { snapshotOf(room) } }

    fun contains(
        roomId: Uuid,
        identity: String,
    ): Boolean = rooms[roomId]?.let { room -> synchronized(room) { identity in room.order } } == true

    /**
     * Compares the queue with the people LiveKit reports in the room ([present], member UUIDs and `evg-` identities only). Somebody who is
     * missing for [graceSeconds] or longer is removed (a reconnect with the same identity within the grace keeps the position); the
     * identities in [present] that were not present at the previous call are returned as [PresenceResult.newlySeen] so the caller can send
     * them the current snapshot (account-less event guests have no read API).
     */
    fun observePresence(
        roomId: Uuid,
        present: Set<String>,
        graceSeconds: Long = DEFAULT_GRACE_SECONDS,
    ): PresenceResult =
        room(roomId).let { room ->
            synchronized(room) {
                val now = nowMillis()
                var removed = false
                val iterator = room.order.iterator()
                while (iterator.hasNext()) {
                    val identity = iterator.next()
                    if (identity in present) {
                        room.missingSince.remove(identity)
                        continue
                    }
                    val since = room.missingSince.getOrPut(identity) { now }
                    if (now - since >= graceSeconds * 1000) {
                        iterator.remove()
                        room.missingSince.remove(identity)
                        removed = true
                    }
                }
                val newlySeen = present - room.lastSeen
                room.lastSeen = present.toSet()
                PresenceResult(change = if (removed) changed(room) else Change.None, newlySeen = newlySeen)
            }
        }

    /** Drops every in-memory trace of rooms that are not in [activeRoomIds] (ended rooms). A mutex that is currently held is kept. */
    fun pruneInactiveRooms(activeRoomIds: Set<Uuid>) {
        rooms.keys.removeIf { it !in activeRoomIds }
        roomLocks.entries.removeIf { (id, mutex) -> id !in activeRoomIds && !mutex.isLocked }
    }

    /** Rooms this state has an entry for (the reconciler checks them against the database before pruning). */
    fun knownRoomIds(): Set<Uuid> = rooms.keys.toSet()

    /** Rooms with at least one raised hand: the only rooms the reconciler has to look at. */
    fun activeRoomIds(): Set<Uuid> =
        rooms.entries
            .filter { (_, room) ->
                synchronized(room) { room.order.isNotEmpty() }
            }.map { it.key }
            .toSet()

    private fun room(roomId: Uuid): Room = rooms.computeIfAbsent(roomId) { Room() }

    private fun changed(room: Room): Change.Changed {
        room.seq++
        return Change.Changed(snapshotOf(room))
    }

    private fun snapshotOf(room: Room) = Snapshot(epoch = epoch, seq = room.seq, identities = room.order.toList())

    companion object {
        /** How long somebody may be missing from LiveKit before the hand falls (a reconnect with the same identity keeps the position). */
        const val DEFAULT_GRACE_SECONDS = 20L

        fun randomEpoch(): String {
            val bytes = ByteArray(8)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

/** The closed set of server -> client hands payloads (`{"v":1,"e":"<epoch>","seq":N,"ids":[...]}`). */
internal object ConferenceHandsPackets {
    fun snapshot(snapshot: ConferenceHandQueueState.Snapshot): ByteArray {
        require(snapshot.identities.all { HAND_IDENTITY_REGEX.matches(it) }) { "not a hand identity" }
        val ids = snapshot.identities.joinToString(",") { "\"$it\"" }
        val json = """{"v":1,"e":"${snapshot.epoch}","seq":${snapshot.seq},"ids":[$ids]}"""
        return json.toByteArray(Charsets.UTF_8).also {
            check(it.size <= CONFERENCE_HANDS_MAX_PAYLOAD_BYTES)
        }
    }
}
