package network.lapis.cloud.server.conference

/**
 * V1.9.294: the video inputs of a single-image recording -- every screen share, plus every other input whose track row belongs to
 * [identity]. Audio is never filtered (the caller passes all audio inputs through). An input whose track is unknown is dropped.
 * With no match the list holds only the screen shares (possibly none): the composer then renders black for the stretch.
 */
internal fun filterForSingleParticipant(
    videoInputs: List<RecordingComposeVideoInput>,
    identityByTrackId: Map<String, String>,
    identity: String,
): List<RecordingComposeVideoInput> = videoInputs.filter { it.isScreenShare || identityByTrackId[it.trackId] == identity }
