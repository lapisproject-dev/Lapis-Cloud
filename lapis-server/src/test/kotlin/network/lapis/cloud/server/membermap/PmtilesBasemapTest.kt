package network.lapis.cloud.server.membermap

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.nio.file.Files

private fun fixturePath(): String =
    checkNotNull(PmtilesBasemapTest::class.java.getResource("/member-map/germany-test-fixture.pmtiles")) {
        "test fixture missing from classpath"
    }.path

/**
 * [PmtilesBasemap.probe] never throws (see its own KDoc) -- every branch below is exercised via its
 * return type, never a caught exception.
 */
class PmtilesBasemapTest :
    FunSpec({
        test("null path -> NotConfigured") {
            PmtilesBasemap(null).probe() shouldBe PmtilesProbe.NotConfigured
        }

        test("real fixture -> Available") {
            val probe = PmtilesBasemap(fixturePath()).probe()
            probe.shouldBeInstanceOf<PmtilesProbe.Available>()
        }

        test("missing file -> Missing") {
            val path = Files.createTempDirectory("pmtiles-test").resolve("does-not-exist.pmtiles").toString()
            PmtilesBasemap(path).probe() shouldBe PmtilesProbe.Missing
        }

        test("directory instead of file -> NotRegularFile") {
            val dir = Files.createTempDirectory("pmtiles-test-dir")
            PmtilesBasemap(dir.toString()).probe() shouldBe PmtilesProbe.NotRegularFile
        }

        test("wrong magic bytes (random bytes, same length) -> InvalidHeader") {
            val random = File(checkNotNull(PmtilesBasemapTest::class.java.getResource("/conference-backgrounds/random-bytes.bin")).path)
            val tempFile = Files.createTempFile("wrong-magic", ".pmtiles")
            random.copyTo(tempFile.toFile(), overwrite = true)
            PmtilesBasemap(tempFile.toString()).probe() shouldBe PmtilesProbe.InvalidHeader
        }

        test("correct magic but wrong spec version byte -> InvalidHeader") {
            val real = File(fixturePath())
            val bytes = real.readBytes()
            bytes[7] = 2
            val tempFile = Files.createTempFile("wrong-version", ".pmtiles")
            tempFile.toFile().writeBytes(bytes)
            PmtilesBasemap(tempFile.toString()).probe() shouldBe PmtilesProbe.InvalidHeader
        }

        test("truncated file (100 bytes, shorter than the 127-byte header) -> InvalidHeader") {
            val tempFile = Files.createTempFile("truncated", ".pmtiles")
            tempFile.toFile().writeBytes(ByteArray(100) { 0x41 })
            PmtilesBasemap(tempFile.toString()).probe() shouldBe PmtilesProbe.InvalidHeader
        }

        test("empty file -> InvalidHeader") {
            val tempFile = Files.createTempFile("empty", ".pmtiles")
            PmtilesBasemap(tempFile.toString()).probe() shouldBe PmtilesProbe.InvalidHeader
        }
    })
