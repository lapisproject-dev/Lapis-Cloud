package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.ConferenceHandQueueDto

/**
 * V1.9.127 -- the raised-hand queue ("Wortmeldeliste") of a video conference. The order lives in server memory only
 * ([network.lapis.cloud.server.conference.ConferenceHandQueueState]): nothing is written to the database, no audit entry is made (the list
 * is volatile and an audit would tie people to speaking requests), and the list is lost on a server restart (clients re-raise on a changed
 * epoch, see [ConferenceHandQueueDto]).
 *
 * A SEPARATE service from [IConferenceService] on purpose (it has well over 1600 lines and the event-room tripwire only scans that file).
 *
 * Every refusal of the own-hand methods (unknown / ended / encounter room, not a participant, not present in LiveKit) is the same single
 * `ForbiddenException`, so there is no existence oracle; a rate limit or the disabled feature is a `ConflictException`. Moderation
 * (`lowerHandOf`, `lowerAllHands`) uses the shared `ConferenceModeratorAuthority` (room creator, BOARD/ADMIN). Every successful change is
 * broadcast to the room on the server-only hands topic and returned as the new snapshot.
 */
@RpcService
interface IConferenceHandService {
    /** Raises the caller's hand (idempotent: the position is kept). A full queue is a `ConflictException`. */
    suspend fun raiseHand(roomId: String): ConferenceHandQueueDto

    /** Lowers the caller's own hand. Raising again later puts the caller at the end. */
    suspend fun lowerHand(roomId: String): ConferenceHandQueueDto

    /** The current queue. Allowed for people present in the room and for moderators. */
    suspend fun handQueue(roomId: String): ConferenceHandQueueDto

    /** Moderation: lowers the hand of [identity] (a member UUID or `evg-<uuid>`). A target that is not in the queue is a silent no-op. */
    suspend fun lowerHandOf(
        roomId: String,
        identity: String,
    ): ConferenceHandQueueDto

    /** Moderation: lowers every hand of the room. */
    suspend fun lowerAllHands(roomId: String): ConferenceHandQueueDto
}
