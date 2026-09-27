package network.lapis.cloud.server.events

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Welle "Veranstaltungs-Titelbild" (Event Cover Image) -- on-disk storage for processed cover
 * images. Physically lives on the SAME durable Docker volume as `documentStorageRoot` (see
 * `Application.kt`'s own `eventCoverStorageRoot` wiring KDoc for why `documentStorageRoot.resolve
 * ("event-covers")` is the default, not a brand-new volume) but is a logically SEPARATE code path:
 * no document access-control model applies here, and there is no collision risk with document
 * blobs (those live under per-document UUID subfolders, this stores flat `<id>.<ext>` files).
 *
 * The stored file name is derived EXCLUSIVELY from a server-generated [Uuid] and a fixed
 * [CoverImageFormat] enum value -- never from any client-supplied string (filename, slug, event
 * title, ...). [fileFor]'s canonical-path check is defense in depth on top of that, not the
 * primary safeguard.
 */
internal class EventCoverStorage(
    root: File,
) {
    private val canonicalRoot: File = root.apply { mkdirs() }.canonicalFile

    /** Atomic write: encodes to a `.tmp` sibling first, then an atomic (same-filesystem) move/replace -- a reader can never observe a partially-written file. */
    fun write(
        id: Uuid,
        format: CoverImageFormat,
        bytes: ByteArray,
    ) {
        val target = fileFor(id = id, format = format)
        target.parentFile.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeBytes(bytes)
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Tries `.jpg` first, then `.png` -- exactly one can exist for a given [id] at a time (see class KDoc), the order is otherwise arbitrary. Returns `null` if neither file exists. */
    fun resolve(id: Uuid): Pair<File, CoverImageFormat>? {
        for (format in CoverImageFormat.entries) {
            val file = fileFor(id = id, format = format)
            if (file.exists()) return file to format
        }
        return null
    }

    /** Deletes both possible extensions for [id] -- idempotent, never throws (a failed delete is logged and otherwise ignored, same posture `EventCoverRoutes`' own KDoc documents for post-commit cleanup). */
    fun delete(id: Uuid) {
        for (format in CoverImageFormat.entries) {
            val file = fileFor(id = id, format = format)
            runCatching {
                if (file.exists() && !file.delete()) {
                    logger.warn { "Could not delete event cover file: ${file.name}" }
                }
            }.onFailure { e -> logger.warn(e) { "Error deleting event cover file: ${file.name}" } }
        }
    }

    /** Canonical-path check as defense in depth on top of the "id/enum-only" filename discipline (see class KDoc). */
    private fun fileFor(
        id: Uuid,
        format: CoverImageFormat,
    ): File {
        val candidate = File(canonicalRoot, "$id.${format.extension}").canonicalFile
        require(candidate.parentFile == canonicalRoot) { "Event cover path escapes storage root" }
        return candidate
    }
}
