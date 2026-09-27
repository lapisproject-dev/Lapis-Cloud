package network.lapis.cloud.server.events

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.uuid.Uuid

/**
 * Welle "Veranstaltungs-Titelbild" (Event Cover Image) -- [EventCoverStorage] is a thin,
 * DB-independent file-storage wrapper, same "unit-testable in isolation" posture every other pure
 * helper in this package gets (`EventPolicyTest`, `EventCoverImageProcessorTest`).
 */
class EventCoverStorageTest :
    FunSpec({
        test("write then resolve returns the same bytes and the correct format") {
            val root = Files.createTempDirectory("event-cover-storage-test").toFile()
            try {
                val storage = EventCoverStorage(root)
                val id = Uuid.random()
                storage.write(id = id, format = CoverImageFormat.JPEG, bytes = byteArrayOf(1, 2, 3, 4))
                val resolved = storage.resolve(id)
                // Compares against root.canonicalFile, not the raw temp-dir File -- on macOS
                // Files.createTempDirectory returns a path under /var/..., which canonicalizes to
                // /private/var/... (a symlink), and EventCoverStorage always resolves against the
                // CANONICAL root (see its own KDoc "canonical-path check").
                resolved shouldBe (root.canonicalFile.resolve("$id.jpg") to CoverImageFormat.JPEG)
                resolved!!.first.readBytes() shouldBe byteArrayOf(1, 2, 3, 4)
                // No leftover .tmp sibling after a successful write (atomic move).
                root.resolve("$id.jpg.tmp").exists() shouldBe false
            } finally {
                root.deleteRecursively()
            }
        }

        test("resolve returns null for an id that was never written") {
            val root = Files.createTempDirectory("event-cover-storage-test").toFile()
            try {
                EventCoverStorage(root).resolve(Uuid.random()) shouldBe null
            } finally {
                root.deleteRecursively()
            }
        }

        test("write with REPLACE_EXISTING overwrites a same-id/-format file atomically") {
            val root = Files.createTempDirectory("event-cover-storage-test").toFile()
            try {
                val storage = EventCoverStorage(root)
                val id = Uuid.random()
                storage.write(id = id, format = CoverImageFormat.PNG, bytes = byteArrayOf(1))
                storage.write(id = id, format = CoverImageFormat.PNG, bytes = byteArrayOf(2, 2))
                storage.resolve(id)!!.first.readBytes() shouldBe byteArrayOf(2, 2)
            } finally {
                root.deleteRecursively()
            }
        }

        test("delete removes both possible extensions and is idempotent (never throws when nothing exists)") {
            val root = Files.createTempDirectory("event-cover-storage-test").toFile()
            try {
                val storage = EventCoverStorage(root)
                val id = Uuid.random()
                storage.write(id = id, format = CoverImageFormat.JPEG, bytes = byteArrayOf(9))
                storage.delete(id)
                storage.resolve(id) shouldBe null
                // Second delete on an already-gone id -- must not throw.
                storage.delete(id)
            } finally {
                root.deleteRecursively()
            }
        }

        test("delete on an id that was never written does not throw") {
            val root = Files.createTempDirectory("event-cover-storage-test").toFile()
            try {
                EventCoverStorage(root).delete(Uuid.random())
            } finally {
                root.deleteRecursively()
            }
        }

        test("replacing an existing JPEG cover with a PNG one leaves only the PNG file behind (caller-driven cleanup)") {
            val root = Files.createTempDirectory("event-cover-storage-test").toFile()
            try {
                val storage = EventCoverStorage(root)
                val id = Uuid.random()
                storage.write(id = id, format = CoverImageFormat.JPEG, bytes = byteArrayOf(1))
                storage.write(id = id, format = CoverImageFormat.PNG, bytes = byteArrayOf(2))
                // Both files can coexist at the storage layer (it never enforces "one extension per
                // id" itself) -- EventCoverRoutes always uses a FRESH Uuid.random() per upload, so in
                // practice this never happens; this test documents that the layer itself does not
                // prevent it, and that resolve() picks JPEG first when both exist (see class KDoc
                // "Tries .jpg first").
                storage.resolve(id) shouldBe (root.canonicalFile.resolve("$id.jpg") to CoverImageFormat.JPEG)
            } finally {
                root.deleteRecursively()
            }
        }

        test("storage root is created if it does not yet exist") {
            val parent = Files.createTempDirectory("event-cover-storage-test").toFile()
            try {
                val root = parent.resolve("does-not-exist-yet")
                EventCoverStorage(root)
                root.exists() shouldBe true
            } finally {
                parent.deleteRecursively()
            }
        }
    })
