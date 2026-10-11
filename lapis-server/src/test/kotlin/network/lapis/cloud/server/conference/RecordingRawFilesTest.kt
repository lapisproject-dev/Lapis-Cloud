package network.lapis.cloud.server.conference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.ConferenceRecordingTrackSource
import java.nio.file.Files
import kotlin.uuid.Uuid

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

                val resolved =
                    RecordingRawFiles.resolveWithin(
                        hostRawRoot = hostRawRoot,
                        recordingId = recordingId,
                        reportedFilename = "alice__CAMERA__TR_abc.mp4",
                    )

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

                val resolved =
                    RecordingRawFiles.resolveWithin(
                        hostRawRoot = hostRawRoot,
                        recordingId = recordingId,
                        reportedFilename = "/out/$recordingId/bob__MICROPHONE__TR_def.ogg",
                    )

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

                RecordingRawFiles.resolveWithin(hostRawRoot = hostRawRoot, recordingId = recordingId, reportedFilename = "a/..") shouldBe
                    null
            } finally {
                hostRawRoot.deleteRecursively()
            }
        }

        test("a missing file (egress has not written it yet) resolves to null, never throws") {
            val hostRawRoot = freshHostRawRoot()
            try {
                val recordingId = "44444444-4444-4444-4444-444444444444"
                hostRawRoot.resolve(recordingId).mkdirs()

                RecordingRawFiles.resolveWithin(
                    hostRawRoot = hostRawRoot,
                    recordingId = recordingId,
                    reportedFilename = "never-written.mp4",
                ) shouldBe
                    null
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
                    RecordingRawFiles.resolveWithin(
                        hostRawRoot = hostRawRoot,
                        recordingId = recordingId,
                        reportedFilename = "evil.mp4",
                    ) shouldBe
                        null
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

                RecordingRawFiles.resolveWithin(hostRawRoot = hostRawRoot, recordingId = recordingId, reportedFilename = "") shouldBe null
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
                RecordingRawFiles.resolveWithin(
                    hostRawRoot = hostRawRoot,
                    recordingId = recordingIdA,
                    reportedFilename = "track.mp4",
                ) shouldBe
                    null
            } finally {
                hostRawRoot.deleteRecursively()
            }
        }

        // ── rawFilesAvailable ────────────────────────────────────────────

        val camera = ConferenceRecordingTrackSource.CAMERA
        val microphone = ConferenceRecordingTrackSource.MICROPHONE

        fun rawDirWith(
            root: java.io.File,
            vararg files: Pair<String, ByteArray>,
        ): String {
            val id = Uuid.random().toString()
            val dir = root.resolve(id).apply { mkdirs() }
            files.forEach { (name, bytes) -> dir.resolve(name).writeBytes(bytes) }
            return id
        }

        test("rawFilesAvailable: all COMPLETE tracks present and non-empty, with a video track -> true") {
            val root = freshHostRawRoot()
            try {
                val id = rawDirWith(root, "cam.mp4" to byteArrayOf(1), "mic.ogg" to byteArrayOf(2))
                RecordingRawFiles.rawFilesAvailable(
                    hostRawRoot = root,
                    rawDir = id,
                    completedTracks =
                        listOf(
                            camera to "cam.mp4",
                            microphone to "mic.ogg",
                        ),
                ) shouldBe
                    true
            } finally {
                root.deleteRecursively()
            }
        }

        test(
            "rawFilesAvailable (V1.9.110): a Participant-Egress person file (row source CAMERA) counts as the video track, with a screen share next to it",
        ) {
            val root = freshHostRawRoot()
            try {
                val person = "p-0b0f1c6e-7f3a-4a53-9c1b-6f2f0a6b5d41.mp4"
                val id = rawDirWith(root, person to byteArrayOf(1), "alice__SCREEN_SHARE__TR_s.mp4" to byteArrayOf(2))
                RecordingRawFiles.rawFilesAvailable(
                    hostRawRoot = root,
                    rawDir = id,
                    completedTracks =
                        listOf(
                            camera to person,
                            ConferenceRecordingTrackSource.SCREEN_SHARE to "alice__SCREEN_SHARE__TR_s.mp4",
                        ),
                ) shouldBe true
                // the person file resolves like every other raw file (container path -> basename below the recording's directory)
                RecordingRawFiles.resolveWithin(hostRawRoot = root, recordingId = id, reportedFilename = "/out/$id/$person") shouldBe
                    root.resolve(id).resolve(person).canonicalFile
            } finally {
                root.deleteRecursively()
            }
        }

        test("rawFilesAvailable: zero COMPLETE tracks, or no video track -> false") {
            val root = freshHostRawRoot()
            try {
                val id = rawDirWith(root, "mic.ogg" to byteArrayOf(2))
                RecordingRawFiles.rawFilesAvailable(hostRawRoot = root, rawDir = id, completedTracks = emptyList()) shouldBe false
                RecordingRawFiles.rawFilesAvailable(
                    hostRawRoot = root,
                    rawDir = id,
                    completedTracks = listOf(microphone to "mic.ogg"),
                ) shouldBe
                    false
            } finally {
                root.deleteRecursively()
            }
        }

        test("rawFilesAvailable: missing file, empty file or null fileName -> false") {
            val root = freshHostRawRoot()
            try {
                val id = rawDirWith(root, "cam.mp4" to byteArrayOf(1), "empty.ogg" to byteArrayOf())
                RecordingRawFiles.rawFilesAvailable(
                    hostRawRoot = root,
                    rawDir = id,
                    completedTracks =
                        listOf(
                            camera to "cam.mp4",
                            microphone to "gone.ogg",
                        ),
                ) shouldBe
                    false
                RecordingRawFiles.rawFilesAvailable(
                    hostRawRoot = root,
                    rawDir = id,
                    completedTracks =
                        listOf(
                            camera to "cam.mp4",
                            microphone to "empty.ogg",
                        ),
                ) shouldBe
                    false
                RecordingRawFiles.rawFilesAvailable(
                    hostRawRoot = root,
                    rawDir = id,
                    completedTracks =
                        listOf(
                            camera to "cam.mp4",
                            microphone to null,
                        ),
                ) shouldBe
                    false
            } finally {
                root.deleteRecursively()
            }
        }

        test("rawFilesAvailable: raw_dir that is not a UUID or escapes the root -> false") {
            val root = freshHostRawRoot()
            try {
                rawDirWith(root, "cam.mp4" to byteArrayOf(1))
                RecordingRawFiles.rawFilesAvailable(
                    hostRawRoot = root,
                    rawDir = "../etc",
                    completedTracks = listOf(camera to "cam.mp4"),
                ) shouldBe
                    false
                RecordingRawFiles.rawFilesAvailable(hostRawRoot = root, rawDir = "", completedTracks = listOf(camera to "cam.mp4")) shouldBe
                    false
                RecordingRawFiles.rawFilesAvailable(
                    hostRawRoot = root,
                    rawDir = "not-a-uuid",
                    completedTracks = listOf(camera to "cam.mp4"),
                ) shouldBe
                    false
            } finally {
                root.deleteRecursively()
            }
        }

        test("rawFilesAvailable: a fileName that is a symlink out of the directory -> false") {
            val root = freshHostRawRoot()
            val outside = Files.createTempFile("outside-secret", ".txt").toFile().apply { writeText("x") }
            try {
                val id = rawDirWith(root)
                Files.createSymbolicLink(root.resolve(id).resolve("cam.mp4").toPath(), outside.toPath())
                RecordingRawFiles.rawFilesAvailable(hostRawRoot = root, rawDir = id, completedTracks = listOf(camera to "cam.mp4")) shouldBe
                    false
            } finally {
                outside.delete()
                root.deleteRecursively()
            }
        }
    })
