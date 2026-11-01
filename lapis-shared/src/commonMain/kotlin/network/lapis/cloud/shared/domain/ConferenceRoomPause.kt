package network.lapis.cloud.shared.domain

import kotlinx.serialization.Serializable

// V1.9.254 -- pause mode of the video conference. The state lives on the server (room.capture_paused_at, recording.paused_at, stream pause
// reason ROOM_PAUSE); the scope is DERIVED, never stored. Everybody learns it from the DTOs they already read (ConferenceRecordingDto.paused,
// ConferenceStreamDto.pauseReason) -- there is deliberately no extra read RPC.

/** What a room pause stops: only the recording, only the live stream, or both. */
@Serializable
enum class RoomPauseScope { RECORDING, STREAM, BOTH }

/**
 * The scope derived from the two facts, `null` when nothing is paused. Pure so the server (result DTO) and the client (banner, badge)
 * cannot disagree.
 */
fun roomPauseScopeOf(
    recordingPaused: Boolean,
    streamPaused: Boolean,
): RoomPauseScope? =
    when {
        recordingPaused && streamPaused -> RoomPauseScope.BOTH
        recordingPaused -> RoomPauseScope.RECORDING
        streamPaused -> RoomPauseScope.STREAM
        else -> null
    }

/**
 * The result of `pauseRoomCapture` / `resumeRoomCapture`. [roomPaused] is `false` after a resume. [streamShowsImage] is always `false` in
 * V1.9.254 (the pause image arrives in a later step); it is part of the DTO so the banner text does not change shape then.
 */
@Serializable
data class ConferenceRoomPauseDto(
    val roomPaused: Boolean,
    val recordingPaused: Boolean,
    val streamPaused: Boolean,
    val streamShowsImage: Boolean = false,
)
