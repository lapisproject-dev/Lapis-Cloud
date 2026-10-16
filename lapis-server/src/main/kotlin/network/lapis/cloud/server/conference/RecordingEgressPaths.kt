package network.lapis.cloud.server.conference

import kotlin.uuid.Uuid

/**
 * V1.9.110 -- the ONE place that builds the output path of a Participant Egress file, PURE and testable.
 *
 * The path is composed ONLY of operator configuration ([ConferenceRecordingConfig.outputContainerDir]), the recording's own UUID
 * (`raw_dir`, server-generated) and the UUID of the `conference_recording_track` row generated for this file beforehand -- never a
 * participant identity, a participant SID or any other value that originates from a user or from LiveKit. A hostile or merely odd
 * identity can therefore never influence where a file is written. (The Track-Egress path of the earlier modes still carries the
 * identity; it is protected by [isAcceptableIdentity] instead.)
 */
object RecordingEgressPaths {
    /**
     * `"<containerDir>/<rawDir>/p-<rowId>.mp4"`. [containerDir] must be absolute and free of `..` segments (a trailing `/` is
     * normalised away), [rawDir] must parse as a [Uuid] (the "raw_dir is always the recording's own UUID" invariant).
     */
    fun participantFile(
        containerDir: String,
        rawDir: String,
        rowId: Uuid,
    ): String {
        require(containerDir.startsWith("/")) { "container output directory must be absolute" }
        require(containerDir.split('/').none { it == ".." } && '\\' !in containerDir && containerDir.none { it.isISOControl() }) {
            "container output directory must not contain '..', backslashes or control characters"
        }
        val parsedRawDir = runCatching { Uuid.parse(rawDir) }.getOrNull()
        require(parsedRawDir != null) { "raw directory must be a UUID" }
        return "${containerDir.trimEnd('/')}/$parsedRawDir/p-$rowId.mp4"
    }

    /**
     * V1.9.143 -- `"<containerDir>/pulpit/<spaceId>/<fileId>.mp4"`, the output of ONE pulpit recording file. Composed only of operator
     * configuration, the encounter space's own UUID and a random UUID generated for this file -- never a participant identity, so a hostile or
     * odd identity cannot influence where a file is written, and the file name tells nothing about who stands at the pulpit. The `pulpit/`
     * subdirectory keeps the files apart from the conference recordings (`RecordingPoller`'s raw directories are UUID directories at the top
     * level), so neither side's sweep ever touches the other's files.
     */
    fun pulpitFile(
        containerDir: String,
        spaceId: Uuid,
        fileId: Uuid,
    ): String {
        require(containerDir.startsWith("/")) { "container output directory must be absolute" }
        require(containerDir.split('/').none { it == ".." } && '\\' !in containerDir && containerDir.none { it.isISOControl() }) {
            "container output directory must not contain '..', backslashes or control characters"
        }
        return "${containerDir.trimEnd('/')}/$PULPIT_DIRECTORY/$spaceId/$fileId.mp4"
    }

    /** The subdirectory (below the egress output directory) that holds every pulpit recording file. */
    const val PULPIT_DIRECTORY: String = "pulpit"

    /**
     * `true` iff [identity] fits the `participant_identity` VARCHAR(64) column and consists only of characters that are harmless in a
     * file name and a log line (`[A-Za-z0-9_.@:-]`; this codebase's identities are member UUID strings).
     */
    fun isAcceptableIdentity(identity: String): Boolean =
        identity.length in 1..MAX_PARTICIPANT_IDENTITY_LENGTH && identity.all { it in IDENTITY_CHARACTERS }

    private val IDENTITY_CHARACTERS: Set<Char> = (('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('_', '.', '@', ':', '-')).toSet()
}
