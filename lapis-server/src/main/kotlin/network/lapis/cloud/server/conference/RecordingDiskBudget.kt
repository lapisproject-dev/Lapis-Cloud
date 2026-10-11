package network.lapis.cloud.server.conference

import java.io.File
import kotlin.math.max

/**
 * V1.9.109 -- the free-space rule of a recompute ("Neu berechnen"), PURE apart from the injected [diskSpace] probe.
 *
 * A recompute needs room for: the remuxed copies of the raw files (about the raw size), the per-segment MPEG-TS files and the PCM audio
 * of all segments (48 kHz, 16 bit, stereo: 192 000 bytes per second -- the dominant term for a long meeting), the finished MP4 and, in
 * the document store, one more complete copy (the previous version stays in the history). The check runs twice, in the RPC (a quick
 * refusal) and in the poller right before the work starts (the situation may have changed while the request waited).
 *
 * - work volume (the raw directory): `max(1.5 x raw + 1 GiB, raw + pcm + 2 x file + 1 GiB)`
 * - document volume: `file + 512 MiB`
 *
 * where `file` is the size of the current composed file (the raw size if it is unknown).
 */
object RecordingDiskBudget {
    private const val GIB = 1L shl 30
    private const val MIB = 1L shl 20
    private const val PCM_BYTES_PER_SECOND = 48_000L * 2 * 2

    fun requiredWorkBytes(
        rawBytes: Long,
        durationSeconds: Long,
        currentFileBytes: Long?,
    ): Long {
        val file = currentFileBytes ?: rawBytes
        val pcm = durationSeconds.coerceAtLeast(0L) * PCM_BYTES_PER_SECOND
        return max(rawBytes + rawBytes / 2 + GIB, rawBytes + pcm + 2 * file + GIB)
    }

    fun requiredDocumentBytes(
        rawBytes: Long,
        currentFileBytes: Long?,
    ): Long = (currentFileBytes ?: rawBytes) + 512 * MIB

    /** `true` iff both volumes have enough free space; a [documentRoot] of `null` skips the document check. */
    fun hasRoom(
        workRoot: File,
        documentRoot: File?,
        rawBytes: Long,
        durationSeconds: Long,
        currentFileBytes: Long?,
        diskSpace: (File) -> Long = { it.usableSpace },
    ): Boolean {
        if (diskSpace(workRoot) <
            requiredWorkBytes(rawBytes = rawBytes, durationSeconds = durationSeconds, currentFileBytes = currentFileBytes)
        ) {
            return false
        }
        if (documentRoot != null &&
            diskSpace(documentRoot) < requiredDocumentBytes(rawBytes = rawBytes, currentFileBytes = currentFileBytes)
        ) {
            return false
        }
        return true
    }
}
