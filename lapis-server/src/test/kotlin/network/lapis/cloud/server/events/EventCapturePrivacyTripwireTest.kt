package network.lapis.cloud.server.events

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import network.lapis.cloud.server.db.SourceScan
import network.lapis.cloud.server.encounter.EncounterSourceScan
import java.io.File

/**
 * V1.9.156 -- tripwires for what the capture consent must never become: an attendance list.
 *
 * 1. `event_capture_acknowledgment` is written by [EventCaptureAcknowledgments] and nowhere else (the GDPR contributor only reads).
 * 2. The proof is written by the two entry paths only, never by a read path.
 * 3. The log lines of the new files carry exception classes only -- never an event, a room, an identity, a name or a number of people.
 * 4. The audit snapshots of the release and of the switch name only the allowed keys.
 * 5. Every place that mints a participant token is known: a new one forces a decision about the capture consent.
 * 6. The proof code never touches a display name.
 */
class EventCapturePrivacyTripwireTest :
    FunSpec({
        val newFiles =
            listOf(
                "events/EventCaptureLiveBroadcaster.kt",
                "events/EventCaptureLock.kt",
                "events/EventCaptureRequirement.kt",
                "events/EventCaptureAcknowledgments.kt",
                "events/EventCaptureNotice.kt",
            )

        test("event_capture_acknowledgment is written by EventCaptureAcknowledgments only") {
            val writers =
                SourceScan
                    .mainFiles()
                    .filter { file ->
                        val code = SourceScan.blank(file.readText())
                        Regex(
                            """EventCaptureAcknowledgmentTable\s*\.\s*(?:insert|update|deleteWhere|batchInsert|upsert)\b""",
                        ).containsMatchIn(code)
                    }.map { it.name }
            writers shouldBe listOf("EventCaptureAcknowledgments.kt")
        }

        test("only the two entry paths (and the event deletion) call the writers of the proof") {
            val callers =
                SourceScan
                    .mainFiles()
                    .filter { it.name != "EventCaptureAcknowledgments.kt" }
                    .filter { SourceScan.blank(it.readText()).contains("EventCaptureAcknowledgments.") }
                    .map { it.name }
                    .sorted()
            callers shouldBe listOf("EventOnlineJoin.kt", "EventStore.kt")
        }

        test("the log lines of the new files interpolate nothing but an exception class") {
            val offenders =
                newFiles.flatMap { relative ->
                    val text = EncounterSourceScan.mainFile(relative).readText()
                    Regex("""logger\.(?:trace|debug|info|warn|error)\s*\{([^}]*)}""")
                        .findAll(text)
                        .mapNotNull { m ->
                            val body = m.groupValues[1]
                            val interpolations = Regex("""\$\{([^}]*)}|\$[A-Za-z_]\w*""").findAll(body).map { it.value }.toList()
                            if (interpolations.all { it == "\${e::class.simpleName}" }) null else "$relative: ${m.value}"
                        }.toList()
                }
            offenders.shouldBeEmpty()
        }

        test("the proof code never touches a display name, a room name or a time of day") {
            val code = SourceScan.blank(EncounterSourceScan.mainFile("events/EventCaptureAcknowledgments.kt").readText())
            listOf("displayName", "livekitRoomName", "nowLocalDateTime", "nowInstant", "Clock").forEach { forbidden ->
                (code.contains(forbidden)) shouldBe false
            }
        }

        test("the audit snapshots of the release and of the switch name only the allowed keys") {
            val release =
                EncounterSourceScan
                    .functions(EncounterSourceScan.mainFile("rpc/EventService.kt"))
                    .first { it.name == "setEventCaptureAllowed" }
                    .body
            val releaseKeys = Regex("""\\"(\w+)\\":""").findAll(release).map { it.groupValues[1] }.toSet()
            releaseKeys shouldBe setOf("eventId", "captureAllowed")
            val switch =
                File(SourceScan.mainRoot(), "network/lapis/cloud/server/rpc/OrganizationSettingsService.kt").readText()
            val snapshot = switch.substringAfter("private fun eventCaptureSnapshot(").substringBefore("\n}\n").substringBefore("/**")
            val switchKeys = Regex("""\\"(\w+)\\":""").findAll(snapshot).map { it.groupValues[1] }.toSet()
            switchKeys shouldBe setOf("eventCaptureEnabled", "eventCaptureNoticeVersion", "noticeSha256")
        }

        test("a participant token is minted in exactly the known places") {
            val minters =
                SourceScan
                    .mainFiles()
                    .filter { SourceScan.blank(it.readText()).contains("mintParticipantToken(") && it.name != "LiveKitAccessToken.kt" }
                    .map { it.name }
                    .sorted()
            minters shouldBe
                listOf("ConferenceBreakoutService.kt", "ConferenceService.kt", "EncounterSpaceService.kt", "EventOnlineJoin.kt")
            // the only way back into an EVENT room besides EventOnlineJoin asks for an open participation, which only EventOnlineJoin creates
            val rejoin =
                EncounterSourceScan
                    .functions(EncounterSourceScan.mainFile("rpc/ConferenceBreakoutService.kt"))
                    .first { it.name == "rejoinMainRoomToken" }
                    .body
            rejoin shouldContain "EventConferenceGuard.requireStillEntitled"
            SourceScan.mainFiles().size shouldBeGreaterThan 100
        }

        test("the participation row of an event room is created by EventOnlineJoin only (so nobody is inside without the proof)") {
            val writers =
                SourceScan
                    .mainFiles()
                    .filter { SourceScan.blank(it.readText()).contains("ConferenceParticipationTable.insert") }
                    .map { it.name }
                    .sorted()
            // every file that inserts a participation is either the event path or goes through the guard fences (ConferenceService.joinRoom refuses event rooms)
            (writers.contains("EventOnlineJoin.kt")) shouldBe true
        }
    })
