package network.lapis.cloud.server.payment.fints

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * Welle V1.4.14 Wave 2 "FinTS/HBCI-Live-Kontoabruf" -- OQ-3 (PIN persistence) + [FinTsConfig
 * .passportDir]'s "MUST lie outside documentStorageRoot" posture, both verified structurally
 * rather than trusted. POSIX-only (skips on a non-POSIX filesystem, same posture every permission-
 * bearing test in this codebase applies).
 */
class FinTsPassportFileTest :
    FunSpec({
        val isPosix =
            runCatching {
                Files.getFileAttributeView(
                    File(System.getProperty("java.io.tmpdir")).toPath(),
                    java.nio.file.attribute.PosixFileAttributeView::class.java,
                ) !=
                    null
            }.getOrDefault(false)

        test("FinTsConfig.load() default passportDir is NOT nested under a typical documentStorageRoot path") {
            val config = FinTsConfig.load { null }
            val documentStorageRootMarker = "document-storage"
            // The default passportDir is derived from `user.dir`, an ENTIRELY different base than
            // `LAPIS_DOCUMENT_STORAGE_ROOT` -- this test pins that the two env vars are read
            // independently (no accidental fallback of one onto the other), not any specific path.
            config.passportDir.contains(documentStorageRootMarker) shouldBe false
        }

        test("LAPIS_FINTS_PASSPORT_DIR is honoured verbatim when set") {
            val custom = File(System.getProperty("java.io.tmpdir"), "fints-passport-test-${System.nanoTime()}").absolutePath
            val config = FinTsConfig.load { key -> if (key == "LAPIS_FINTS_PASSPORT_DIR") custom else null }
            config.passportDir shouldBe custom
        }

        // Review fix (MINOR): the three tests below used to exercise `java.nio.file.Files` directly
        // -- setting permissions themselves, then asserting on exactly the permissions they had just
        // set -- never calling `Hbci4jFinTsClient.passportFile()`, the ONLY function that actually
        // creates this directory/file in production. Now `passportFile` is `internal` (see its own
        // KDoc), so these tests call the real function and can actually fail if someone weakens it.

        fun clientWithPassportDir(dir: File): Hbci4jFinTsClient =
            Hbci4jFinTsClient(
                config = FinTsConfig.load { key -> if (key == "LAPIS_FINTS_PASSPORT_DIR") dir.absolutePath else null },
            )

        test("passportFile(): a freshly created directory/file end up with POSIX 0700/0600 permissions") {
            if (!isPosix) return@test
            val dir = File(System.getProperty("java.io.tmpdir"), "fints-passport-perm-test-${System.nanoTime()}")
            dir.exists() shouldBe false

            val file = clientWithPassportDir(dir).passportFile("test-account")

            val dirPerms = Files.getPosixFilePermissions(dir.toPath())
            dirPerms shouldBe setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
            val filePerms = Files.getPosixFilePermissions(file.toPath())
            filePerms shouldBe setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

            file.delete()
            dir.delete()
        }

        test("passportFile(): a directory pre-existing with wider (0755) permissions is narrowed to 0700") {
            // The exact gap the pre-fix version of this file could never have caught: mkdirs() only
            // runs when the directory does not exist yet, so if the narrowing were still gated on
            // that same branch, a LAPIS_FINTS_PASSPORT_DIR pre-provisioned by deployment tooling
            // under a permissive umask would silently stay wide open forever.
            if (!isPosix) return@test
            val dir = File(System.getProperty("java.io.tmpdir"), "fints-passport-preexisting-test-${System.nanoTime()}")
            dir.mkdirs()
            Files.setPosixFilePermissions(
                dir.toPath(),
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE,
                ),
            )

            clientWithPassportDir(dir).passportFile("test-account")

            Files.getPosixFilePermissions(dir.toPath()) shouldBe
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
            dir.deleteRecursively()
        }

        test("OQ-3: passportFile() never writes the PIN anywhere -- it only returns a File handle, it never touches its content") {
            // hbci4j itself is not exercised here (no live bank) -- passportFile() only resolves/
            // creates the FILE, it never writes hbci4j's actual passport bytes (that happens later,
            // inside hbci4j's own AbstractHBCIPassport.getInstance(File), entirely outside this
            // codebase's control and therefore outside what this test can assert on). What this test
            // DOES pin: a freshly created passport file is EMPTY (zero bytes), so a plaintext PIN
            // cannot possibly already be sitting in it right after passportFile() returns -- the
            // narrowest true claim this codebase can make about credential material and this file.
            if (!isPosix) return@test
            val dir = File(System.getProperty("java.io.tmpdir"), "fints-passport-pin-test-${System.nanoTime()}")

            val file = clientWithPassportDir(dir).passportFile("test-account")

            file.readBytes().size shouldBe 0

            file.delete()
            dir.delete()
        }
    })
