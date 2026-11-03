package network.lapis.cloud.server.documents

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.StorageSpaceLevel
import java.io.File
import java.io.IOException
import java.nio.file.Files

private const val GB = StorageSpaceProbe.BYTES_PER_GB
private const val TB = 1_000 * GB

/** V1.9.263 -- the pure level function and the measurement of [StorageSpaceProbe]. */
class StorageSpaceProbeTest :
    FunSpec({
        fun level(
            free: Long,
            total: Long,
        ) = StorageSpaceProbe.level(free = free, total = total)

        context("percent boundaries on a 1 TB volume (the GB criterion does not apply)") {
            test("19.9 % free is HINT") { level(199 * GB, TB) shouldBe StorageSpaceLevel.HINT }
            test("20.0 % free is INFO") { level(200 * GB, TB) shouldBe StorageSpaceLevel.INFO }
            test("4.9 % free is WARNING") { level(49 * GB, TB) shouldBe StorageSpaceLevel.WARNING }
            test("5.0 % free is HINT") { level(50 * GB, TB) shouldBe StorageSpaceLevel.HINT }
        }

        context("GB boundaries on small volumes (percent is at least 20 % resp. 5 %)") {
            test(
                "9.9 GB of 40 GB is HINT through the GB criterion alone",
            ) { level(9_900_000_000L, 40 * GB) shouldBe StorageSpaceLevel.HINT }
            test("10.0 GB of 40 GB is INFO") { level(10 * GB, 40 * GB) shouldBe StorageSpaceLevel.INFO }
            test("1.9 GB of 8 GB is WARNING through the GB criterion alone") {
                level(1_900_000_000L, 8 * GB) shouldBe StorageSpaceLevel.WARNING
            }
            test("2.0 GB of 8 GB is HINT") { level(2 * GB, 8 * GB) shouldBe StorageSpaceLevel.HINT }
        }

        context("both criteria") {
            test("2.4 GB of 8 GB (30 %) is HINT through the GB criterion") { level(2_400_000_000L, 8 * GB) shouldBe StorageSpaceLevel.HINT }
            test("1.5 TB of 10 TB (15 %) is HINT through the percent criterion") {
                level(1_500 * GB, 10 * TB) shouldBe
                    StorageSpaceLevel.HINT
            }
            test("1 GB of 100 GB is WARNING through both") { level(GB, 100 * GB) shouldBe StorageSpaceLevel.WARNING }
        }

        context("edge cases") {
            test("nothing free is WARNING") { level(0L, 100 * GB) shouldBe StorageSpaceLevel.WARNING }
            test("an empty volume of at least 10 GB is INFO") { level(10 * GB, 10 * GB) shouldBe StorageSpaceLevel.INFO }
            test("an empty volume of 5 GB is HINT (a small volume is always at least HINT)") {
                level(5 * GB, 5 * GB) shouldBe StorageSpaceLevel.HINT
            }
            test("an overflow is UNKNOWN, not a wrong level") {
                level(Long.MAX_VALUE / 2, Long.MAX_VALUE) shouldBe StorageSpaceLevel.UNKNOWN
            }
            test("implausible numbers are UNKNOWN") {
                level(1L, 0L) shouldBe StorageSpaceLevel.UNKNOWN
                level(-1L, 100L) shouldBe StorageSpaceLevel.UNKNOWN
                level(101L, 100L) shouldBe StorageSpaceLevel.UNKNOWN
            }
        }

        context("measure") {
            val dir = Files.createTempDirectory("storage-space-probe").toFile()

            fun assertUnknown(
                root: File?,
                usable: (File) -> Long = { 10 * GB },
                total: (File) -> Long = { 100 * GB },
            ) {
                val dto = StorageSpaceProbe.measure(root = root, usable = usable, total = total)
                dto.level shouldBe StorageSpaceLevel.UNKNOWN
                dto.freeBytes shouldBe null
                dto.totalBytes shouldBe null
            }

            test("no root is UNKNOWN") { assertUnknown(null) }
            test("a missing directory is UNKNOWN with the real probes, not WARNING") {
                val missing = File(dir, "does-not-exist")
                val dto = StorageSpaceProbe.measure(root = missing)
                dto.level shouldBe StorageSpaceLevel.UNKNOWN
                dto.freeBytes shouldBe null
            }
            test("a file instead of a directory is UNKNOWN") {
                val file = File(dir, "plain.txt").also { it.writeText("x") }
                assertUnknown(file)
            }
            test("total 0 is UNKNOWN") { assertUnknown(dir, total = { 0L }) }
            test("free above total is UNKNOWN") { assertUnknown(dir, usable = { 200 * GB }) }
            test("negative values are UNKNOWN") { assertUnknown(dir, usable = { -1L }) }
            test("an IOException of the probe is UNKNOWN") { assertUnknown(dir, usable = { throw IOException("/secret/path") }) }
            test("a SecurityException of the probe is UNKNOWN") { assertUnknown(dir, total = { throw SecurityException("/secret/path") }) }

            test("a fake probe yields the level and both numbers") {
                val dto = StorageSpaceProbe.measure(root = dir, usable = { 15 * GB }, total = { 100 * GB })
                dto.level shouldBe StorageSpaceLevel.HINT
                dto.freeBytes shouldBe 15 * GB
                dto.totalBytes shouldBe 100 * GB
            }

            test("sanity: a real temp directory with the real probes is measurable") {
                val dto = StorageSpaceProbe.measure(root = dir)
                (dto.level != StorageSpaceLevel.UNKNOWN) shouldBe true
                val free = dto.freeBytes!!
                val total = dto.totalBytes!!
                (free in 0..total) shouldBe true
            }

            afterSpec { dir.deleteRecursively() }
        }
    })
