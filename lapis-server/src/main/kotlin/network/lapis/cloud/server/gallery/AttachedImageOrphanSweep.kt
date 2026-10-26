package network.lapis.cloud.server.gallery

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import kotlin.concurrent.thread

private val logger = KotlinLogging.logger {}

/**
 * V1.9.222 -- the start-up net under the "files are written before the commit" rule. A crash between writing a picture and committing
 * its
 * row
 * leaves a file no row points to. This sweep deletes such a file, but ONLY when
 *   * its name is exactly `<uuid>.<jpg|png>` (a stranger's file in the folder is never touched, `.tmp` files included),
 *   * no `attached_image.storage_key` equals the UUID, and
 * * it is older than [minAgeMillis] (24 h by default) -- an upload in flight, or one whose row a concurrent restore is just writing, is
 * far
 *     younger.
 * There is no restore flag in the backup code to consult; the age guard is the protection, and the sweep runs once per start, nothing
 * more.
 * Only counts are logged -- never a name or path.
 */
internal object AttachedImageOrphanSweep {
    private val FILE_NAME = Regex("^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.(jpg|png)$")
    const val DEFAULT_MIN_AGE_MILLIS: Long = 24L * 60 * 60 * 1000

    /** Runs [sweep] once on a daemon thread; a failure is logged, never fatal. */
    fun startInBackground(storage: AttachedImageStorage) {
        thread(name = "attached-image-orphan-sweep", isDaemon = true) {
            runCatching { sweep(storage = storage) }.onFailure { e -> logger.warn(e) { "Gallery orphan sweep failed" } }
        }
    }

    /** Returns the number of files deleted. */
    fun sweep(
        storage: AttachedImageStorage,
        minAgeMillis: Long = DEFAULT_MIN_AGE_MILLIS,
        nowMillis: Long = System.currentTimeMillis(),
    ): Int {
        val files: Array<File> = storage.canonicalRoot.listFiles() ?: return 0
        val candidates = files.filter { it.isFile && FILE_NAME.matches(it.name) && nowMillis - it.lastModified() > minAgeMillis }
        if (candidates.isEmpty()) return 0
        val known = transaction { AttachedImageStore.allStorageKeysInTx() }.map { it.toString() }.toSet()
        var deleted = 0
        candidates.sortedBy { it.name }.forEach { file ->
            val key = FILE_NAME.matchEntire(file.name)!!.groupValues[1]
            if (key !in known && file.delete()) deleted++
        }
        if (deleted > 0) logger.info { "Gallery orphan sweep: deleted $deleted orphan file(s)" }
        return deleted
    }
}
