package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.RoomPollStartInput
import network.lapis.cloud.shared.domain.RoomPollStateDto
import network.lapis.cloud.shared.domain.RoomPollVersionDto

/**
 * V1.9.304 -- non-binding polls inside a video conference ("room polls"). A SEPARATE service from [IConferenceService] on purpose (that one
 * has well over 1600 lines and the event-room tripwire only scans it). New methods are always appended at the END.
 *
 * Every refusal (unknown / ended / encounter / event room, not present, wrong status, not leadership) is the same single
 * `ForbiddenException`, so there is no existence oracle. A second open poll, an already given answer, a rate limit and the disabled feature
 * are a `ConflictException`; a malformed input is a `BadRequestException`. Starting and ending is for the room's moderators
 * (`ConferenceModeratorAuthority`); everybody present with a status of `MemberStatusSets.ROOM_POLL_RESPONDER` answers once.
 */
@RpcService
interface IConferenceRoomPollService {
    /** Moderation: starts a poll. One open poll per room at most. */
    suspend fun startRoomPoll(input: RoomPollStartInput): RoomPollStateDto

    /** Moderation: ends the poll; ending an already ended poll is a no-op that returns the current state. */
    suspend fun closeRoomPoll(
        roomId: String,
        pollId: String,
    ): RoomPollStateDto

    /** Answers once, not changeable. */
    suspend fun answerRoomPoll(
        roomId: String,
        pollId: String,
        optionId: String,
    ): RoomPollStateDto

    /** The current state for the caller; for a poll that ended, its result stays visible for 12 hours. */
    suspend fun getRoomPollState(roomId: String): RoomPollStateDto

    /** A cheap change check: `changed = false` when [knownVersion] is still current. */
    suspend fun getRoomPollVersion(
        roomId: String,
        knownVersion: String?,
    ): RoomPollVersionDto
}
