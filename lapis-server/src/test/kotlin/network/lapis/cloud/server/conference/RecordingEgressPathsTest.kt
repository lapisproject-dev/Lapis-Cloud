package network.lapis.cloud.server.conference

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlin.uuid.Uuid

/** V1.9.110 -- the path of a Participant-Egress file is built from configuration and server-generated UUIDs only. */
class RecordingEgressPathsTest :
    FunSpec({
        val rawDir = "6d1b6b3a-2d7e-4a7c-8f63-0d7b2f5c9a11"
        val rowId = Uuid.parse("0b0f1c6e-7f3a-4a53-9c1b-6f2f0a6b5d41")

        test("the path is <containerDir>/<rawDir>/p-<rowUuid>.mp4") {
            RecordingEgressPaths.participantFile(containerDir = "/out", rawDir = rawDir, rowId = rowId) shouldBe
                "/out/$rawDir/p-$rowId.mp4"
        }

        test("a trailing slash of the container directory is normalised, nested directories are kept") {
            RecordingEgressPaths.participantFile(containerDir = "/out/", rawDir = rawDir, rowId = rowId) shouldBe
                "/out/$rawDir/p-$rowId.mp4"
            RecordingEgressPaths.participantFile(containerDir = "/mnt/egress//", rawDir = rawDir, rowId = rowId) shouldBe
                "/mnt/egress/$rawDir/p-$rowId.mp4"
        }

        test("the path contains no identity and no participant SID -- only the configuration and the two UUIDs") {
            val path = RecordingEgressPaths.participantFile(containerDir = "/out", rawDir = rawDir, rowId = rowId)
            path shouldNotContain "member"
            path shouldNotContain "PA_"
            // the file name carries exactly the row UUID
            path.substringAfterLast('/') shouldBe "p-$rowId.mp4"
        }

        test("a raw directory that is not a UUID is rejected (traversal, identity-shaped names)") {
            for (bad in listOf("../etc", "..", "", "member-1", "$rawDir/../x", "$rawDir/")) {
                shouldThrow<IllegalArgumentException> {
                    RecordingEgressPaths.participantFile(
                        containerDir = "/out",
                        rawDir = bad,
                        rowId = rowId,
                    )
                }
            }
        }

        test("a container directory that is relative, contains '..', a backslash or a control character is rejected") {
            for (bad in listOf("out", "", "/out/../etc", "/out\\x", "/out\u0000", "/out\n")) {
                shouldThrow<IllegalArgumentException> {
                    RecordingEgressPaths.participantFile(
                        containerDir = bad,
                        rawDir = rawDir,
                        rowId = rowId,
                    )
                }
            }
        }

        test("isAcceptableIdentity: member UUIDs and the usual identifier characters pass, length is bounded by the column") {
            RecordingEgressPaths.isAcceptableIdentity("0b0f1c6e-7f3a-4a53-9c1b-6f2f0a6b5d41") shouldBe true
            RecordingEgressPaths.isAcceptableIdentity("a.b_c-d@e:f") shouldBe true
            RecordingEgressPaths.isAcceptableIdentity("x".repeat(64)) shouldBe true
            RecordingEgressPaths.isAcceptableIdentity("x".repeat(65)) shouldBe false
            RecordingEgressPaths.isAcceptableIdentity("") shouldBe false
        }

        test("isAcceptableIdentity: path separators, whitespace, control characters and non-ASCII letters are refused") {
            for (bad in listOf("../x", "a/b", "a\\b", "a b", "a\nb", "a\u0000b", "mül", "a;b", "a\$b", "`x`")) {
                RecordingEgressPaths.isAcceptableIdentity(bad) shouldBe false
            }
        }
    })
