package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.ConferenceBroadcastView
import network.lapis.cloud.shared.domain.ConferenceRoomDto
import network.lapis.cloud.shared.domain.ConferenceRoomPauseDto
import network.lapis.cloud.shared.domain.GuestScreenShareResultDto
import network.lapis.cloud.shared.domain.GuestScreenShareStateDto

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

    /**
     * V1.9.120 -- switches the join/leave tones of the room on or off for everybody (client-side tones; each person can additionally switch
     * them off for themselves). Running clients learn the change at their next join or reload; the moderator sees it at once. Same authority
     * and the same single `ForbiddenException` for every refusal as [setRoomAudioPolicy]; no LiveKit call.
     */
    suspend fun setRoomJoinLeaveSounds(
        roomId: String,
        enabled: Boolean,
    ): ConferenceRoomDto

    /**
     * V1.9.127 -- switches the reactions of the room on or off for everybody. Reactions are peer packets between the clients, so the switch
     * works through the clients only (it does NOT touch the LiveKit `can_publish_data` permission, because the chat travels through it as
     * well); running clients follow at once through a `reactions_on` / `reactions_off` moderation packet. It does not affect the raised
     * hand. Same authority and the same single `ForbiddenException` for every refusal as [setRoomAudioPolicy].
     */
    suspend fun setRoomReactions(
        roomId: String,
        enabled: Boolean,
    ): ConferenceRoomDto

    /**
     * V1.9.254 -- pause mode: pauses what is running in the room (the recording, the live stream, or both). The recording stops writing at
     * once (the egresses are stopped; the pause is cut out of the finished video), the stream is stopped and resumed later. People keep
     * talking. Same authority and the same single `ForbiddenException` for every refusal as [setRoomAudioPolicy]. Idempotent while paused.
     * Throws [ConferenceRoomPauseNothingToPauseException] when nothing runs and [ConferenceRoomPauseLimitException] at the pause limit.
     */
    suspend fun pauseRoomCapture(roomId: String): ConferenceRoomPauseDto

    /** V1.9.254 -- ends the pause: the recording continues in the same file, the stream starts again. Idempotent when the room is not paused. */
    suspend fun resumeRoomCapture(roomId: String): ConferenceRoomPauseDto

    /**
     * V1.9.282 -- allows ([allowed] `true`) or withdraws (`false`) the screen share of ONE account-less event guest ([guestIdentity], an
     * `evg-...` LiveKit identity) in an EVENT room. At most one guest per room may share. Withdrawing stops the share (permission, mute,
     * and removal of the guest if the share does not stop). Same single `ForbiddenException` for every refusal (no authority, unknown/ended
     * room, not an event room, not a guest identity) -- no oracle; the distinguishable results are in the returned outcome.
     */
    suspend fun setGuestScreenShare(
        roomId: String,
        guestIdentity: String,
        allowed: Boolean,
    ): GuestScreenShareResultDto

    /** V1.9.282 -- who may share right now and whether that guest does, so a second moderator or a freshly opened panel sees the server state. */
    suspend fun guestScreenShareState(roomId: String): GuestScreenShareStateDto
}
