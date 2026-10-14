package network.lapis.cloud.server.videooverlays

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** The two stored raster formats of the overlay library (PNG keeps its alpha channel; a logo may also be a JPEG). */
internal enum class VideoOverlayFormat(
    val extension: String,
    val contentType: String,
) {
    PNG("png", "image/png"),
    JPG("jpg", "image/jpeg"),
    ;

    companion object {
        fun fromExtension(extension: String): VideoOverlayFormat? = entries.firstOrNull { it.extension == extension }
    }
}

/**
 * Welle V1.9.129 -- on-disk storage of the overlay library images. Same semantics as `ChapterCrestStorage`: the file name is a
 * server-generated UUID plus a fixed format extension (never client input), the write is atomic, and every path is canonicalised and must
 * be a direct child of the storage root. No SVG is ever stored.
 */
internal class VideoOverlayStorage(
    root: File,
) {
    private val canonicalRoot: File = root.apply { mkdirs() }.canonicalFile

    fun write(
        id: Uuid,
        format: VideoOverlayFormat,
        bytes: ByteArray,
    ) {
        val target = fileFor(id = id, format = format)
        target.parentFile.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        try {
            tmp.writeBytes(bytes)
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    /** The file of [id] in the given [format], or `null` when it does not exist on disk. */
    fun resolve(
        id: Uuid,
        format: VideoOverlayFormat,
    ): File? = fileFor(id = id, format = format).takeIf { it.isFile }

    /** Deletes every possible extension for [id] -- idempotent, never throws. */
    fun delete(id: Uuid) {
        for (format in VideoOverlayFormat.entries) {
            val file = fileFor(id = id, format = format)
            runCatching {
                if (file.exists() && !file.delete()) logger.warn { "Could not delete video overlay file: ${file.name}" }
            }.onFailure { e -> logger.warn(e) { "Error deleting video overlay file: ${file.name}" } }
        }
    }

    private fun fileFor(
        id: Uuid,
        format: VideoOverlayFormat,
    ): File {
        val candidate = File(canonicalRoot, "$id.${format.extension}").canonicalFile
        require(candidate.parentFile == canonicalRoot) { "Video overlay path escapes storage root" }
        return candidate
    }
}
