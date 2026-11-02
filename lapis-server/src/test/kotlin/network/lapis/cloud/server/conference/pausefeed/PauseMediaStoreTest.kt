package network.lapis.cloud.server.conference.pausefeed

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

private fun freshRoot(): File = Files.createTempDirectory("pause-media-test").toFile()

class PauseMediaStoreTest :
    FunSpec({
        test("files carry only the UUID and the fixed extension; writes are atomic and leave no tmp file") {
            val root = freshRoot()
            val store = PauseMediaStore(root = root)
            val key = Uuid.random()
            store.write(key = key, kind = PauseMediaKind.IMAGE, bytes = byteArrayOf(1, 2, 3))
            store.write(key = Uuid.random(), kind = PauseMediaKind.MUSIC, bytes = byteArrayOf(9))
            root.listFiles()!!.filter { it.isFile }.all { Regex("[0-9a-f-]{36}\\.(png|m4a)").matches(it.name) } shouldBe true
            store.resolve(key = key, kind = PauseMediaKind.IMAGE)!!.readBytes().toList() shouldBe listOf<Byte>(1, 2, 3)
            root.walkTopDown().any { it.name.endsWith(".tmp") } shouldBe false
        }

        test("a symlink that escapes the root is refused by the canonical-path check") {
            val root = freshRoot()
            val outside = freshRoot().resolve("outside.png").also { it.writeBytes(byteArrayOf(1)) }
            val key = Uuid.random()
            Files.createSymbolicLink(root.resolve("$key.png").toPath(), outside.toPath())
            shouldThrow<IllegalArgumentException> { PauseMediaStore(root = root).resolve(key = key, kind = PauseMediaKind.IMAGE) }
        }

        test("a retired file survives while a feed runs and is deleted once none runs") {
            val root = freshRoot()
            var running = true
            val store = PauseMediaStore(root = root, feedsRunning = { running })
            val key = Uuid.random()
            store.write(key = key, kind = PauseMediaKind.IMAGE, bytes = byteArrayOf(1))
            store.retire(key = key, kind = PauseMediaKind.IMAGE)
            (store.resolve(key = key, kind = PauseMediaKind.IMAGE) != null) shouldBe true // still there
            root.resolve("$key.png").exists() shouldBe true
            running = false
            store.sweep()
            root.resolve("$key.png").exists() shouldBe false
            // retire with nothing running deletes at once
            val other = Uuid.random()
            store.write(key = other, kind = PauseMediaKind.MUSIC, bytes = byteArrayOf(2))
            store.retire(key = other, kind = PauseMediaKind.MUSIC)
            root.resolve("$other.m4a").exists() shouldBe false
        }

        test("the built-in picture is a 1280x720 PNG, dark at the corners, gold bars in the middle band") {
            val file = PauseMediaStore(root = freshRoot()).defaultImage()
            val img = javax.imageio.ImageIO.read(file)
            (img.width to img.height) shouldBe (1280 to 720)
            listOf(0 to 0, 1279 to 0, 0 to 719, 1279 to 719).forEach { (x, y) -> (img.getRGB(x, y) and 0xFFFFFF) shouldBe 0x14171C }
            // the gold of --lapis-fleck (#C9A227) somewhere in the vertical centre band
            (0 until 1280).any { x -> (img.getRGB(x, 360) and 0xFFFFFF) == 0xC9A227 } shouldBe true
        }

        test("the temp directory is private to the store and temp files get random names") {
            val root = freshRoot()
            val store = PauseMediaStore(root = root)
            val a = store.newTempFile()
            val b = store.newTempFile()
            (a.name != b.name) shouldBe true
            a.parentFile.canonicalPath shouldBe root.canonicalFile.resolve("tmp").path
        }
    })
