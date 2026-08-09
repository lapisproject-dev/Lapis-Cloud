package network.lapis.cloud.server.conference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * [RecordingRawFiles.resolveWithin] -- see that object's own KDoc "the single highest-value
 * security test in this wave". Every test uses a REAL temp directory tree (no mocking of the
 * filesystem) since the whole point is real `toRealPath()` symlink/existence resolution.
 */
class RecordingRawFilesTest :
    FunSpec({
        fun freshHostRawRoot() = Files.createTempDirectory("recording-raw-files-test").toFile()

        test("happy path: a plain filename inside the recording's own raw dir resolves") {
            val hostRawRoot = freshHostRawRoot()
            try {
                val recordingId = "11111111-1111-1111-1111-111111111111"
                val recordingDir = hostRawRoot.resolve(recordingId).apply { mkdirs() }
                val target = recordingDir.resolve("alice__CAMERA__TR_abc.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }

                val resolved = RecordingRawFiles.resolveWithin(hostRawRoot, recordingId, "alice__CAMERA__TR_abc.mp4")

                resolved shouldBe target.canonicalFile
            } finally {
                hostRawRoot.deleteRecursively()
            }
        }

        test("container-style absolute path (LiveKit's own file_results[].filename shape) resolves to the same basename") {
            val hostRawRoot = freshHostRawRoot()
            try {
                val recordingId = "22222222-2222-2222-2222-222222222222"
                val recordingDir = hostRawRoot.resolve(recordingId).apply { mkdirs() }
                val target = recordingDir.resolve("bob__MICROPHONE__TR_def.ogg").apply { writeBytes(byteArrayOf(4, 5)) }

                val resolved = RecordingRawFiles.resolveWithin(hostRawRoot, recordingId, "/out/$recordingId/bob__MICROPHONE__TR_def.ogg")

                resolved shouldBe target.canonicalFile
            } finally {
                hostRawRoot.deleteRecursively()
            }
        }

        test("a reportedFilename whose last path segment is literally '..' is rejected") {
            val hostRawRoot = freshHostRawRoot()
            try {
                val recordingId = "33333333-3333-3333-3333-333333333333"
                hostRawRoot.resolve(recordingId).mkdirs()

                RecordingRawFiles.resolveWithin(hostRawRoot, recordingId, "a/..") shouldBe null
            } finally {
                hostRawRoot.deleteRecursively()
            }
        }

        test("a missing file (egress has not written it yet) resolves to null, never throws") {
            val hostRawRoot = freshHostRawRoot()
            try {
                val recordingId = "44444444-4444-4444-4444-444444444444"
                hostRawRoot.resolve(recordingId).mkdirs()

                RecordingRawFiles.resolveWithin(hostRawRoot, recordingId, "never-written.mp4") shouldBe null
            } finally {
                hostRawRoot.deleteRecursively()
            }
        }

        test("a symlink inside the recording's raw dir that escapes it is rejected (symlink-planted-in-bind-mount defense)") {
            val hostRawRoot = freshHostRawRoot()
            try {
                val recordingId = "55555555-5555-5555-5555-555555555555"
                val recordingDir = hostRawRoot.resolve(recordingId).apply { mkdirs() }
                val outsideSecret = Files.createTempFile("outside-secret", ".txt").toFile().apply { writeText("do not leak me") }
                val evilLink = recordingDir.resolve("evil.mp4")
                Files.createSymbolicLink(evilLink.toPath(), outsideSecret.toPath())

                try {
                    RecordingRawFiles.resolveWithin(hostRawRoot, recordingId, "evil.mp4") shouldBe null
                } finally {
                    outsideSecret.delete()
                }
            } finally {
                hostRawRoot.deleteRecursively()
            }
        }

        test("blank reportedFilename resolves to null") {
            val hostRawRoot = freshHostRawRoot()
            try {
                val recordingId = "66666666-6666-6666-6666-666666666666"
                hostRawRoot.resolve(recordingId).mkdirs()

                RecordingRawFiles.resolveWithin(hostRawRoot, recordingId, "") shouldBe null
            } finally {
                hostRawRoot.deleteRecursively()
            }
        }

        test("a file belonging to a DIFFERENT recording's raw dir is never returned for this recordingId") {
            val hostRawRoot = freshHostRawRoot()
            try {
                val recordingIdA = "77777777-7777-7777-7777-777777777777"
                val recordingIdB = "88888888-8888-8888-8888-888888888888"
                hostRawRoot.resolve(recordingIdA).mkdirs()
                hostRawRoot
                    .resolve(recordingIdB)
                    .apply { mkdirs() }
                    .resolve("track.mp4")
                    .writeBytes(byteArrayOf(9))

                // Asking for recordingIdA's raw dir must never resolve a file that only exists
                // under recordingIdB's own raw dir.
                RecordingRawFiles.resolveWithin(hostRawRoot, recordingIdA, "track.mp4") shouldBe null
            } finally {
                hostRawRoot.deleteRecursively()
            }
        }
    })
