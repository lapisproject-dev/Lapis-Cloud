package network.lapis.cloud.server.conference

/**
 * V1.9.154 -- the one decision about the egress DTX drift of a per-person file: the egress writes the audio ~0.34 % too long (every
 * DTX gap is filled ~1.5 ms too long), while the picture runs on the wall clock. Returns the `atempo` ratio (media / wall, greater than
 * 1 = the audio is played faster) or `null` for "leave the file alone".
 *
 * The ratio is computed from the END POINTS inside the container, not from `audioDur / videoDur`: the video usually starts 1 to 2 s
 * after the audio, which is an offset and no drift. Assumption: both streams end together when the egress stops.
 */
object ParticipantAudioDrift {
    const val MIN_STREAM_SECONDS = 30.0
    const val MIN_RELATIVE_EXCESS = 0.0005
    const val MIN_ABSOLUTE_EXCESS_SECONDS = 0.25
    const val MAX_RELATIVE_EXCESS = 0.02

    fun tempoFor(
        audioMediaSeconds: Double?,
        audioStartSeconds: Double?,
        videoMediaSeconds: Double?,
        videoStartSeconds: Double?,
    ): Double? {
        if (audioMediaSeconds == null || audioStartSeconds == null || videoMediaSeconds == null || videoStartSeconds == null) return null
        if (!listOf(audioMediaSeconds, audioStartSeconds, videoMediaSeconds, videoStartSeconds).all { it.isFinite() }) return null
        val wallSpan = videoStartSeconds + videoMediaSeconds - audioStartSeconds
        if (audioMediaSeconds < MIN_STREAM_SECONDS || videoMediaSeconds < MIN_STREAM_SECONDS || wallSpan < MIN_STREAM_SECONDS) return null
        val excess = audioMediaSeconds - wallSpan
        if (excess < maxOf(MIN_ABSOLUTE_EXCESS_SECONDS, MIN_RELATIVE_EXCESS * wallSpan)) return null
        if (excess > MAX_RELATIVE_EXCESS * wallSpan) return null
        val ratio = audioMediaSeconds / wallSpan
        require(ratio > 1.0 && ratio <= 1.0 + MAX_RELATIVE_EXCESS + 1e-12) { "drift ratio out of range" }
        return ratio
    }
}
