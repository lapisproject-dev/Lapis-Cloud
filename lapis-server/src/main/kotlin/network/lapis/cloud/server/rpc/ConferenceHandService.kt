package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.conference.ConferenceHandQueueState
import network.lapis.cloud.server.conference.ConferenceHandsPackets
import network.lapis.cloud.server.conference.HAND_IDENTITY_REGEX
import network.lapis.cloud.server.conference.HandQueueFullException
import network.lapis.cloud.server.conference.LiveKitAdminClient
import network.lapis.cloud.server.conference.LiveKitAdminException
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.encounter.ConferenceModeratorAuthority
import network.lapis.cloud.server.encounter.EncounterRoomGuard
import network.lapis.cloud.server.events.EventConferenceGuard
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.CONFERENCE_HANDS_TOPIC
import network.lapis.cloud.shared.domain.ConferenceHandQueueDto
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IConferenceHandService
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

// Privacy rule of this file: the logs name counts and exception classes only -- never a member id, identity, display name or room id.
private val logger = KotlinLogging.logger {}

/**
 * The server-side-only reason of EVERY refused hand call (Kilua transmits only the exception TYPE, so a client cannot tell "unknown room"
 * from "ended" from "not in the room" from "no authority" -- there is no existence oracle).
 */
internal const val HAND_DENIED = "Raised-hand action not possible"

/**
 * V1.9.127 -- see [IConferenceHandService]. Phases of every call (LiveKit is never called inside a `transaction {}`, because Exposed
 * repeats the whole block on a `SQLException`):
 *
 *  1. a `transaction {}` that authorizes (room active, no encounter session, eligible membership, the event-room guard);
 *  2. for raising a hand (and reading the queue as a non-moderator): the caller must currently be IN the LiveKit room -- the same truth
 *     the moderation uses -- so a person who is not (or no longer) present can never hold a place in the queue;
 *  3. mutate + build the snapshot under the per-room lock of [ConferenceHandQueueState]; the broadcast goes out AFTER the lock, best
 *     effort (the RPC answer carries the snapshot, clients drop an older `seq`).
 *
 * No audit entry on purpose, not even when a moderator lowers a hand: the list is volatile and an audit would tie people to speaking requests.
 *
 * Account-less event guests (`evg-`) have no session and therefore no way into this RPC; they are served by the read-only snapshot the
 * reconciler pushes to them (`ConferenceHandQueueReconciler`). The state and the rate limiters are process-wide singletons wired in
 * `Application.module` -- NO defaults on purpose (Kilua builds this service per RPC call).
 */
class ConferenceHandService(
    private val call: ApplicationCall,
    private val liveKitAdminClient: LiveKitAdminClient,
    private val config: ConferenceConfig,
    private val state: ConferenceHandQueueState,
    private val ownHandRateLimiter: FederationInboxRateLimiter,
    private val readRateLimiter: FederationInboxRateLimiter,
    private val lowerOneRateLimiter: FederationInboxRateLimiter,
    private val lowerAllRateLimiter: FederationInboxRateLimiter,
) : IConferenceHandService {
    private data class RoomContext(
        val roomId: Uuid,
        val livekitRoomName: String,
        val isModerator: Boolean,
    )

    override suspend fun raiseHand(roomId: String): ConferenceHandQueueDto {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        val room = transaction { authorize(roomId = roomId, current = current) }
        requireWithinRate(limiter = ownHandRateLimiter, key = "member:${current.memberId}:room:${room.roomId}")
        requirePresent(room = room, identity = current.memberId.toString())
        return mutate(room = room) {
            try {
                state.raise(roomId = room.roomId, identity = current.memberId.toString())
            } catch (e: HandQueueFullException) {
                throw ConflictException("Too many raised hands")
            }
        }
    }

    override suspend fun lowerHand(roomId: String): ConferenceHandQueueDto {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        val room = transaction { authorize(roomId = roomId, current = current) }
        requireWithinRate(limiter = ownHandRateLimiter, key = "member:${current.memberId}:room:${room.roomId}")
        // No presence check: lowering one's OWN hand grants nothing, and a person must always be able to take it back.
        return mutate(room = room) { state.lower(roomId = room.roomId, identity = current.memberId.toString()) }
    }

    override suspend fun handQueue(roomId: String): ConferenceHandQueueDto {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        val room = transaction { authorize(roomId = roomId, current = current) }
        requireWithinRate(limiter = readRateLimiter, key = "member:${current.memberId}")
        if (!room.isModerator) requirePresent(room = room, identity = current.memberId.toString())
        return state.snapshot(room.roomId).toDto()
    }

    override suspend fun lowerHandOf(
        roomId: String,
        identity: String,
    ): ConferenceHandQueueDto {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        val room = transaction { authorize(roomId = roomId, current = current) }
        if (!room.isModerator || !HAND_IDENTITY_REGEX.matches(identity)) throw ForbiddenException(HAND_DENIED)
        requireWithinRate(limiter = lowerOneRateLimiter, key = "room:${room.roomId}")
        // A target that is not in the queue is a silent no-op: the snapshot is visible to everybody in the room anyway.
        return mutate(room = room) { state.lower(roomId = room.roomId, identity = identity) }
    }

    override suspend fun lowerAllHands(roomId: String): ConferenceHandQueueDto {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        val room = transaction { authorize(roomId = roomId, current = current) }
        if (!room.isModerator) throw ForbiddenException(HAND_DENIED)
        requireWithinRate(limiter = lowerAllRateLimiter, key = "room:${room.roomId}")
        return mutate(room = room) { state.lowerAll(room.roomId) }
    }

    // -- Helpers ------------------------------------------------------------------------------------------------------------------------

    /** Mutates and snapshots under the room lock, broadcasts after it (best effort) and answers with the snapshot. */
    private suspend fun mutate(
        room: RoomContext,
        action: () -> ConferenceHandQueueState.Change,
    ): ConferenceHandQueueDto {
        val (change, snapshot) =
            state.withRoomLock(roomId = room.roomId) {
                val change = action()
                change to state.snapshot(room.roomId)
            }
        if (change is ConferenceHandQueueState.Change.Changed) {
            try {
                liveKitAdminClient.sendData(
                    room = room.livekitRoomName,
                    topic = CONFERENCE_HANDS_TOPIC,
                    payload = ConferenceHandsPackets.snapshot(snapshot),
                )
            } catch (e: LiveKitAdminException) {
                // The answer below carries the snapshot and the clients' periodic read heals the room.
                logger.warn { "conference hands: the queue broadcast failed" }
            }
        }
        return snapshot.toDto()
    }

    /** The room is active, an ordinary or event conference (no encounter session), and the caller may be in it -- else the ONE uniform refusal. */
    private fun authorize(
        roomId: String,
        current: CurrentMember,
    ): RoomContext {
        val id = runCatching { Uuid.parse(roomId) }.getOrElse { throw ForbiddenException(HAND_DENIED) }
        val row =
            ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq id }.singleOrNull()
                ?: throw ForbiddenException(HAND_DENIED)
        if (EncounterRoomGuard.isEncounterRoom(row) || row[ConferenceRoomTable.endedAt] != null) throw ForbiddenException(HAND_DENIED)
        requireConferenceEligibleMembership(memberId = current.memberId)
        // An event room is for its moderators and for people who currently sit in it (which only `joinEventOnline` creates).
        EventConferenceGuard.requireParticipantOrModerator(row = row, current = current)
        return RoomContext(
            roomId = id,
            livekitRoomName = row[ConferenceRoomTable.livekitRoomName],
            isModerator = ConferenceModeratorAuthority.isModerator(row = row, current = current),
        )
    }

    private suspend fun requirePresent(
        room: RoomContext,
        identity: String,
    ) {
        val present =
            try {
                liveKitAdminClient.listParticipants(room.livekitRoomName).any { it.identity == identity }
            } catch (e: LiveKitAdminException) {
                throw ConflictException("LiveKit request failed")
            }
        if (!present) throw ForbiddenException(HAND_DENIED)
    }

    private fun ConferenceHandQueueState.Snapshot.toDto() = ConferenceHandQueueDto(epoch = epoch, seq = seq, identities = identities)

    private fun requireConferenceEnabled() {
        if (!config.enabled) {
            throw ConflictException("Videokonferenzen is not configured on this server (LAPIS_LIVEKIT_URL/_API_KEY/_API_SECRET unset)")
        }
    }

    private fun requireWithinRate(
        limiter: FederationInboxRateLimiter,
        key: String,
    ) {
        if (!limiter.checkAndRecord(key)) throw ConflictException("Too many requests -- try again later")
    }
}
