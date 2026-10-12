package network.lapis.cloud.server.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.backup.OrganizationSchemaCatalog
import network.lapis.cloud.server.db.SourceScan
import java.io.File

/**
 * V1.9.115 -- privacy tripwires of the speaker timeline of a recording (it is derived from audio, so it is treated like the raw files):
 *
 *  1. `conference_recording_speaker_span` is read and written ONLY by `RecordingSpeakerTimelineStore` (plus its table object and the backup
 *     exclusion). Nobody else may touch it -- so there is no accidental API, export or analysis.
 *  2. No DTO or RPC interface of `lapis-shared` knows a speaker span or timeline.
 *  3. A log line of the speaker analysis, the timeline or the composer's speaker layout never names an identity, a file or a path.
 *  4. The table is excluded from the organization backup.
 */
class SpeakerTimelineAccessTripwireTest :
    FunSpec({
        val files = SourceScan.mainFiles()

        fun stripLineComments(src: String): String =
            src.lines().joinToString("\n") { line ->
                line.substringBefore("//").let {
                    if (line.trimStart().startsWith("*")) "" else it
                }
            }

        test("the scanner really sees the main sources (guards against a silently empty scan)") {
            (files.size > 100) shouldBe true
            files.any { it.name == "RecordingSpeakerTimeline.kt" } shouldBe true
        }

        test("only the store (and the table object itself) use the Exposed table, only the table object and the backup exclusion name it") {
            val users =
                files
                    .filter { "ConferenceRecordingSpeakerSpanTable" in SourceScan.blank(it.readText()) }
                    .map { it.name }
                    .toSet()
            users shouldBe setOf("RecordingSpeakerTimeline.kt", "ConferenceRecordingSpeakerSpanTable.kt")
            val namedInLiterals =
                files
                    .filter { "\"conference_recording_speaker_span\"" in it.readText() }
                    .map { it.name }
                    .toSet()
            namedInLiterals shouldBe setOf("ConferenceRecordingSpeakerSpanTable.kt", "OrganizationSchemaCatalog.kt")
        }

        test("no DTO or RPC interface of the shared module carries a speaker span or timeline") {
            val root =
                File(
                    "../lapis-shared/src/commonMain/kotlin",
                ).let { if (it.exists()) it else File("lapis-shared/src/commonMain/kotlin") }
            val shared =
                root
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" && ("/domain/" in it.path || "/rpc/" in it.path) }
                    .toList()
            (shared.size > 10) shouldBe true
            val offenders =
                shared.filter {
                    Regex(
                        "(?i)speakerSpan|speakerTimeline|speaker_span|SpeechSample",
                    ).containsMatchIn(stripLineComments(it.readText()))
                }
            offenders.map { it.name }.shouldBeEmpty()
        }

        test("log lines of the speaker analysis never name an identity, a file or a path") {
            val checked =
                setOf(
                    "RecordingSpeakerTimeline.kt",
                    "SpeechActivity.kt",
                    "SpeechActivityAnalyzer.kt",
                    "RecordingPoller.kt",
                    "FfmpegSegmentedComposer.kt",
                    "RecordingEditDecisionList.kt",
                )
            val forbidden =
                Regex("""(?i)identity|participantIdentity|fileName|file\.name|\.name\b|absolutePath|\.path\b|displayName|email""")
            val offenders = mutableListOf<String>()
            files.filter { it.name in checked }.forEach { f ->
                val lines = f.readLines()
                var i = 0
                while (i < lines.size) {
                    if (Regex("""\blogger\.(info|warn|debug|error|trace)\b""").containsMatchIn(lines[i])) {
                        val statement = StringBuilder()
                        var depth = 0
                        var j = i
                        do {
                            statement.appendLine(lines[j])
                            depth += lines[j].count { it == '{' } - lines[j].count { it == '}' }
                            j++
                        } while (depth > 0 && j < lines.size)
                        val text = statement.toString()
                        val speakerRelated = Regex("(?i)speaker|speech|timeline").containsMatchIn(text)
                        if (speakerRelated && forbidden.containsMatchIn(text)) offenders += "${f.name}:${i + 1}"
                        i = j
                    } else {
                        i++
                    }
                }
            }
            offenders.shouldBeEmpty()
        }

        test("the span table is excluded from the organization backup") {
            OrganizationSchemaCatalog.EXCLUDED_TABLES.containsKey("conference_recording_speaker_span") shouldBe true
        }
    })
