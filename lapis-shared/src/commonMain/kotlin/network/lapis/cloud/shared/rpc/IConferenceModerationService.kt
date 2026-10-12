package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.ConferenceBroadcastView
import network.lapis.cloud.shared.domain.ConferenceRoomDto

/**
 * V1.9.108 -- moderator audio controls of a running video conference: mute one person, mute everybody, ask somebody to switch the
 * microphone on, lock the room's microphones. Authority is the shared `ConferenceModeratorAuthority` (room creator, or BOARD/ADMIN),
 * always re-derived from the database.
 *
 * **There is deliberately no method that switches somebody's microphone ON.** A moderator can only ask ([requestUnmute]); the person
 * switches on themselves. A tripwire test fails the build if a forced-unmute path appears.
 *
 * Every refusal of [muteParticipant]/[muteAll]/[requestUnmute]/[setRoomAudioPolicy]/[estimateMuteAll] (no authority, unknown/ended room,
 * encounter session, target not in the room, target is the creator or oneself, peer rule) is the SAME `ForbiddenException`: Kilua
 * transmits only the exception type, so the client cannot tell the cases apart and no existence oracle arises. Rate-limit and
 * "conference off" refusals keep their own `ConflictException`.
 *
 * Separate from [IConferenceService] (already over 1600 lines) like the breakout, whiteboard, recording and streaming services.
 */
@RpcService
interface IConferenceModerationService {
    /** Mutes the open microphone of [memberId] (nothing else: never camera, never screen share). No open microphone is a silent no-op. */
    suspend fun muteParticipant(
        roomId: String,
        memberId: String,
    )

    /** Mutes every open microphone except moderators' (creator, caller, BOARD/ADMIN). Returns the number of people actually muted. */
    suspend fun muteAll(roomId: String): Int

    /** How many people [muteAll] would mute right now -- for the confirmation dialog. Same gate, its own small budget. */
    suspend fun estimateMuteAll(roomId: String): Int

    /**
     * Asks [memberId] to switch the microphone on. In a locked room this additionally releases the microphone for that ONE person
     * (in-memory, dropped when the lock changes, the person leaves or the server restarts). Never switches anything on by itself.
     */
    suspend fun requestUnmute(
        roomId: String,
        memberId: String,
    )

    /**
     * Sets the room's audio policy: [muteOnJoin] (a join default for non-moderators) and [participantsMayUnmute] (`false` = lock). A change
     * of the lock applies to everybody present at once; switching the lock on also mutes open microphones of non-moderators.
     */
    suspend fun setRoomAudioPolicy(
        roomId: String,
        muteOnJoin: Boolean,
        participantsMayUnmute: Boolean,
    ): ConferenceRoomDto

    /**
     * V1.9.115 -- sets how a recording (and the start dialog of a stream) shows the room: [ConferenceBroadcastView.RASTER] or
     * [ConferenceBroadcastView.SPEAKER]. Applies from the NEXT recording / stream start; a running recording keeps the view it froze at
     * its start. Same authority and the same single `ForbiddenException` for every refusal as [setRoomAudioPolicy]; no LiveKit call.
     */
    suspend fun setRoomBroadcastView(
        roomId: String,
        view: ConferenceBroadcastView,
    ): ConferenceRoomDto
}
