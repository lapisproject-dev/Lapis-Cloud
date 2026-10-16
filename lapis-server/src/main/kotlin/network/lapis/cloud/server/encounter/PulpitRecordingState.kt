package network.lapis.cloud.server.encounter

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

/**
 * V1.9.143 -- the in-memory state of the pulpit recordings. **Nothing here is persisted** (`PulpitRecordingPersistenceTripwireTest` fails the
 * build if this file ever refers to the database): "who confirmed in this session" and "who is being recorded right now" would, as a table, be a
 * lasting trace of who stood at the pulpit in an Art. 9 context. The proof with a person attached lives outside Lapis Cloud; the audit log records
 * only that a recording started, whether the pulpit had confirmed, and the hashes of the two texts.
 *
 * It holds member UUIDs of PULPIT office holders (needed to address the egress and the point-to-point packets) -- never anybody of the
 * congregation. Keyed by the session's conference room id. At most [MAX_SESSIONS] sessions are tracked; a further one is REFUSED, never
 * evicts an existing one (an evicted session would keep recording without the server knowing).
 */
class PulpitRecordingState(
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
    /** One running egress file. [fileId] names the file on the egress volume; [startedAtWall] is the moment for the document's marker row. */
    data class Egress(
        val memberId: Uuid,
        val egressId: String,
        val fileId: Uuid,
        val startedAt: ComparableTimeMark,
        val startedAtWall: LocalDateTime,
    )

    /** An egress that was stopped (or ended) and whose file has still to be archived once LiveKit reports it terminal. */
    data class Finalizing(
        val spaceId: Uuid,
        val livekitRoomName: String,
        val egressId: String,
        val fileId: Uuid,
        val startedAtWall: LocalDateTime,
        val uploaderMemberId: Uuid,
        val stoppedAt: ComparableTimeMark,
        val attempts: Int = 0,
        /** The start was never confirmed by the audit (the re-check failed): the file is deleted, never archived. */
        val discard: Boolean = false,
    )

    /**
     * A recording that is switched on for one session of a room. It exists from the moment of the start until the last stop, also while no
     * pulpit person is currently connected (the recording is "armed" and records the pulpit again as soon as the person is back).
     */
    class Session(
        val spaceId: Uuid,
        val livekitRoomName: String,
        /** The person who started the recording; becomes the creator of the document. In memory only. */
        val actorMemberId: Uuid,
        /** The hashes written to the audit entry of the start. */
        val roomTextSha256: String,
        val pulpitTextSha256: String,
    ) {
        /** Running files by PULPIT member. */
        val egresses: MutableMap<Uuid, Egress> = LinkedHashMap()

        /** Participants (identity + LiveKit sid) who were told the current state. */
        val notified: MutableSet<String> = LinkedHashSet()

        /** Set when the per-room limit left a pulpit person unrecorded. */
        var maxReached: Boolean = false
    }

    /** A start waiting for the confirmation of the pulpit. Not attached to a [Session] (nothing is recording yet). */
    private data class Pending(
        val spaceId: Uuid,
        val livekitRoomName: String,
        val actorMemberId: Uuid,
        val actorRole: AccountRole,
        val until: ComparableTimeMark,
    )

    /** The person who requested a start that still waits for the pulpit, with the role the audit entry of the start records. */
    data class PendingActor(
        val memberId: Uuid,
        val role: AccountRole,
    )

    private val lock = Any()
    private val sessions = LinkedHashMap<Uuid, Session>()
    private val pending = HashMap<Uuid, Pending>()

    /** The PULPIT persons who confirmed in the current session of a room (dropped when a recording ends or the text changes, see [clearConfirmations]). */
    private val confirmations = HashMap<Uuid, MutableSet<Uuid>>()

    /** The SHA-256 of the REC_PULPIT text the confirmations of a room were given for; a different current text invalidates them. */
    private val confirmationTexts = HashMap<Uuid, String>()
    private val finalizing = ArrayList<Finalizing>()

    fun session(roomId: Uuid): Session? = synchronized(lock) { sessions[roomId] }

    /** The session of [roomId] or a new one; `null` if the table is full and [roomId] is not tracked yet. */
    fun getOrCreate(
        roomId: Uuid,
        create: () -> Session,
    ): Session? =
        synchronized(lock) {
            sessions[roomId] ?: if (sessions.size >= MAX_SESSIONS) null else create().also { sessions[roomId] = it }
        }

    fun remove(roomId: Uuid): Session? = synchronized(lock) { sessions.remove(roomId) }

    fun sessionIds(): Set<Uuid> = synchronized(lock) { sessions.keys.toSet() }

    /** `(roomId, session)` for every session whose [Session.spaceId] is [spaceId]. */
    fun sessionsOfSpace(spaceId: Uuid): List<Pair<Uuid, Session>> =
        synchronized(lock) { sessions.entries.filter { it.value.spaceId == spaceId }.map { it.key to it.value } }

    fun activeEgressCount(): Int = synchronized(lock) { sessions.values.sumOf { it.egresses.size } }

    /** Drops everything that belongs to a room that is not in [openRoomIds] (a session that ended). Sessions are NOT dropped here: the coordinator stops them first. */
    fun retainRooms(openRoomIds: Set<Uuid>) {
        synchronized(lock) {
            confirmations.keys.retainAll(openRoomIds)
            confirmationTexts.keys.retainAll(openRoomIds)
            pending.keys.retainAll(openRoomIds)
        }
    }

    // ── Confirmations ────────────────────────────────────────────────────

    fun confirmedSet(roomId: Uuid): Set<Uuid> = synchronized(lock) { confirmations[roomId]?.toSet() ?: emptySet() }

    fun hasConfirmations(roomId: Uuid): Boolean = synchronized(lock) { confirmations[roomId]?.isNotEmpty() == true }

    /**
     * `false` only if the table of tracked rooms is full (the confirmation is then refused, never evicting another room's). The confirmation is
     * bound to [pulpitTextSha256]: earlier confirmations given for another text are dropped first.
     */
    fun confirm(
        roomId: Uuid,
        memberId: Uuid,
        pulpitTextSha256: String,
    ): Boolean =
        synchronized(lock) {
            if (confirmations.size >= MAX_SESSIONS && roomId !in confirmations) return@synchronized false
            if (confirmationTexts[roomId] != pulpitTextSha256) confirmations.remove(roomId)
            confirmationTexts[roomId] = pulpitTextSha256
            confirmations.getOrPut(roomId) { LinkedHashSet() } += memberId
            true
        }

    /** Drops the confirmations of [roomId] if they were given for a text other than [currentPulpitTextSha256]. */
    fun invalidateConfirmationsIfTextChanged(
        roomId: Uuid,
        currentPulpitTextSha256: String,
    ) {
        synchronized(lock) {
            val stored = confirmationTexts[roomId] ?: return@synchronized
            if (stored != currentPulpitTextSha256) clearConfirmations(roomId)
        }
    }

    /** A recording ended: the next one asks the pulpit again (consent is per recording, not per session). */
    fun clearConfirmations(roomId: Uuid) {
        synchronized(lock) {
            confirmations.remove(roomId)
            confirmationTexts.remove(roomId)
        }
    }

    /** The withdrawal of one person's agreement. */
    fun withdraw(
        roomId: Uuid,
        memberId: Uuid,
    ) {
        synchronized(lock) {
            val set = confirmations[roomId] ?: return@synchronized
            set -= memberId
            if (set.isEmpty()) {
                confirmations.remove(roomId)
                confirmationTexts.remove(roomId)
            }
        }
    }

    // ── Open start request ───────────────────────────────────────────────

    fun setPending(
        roomId: Uuid,
        spaceId: Uuid,
        livekitRoomName: String,
        actorMemberId: Uuid,
        actorRole: AccountRole,
    ): Boolean =
        synchronized(lock) {
            if (pending.size >= MAX_SESSIONS && roomId !in pending) return@synchronized false
            pending[roomId] =
                Pending(
                    spaceId = spaceId,
                    livekitRoomName = livekitRoomName,
                    actorMemberId = actorMemberId,
                    actorRole = actorRole,
                    until =
                        timeSource.markNow() + PENDING_VALIDITY_MINUTES.minutes,
                )
            true
        }

    /** The actor (member and role) of a still valid start request of [roomId], or `null`. */
    fun pendingActorInfo(roomId: Uuid): PendingActor? =
        synchronized(lock) {
            val p = pending[roomId] ?: return@synchronized null
            if (p.until.hasPassedNow()) {
                pending.remove(roomId)
                null
            } else {
                PendingActor(memberId = p.actorMemberId, role = p.actorRole)
            }
        }

    /** The actor of a still valid start request of [roomId], or `null`. */
    fun pendingActor(roomId: Uuid): Uuid? = pendingActorInfo(roomId)?.memberId

    fun hasPending(roomId: Uuid): Boolean = pendingActor(roomId) != null

    fun clearPending(roomId: Uuid) {
        synchronized(lock) { pending.remove(roomId) }
    }

    // ── Files that still have to be archived ─────────────────────────────

    fun addFinalizing(entry: Finalizing): Boolean =
        synchronized(lock) {
            if (finalizing.size >= MAX_FINALIZING) return@synchronized false
            finalizing += entry
            true
        }

    fun finalizingSnapshot(): List<Finalizing> = synchronized(lock) { finalizing.toList() }

    fun replaceFinalizing(
        old: Finalizing,
        new: Finalizing?,
    ) {
        synchronized(lock) {
            finalizing.remove(old)
            if (new != null) finalizing += new
        }
    }

    fun nowMark(): ComparableTimeMark = timeSource.markNow()

    companion object {
        const val MAX_SESSIONS = 64
        const val MAX_FINALIZING = 256
        const val PENDING_VALIDITY_MINUTES = 2
    }
}
