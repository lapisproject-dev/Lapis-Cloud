package network.lapis.cloud.server

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import java.io.File

/**
 * V1.9.263 -- source-scan tripwires of the storage-space indicator: one single measurement, ADMIN check before limiter before
 * file system, the shared limiter wired in production, the RPC at the end of the interface, no path in the log line.
 */
class StorageSpaceTripwireTest :
    FunSpec({
        val probeFile = "network/lapis/cloud/server/documents/StorageSpaceProbe.kt"

        fun main(relative: String): String = File(SourceScan.mainRoot(), relative).readText()

        fun shared(relative: String): String {
            val base =
                File(
                    "../lapis-shared/src/commonMain/kotlin",
                ).let { if (it.exists()) it else File("lapis-shared/src/commonMain/kotlin") }
            return File(base, relative).readText()
        }

        test("only StorageSpaceProbe asks the file system for free or total space") {
            val pattern =
                Regex("""\b(usableSpace|totalSpace|getUsableSpace|getTotalSpace|getFreeSpace|freeSpace|FileStore|getFileStore)\b""")
            val root = SourceScan.mainRoot()
            val offenders =
                SourceScan
                    .mainFiles()
                    .filter { pattern.containsMatchIn(SourceScan.blank(it.readText())) }
                    .map { it.relativeTo(root).path.replace(File.separatorChar, '/') }
            offenders shouldBe listOf(probeFile)
        }

        test("the probe and the reader never walk a tree or sum file sizes") {
            val forbidden = Regex("""\b(walk|walkTopDown|listFiles|Files\.size|length)\s*\(""")
            listOf(probeFile, "network/lapis/cloud/server/documents/DocumentStorageSpaceReader.kt").forEach {
                forbidden.containsMatchIn(SourceScan.blank(main(it))) shouldBe false
            }
        }

        test("Application wires the shared reader into DocumentService (no silent fallback to UNCONFIGURED)") {
            main("network/lapis/cloud/server/Application.kt")
                .contains("DocumentService(call = call, storageSpaceReader = documentStorageSpaceReader)") shouldBe true
        }

        test("storageSpace is the last method of IDocumentService") {
            val text = SourceScan.blank(shared("network/lapis/cloud/shared/rpc/IDocumentService.kt"))
            val lastFun = Regex("""suspend fun (\w+)""").findAll(text).last().groupValues[1]
            lastFun shouldBe "storageSpace"
        }

        test("DocumentService.storageSpace checks ADMIN before it touches the reader") {
            val text = SourceScan.blank(main("network/lapis/cloud/server/rpc/DocumentService.kt"))
            val body = text.substring(text.indexOf("override suspend fun storageSpace"))
            val role = body.indexOf("requireRole(AccountRole.ADMIN)")
            val reader = body.indexOf("storageSpaceReader")
            (role in 0 until reader) shouldBe true
        }

        test("the reader limits before it measures") {
            val text = SourceScan.blank(main("network/lapis/cloud/server/documents/DocumentStorageSpaceReader.kt"))
            val body = text.substring(text.indexOf("suspend fun read"))
            (body.indexOf("checkAndRecord") in 0 until body.indexOf("measure")) shouldBe true
        }

        test("the log line of the probe carries no message, path or root") {
            val raw = main(probeFile)
            val logLines = raw.lines().filter { it.contains("logger.") }
            (logLines.isNotEmpty()) shouldBe true
            logLines.forEach { line ->
                listOf("message", "path", "root", "absolute").none { line.contains(it) } shouldBe true
            }
        }
    })
