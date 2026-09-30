package network.lapis.cloud.server.chapters

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.http.ContentType
import kotlin.io.path.createTempDirectory
import kotlin.uuid.Uuid

class ChapterCrestStorageTest :
    FunSpec({
        fun newStorage() = ChapterCrestStorage(createTempDirectory("crest-storage").toFile())

        test("write, resolve and delete work for all three formats") {
            for (format in ChapterCrestFormat.entries) {
                val storage = newStorage()
                val id = Uuid.random()
                storage.resolve(id) shouldBe null
                storage.write(id = id, format = format, bytes = byteArrayOf(1, 2, 3))
                val (file, resolved) = storage.resolve(id)!!
                resolved shouldBe format
                file.name shouldBe "$id.${format.extension}"
                file.readBytes().toList() shouldBe listOf<Byte>(1, 2, 3)
                storage.delete(id)
                storage.resolve(id) shouldBe null
            }
        }

        test("delete removes an SVG file too (a replaced SVG crest must not stay on disk)") {
            val root = createTempDirectory("crest-storage-svg").toFile()
            val storage = ChapterCrestStorage(root)
            val id = Uuid.random()
            storage.write(id = id, format = ChapterCrestFormat.SVG, bytes = "<svg/>".toByteArray())
            root.listFiles()!!.size shouldBe 1
            storage.delete(id)
            root.listFiles()!!.size shouldBe 0
            storage.delete(id) // idempotent
        }

        test("the file name only ever derives from a UUID and a fixed extension") {
            val storage = newStorage()
            val id = Uuid.random()
            storage.write(id = id, format = ChapterCrestFormat.SVG, bytes = byteArrayOf(1))
            storage
                .resolve(id)!!
                .first.parentFile.canonicalPath shouldBe
                storage
                    .resolve(id)!!
                    .first.canonicalFile.parentFile.canonicalPath
            shouldThrow<IllegalArgumentException> { Uuid.parse("../../etc/passwd") }
        }

        test("format mapping: stored content types and delivery content types") {
            ChapterCrestPolicy.contentTypeOf(ChapterCrestFormat.SVG) shouldBe "image/svg+xml"
            ChapterCrestPolicy.formatOf("image/svg+xml") shouldBe ChapterCrestFormat.SVG
            ChapterCrestPolicy.formatOf("image/png") shouldBe ChapterCrestFormat.PNG
            ChapterCrestPolicy.formatOf("image/jpeg") shouldBe ChapterCrestFormat.JPEG
            ChapterCrestPolicy.formatOf("image/svg") shouldBe null
            ChapterCrestPolicy.formatOf("text/html") shouldBe null
            ChapterCrestFormat.SVG.deliveryContentType.toString() shouldBe "image/svg+xml; charset=utf-8"
            ChapterCrestFormat.PNG.deliveryContentType shouldBe ContentType.Image.PNG
            ChapterCrestFormat.entries.map { it.downloadFileName } shouldBe listOf("crest.jpg", "crest.png", "crest.svg")
        }
    })
