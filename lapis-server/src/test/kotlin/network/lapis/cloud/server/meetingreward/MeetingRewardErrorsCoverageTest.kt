package network.lapis.cloud.server.meetingreward

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import java.io.File

private val serverRoot: File =
    File("src/main/kotlin/network/lapis/cloud/server").let {
        if (it.exists()) it else File("lapis-server/src/main/kotlin/network/lapis/cloud/server")
    }
private val clientErrors: File =
    File("../lapis-client/src/jsMain/kotlin/network/lapis/cloud/client/MeetingRewardClientSupport.kt")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin/network/lapis/cloud/client/MeetingRewardClientSupport.kt") }

/**
 * V1.9.316 -- every refusal the recognition code can throw has a German text on the client (`MeetingRewardClientSupport.kt`). The scan reads the fixed
 * sentences (`const val NAME = "..."` of `MeetingRewardRules.kt`) that a `ConflictException(NAME)` throws and fails the build as soon as one has no entry on
 * the client -- before a person ever sees the generic fallback for something that has a proper sentence.
 */
class MeetingRewardErrorsCoverageTest :
    FunSpec({
        val files =
            File(serverRoot, "meetingreward").listFiles { f -> f.extension == "kt" }!!.toList() +
                File(serverRoot, "rpc/MeetingRewardService.kt")
        val rules = File(serverRoot, "meetingreward/MeetingRewardRules.kt").readText()
        val constants: Map<String, String> =
            Regex("""internal const val ([A-Z_]+) = "((?:[^"\\]|\\.)*)"""").findAll(rules).associate {
                it.groupValues[1] to
                    it.groupValues[2]
            }
        val thrown: Set<String> =
            files
                .flatMap { f ->
                    Regex(
                        """ConflictException\(\s*(?:[A-Za-z_.]+\s*\?\s*)?([A-Z_]{4,})\s*\)""",
                    ).findAll(f.readText()).map { it.groupValues[1] }.toList() +
                        Regex("""ConflictException\(\s*\n?\s*if\s*\([^)]*\)\s*([A-Z_]{4,})\s+else\s+([A-Z_]{4,})""")
                            .findAll(f.readText())
                            .flatMap { listOf(it.groupValues[1], it.groupValues[2]) }
                            .toList()
                }.toSet()
        val client = clientErrors.readText()

        test("the scan sees the constants and the throws (not vacuous)") {
            constants.size shouldBeGreaterThan 15
            thrown.size shouldBeGreaterThan 10
        }

        test("every thrown sentence has a German text on the client") {
            val missing =
                thrown
                    .mapNotNull { name -> constants[name]?.let { name to it } }
                    .filter { (_, text) -> "\"$text\"" !in client }
                    .map { it.first }
            missing.shouldBeEmpty()
        }

        test("every sentence of the unavailable reasons has a German text on the client") {
            listOf(
                "FEATURE_DISABLED",
                "COMMITTEE_NOT_ENABLED",
                "MEETING_NOT_HELD",
                "PROTOCOL_NOT_CONFIRMED",
                "ATTENDANCE_CHANGED_AFTER_PROTOCOL",
            ).filter { "\"${constants.getValue(it)}\"" !in client }.shouldBeEmpty()
        }
    })
