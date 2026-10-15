package network.lapis.cloud.server.conference

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.CONFERENCE_TIMER_MAX_PAYLOAD_BYTES
import network.lapis.cloud.shared.domain.CONFERENCE_TIMER_MAX_SECONDS
import network.lapis.cloud.shared.domain.CONFERENCE_TIMER_MIN_SECONDS
import network.lapis.cloud.shared.domain.ConferenceTimerStatus
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

/** Thrown when an action is not possible in the current state (start on a running timer, pause on an expired one, ...). */
class TimerTransitionException : Exception("Timer transition not allowed")

/** Thrown when a duration (or the remaining time after an extension) is outside 10 s .. 60 min. */
class TimerLimitException : Exception("Timer duration out of range")

private const val TIMER_MIN_MS = CONFERENCE_TIMER_MIN_SECONDS * 1000L
private const val TIMER_MAX_MS = CONFERENCE_TIMER_MAX_SECONDS * 1000L

/** The progress-ring denominator grows with every extension; it is capped so the packet size stays bounded. */
private const val TIMER_INITIAL_CAP_MS = 86_400_000L

private val TIMER_EPOCH_REGEX = Regex("^[0-9a-f]{16}\$")

private const val TIMER_EPOCH_BYTES = 8

/** 16 random lower-case hex characters, drawn once per process (the packet and the clients check exactly this shape). */
internal fun randomTimerEpoch(): String {
    val bytes = ByteArray(TIMER_EPOCH_BYTES)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

/**
 * V1.9.133 -- the display-only timer of every active conference room. **In memory on purpose**, like [ConferenceHandQueueState]: no
 * database row, no stored time, no history, no audit. The remaining time of a running timer is derived from a monotonic mark
 * (`endMark`), never from a wall clock; the wall clock ([wallNowMillis]) only fills the packet's `t` so a client can estimate the offset
 * between its clock and the server's. [Status EXPIRED][ConferenceTimerStatus.EXPIRED] is derived (a running timer whose remaining time is
 * 0): it is stored nowhere, does not raise [Snapshot.seq] and needs no scheduler.
 *
 * [epoch] is random per process: after a restart every timer is off and `seq` starts over, which clients detect as an epoch change.
 *
 * A process-wide singleton wired in `Application.module`. Thread safety: every access to one room's data synchronises on that room's
 * object; [withRoomLock] additionally serialises "mutate + build the snapshot" of the RPC service and the reconciler's pass over one room;
 * it is NOT reentrant. Nothing in here is ever logged -- not a room id, not a time.
 */
class ConferenceTimerState(
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    private val wallNowMillis: () -> Long = { ServerClock.nowInstant().toEpochMilliseconds() },
    val epoch: String = randomTimerEpoch(),
) {
    /** One consistent view of a room's timer; [remainingMs] is the time left at [serverNowEpochMs] and never negative. */
    data class Snapshot(
        val epoch: String,
        val seq: Long,
        val status: ConferenceTimerStatus,
        val initialDurationMs: Long,
        val remainingMs: Long,
        val serverNowEpochMs: Long,
    )

    /** What a mutation did: nothing, or a new [Snapshot] that has to be broadcast. */
    sealed interface Change {
        data object None : Change

        data class Changed(
            val snapshot: Snapshot,
        ) : Change
    }

    private enum class Phase { OFF, RUNNING, PAUSED }

    private class Room {
        var phase = Phase.OFF
        var initialDurationMs = 0L
        var endMark: ComparableTimeMark? = null
        var pausedRemainingMs = 0L
        var seq = 0L

        /** identity -> LiveKit participant sid of the participants that were told the current snapshot (cleared on every change). */
        var lastSeen: Map<String, String> = emptyMap()
    }

    private val rooms = ConcurrentHashMap<Uuid, Room>()
    private val roomLocks = ConcurrentHashMap<Uuid, Mutex>()

    /** Serialises "mutate + build the snapshot" per room. NOT reentrant -- [block] must not call [withRoomLock] for the same room. */
    suspend fun <T> withRoomLock(
        roomId: Uuid,
        block: suspend () -> T,
    ): T = roomLocks.computeIfAbsent(roomId) { Mutex() }.withLock { block() }

    /** Starts a timer of [durationMs] (10 s .. 60 min); allowed when it is off or expired, else [TimerTransitionException]. */
    fun start(
        roomId: Uuid,
        durationMs: Long,
    ): Change {
        if (durationMs !in TIMER_MIN_MS..TIMER_MAX_MS || durationMs % 1000L != 0L) throw TimerLimitException()
        return synchronizedRoom(roomId = roomId) { room ->
            if (room.phase != Phase.OFF && remainingOf(room) > 0) throw TimerTransitionException()
            startRun(room = room, remainingMs = durationMs, initialMs = durationMs)
            changed(room)
        }
    }

    /** Pauses a running timer; a paused one is a no-op, off or expired is [TimerTransitionException]. */
    fun pause(roomId: Uuid): Change =
        synchronizedRoom(roomId = roomId) { room ->
            when {
                room.phase == Phase.PAUSED -> Change.None
                room.phase == Phase.RUNNING && remainingOf(room) > 0 -> {
                    room.pausedRemainingMs = remainingOf(room)
                    room.endMark = null
                    room.phase = Phase.PAUSED
                    changed(room)
                }
                else -> throw TimerTransitionException()
            }
        }

    /** Resumes a paused timer; a running one is a no-op, off or expired is [TimerTransitionException]. */
    fun resume(roomId: Uuid): Change =
        synchronizedRoom(roomId = roomId) { room ->
            when {
                room.phase == Phase.RUNNING && remainingOf(room) > 0 -> Change.None
                room.phase == Phase.PAUSED -> {
                    room.endMark = timeSource.markNow() + room.pausedRemainingMs.milliseconds
                    room.phase = Phase.RUNNING
                    changed(room)
                }
                else -> throw TimerTransitionException()
            }
        }

    /** Clears the timer from any state; off is a no-op. */
    fun stop(roomId: Uuid): Change =
        synchronizedRoom(roomId = roomId) { room ->
            if (room.phase == Phase.OFF) return@synchronizedRoom Change.None
            room.phase = Phase.OFF
            room.endMark = null
            room.pausedRemainingMs = 0
            room.initialDurationMs = 0
            changed(room)
        }

    /**
     * Adds [extensionMs] to a running or paused timer (the remaining time must stay <= 60 min, else [TimerLimitException]); on an
     * expired timer it starts a new one of that length; off is [TimerTransitionException].
     */
    fun extend(
        roomId: Uuid,
        extensionMs: Long,
    ): Change {
        if (extensionMs <= 0L) throw TimerLimitException()
        return synchronizedRoom(roomId = roomId) { room ->
            if (room.phase == Phase.OFF) throw TimerTransitionException()
            val remaining = remainingOf(room)
            if (room.phase == Phase.RUNNING && remaining <= 0) {
                if (extensionMs > TIMER_MAX_MS) throw TimerLimitException()
                startRun(room = room, remainingMs = extensionMs, initialMs = extensionMs)
            } else {
                val newRemaining = remaining + extensionMs
                if (newRemaining > TIMER_MAX_MS) throw TimerLimitException()
                val newInitial = minOf(room.initialDurationMs + extensionMs, TIMER_INITIAL_CAP_MS)
                if (room.phase == Phase.RUNNING) {
                    startRun(room = room, remainingMs = newRemaining, initialMs = newInitial)
                } else {
                    room.pausedRemainingMs = newRemaining
                    room.initialDurationMs = newInitial
                }
            }
            changed(room)
        }
    }

    fun snapshot(roomId: Uuid): Snapshot = room(roomId).let { room -> synchronized(room) { snapshotOf(room) } }

    /**
     * Compares the participants LiveKit reports in the room ([present]: identity -> participant sid) with those already told the
     * current snapshot and returns the ones still to tell (identity -> sid). A known identity with a new sid (reload, reconnect) counts
     * as new. The returned entries are NOT recorded: the caller confirms each successful send with [markTold], so a failed send is
     * retried on the next tick.
     */
    fun observePresence(
        roomId: Uuid,
        present: Map<String, String>,
    ): Map<String, String> =
        room(roomId).let { room ->
            synchronized(room) {
                room.lastSeen = room.lastSeen.filter { (identity, sid) -> present[identity] == sid }
                present.filter { (identity, sid) -> room.lastSeen[identity] != sid }
            }
        }

    /** Records [told] (identity -> sid) as informed -- only if the timer did not change since the snapshot with [seq] was built. */
    fun markTold(
        roomId: Uuid,
        seq: Long,
        told: Map<String, String>,
    ) {
        room(roomId).let { room ->
            synchronized(room) {
                if (room.seq == seq) room.lastSeen = room.lastSeen + told
            }
        }
    }

    /** Drops every in-memory trace of rooms that are not in [activeRoomIds] (ended rooms). A mutex that is currently held is kept. */
    fun pruneInactiveRooms(activeRoomIds: Set<Uuid>) {
        rooms.keys.removeIf { it !in activeRoomIds }
        roomLocks.entries.removeIf { (id, mutex) -> id !in activeRoomIds && !mutex.isLocked }
    }

    /** Rooms this state has an entry for (the reconciler checks them against the database before pruning). */
    fun knownRoomIds(): Set<Uuid> = rooms.keys.toSet()

    /** Rooms whose timer is not off (running, paused or expired): the only rooms the reconciler has to look at. */
    fun nonOffRoomIds(): Set<Uuid> =
        rooms.entries
            .filter { (_, room) ->
                synchronized(room) { room.phase != Phase.OFF }
            }.map { it.key }
            .toSet()

    private fun room(roomId: Uuid): Room = rooms.computeIfAbsent(roomId) { Room() }

    private inline fun <T> synchronizedRoom(
        roomId: Uuid,
        block: (Room) -> T,
    ): T = room(roomId).let { room -> synchronized(room) { block(room) } }

    private fun startRun(
        room: Room,
        remainingMs: Long,
        initialMs: Long,
    ) {
        room.phase = Phase.RUNNING
        room.endMark = timeSource.markNow() + remainingMs.milliseconds
        room.pausedRemainingMs = 0
        room.initialDurationMs = initialMs
    }

    private fun remainingOf(room: Room): Long =
        when (room.phase) {
            Phase.OFF -> 0L
            Phase.PAUSED -> room.pausedRemainingMs
            Phase.RUNNING ->
                room.endMark
                    ?.let { maxOf(0L, (-it.elapsedNow()).inWholeMilliseconds) }
                    ?: 0L
        }

    private fun changed(room: Room): Change.Changed {
        room.seq++
        // Everybody present has to be told the new state again by the reconciler (heals a failed broadcast).
        room.lastSeen = emptyMap()
        return Change.Changed(snapshotOf(room))
    }

    private fun snapshotOf(room: Room): Snapshot {
        val remaining = remainingOf(room)
        val status =
            when (room.phase) {
                Phase.OFF -> ConferenceTimerStatus.OFF
                Phase.PAUSED -> ConferenceTimerStatus.PAUSED
                Phase.RUNNING -> if (remaining > 0) ConferenceTimerStatus.RUNNING else ConferenceTimerStatus.EXPIRED
            }
        return Snapshot(
            epoch = epoch,
            seq = room.seq,
            status = status,
            initialDurationMs = if (room.phase == Phase.OFF) 0L else room.initialDurationMs,
            remainingMs = remaining,
            serverNowEpochMs = wallNowMillis(),
        )
    }
}

/** The closed set of server -> client timer payloads (`{"v":1,"e":"<epoch>","seq":N,"s":"R","d":<ms>,"rem":<ms>,"t":<epochMs>}`). */
internal object ConferenceTimerPackets {
    fun snapshot(snapshot: ConferenceTimerState.Snapshot): ByteArray {
        require(TIMER_EPOCH_REGEX.matches(snapshot.epoch)) { "not a timer epoch" }
        val status =
            when (snapshot.status) {
                ConferenceTimerStatus.RUNNING -> "R"
                ConferenceTimerStatus.PAUSED -> "P"
                ConferenceTimerStatus.EXPIRED -> "X"
                ConferenceTimerStatus.OFF -> "O"
            }
        val json =
            "{\"v\":1,\"e\":\"${snapshot.epoch}\",\"seq\":${snapshot.seq},\"s\":\"$status\"," +
                "\"d\":${snapshot.initialDurationMs},\"rem\":${snapshot.remainingMs},\"t\":${snapshot.serverNowEpochMs}}"
        return json.toByteArray(Charsets.UTF_8).also {
            check(it.size <= CONFERENCE_TIMER_MAX_PAYLOAD_BYTES)
        }
    }
}
