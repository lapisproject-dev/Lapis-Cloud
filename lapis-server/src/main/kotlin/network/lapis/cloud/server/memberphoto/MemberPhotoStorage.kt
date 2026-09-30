package network.lapis.cloud.server.memberphoto

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.9.19 "Mitglieder-Foto" -- on-disk storage for processed member photos. Lives on the
 * SAME durable volume as `documentStorageRoot` (subdirectory `member-photos`), which is already
 * mounted on every real instance, so no compose change is needed.
 *
 * File names are `<random uuid>.jpg` -- NEVER the member id and never derived from client input.
 * [resolve]'s format check plus canonical-path check are defense in depth on top of that.
 *
 * **[fromEnvironment] is the ONLY place the root is derived from the environment** -- `Application`
 * and the DSGVO contributor both call it, so the two can never disagree about where photos live.
 */
internal class MemberPhotoStorage(
    root: File,
) {
    private val canonicalRoot: File = root.apply { mkdirs() }.canonicalFile

    /** Atomically writes [bytes] under a fresh key and returns the storage key (`<uuid>.jpg`). */
    fun write(
        key: Uuid,
        bytes: ByteArray,
    ): String {
        val storageKey = "$key.jpg"
        val target = fileFor(storageKey) ?: error("Invalid member photo key")
        val tmp = File(target.parentFile, "$storageKey.tmp")
        try {
            tmp.writeBytes(bytes)
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        return storageKey
    }

    /** The file for [storageKey], or `null` if the key is malformed, escapes the root, or the file is missing. */
    fun resolve(storageKey: String): File? = fileFor(storageKey)?.takeIf { it.isFile }

    /** Idempotent, never throws -- a failed delete is logged only (the DB row is authoritative). */
    fun delete(storageKey: String) {
        val file = fileFor(storageKey) ?: return
        runCatching {
            if (file.exists() && !file.delete()) logger.warn { "Could not delete member photo file" }
        }.onFailure { e -> logger.warn(e) { "Error deleting member photo file" } }
    }

    private fun fileFor(storageKey: String): File? {
        if (!KEY_PATTERN.matches(storageKey)) return null
        val candidate = File(canonicalRoot, storageKey).canonicalFile
        if (candidate.parentFile != canonicalRoot) return null
        return candidate
    }

    companion object {
        const val ENV_STORAGE_ROOT = "LAPIS_MEMBER_PHOTO_STORAGE_ROOT"
        private val KEY_PATTERN = Regex("^[0-9a-f-]{36}\\.jpg$")

        fun fromEnvironment(env: (String) -> String? = System::getenv): MemberPhotoStorage {
            val explicit = env(ENV_STORAGE_ROOT)?.takeIf { it.isNotBlank() }
            val root =
                if (explicit != null) {
                    File(explicit)
                } else {
                    File(env("LAPIS_DOCUMENT_STORAGE_ROOT") ?: "build/document-storage").resolve("member-photos")
                }
            return MemberPhotoStorage(root)
        }
    }
}
