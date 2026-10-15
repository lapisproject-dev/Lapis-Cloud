package network.lapis.cloud.server.encounter

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import network.lapis.cloud.shared.domain.ENCOUNTER_FLOOR_DEFAULT_SECONDS
import network.lapis.cloud.shared.domain.ENCOUNTER_FLOOR_MAX_TOTAL_SECONDS
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

/**
 * V1.9.138 -- the ONLY state of the temporary floor of an encounter session ("Rederecht"): who may speak right now and for how long.
 * **Memory only, never persisted** (a stored "who was given the floor when" would be a lasting record about a person in connection with a
 * church service, Art. 9 GDPR). Consequence, documented in `docs/architecture/encounter-space.adoc`: a server restart forgets the floor, and
 * the first poller run then disconnects whoever still publishes -- the floor is lost, never kept.
 *
 * The remaining time comes from a MONOTONIC time mark only (no wall clock, nothing stored). At most [MAX_SESSIONS] sessions are tracked; a
 * full table refuses a new grant ([GrantResult.Full]) and never evicts an existing one. One person per session at a time.
 *
 * A grant that is being taken away ([Grant.revoking]) is no longer valid ([isValid]) but still occupies the session until the revoke
 * succeeded, so a new grant cannot start while the old holder might still be able to publish.
 */
class EncounterFloorState(
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
    /** One running floor. [serial] tells two grants of the same person apart, so a late revoke can never remove a newer grant. */
    data class Grant(
        val serial: Long,
        val memberId: Uuid,
        val livekitRoomName: String,
        /** In memory only, never logged. */
        val displayName: String,
        val grantedAt: ComparableTimeMark,
        val totalSeconds: Int,
        val revoking: Boolean,
        /** Consecutive verifications in which the holder was not connected. */
        val absentChecks: Int,
        val lastVerifiedAt: ComparableTimeMark,
        val lastRevokeAttemptAt: ComparableTimeMark?,
    )

    sealed interface GrantResult {
        data class Granted(
            val grant: Grant,
        ) : GrantResult

        /** Somebody has the floor (or is just losing it). */
        data object Busy : GrantResult

        /** The table of tracked sessions is full. */
        data object Full : GrantResult
    }

    sealed interface ExtendResult {
        data class Extended(
            val grant: Grant,
        ) : ExtendResult

        /** No valid grant to extend. */
        data object NoGrant : ExtendResult

        /** The total would exceed [ENCOUNTER_FLOOR_MAX_TOTAL_SECONDS]. */
        data object LimitReached : ExtendResult
    }

    private val lock = Any()
    private val grants = HashMap<Uuid, Grant>()
    private val recentlyEnded = HashMap<Pair<Uuid, Uuid>, ComparableTimeMark>()
    private val mutexes = ConcurrentHashMap<Uuid, Mutex>()
    private var nextSerial = 1L

    /** One lock per session, NOT reentrant: never call [withSessionLock] again from inside [block]. */
    suspend fun <T> withSessionLock(
        roomId: Uuid,
        block: suspend () -> T,
    ): T = mutexes.computeIfAbsent(roomId) { Mutex() }.withLock { block() }

    fun current(roomId: Uuid): Grant? = synchronized(lock) { grants[roomId] }

    /** Whole seconds left, rounded up, never below 0. */
    fun remainingSeconds(grant: Grant): Int {
        val left = grant.totalSeconds.seconds - grant.grantedAt.elapsedNow()
        return ((left.inWholeMilliseconds + 999) / 1000).toInt().coerceIn(0, ENCOUNTER_FLOOR_MAX_TOTAL_SECONDS)
    }

    /** `true` iff [memberId] has a grant that is not being revoked and has time left. */
    fun isValid(
        roomId: Uuid,
        memberId: Uuid,
    ): Boolean =
        synchronized(lock) {
            val g = grants[roomId] ?: return@synchronized false
            g.memberId == memberId && !g.revoking && remainingSeconds(g) > 0
        }

    fun tryGrant(
        roomId: Uuid,
        livekitRoomName: String,
        memberId: Uuid,
        displayName: String,
    ): GrantResult =
        synchronized(lock) {
            if (grants.containsKey(roomId)) return@synchronized GrantResult.Busy
            if (grants.size >= MAX_SESSIONS) return@synchronized GrantResult.Full
            val now = timeSource.markNow()
            val grant =
                Grant(
                    serial = nextSerial++,
                    memberId = memberId,
                    livekitRoomName = livekitRoomName,
                    displayName = displayName,
                    grantedAt = now,
                    totalSeconds = ENCOUNTER_FLOOR_DEFAULT_SECONDS,
                    revoking = false,
                    absentChecks = 0,
                    lastVerifiedAt = now,
                    lastRevokeAttemptAt = null,
                )
            grants[roomId] = grant
            GrantResult.Granted(grant)
        }

    fun extend(
        roomId: Uuid,
        seconds: Int,
    ): ExtendResult =
        synchronized(lock) {
            val g = grants[roomId]
            if (g == null || g.revoking || remainingSeconds(g) <= 0) return@synchronized ExtendResult.NoGrant
            if (g.totalSeconds + seconds > ENCOUNTER_FLOOR_MAX_TOTAL_SECONDS) return@synchronized ExtendResult.LimitReached
            val updated = g.copy(totalSeconds = g.totalSeconds + seconds)
            grants[roomId] = updated
            ExtendResult.Extended(updated)
        }

    /** Marks the grant as being taken away (idempotent). `null` if there is none. */
    fun markRevoking(roomId: Uuid): Grant? =
        synchronized(lock) {
            val g = grants[roomId] ?: return@synchronized null
            val updated = if (g.revoking) g else g.copy(revoking = true)
            grants[roomId] = updated
            updated
        }

    fun noteRevokeAttempt(
        roomId: Uuid,
        serial: Long,
    ) {
        synchronized(lock) {
            val g = grants[roomId] ?: return
            if (g.serial == serial) grants[roomId] = g.copy(lastRevokeAttemptAt = timeSource.markNow())
        }
    }

    /** Records one verification: the holder was [present] or not. Returns the new count of consecutive absences. */
    fun recordPresence(
        roomId: Uuid,
        serial: Long,
        present: Boolean,
    ): Int =
        synchronized(lock) {
            val g = grants[roomId] ?: return@synchronized 0
            if (g.serial != serial) return@synchronized 0
            val updated = g.copy(absentChecks = if (present) 0 else g.absentChecks + 1, lastVerifiedAt = timeSource.markNow())
            grants[roomId] = updated
            updated.absentChecks
        }

    /** Removes the grant iff it is still the one with [serial]; remembers the holder for the poller's grace period. */
    fun remove(
        roomId: Uuid,
        serial: Long,
    ): Boolean =
        synchronized(lock) {
            val g = grants[roomId] ?: return@synchronized false
            if (g.serial != serial) return@synchronized false
            grants.remove(roomId)
            pruneEndedLocked()
            if (recentlyEnded.size < MAX_RECENTLY_ENDED) recentlyEnded[roomId to g.memberId] = timeSource.markNow()
            true
        }

    fun sessionsWithGrant(): Map<Uuid, Grant> = synchronized(lock) { grants.toMap() }

    /** `true` for [GRACE_SECONDS] after the floor of [memberId] ended: a muted microphone leftover is tolerated, nothing else. */
    fun recentlyEndedWithinGrace(
        roomId: Uuid,
        memberId: Uuid,
    ): Boolean =
        synchronized(lock) {
            pruneEndedLocked()
            recentlyEnded.containsKey(roomId to memberId)
        }

    fun clear(roomId: Uuid) {
        synchronized(lock) {
            grants.remove(roomId)
            recentlyEnded.keys.removeAll { it.first == roomId }
        }
        mutexes.remove(roomId)
    }

    /** Forgets every session that is not open any more. */
    fun retainOnly(openRoomIds: Set<Uuid>) {
        synchronized(lock) {
            grants.keys.retainAll(openRoomIds)
            recentlyEnded.keys.removeAll { it.first !in openRoomIds }
        }
        mutexes.keys.retainAll(openRoomIds)
    }

    internal fun size(): Int = synchronized(lock) { grants.size }

    private fun pruneEndedLocked() {
        recentlyEnded.values.removeAll { it.elapsedNow() > GRACE_SECONDS.seconds }
    }

    companion object {
        const val MAX_SESSIONS = 64

        /** How long after the end of a floor the poller tolerates a muted leftover microphone track. */
        const val GRACE_SECONDS = 90
        private const val MAX_RECENTLY_ENDED = 256
    }
}
