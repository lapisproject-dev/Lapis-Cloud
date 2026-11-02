package network.lapis.cloud.server.conference.pausefeed

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** The two kinds of stored pause media. The extension is part of the type, never taken from a client. */
internal enum class PauseMediaKind(
    val extension: String,
) {
    IMAGE("png"),
    MUSIC("m4a"),
}

/** The stored pause media of the organization, as the database holds it. */
internal data class PauseMediaRow(
    val imageKey: Uuid?,
    val imageUpdatedAt: LocalDateTime?,
    val musicKey: Uuid?,
    val musicLabel: String?,
    val musicDurationSeconds: Int?,
    val musicUpdatedAt: LocalDateTime?,
)

/** What the pause feed needs from the media: a picture file (always) and music (optional). */
internal interface PauseMediaSource {
    fun imageFile(): File

    fun musicFile(): File?
}

/** The only code that touches the six pause-media columns of `organization_settings`. Every function must run inside a `transaction {}`. */
internal object PauseMediaDb {
    fun load(): PauseMediaRow {
        val row =
            OrganizationSettingsTable.selectAll().where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }.singleOrNull()
                ?: return PauseMediaRow(
                    imageKey = null,
                    imageUpdatedAt = null,
                    musicKey = null,
                    musicLabel = null,
                    musicDurationSeconds = null,
                    musicUpdatedAt = null,
                )
        return PauseMediaRow(
            imageKey = row[OrganizationSettingsTable.pauseImageKey],
            imageUpdatedAt = row[OrganizationSettingsTable.pauseImageUpdatedAt],
            musicKey = row[OrganizationSettingsTable.pauseMusicKey],
            musicLabel = row[OrganizationSettingsTable.pauseMusicLabel],
            musicDurationSeconds = row[OrganizationSettingsTable.pauseMusicDurationSeconds]?.toInt(),
            musicUpdatedAt = row[OrganizationSettingsTable.pauseMusicUpdatedAt],
        )
    }

    fun lock() {
        OrganizationSettingsTable
            .selectAll()
            .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
            .forUpdate()
            .toList()
    }

    fun setImage(
        key: Uuid?,
        now: LocalDateTime?,
    ) {
        OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
            it[pauseImageKey] = key
            it[pauseImageUpdatedAt] = now
        }
    }

    fun setMusic(
        key: Uuid?,
        label: String?,
        durationSeconds: Int?,
        now: LocalDateTime?,
    ) {
        OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
            it[pauseMusicKey] = key
            it[pauseMusicLabel] = label
            it[pauseMusicDurationSeconds] = durationSeconds?.toShort()
            it[pauseMusicUpdatedAt] = now
        }
    }
}

/**
 * V1.9.255 -- on-disk store of the pause picture and music. Lives on the same durable volume as `documentStorageRoot`
 * (`documentStorageRoot/conference-pause-media`), a logically separate code path like [network.lapis.cloud.server.events.EventCoverStorage].
 *
 * File names are ONLY `<server-generated UUID>.<png|m4a>` ([PauseMediaKind]); no client string ever reaches a path. [fileFor] checks the
 * canonical path as defence in depth. Writes are atomic (`.tmp` + `ATOMIC_MOVE`).
 *
 * **A running feed re-reads the picture file for every frame** (the image2 demuxer reopens it), so a replaced or removed file must not vanish
 * under it. [retire] therefore only DELETES a file when no feed runs ([feedsRunning] false); otherwise it is queued and [sweep] (called whenever
 * a feed ends) removes it later. Leftovers of a crash in between are small and harmless (they are never read again).
 */
internal class PauseMediaStore(
    root: File,
    private val feedsRunning: () -> Boolean = { false },
) : PauseMediaSource {
    private val canonicalRoot: File = root.apply { mkdirs() }.canonicalFile
    private val retired = ConcurrentLinkedQueue<File>()

    /** A private scratch directory for uploads and transcodes (random names, deleted by the caller). */
    fun tmpDir(): File = File(canonicalRoot, "tmp").apply { mkdirs() }

    fun newTempFile(): File = Files.createTempFile(tmpDir().toPath(), "up-", ".bin").toFile()

    fun write(
        key: Uuid,
        kind: PauseMediaKind,
        bytes: ByteArray,
    ) {
        val target = fileFor(key = key, kind = kind)
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeBytes(bytes)
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Moves an already written file into the store (the transcode output). */
    fun adopt(
        key: Uuid,
        kind: PauseMediaKind,
        source: File,
    ) {
        val target = fileFor(key = key, kind = kind)
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    fun resolve(
        key: Uuid,
        kind: PauseMediaKind,
    ): File? = fileFor(key = key, kind = kind).takeIf { it.isFile }

    /** Deletes at once -- only for a file that no feed can have seen (a failed commit). */
    fun deleteNow(
        key: Uuid,
        kind: PauseMediaKind,
    ) {
        runCatching { fileFor(key = key, kind = kind).delete() }.onFailure { logger.warn { "Could not delete a pause media file" } }
    }

    /** Replaced or removed media: deleted when no feed runs, else queued for [sweep]. */
    fun retire(
        key: Uuid,
        kind: PauseMediaKind,
    ) {
        retired += fileFor(key = key, kind = kind)
        sweep()
    }

    fun sweep() {
        if (feedsRunning()) return
        while (true) {
            val file = retired.poll() ?: return
            runCatching { file.delete() }.onFailure { logger.warn { "Could not delete a retired pause media file" } }
        }
    }

    /** The built-in picture, copied out of the classpath on first use (ffmpeg needs a real file). */
    fun defaultImage(): File {
        val target = File(canonicalRoot, DEFAULT_IMAGE_FILE)
        if (target.isFile && target.length() > 0) return target
        val stream =
            PauseMediaStore::class.java.getResourceAsStream(DEFAULT_IMAGE_RESOURCE)
                ?: error("built-in pause picture missing from the classpath")
        stream.use { input ->
            val tmp = File(canonicalRoot, "$DEFAULT_IMAGE_FILE.tmp")
            Files.copy(input, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        return target
    }

    override fun imageFile(): File {
        val key = transaction { PauseMediaDb.load().imageKey }
        return (key?.let { resolve(key = it, kind = PauseMediaKind.IMAGE) }) ?: defaultImage()
    }

    override fun musicFile(): File? {
        val key = transaction { PauseMediaDb.load().musicKey } ?: return null
        return resolve(key = key, kind = PauseMediaKind.MUSIC)
    }

    private fun fileFor(
        key: Uuid,
        kind: PauseMediaKind,
    ): File {
        val candidate = File(canonicalRoot, "$key.${kind.extension}").canonicalFile
        require(candidate.parentFile == canonicalRoot) { "Pause media path escapes the storage root" }
        return candidate
    }

    companion object {
        const val DEFAULT_IMAGE_RESOURCE = "/conference/pause-default.png"
        private const val DEFAULT_IMAGE_FILE = "default-pause.png"
    }
}
