package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.ConferenceTimerDto

/**
 * V1.9.133 -- the display-only timer of a video conference. It shows a countdown to everybody in the room and enforces nothing. The
 * state lives in server memory only ([network.lapis.cloud.server.conference.ConferenceTimerState]): nothing is written to the database
 * and no audit entry is made; the timer is lost on a server restart (clients detect the changed epoch).
 *
 * A SEPARATE service from [IConferenceService] on purpose (it has well over 1600 lines and the event-room tripwire only scans that file).
 *
 * Every refusal (unknown / ended / encounter / breakout room, not a moderator, not present in LiveKit) is the same single
 * `ForbiddenException`, so there is no existence oracle. A rate limit, an impossible state transition and the disabled feature are a
 * `ConflictException`; an out-of-range duration is a `BadRequestException`. Control is for moderators only (the shared
 * `ConferenceModeratorAuthority`: room creator, BOARD/ADMIN). Every successful change is broadcast to the room on the server-only timer
 * topic and returned as the new snapshot.
 */
@RpcService
interface IConferenceTimerService {
    /** Moderation: starts the timer ([durationSeconds] in 10..3600). Allowed when it is off or expired. */
    suspend fun startTimer(
        roomId: String,
        durationSeconds: Int,
    ): ConferenceTimerDto

    /** Moderation: pauses a running timer (a no-op when already paused). */
    suspend fun pauseTimer(roomId: String): ConferenceTimerDto

    /** Moderation: resumes a paused timer (a no-op when already running). */
    suspend fun resumeTimer(roomId: String): ConferenceTimerDto

    /** Moderation: stops and clears the timer (a no-op when it is off). */
    suspend fun stopTimer(roomId: String): ConferenceTimerDto

    /** Moderation: adds 30, 60 or 300 seconds; on an expired timer it starts a new one of that length. */
    suspend fun extendTimer(
        roomId: String,
        extensionSeconds: Int,
    ): ConferenceTimerDto

    /** The current timer (a fresh snapshot, also used by clients to measure the clock offset). Allowed for people present in the room and for moderators. */
    suspend fun timerState(roomId: String): ConferenceTimerDto
}
