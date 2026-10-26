package network.lapis.cloud.server.gallery

import io.github.oshai.kotlinlogging.KotlinLogging
import network.lapis.cloud.server.events.CoverImageFormat
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * V1.9.222 -- on-disk storage of the processed gallery pictures (`attached-images/` on the durable document volume, override
 * `LAPIS_ATTACHED_IMAGE_STORAGE_ROOT`). The file name is `<storage_key>.<jpg|png>`: the `storage_key` is a server-generated UUID that
 * is
 * distinct from the row id, the extension comes from the fixed [CoverImageFormat] enum -- never from client input. The canonical-path
 * check
 * is
 * defence in depth. [resolve] asks for ONE extension (the one the row's `mime_type` names), it does not guess.
 *
 * [fromEnvironment] is the single place the root is derived: `Application`, the DSGVO contributor and the start sweep all call it.
 */
internal class AttachedImageStorage(
    root: File,
) {
    val canonicalRoot: File = root.apply { mkdirs() }.canonicalFile

    /** Atomic write: a `.tmp` sibling, then a same-filesystem move, so a reader never sees a partial file. */
    fun write(
        storageKey: Uuid,
        format: CoverImageFormat,
        bytes: ByteArray,
    ) {
        val target = fileFor(storageKey = storageKey, extension = format.extension)
        val tmp = File(target.parentFile, "${target.name}.tmp")
        try {
            tmp.writeBytes(bytes)
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    /** The file for [storageKey] with the extension of [mimeType], or `null` when the type is unknown or the file is missing. */
    fun resolve(
        storageKey: Uuid,
        mimeType: String,
    ): File? {
        val extension = extensionFor(mimeType) ?: return null
        return fileFor(storageKey = storageKey, extension = extension).takeIf { it.isFile }
    }

    /** Deletes both possible extensions -- idempotent, never throws (a failed delete is logged, the row is authoritative). */
    fun delete(storageKey: Uuid) {
        for (format in CoverImageFormat.entries) {
            val file = fileFor(storageKey = storageKey, extension = format.extension)
            runCatching {
                if (file.exists() && !file.delete()) logger.warn { "Could not delete a gallery image file" }
            }.onFailure { e -> logger.warn(e) { "Error deleting a gallery image file" } }
        }
    }

    /** To be called AFTER the transaction committed (Exposed may repeat a transaction block, a file delete must not be repeated into
     * one).
     * */
    fun deleteAfterCommit(storageKeys: Collection<Uuid>) {
        storageKeys.forEach { delete(it) }
    }

    private fun fileFor(
        storageKey: Uuid,
        extension: String,
    ): File {
        val candidate = File(canonicalRoot, "$storageKey.$extension").canonicalFile
        require(candidate.parentFile == canonicalRoot) { "Gallery image path escapes the storage root" }
        return candidate
    }

    companion object {
        const val ENV_STORAGE_ROOT = "LAPIS_ATTACHED_IMAGE_STORAGE_ROOT"

        fun extensionFor(mimeType: String): String? =
            when (mimeType) {
                "image/jpeg" -> "jpg"
                "image/png" -> "png"
                else -> null
            }

        fun mimeTypeFor(format: CoverImageFormat): String = format.contentType.toString()

        fun fromEnvironment(
            documentStorageRoot: File? = null,
            env: (String) -> String? = System::getenv,
        ): AttachedImageStorage {
            val explicit = env(ENV_STORAGE_ROOT)?.takeIf { it.isNotBlank() }
            val root =
                when {
                    explicit != null -> File(explicit)
                    documentStorageRoot != null -> documentStorageRoot.resolve("attached-images")
                    else -> File(env("LAPIS_DOCUMENT_STORAGE_ROOT") ?: "build/document-storage").resolve("attached-images")
                }
            return AttachedImageStorage(root)
        }
    }
}
