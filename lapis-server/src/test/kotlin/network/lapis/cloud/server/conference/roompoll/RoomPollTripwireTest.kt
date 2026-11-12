package network.lapis.cloud.server.conference.roompoll

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import network.lapis.cloud.server.db.SourceScan
import network.lapis.cloud.server.db.generated.ConferenceRoomPollAnswerTable
import network.lapis.cloud.server.encounter.EncounterSourceScan
import java.io.File

private fun pollFiles(): List<File> =
    SourceScan.mainFiles().filter {
        it.path.contains("/conference/roompoll/") || it.name == "ConferenceRoomPollService.kt" || it.name == "RoomPollPersonalData.kt"
    }

private fun code(file: File) = SourceScan.blank(file.readText())

/** V1.9.304 -- source tripwires of the room polls: access gate, single writer, log privacy, anonymity, topic and set confinement, order of the RPC. */
class RoomPollTripwireTest :
    FunSpec({
        test("every method of the service runs the shared access gate before anything else of the room is touched") {
            val fns = EncounterSourceScan.functions(EncounterSourceScan.mainFile("rpc/ConferenceRoomPollService.kt"))
            val methods = fns.filter { it.isOverride }
            methods.map { it.name }.toSet() shouldBe
                setOf("startRoomPoll", "closeRoomPoll", "answerRoomPoll", "getRoomPollState", "getRoomPollVersion")
            methods.forEach { m ->
                m.body shouldContain "RoomPollAccess.audience("
                (m.body.indexOf("resolveCurrentMember") < m.body.indexOf("requireConferenceEnabled")) shouldBe true
                (m.body.indexOf("requireConferenceEnabled") < m.body.indexOf("requireWithinRate")) shouldBe true
                (m.body.indexOf("requireWithinRate") < m.body.indexOf("transaction")) shouldBe true
            }
            // the live status is read before the first poll query of an answer
            val answer = methods.first { it.name == "answerRoomPoll" }.body
            (answer.indexOf("RoomPollAccess.audience(") < answer.indexOf("ConferenceRoomPollTable")) shouldBe true
            (answer.indexOf("requireResponderStatus") < answer.indexOf("ConferenceRoomPollTable")) shouldBe true
            // rights come from the shared authorities and nowhere else
            val gate = code(EncounterSourceScan.mainFile("conference/roompoll/RoomPollAccess.kt"))
            gate shouldContain "ConferenceModeratorAuthority.isModerator"
            gate shouldContain "requireRoomEntryAuthorization"
            gate.contains("isModeratorIdentity") shouldBe false
            gate.contains("AccountRole") shouldBe false
        }

        test("the poll tables are written only by the service, the lifecycle and the DSGVO contributor") {
            val writers =
                SourceScan
                    .mainFiles()
                    .filter {
                        Regex("""ConferenceRoomPoll\w*Table\s*\.\s*(insert|update|deleteWhere|deleteAll|batchInsert)""")
                            .containsMatchIn(code(it))
                    }.map { it.name }
                    .toSet()
            writers shouldBe setOf("ConferenceRoomPollService.kt", "RoomPollLifecycle.kt", "RoomPollPersonalData.kt")
            // the answer table has exactly one writer: the service (an answer is never written by anything else)
            SourceScan
                .mainFiles()
                .filter {
                    Regex(
                        """ConferenceRoomPollAnswerTable\s*\.\s*(insert|update|deleteWhere|deleteAll)""",
                    ).containsMatchIn(code(it))
                }.map { it.name } shouldBe listOf("ConferenceRoomPollService.kt")
        }

        test("nothing about a room poll reaches a log: no question, option, member or room in any log line") {
            pollFiles().forEach { file ->
                val raw = file.readText()
                Regex("""logger\.\w+\s*(\([^)]*\))?\s*\{[^}]*}""").findAll(raw).forEach { log ->
                    Regex("""(?i)question|option|text|memberId|displayName|roomId|identity|\$""").containsMatchIn(log.value) shouldBe false
                }
            }
            // the service itself has no logger at all
            code(EncounterSourceScan.mainFile("rpc/ConferenceRoomPollService.kt")).contains("logger") shouldBe false
        }

        test("who answered and what was answered are never joined, and the answer table has no member and no time column") {
            pollFiles().forEach { file ->
                Regex("""\b(join|innerJoin|leftJoin|rightJoin|crossJoin|fullJoin)\b""").containsMatchIn(code(file)) shouldBe false
            }
            ConferenceRoomPollAnswerTable.columns
                .map { it.name }
                .filter { Regex("member|time|_at|seq|created|voted").containsMatchIn(it) }
                .shouldBeEmpty()
            // no file that reads the answers names the marker table in the same query chain: the tally file never mentions it
            code(EncounterSourceScan.mainFile("conference/roompoll/RoomPollTally.kt")).contains("Voted") shouldBe false
        }

        test("the nudge topic is used only by the nudge, the payload limit and the shared constant; no packet follows an answer") {
            SourceScan
                .mainFiles()
                .filter { code(it).contains("ROOM_POLL_TOPIC") }
                .map { it.name }
                .toSet() shouldBe setOf("RoomPollNudge.kt", "LiveKitAdminClient.kt")
            val answer =
                EncounterSourceScan.functions(EncounterSourceScan.mainFile("rpc/ConferenceRoomPollService.kt")).first {
                    it.name ==
                        "answerRoomPoll"
                }
            answer.body.contains("RoomPollNudge") shouldBe false
            answer.body.contains("nudgeRoom = null") shouldBe true
            // LiveKit is never called inside a transaction: the nudge lives only in finish(), outside every transaction block
            val finish =
                EncounterSourceScan.functions(EncounterSourceScan.mainFile("rpc/ConferenceRoomPollService.kt")).first {
                    it.name ==
                        "finish"
                }
            finish.body shouldContain "RoomPollNudge.send("
            finish.body.contains("transaction") shouldBe false
        }

        test("the room-poll sets are used only by the room-poll files; the guest sets of the other features are not used there") {
            SourceScan
                .mainFiles()
                .filter { Regex("""ROOM_POLL_(MEMBERS|GUESTS_AND_FRIENDS|RESPONDER)""").containsMatchIn(code(it)) }
                .map { it.name }
                .toSet() shouldBe setOf("RoomPollAccess.kt", "ConferenceRoomPollService.kt")
            pollFiles().forEach { file ->
                Regex("""NON_MEMBER|CONFERENCE_GUEST|isNonMember""").containsMatchIn(code(file)) shouldBe false
            }
        }

        test("the RPC interface keeps its order: new methods only at the end (Kilua numbers routes by declaration order)") {
            val source =
                File("../lapis-shared/src/commonMain/kotlin/network/lapis/cloud/shared/rpc/IConferenceRoomPollService.kt")
                    .let {
                        if (it.exists()) {
                            it
                        } else {
                            File(
                                "lapis-shared/src/commonMain/kotlin/network/lapis/cloud/shared/rpc/IConferenceRoomPollService.kt",
                            )
                        }
                    }.readText()
            Regex("""suspend fun (\w+)""").findAll(source).map { it.groupValues[1] }.toList() shouldBe
                listOf("startRoomPoll", "closeRoomPoll", "answerRoomPoll", "getRoomPollState", "getRoomPollVersion")
        }

        test("the service is the LAST registerService in Application.kt and the limiters are process-wide singletons") {
            val app = SourceScan.blank(EncounterSourceScan.mainFile("Application.kt").readText())
            val registrations = Regex("""registerService\(""").findAll(app).map { it.range.first }.toList()
            registrations.last() shouldBe app.indexOf("registerService(IConferenceRoomPollService::class)")
            listOf("roomPollWriteLimiter", "roomPollAnswerLimiter", "roomPollStateLimiter", "roomPollVersionLimiter").forEach {
                Regex("""val $it = FederationInboxRateLimiter\(""").containsMatchIn(app) shouldBe true
            }
        }
    })
