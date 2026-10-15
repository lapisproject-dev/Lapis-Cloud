package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.conference.ConferenceTimerPackets
import network.lapis.cloud.server.conference.ConferenceTimerState
import network.lapis.cloud.server.conference.LiveKitAdminClient
import network.lapis.cloud.server.conference.LiveKitAdminException
import network.lapis.cloud.server.conference.TimerLimitException
import network.lapis.cloud.server.conference.TimerTransitionException
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.encounter.ConferenceModeratorAuthority
import network.lapis.cloud.server.encounter.EncounterRoomGuard
import network.lapis.cloud.server.events.EventConferenceGuard
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.CONFERENCE_TIMER_EXTENSIONS_SECONDS
import network.lapis.cloud.shared.domain.CONFERENCE_TIMER_MAX_SECONDS
import network.lapis.cloud.shared.domain.CONFERENCE_TIMER_MIN_SECONDS
import network.lapis.cloud.shared.domain.CONFERENCE_TIMER_TOPIC
import network.lapis.cloud.shared.domain.ConferenceTimerDto
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IConferenceTimerService
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

// Privacy rule of this file: the logs name counts and exception classes only -- never a member id, identity, display name or room id.
private val logger = KotlinLogging.logger {}

/**
 * The server-side-only reason of EVERY refused timer call (Kilua transmits only the exception TYPE, so a client cannot tell "unknown
 * room" from "ended" from "breakout" from "no authority" -- there is no existence oracle).
 */
internal const val TIMER_DENIED = "Timer action not possible"

/**
 * V1.9.133 -- see [IConferenceTimerService]. Phases of every call (LiveKit is never called inside a `transaction {}`, because Exposed
 * repeats the whole block on a `SQLException`):
 *
 *  1. a `transaction {}` that authorizes (room active, no encounter session, eligible membership, the event-room guard);
 *  2. control calls: the moderator check comes BEFORE the rate limit and before any mutation (no oracle through the limiter);
 *  3. mutate + build the snapshot under the per-room lock of [ConferenceTimerState]; the broadcast goes out AFTER the lock, best effort
 *     (the RPC answer carries the snapshot, clients drop an older `seq`).
 *
 * No audit entry and no persistence on purpose. Account-less event guests (`evg-`) have no session; they are served by the snapshot the
 * reconciler pushes to them (`ConferenceTimerReconciler`). The state and the rate limiters are process-wide singletons wired in
 * `Application.module` -- NO defaults on purpose (Kilua builds this service per RPC call).
 */
class ConferenceTimerService(
    private val call: ApplicationCall,
    private val liveKitAdminClient: LiveKitAdminClient,
    private val config: ConferenceConfig,
    private val state: ConferenceTimerState,
    private val controlRateLimiter: FederationInboxRateLimiter,
    private val readRateLimiter: FederationInboxRateLimiter,
) : IConferenceTimerService {
    private data class RoomContext(
        val roomId: Uuid,
        val livekitRoomName: String,
        val isModerator: Boolean,
    )

    override suspend fun startTimer(
        roomId: String,
        durationSeconds: Int,
    ): ConferenceTimerDto {
        val room = moderatorRoom(roomId)
        if (durationSeconds !in
            CONFERENCE_TIMER_MIN_SECONDS..CONFERENCE_TIMER_MAX_SECONDS
        ) {
            throw BadRequestException("Timer duration out of range")
        }
        requireWithinRate(limiter = controlRateLimiter, key = "room:${room.roomId}")
        return mutate(room = room) { state.start(roomId = room.roomId, durationMs = durationSeconds * MILLIS_PER_SECOND) }
    }

    override suspend fun pauseTimer(roomId: String): ConferenceTimerDto {
        val room = moderatorRoom(roomId)
        requireWithinRate(limiter = controlRateLimiter, key = "room:${room.roomId}")
        return mutate(room = room) { state.pause(room.roomId) }
    }

    override suspend fun resumeTimer(roomId: String): ConferenceTimerDto {
        val room = moderatorRoom(roomId)
        requireWithinRate(limiter = controlRateLimiter, key = "room:${room.roomId}")
        return mutate(room = room) { state.resume(room.roomId) }
    }

    override suspend fun stopTimer(roomId: String): ConferenceTimerDto {
        val room = moderatorRoom(roomId)
        requireWithinRate(limiter = controlRateLimiter, key = "room:${room.roomId}")
        return mutate(room = room) { state.stop(room.roomId) }
    }

    override suspend fun extendTimer(
        roomId: String,
        extensionSeconds: Int,
    ): ConferenceTimerDto {
        val room = moderatorRoom(roomId)
        if (extensionSeconds !in CONFERENCE_TIMER_EXTENSIONS_SECONDS) throw BadRequestException("Timer extension not allowed")
        requireWithinRate(limiter = controlRateLimiter, key = "room:${room.roomId}")
        return mutate(room = room) { state.extend(roomId = room.roomId, extensionMs = extensionSeconds * MILLIS_PER_SECOND) }
    }

    override suspend fun timerState(roomId: String): ConferenceTimerDto {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        val room = transaction { authorize(roomId = roomId, current = current) }
        requireWithinRate(limiter = readRateLimiter, key = "member:${current.memberId}")
        if (!room.isModerator) requirePresent(room = room, identity = current.memberId.toString())
        // A fresh snapshot on purpose: `serverNowEpochMs` must be current for the client's clock-offset measurement.
        return state.snapshot(room.roomId).toDto()
    }

    // -- Helpers ------------------------------------------------------------------------------------------------------------------------

    /** Common start of every control call: member, feature switch, authorization, and the moderator check (before rate limit / mutation). */
    private fun moderatorRoom(roomId: String): RoomContext {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        val room = transaction { authorize(roomId = roomId, current = current) }
        if (!room.isModerator) throw ForbiddenException(TIMER_DENIED)
        return room
    }

    /** Mutates and snapshots under the room lock, broadcasts after it (best effort) and answers with the snapshot. */
    private suspend fun mutate(
        room: RoomContext,
        action: () -> ConferenceTimerState.Change,
    ): ConferenceTimerDto {
        val (change, snapshot) =
            state.withRoomLock(roomId = room.roomId) {
                val change =
                    try {
                        action()
                    } catch (e: TimerTransitionException) {
                        throw ConflictException("Timer action not possible in this state")
                    } catch (e: TimerLimitException) {
                        throw BadRequestException("Timer duration out of range")
                    }
                change to state.snapshot(room.roomId)
            }
        if (change is ConferenceTimerState.Change.Changed) {
            try {
                liveKitAdminClient.sendData(
                    room = room.livekitRoomName,
                    topic = CONFERENCE_TIMER_TOPIC,
                    payload = ConferenceTimerPackets.snapshot(snapshot),
                )
            } catch (e: LiveKitAdminException) {
                // The answer below carries the snapshot; the reconciler tells everybody present again on its next tick (every change clears its memory).
                logger.warn { "conference timer: the broadcast failed" }
            }
        }
        return snapshot.toDto()
    }

    /** The room is active, an ordinary or event conference (no encounter session), and the caller may be in it -- else the ONE uniform refusal. */
    private fun authorize(
        roomId: String,
        current: CurrentMember,
    ): RoomContext {
        val id = runCatching { Uuid.parse(roomId) }.getOrElse { throw ForbiddenException(TIMER_DENIED) }
        val row =
            ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq id }.singleOrNull()
                ?: throw ForbiddenException(TIMER_DENIED)
        if (EncounterRoomGuard.isEncounterRoom(row) || row[ConferenceRoomTable.endedAt] != null) throw ForbiddenException(TIMER_DENIED)
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
        if (!present) throw ForbiddenException(TIMER_DENIED)
    }

    private fun ConferenceTimerState.Snapshot.toDto() =
        ConferenceTimerDto(
            epoch = epoch,
            seq = seq,
            status = status,
            initialDurationMs = initialDurationMs,
            remainingMs = remainingMs,
            serverNowEpochMs = serverNowEpochMs,
        )

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

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
    }
}
