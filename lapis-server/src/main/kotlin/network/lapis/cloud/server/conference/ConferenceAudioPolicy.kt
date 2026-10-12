package network.lapis.cloud.server.conference

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.events.EVENT_GUEST_IDENTITY_PREFIX
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CONFERENCE_MODERATION_KIND_LOCK
import network.lapis.cloud.shared.domain.CONFERENCE_MODERATION_KIND_MUTED
import network.lapis.cloud.shared.domain.CONFERENCE_MODERATION_KIND_MUTED_PEER
import network.lapis.cloud.shared.domain.CONFERENCE_MODERATION_KIND_UNLOCK
import network.lapis.cloud.shared.domain.CONFERENCE_MODERATION_KIND_UNMUTE_REQUEST
import network.lapis.cloud.shared.domain.CONFERENCE_MODERATION_MAX_PAYLOAD_BYTES
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/** LiveKit's protojson name of the microphone track source / publish source. */
internal const val LIVEKIT_MICROPHONE_SOURCE = "MICROPHONE"

/**
 * V1.9.108 -- the SINGLE answer to "may this person publish a microphone in this room right now". Every mint of a participant token for
 * an ordinary conference room ([network.lapis.cloud.server.rpc.ConferenceService.joinRoom] and
 * `ConferenceBreakoutService.rejoinMainRoomToken`) asks this function, so the two cannot drift apart: otherwise "enter a breakout room,
 * come back" would be a way around a lock.
 *
 * Encounter sessions are out of scope (they have their own, stricter audio model) and never reach this function.
 */
internal object ConferenceAudioPolicy {
    /**
     * `true` if the room is not locked, or the person is a moderator, or a moderator released this one person (see
     * [ConferenceAudioGrantState]).
     */
    fun microphoneAllowedFor(
        row: ResultRow,
        memberId: Uuid,
        isModerator: Boolean,
        grants: ConferenceAudioGrantState,
    ): Boolean =
        row[ConferenceRoomTable.participantsMayUnmute] ||
            isModerator ||
            grants.isGranted(roomId = row[ConferenceRoomTable.id], memberId = memberId)

    /** Which of [memberIds] hold the account role BOARD or ADMIN (= `CurrentMember.isPrivileged`). Read-only, needs an open transaction. */
    fun privilegedAmong(memberIds: Collection<Uuid>): Set<Uuid> {
        if (memberIds.isEmpty()) return emptySet()
        return AccountTable
            .selectAll()
            .where { AccountTable.memberId inList memberIds.toList() }
            .filter { it[AccountTable.role] == AccountRole.ADMIN || it[AccountTable.role] == AccountRole.BOARD }
            .map { it[AccountTable.memberId] }
            .toSet()
    }

    /** `true` if [identity] is the room creator or in [privileged]. A LiveKit identity that is no UUID (egress, recorder) is never a moderator. */
    fun isModeratorIdentity(
        identity: Uuid,
        creatorId: Uuid,
        privileged: Set<Uuid>,
    ): Boolean = identity == creatorId || identity in privileged
}

/** Parses a LiveKit participant identity as a member UUID; `null` for anything else (egress/recorder participants). */
internal fun liveKitIdentityAsMemberId(identity: String): Uuid? = runCatching { Uuid.parse(identity) }.getOrNull()

/**
 * `true` for the LiveKit identity of a person who joined an event WITHOUT an account (`evg-<uuid>`). Such a person is never a moderator and
 * never individually released, but is subject to every room-wide audio rule (lock, mute all, the reconciler) like any non-moderator.
 */
internal fun isEventGuestIdentity(identity: String): Boolean = identity.startsWith(EVENT_GUEST_IDENTITY_PREFIX)

/**
 * V1.9.108 -- the people a moderator released individually in a LOCKED room (the "Bitten" action of a locked room, see
 * `IConferenceModerationService.requestUnmute`). **In memory on purpose**: it is bounded (at most [maxPerRoom] per room), dropped when
 * the lock changes, the person leaves or the room ends, and lost on a server restart -- an old release must never silently outlive the
 * situation it was meant for. A process-wide singleton wired in `Application.module` (the services are built per RPC call).
 */
class ConferenceAudioGrantState(
    private val maxPerRoom: Int = DEFAULT_MAX_GRANTS_PER_ROOM,
) {
    private val grants = ConcurrentHashMap<Uuid, Set<Uuid>>()
    private val roomLocks = ConcurrentHashMap<Uuid, Mutex>()
    private val pendingUnlocks = ConcurrentHashMap.newKeySet<Uuid>()

    /**
     * Serialises everything that changes the LiveKit-side audio policy of ONE room: `setRoomAudioPolicy` (commit + application) and the
     * reconciler's per-room pass. Without it the reconciler could act on a lock it read before a moderator unlocked the room and take the
     * microphone away again. NOT reentrant -- [block] must not call [withRoomLock] for the same room.
     */
    suspend fun <T> withRoomLock(
        roomId: Uuid,
        block: suspend () -> T,
    ): T = roomLocks.computeIfAbsent(roomId) { Mutex() }.withLock { block() }

    /** Marks a room whose UNLOCK did not reach everybody in LiveKit; the reconciler hands the microphone back and clears the mark. */
    fun markUnlockPending(roomId: Uuid) {
        pendingUnlocks += roomId
    }

    fun clearUnlockPending(roomId: Uuid) {
        pendingUnlocks -= roomId
    }

    fun isUnlockPending(roomId: Uuid): Boolean = roomId in pendingUnlocks

    /**
     * Drops every in-memory trace of rooms that are no longer active: the unlock-pending mark, the releases and the per-room mutex.
     * [activeRoomIds] is the set of rooms that have not ended. A mutex that is currently held is kept (it is dropped by a later pass).
     */
    fun pruneInactiveRooms(activeRoomIds: Set<Uuid>) {
        pendingUnlocks.removeIf { it !in activeRoomIds }
        grants.keys.removeIf { it !in activeRoomIds }
        roomLocks.entries.removeIf { (id, mutex) -> id !in activeRoomIds && !mutex.isLocked }
    }

    /** Whether a per-room mutex is still kept for [roomId] (observability for tests). */
    fun hasRoomLock(roomId: Uuid): Boolean = roomLocks.containsKey(roomId)

    fun pendingUnlockRooms(): Set<Uuid> = pendingUnlocks.toSet()

    /** Releases the microphone of [memberId] in [roomId]. `false` if the per-room cap is reached (the release is then NOT recorded). */
    fun grant(
        roomId: Uuid,
        memberId: Uuid,
    ): Boolean {
        var granted = true
        grants.compute(roomId) { _, existing ->
            val current = existing.orEmpty()
            if (memberId in current) {
                current
            } else if (current.size >= maxPerRoom) {
                granted = false
                current
            } else {
                current + memberId
            }
        }
        return granted
    }

    fun isGranted(
        roomId: Uuid,
        memberId: Uuid,
    ): Boolean = grants[roomId]?.contains(memberId) == true

    fun revoke(
        roomId: Uuid,
        memberId: Uuid,
    ) {
        grants.computeIfPresent(roomId) { _, existing -> (existing - memberId).ifEmpty { null } }
    }

    /** Drops every release of the room (lock changed, room ended). */
    fun clear(roomId: Uuid) {
        grants.remove(roomId)
    }

    companion object {
        const val DEFAULT_MAX_GRANTS_PER_ROOM = 100
    }
}

/** The closed set of server -> client moderation payloads. Every one is far below [CONFERENCE_MODERATION_MAX_PAYLOAD_BYTES]. */
internal object ConferenceModerationPackets {
    fun muted(): ByteArray = build(kind = CONFERENCE_MODERATION_KIND_MUTED)

    fun unmuteRequest(): ByteArray = build(kind = CONFERENCE_MODERATION_KIND_UNMUTE_REQUEST)

    fun lock(): ByteArray = build(kind = CONFERENCE_MODERATION_KIND_LOCK)

    fun unlock(): ByteArray = build(kind = CONFERENCE_MODERATION_KIND_UNLOCK)

    /** Sent to the moderators only -- it names WHO was muted, which everybody else does not learn. */
    fun mutedPeer(memberId: Uuid): ByteArray = build(kind = CONFERENCE_MODERATION_KIND_MUTED_PEER, memberId = memberId)

    private fun build(
        kind: String,
        memberId: Uuid? = null,
    ): ByteArray {
        val json =
            if (memberId == null) {
                """{"v":1,"k":"$kind"}"""
            } else {
                """{"v":1,"k":"$kind","id":"$memberId"}"""
            }
        return json.toByteArray(Charsets.UTF_8).also { check(it.size <= CONFERENCE_MODERATION_MAX_PAYLOAD_BYTES) }
    }
}
