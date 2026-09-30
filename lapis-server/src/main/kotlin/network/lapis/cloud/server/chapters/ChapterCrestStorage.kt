package network.lapis.cloud.server.chapters

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.9.21 -- on-disk storage of chapter crests (JPEG, PNG or sanitized SVG). Same semantics as
 * `EventCoverStorage` (server-generated UUID + fixed enum extension, atomic write, canonical-path check),
 * typed on [ChapterCrestFormat] so the SVG extension is known to [resolve] and -- crucially -- to [delete]
 * (otherwise a replaced or removed SVG crest would stay on disk forever). Existing `.jpg`/`.png` files stay
 * compatible: no data migration.
 */
internal class ChapterCrestStorage(
    root: File,
) {
    private val canonicalRoot: File = root.apply { mkdirs() }.canonicalFile

    fun write(
        id: Uuid,
        format: ChapterCrestFormat,
        bytes: ByteArray,
    ) {
        val target = fileFor(id = id, format = format)
        target.parentFile.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeBytes(bytes)
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    fun resolve(id: Uuid): Pair<File, ChapterCrestFormat>? {
        for (format in ChapterCrestFormat.entries) {
            val file = fileFor(id = id, format = format)
            if (file.exists()) return file to format
        }
        return null
    }

    /** Deletes every possible extension for [id] -- idempotent, never throws. */
    fun delete(id: Uuid) {
        for (format in ChapterCrestFormat.entries) {
            val file = fileFor(id = id, format = format)
            runCatching {
                if (file.exists() && !file.delete()) logger.warn { "Could not delete chapter crest file: ${file.name}" }
            }.onFailure { e -> logger.warn(e) { "Error deleting chapter crest file: ${file.name}" } }
        }
    }

    private fun fileFor(
        id: Uuid,
        format: ChapterCrestFormat,
    ): File {
        val candidate = File(canonicalRoot, "$id.${format.extension}").canonicalFile
        require(candidate.parentFile == canonicalRoot) { "Chapter crest path escapes storage root" }
        return candidate
    }
}
