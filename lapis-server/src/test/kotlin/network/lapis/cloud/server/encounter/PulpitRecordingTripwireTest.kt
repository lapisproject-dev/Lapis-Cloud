package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import network.lapis.cloud.server.db.SourceScan
import java.io.File

/**
 * V1.9.143 -- source tripwires of the pulpit recording. The recording is a narrow, deliberate exception in a part of the system whose whole promise was "no
 * lasting trace of who attended"; these tests keep the exception narrow:
 *
 * 1. Only the existing recording poller and the pulpit coordinator ever start a participant FILE egress, and the coordinator takes the person only from
 *    [PulpitRecordingSources] -- never from a request parameter.
 * 2. The ordinary conference recording stays fenced off from encounter rooms.
 * 3. Every method of the pulpit service goes through the one gate (or the document authority).
 * 4. The in-memory state never touches the database; LiveKit is never called inside a transaction.
 * 5. No log line of the pulpit files names a person, an identity, a room, a space, a title or a path.
 * 6. The recording topic exists only where it must, and the client accepts it before the participant check.
 */
class PulpitRecordingTripwireTest :
    FunSpec({
        val pulpitFiles =
            listOf(
                "encounter/PulpitRecordingCoordinator.kt",
                "encounter/PulpitRecordingState.kt",
                "encounter/PulpitRecordingSources.kt",
                "encounter/PulpitRecordingDocuments.kt",
                "encounter/PulpitRecordingNotice.kt",
                "encounter/PulpitRecordingControl.kt",
                "rpc/PulpitRecordingService.kt",
            ).map { EncounterSourceScan.mainFile(it) }

        test("the scan sees the pulpit files (not vacuous)") {
            pulpitFiles.forEach { (it.exists() && it.readText().length > 200) shouldBe true }
        }

        test(
            "only the recording poller and the pulpit coordinator start a participant file egress, and the person comes from the source selection",
        ) {
            val callers =
                SourceScan
                    .mainFiles()
                    .filter { file ->
                        file.name != "LiveKitEgressClient.kt" &&
                            Regex("""\bstartParticipantFileEgress\(""").containsMatchIn(SourceScan.blank(file.readText()))
                    }.map { it.name }
                    .toSet()
            callers shouldBe setOf("RecordingPoller.kt", "PulpitRecordingCoordinator.kt")
            val coordinator = SourceScan.blank(EncounterSourceScan.mainFile("encounter/PulpitRecordingCoordinator.kt").readText())
            // every file egress is started by startEgressFor(memberId) and its argument list comes from the selection
            Regex("""startEgressFor\(target = target, memberId = it\)""").findAll(coordinator).count() shouldBe 2
            Regex("""val missing = selection\.sources\.filter""").findAll(coordinator).count() shouldBe 2
            // no identity of a request reaches the egress call
            Regex("""identity\s*=\s*memberId\.toString\(\)""").findAll(coordinator).count() shouldBe 1
        }

        test("the ordinary conference recording keeps its fence against encounter rooms") {
            val service = SourceScan.blank(EncounterSourceScan.mainFile("rpc/ConferenceRecordingService.kt").readText())
            service shouldContain "requireNotEncounterRoom"
            val guard = SourceScan.blank(EncounterSourceScan.mainFile("encounter/EncounterRoomGuard.kt").readText())
            guard shouldContain "ENCOUNTER_ROOM_UNSUPPORTED"
        }

        test(
            "every method of the pulpit service goes through the one gate or the document authority, and the gate re-checks the session is an encounter session",
        ) {
            val file = EncounterSourceScan.mainFile("rpc/PulpitRecordingService.kt")
            val methods = EncounterSourceScan.functions(file).filter { it.isOverride }
            methods.map { it.name }.toSet() shouldBe
                setOf(
                    "pulpitRecordingState",
                    "pulpitRecordingTexts",
                    "startPulpitRecording",
                    "stopPulpitRecording",
                    "confirmPulpitRecording",
                    "declinePulpitRecording",
                    "listPulpitRecordings",
                    "deletePulpitRecording",
                )
            methods.forEach { fn ->
                (fn.body.contains("officerTargetInTx(") || fn.body.contains("requireDocumentAuthority(")) shouldBe true
            }
            val gate = EncounterSourceScan.functions(file).single { it.name == "officerTargetInTx" }
            gate.body shouldContain "EncounterRoomGuard.isEncounterRoom"
            gate.body shouldContain "EncounterRoles.roleOf"
            gate.body shouldContain "throw ForbiddenException()"
        }

        test("the in-memory state never touches the database") {
            val code = SourceScan.blank(EncounterSourceScan.mainFile("encounter/PulpitRecordingState.kt").readText())
            listOf("transaction", ".insert", "Table", "exposed", "AuditLogRecorder").forEach { (it in code) shouldBe false }
        }

        test("LiveKit, the egress and the packets are never called inside a transaction block of the coordinator") {
            val code = SourceScan.blank(EncounterSourceScan.mainFile("encounter/PulpitRecordingCoordinator.kt").readText())
            SourceScan.transactionBlocks(code).forEach { block ->
                val text = code.substring(block.first, block.last + 1)
                listOf("liveKitAdminClient", "liveKitEgressClient", "broadcast(", "send(", "delay(", "withContext").forEach {
                    withClue(clue = "$it inside a transaction block") { text.contains(it) shouldBe false }
                }
            }
        }

        test("no log line of the pulpit files names a person, an identity, a room, a space, a title or a path") {
            val forbidden =
                Regex(
                    """memberId|identity|displayName|roomId|spaceId|roomName|\bpath\b|title|\.mp4|\$\{?(member|room|space|identity|name|file|path|title)""",
                    RegexOption.IGNORE_CASE,
                )
            val offenders = mutableListOf<String>()
            pulpitFiles.forEach { f ->
                f.readLines().forEachIndexed { index, line ->
                    val code = line.substringBefore("//")
                    if (code.contains("logger.") && forbidden.containsMatchIn(code.substringAfter("logger."))) {
                        offenders += "${f.name}:${index + 1}: ${line.trim()}"
                    }
                }
            }
            offenders.shouldBeEmpty()
        }

        test("the recording topic is sent only by the coordinator and named only where it must be") {
            val users =
                SourceScan
                    .mainFiles()
                    .filter {
                        Regex(
                            """\bENCOUNTER_RECORDING_TOPIC\b""",
                        ).containsMatchIn(SourceScan.blank(it.readText()))
                    }.map { it.name }
                    .toSet()
            users shouldBe setOf("PulpitRecordingCoordinator.kt", "LiveKitAdminClient.kt")
        }

        test("the client accepts the recording packet before the participant check, from the server only") {
            val session =
                listOf(File("../lapis-client/src/jsMain/kotlin"), File("lapis-client/src/jsMain/kotlin"))
                    .first { it.exists() }
                    .resolve("network/lapis/cloud/client/livekit/LiveKitRoomSession.kt")
                    .readText()
            val branch = session.indexOf("if (p3 == ENCOUNTER_RECORDING_TOPIC)")
            val participantCheck = session.indexOf("val participant = p1.unsafeCast<RemoteParticipant?>() ?: return@onOwned")
            (branch in 1 until participantCheck) shouldBe true
            session.substring(branch, participantCheck) shouldContain
                "if (fromParticipant || payload.length !in 1..ENCOUNTER_RECORDING_MAX_PAYLOAD_BYTES) return@onOwned"
        }
    })

private inline fun <T> withClue(
    clue: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }
