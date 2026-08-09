package network.lapis.cloud.server.conference

import java.io.File
import java.io.IOException

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 2 "Aufzeichnung" -- the ONE way a LiveKit-reported
 * filename ([network.lapis.cloud.server.conference.LiveKitEgressFileInfo.filename], read back via
 * [network.lapis.cloud.server.db.generated.ConferenceRecordingTrackTable.fileName]) ever becomes a
 * [File] this server reads. Treat it as attacker-adjacent: it arrives from LiveKit's own
 * `file_results[0].filename`, itself only as trustworthy as the egress container's own bind mount
 * -- see `28-conference-recording.kuml.kts` file header "`raw_dir` is ALWAYS exactly the
 * recording's own UUID, never operator or LiveKit input" for why [recordingId] itself IS trusted
 * (server-generated, see [network.lapis.cloud.server.rpc.ConferenceRecordingService.startRecording])
 * while [reportedFilename] is not.
 *
 * **Defense, in order**:
 * 1. Only the BASENAME of [reportedFilename] is ever used -- any directory component (e.g. the
 *    container path `/out/{recordingId}/publisher__CAMERA__TR_xyz.mp4`
 *    [network.lapis.cloud.server.conference.LiveKitEgressClient.startTrackEgress]'s own `filepath`
 *    KDoc documents) is discarded outright via [File.getName].
 * 2. A basename that is blank, `.`, `..`, or still contains a path separator is rejected -- the
 *    last PATH SEGMENT can literally be `..` even after basename extraction (e.g.
 *    `File("a/../..").name == ".."`), which [File.getName] alone would not catch.
 * 3. The candidate is resolved strictly under `{hostRawRoot}/{recordingId}/` -- never the whole
 *    [hostRawRoot], so one recording can never (even accidentally) read another recording's raw
 *    files.
 * 4. [java.nio.file.Path.toRealPath] resolves symlinks AND requires the file to actually exist,
 *    returning `null` for either a missing file (the normal "egress has not written this file
 *    yet" case, [network.lapis.cloud.server.conference.RecordingPoller] simply treats that track as
 *    not-yet-available) or a symlink that escapes the recording's own raw directory -- the same
 *    real-path-containment check that defeats a symlink planted inside the Docker bind mount, not
 *    just a `..`-based traversal string.
 *
 * This is the single highest-value security test in this wave -- see `RecordingRawFilesTest`.
 */
object RecordingRawFiles {
    /**
     * Returns the real, on-disk [File] for [reportedFilename] under `{hostRawRoot}/{recordingId}/`,
     * or `null` if [reportedFilename] is malformed, the file does not exist, or the resolved real
     * path escapes the recording's own raw directory (symlink attack). Never throws.
     */
    fun resolveWithin(
        hostRawRoot: File,
        recordingId: String,
        reportedFilename: String,
    ): File? {
        val basename = File(reportedFilename).name
        if (basename.isBlank() || basename == "." || basename == ".." || basename.contains('/') || basename.contains('\\')) {
            return null
        }
        val recordingDir = File(hostRawRoot, recordingId)
        val candidate = File(recordingDir, basename)
        val realCandidate =
            try {
                candidate.toPath().toRealPath()
            } catch (e: IOException) {
                // Missing file (the normal "egress has not written this yet" case) or an
                // unreadable path -- both simply mean "not resolvable right now", never a crash.
                return null
            }
        val canonicalRecordingDir = recordingDir.canonicalFile.toPath().normalize()
        return if (realCandidate.normalize().startsWith(canonicalRecordingDir)) realCandidate.toFile() else null
    }
}
