package network.lapis.cloud.server.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

/** V1.9.266 -- resolver rules of `<NAME>_FILE`, driven with real temporary files. */
class SecretEnvResolverTest :
    FunSpec({
        val name = "LAPIS_DB_PASSWORD"
        val fileName = "LAPIS_DB_PASSWORD_FILE"
        val dir: Path = Files.createTempDirectory("secret-env-test")

        fun write(
            bytes: ByteArray,
            fileLabel: String = "s${System.nanoTime()}",
        ): Path = dir.resolve(fileLabel).also { Files.write(it, bytes) }

        fun resolveFile(bytes: ByteArray): String? = SecretEnv.resolve(name = name, raw = mapOf(fileName to write(bytes).toString())::get)

        fun reasonOf(block: () -> Unit): SecretFileException.Reason = shouldThrow<SecretFileException>(block).reason

        afterSpec { dir.toFile().deleteRecursively() }

        test("only N returns the value unchanged") {
            for (v in listOf("abc", " a b ", "x\n", "")) {
                SecretEnv.resolve(name = name, raw = mapOf(name to v)::get) shouldBe v
            }
            SecretEnv.resolve(name = name, raw = { null }) shouldBe null
        }

        test("only N_FILE returns the file content") {
            resolveFile("hunter2".toByteArray()) shouldBe "hunter2"
        }

        test("N and N_FILE both set is ambiguous") {
            val p = write("x".toByteArray()).toString()
            reasonOf { SecretEnv.resolve(name = name, raw = mapOf(name to "direct", fileName to p)::get) } shouldBe
                SecretFileException.Reason.AMBIGUOUS
        }

        test("empty or blank N next to N_FILE counts as not set") {
            val p = write("fromfile".toByteArray()).toString()
            SecretEnv.resolve(name = name, raw = mapOf(name to "", fileName to p)::get) shouldBe "fromfile"
            SecretEnv.resolve(name = name, raw = mapOf(name to "  ", fileName to p)::get) shouldBe "fromfile"
        }

        test("empty or blank N_FILE counts as not set") {
            SecretEnv.resolve(name = name, raw = mapOf(fileName to "")::get) shouldBe null
            SecretEnv.resolve(name = name, raw = mapOf(fileName to "   ")::get) shouldBe null
            SecretEnv.resolve(name = name, raw = mapOf(name to "direct", fileName to "")::get) shouldBe "direct"
        }

        test("missing file") {
            reasonOf { SecretEnv.resolve(name = name, raw = mapOf(fileName to dir.resolve("nope").toString())::get) } shouldBe
                SecretFileException.Reason.MISSING
        }

        test("directory is not a regular file") {
            reasonOf { SecretEnv.resolve(name = name, raw = mapOf(fileName to dir.toString())::get) } shouldBe
                SecretFileException.Reason.NOT_REGULAR
        }

        test("FIFO is rejected without hanging") {
            val fifo = dir.resolve("fifo-${System.nanoTime()}")
            val made =
                runCatching {
                    ProcessBuilder("mkfifo", fifo.toString()).start().waitFor(5, TimeUnit.SECONDS)
                }.getOrDefault(false) &&
                    Files.exists(fifo)
            if (made) {
                reasonOf { SecretEnv.resolve(name = name, raw = mapOf(fileName to fifo.toString())::get) } shouldBe
                    SecretFileException.Reason.NOT_REGULAR
            }
        }

        test("empty files and a lone newline") {
            reasonOf { resolveFile(ByteArray(0)) } shouldBe SecretFileException.Reason.EMPTY
            reasonOf { resolveFile("\n".toByteArray()) } shouldBe SecretFileException.Reason.EMPTY
            reasonOf { resolveFile("\r\n".toByteArray()) } shouldBe SecretFileException.Reason.EMPTY
        }

        test("size limit: 65536 ok, 65537 too large") {
            resolveFile(ByteArray(65_536) { 'a'.code.toByte() })!!.length shouldBe 65_536
            reasonOf { resolveFile(ByteArray(65_537) { 'a'.code.toByte() }) } shouldBe SecretFileException.Reason.TOO_LARGE
        }

        test("invalid UTF-8, BOM and NUL") {
            reasonOf { resolveFile(byteArrayOf(0xC3.toByte(), 0x28)) } shouldBe SecretFileException.Reason.NOT_UTF8
            reasonOf { resolveFile(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 'a'.code.toByte())) } shouldBe
                SecretFileException.Reason.BOM
            reasonOf { resolveFile(byteArrayOf('a'.code.toByte(), 0, 'b'.code.toByte())) } shouldBe SecretFileException.Reason.NUL
        }

        context("exactly one trailing line break is removed") {
            withData(
                listOf(
                    "a\n" to "a",
                    "a\r\n" to "a",
                    "a\n\n" to "a\n",
                    "a\r" to "a\r",
                    "a" to "a",
                    " a b " to " a b ",
                    "ä€\n" to "ä€",
                ),
            ) { (content, expected) ->
                resolveFile(content.toByteArray()) shouldBe expected
            }
        }

        test("symlink to a regular file works") {
            val target = write("viaLink".toByteArray())
            val link = dir.resolve("link-${System.nanoTime()}")
            Files.createSymbolicLink(link, target)
            SecretEnv.resolve(name = name, raw = mapOf(fileName to link.toString())::get) shouldBe "viaLink"
        }

        test("Kubernetes style chain ..data -> timestamp dir -> file works") {
            val k = Files.createTempDirectory(dir, "k8s")
            val ts = Files.createDirectory(k.resolve("..2026_11_03"))
            Files.write(ts.resolve("password"), "k8s-secret\n".toByteArray())
            Files.createSymbolicLink(k.resolve("..data"), ts.fileName)
            Files.createSymbolicLink(k.resolve("password"), Path.of("..data/password"))
            SecretEnv.resolve(name = name, raw = mapOf(fileName to k.resolve("password").toString())::get) shouldBe "k8s-secret"
        }

        test("symlink to a directory, dangling symlink") {
            val l1 = dir.resolve("dirlink-${System.nanoTime()}")
            Files.createSymbolicLink(l1, dir)
            reasonOf { SecretEnv.resolve(name = name, raw = mapOf(fileName to l1.toString())::get) } shouldBe
                SecretFileException.Reason.NOT_REGULAR
            val l2 = dir.resolve("dangling-${System.nanoTime()}")
            Files.createSymbolicLink(l2, dir.resolve("gone"))
            reasonOf { SecretEnv.resolve(name = name, raw = mapOf(fileName to l2.toString())::get) } shouldBe
                SecretFileException.Reason.MISSING
        }

        test("relative path and path with NUL") {
            reasonOf { SecretEnv.resolve(name = name, raw = mapOf(fileName to "relative/secret")::get) } shouldBe
                SecretFileException.Reason.NOT_ABSOLUTE
            reasonOf { SecretEnv.resolve(name = name, raw = mapOf(fileName to "/tmp/a\u0000b")::get) } shouldBe
                SecretFileException.Reason.INVALID_PATH
        }

        test("unreadable file (skipped for root)") {
            val p = write("x".toByteArray())
            val ok =
                runCatching { Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("---------")) }.isSuccess &&
                    !Files.isReadable(p)
            if (ok) {
                reasonOf { SecretEnv.resolve(name = name, raw = mapOf(fileName to p.toString())::get) } shouldBe
                    SecretFileException.Reason.MISSING
            }
        }

        test("a name outside SECRET_NAMES ignores X_FILE") {
            val p = write("filevalue".toByteArray()).toString()
            val resolver = SecretEnv.resolving(mapOf("LAPIS_OTHER_THING_FILE" to p, "LAPIS_OTHER_THING" to "plain")::get)
            resolver("LAPIS_OTHER_THING") shouldBe "plain"
        }

        test("resolving memoises: the file is read once per instance") {
            val p = write("once".toByteArray())
            val resolver = SecretEnv.resolving(mapOf(fileName to p.toString())::get)
            resolver(name) shouldBe "once"
            Files.write(p, "changed".toByteArray())
            resolver(name) shouldBe "once"
        }

        test("a file changed while it is read is rejected") {
            val p = write("original".toByteArray())
            val reader = SecretFileReader(afterReadHook = { Files.write(p, "tampered-and-longer".toByteArray()) })
            reasonOf { reader.read(variable = fileName, rawPath = p.toString()) } shouldBe SecretFileException.Reason.CHANGED
        }

        test("preflight names are exactly the secret list") {
            SecretEnv.SECRET_NAMES.size shouldBe 18
        }
    })
