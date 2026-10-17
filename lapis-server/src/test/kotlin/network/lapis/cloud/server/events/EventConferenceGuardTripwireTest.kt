package network.lapis.cloud.server.events

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.db.SourceScan
import network.lapis.cloud.server.encounter.EncounterSourceScan

/**
 * Welle V1.9.113 -- tripwires that keep the ORDINARY conference RPC surface away from the conference rooms of events (a room is entered ONLY
 * through `IEventService.joinEventOnline`, where the registration rule lives). The shape is that of `EncounterRoomGuardTripwireTest`:
 *
 * 1. Every `roomId` method of `ConferenceService` is either on the "must refer to the event guard" list, fenced by the shared
 *    `requireRoomEntryAuthorization` (which refuses an event room unless a read-only caller opts in), or on the allowlist with its reason.
 *    A NEW room-id method cannot be added without a decision.
 * 2. The methods that mint a token, create a recording/stream or change who may enter really contain the guard.
 * 3. The shared gate fences by default; only the three known read-only callers opt out.
 * 4. `event_conference` is written by [EventConferenceStore] and nowhere else.
 */
class EventConferenceGuardTripwireTest :
    FunSpec({
        /** Methods that mint a token, reveal who is in, or reconfigure a room: each must refer to the event guard (or the gate that carries it). */
        val mustFence =
            mapOf(
                "joinRoom" to "EventConferenceGuard.requireNotEventRoom",
                "getGuestJoinInfo" to "EventConferenceGuard.requireNotEventRoom",
                "getRoom" to "EventConferenceGuard.requireParticipantOrModerator",
                "listParticipants" to "EventConferenceGuard.requireParticipantOrModerator",
                "renameRoom" to "EventConferenceGuard.requireNotEventRoom",
                "setRoomGuestAccess" to "EventConferenceGuard.requireNotEventRoom",
                "setRoomMeeting" to "EventConferenceGuard.requireNotEventRoom",
                "getRoomVotingState" to "EventConferenceGuard.isEventRoom",
            )

        /** method -> why it is safe without the guard. */
        val allowlist =
            mapOf(
                "leaveRoom" to "only closes the caller's OWN participation row; no token, no data about anybody else",
                "endRoom" to
                    "moderator-only (ConferenceModeratorAuthority); the moderator may end an event room early -- the event conference follows",
                "removeParticipant" to "moderator-only; removes a MEMBER from LiveKit, closes that member's participation; mints nothing",
            )

        test(
            "every room-id method of ConferenceService is fenced, listed as must-fence, or allowlisted with a reason (a new one forces a decision)",
        ) {
            val fns = EncounterSourceScan.functions(EncounterSourceScan.mainFile("rpc/ConferenceService.kt"))
            val roomIdMethods = fns.filter { it.isOverride && Regex("""\broomId\b""").containsMatchIn(it.params) }
            roomIdMethods.size shouldBeGreaterThan 8
            val undecided =
                roomIdMethods
                    .filter { fn ->
                        fn.name !in mustFence &&
                            fn.name !in allowlist &&
                            !fn.body.contains("requireRoomEntryAuthorization(") &&
                            !fn.body.contains("EventConferenceGuard")
                    }.map { it.name }
            undecided.shouldBeEmpty()
            // the allowlist and the must-fence list have no stale entries
            val names = fns.map { it.name }.toSet()
            allowlist.keys.filterNot { it in names }.shouldBeEmpty()
            mustFence.keys.filterNot { it in names }.shouldBeEmpty()
        }

        test("every must-fence method really contains its guard call") {
            val fns = EncounterSourceScan.functions(EncounterSourceScan.mainFile("rpc/ConferenceService.kt"))
            mustFence.forEach { (name, token) ->
                val fn = fns.first { it.isOverride && it.name == name }
                withClueName(name = name) { fn.body shouldContain token }
            }
        }

        test("the ordinary-room list and the lazy reconciliation never see an event room") {
            val source = EncounterSourceScan.mainFile("rpc/ConferenceService.kt").readText()
            val fns = EncounterSourceScan.functions(EncounterSourceScan.mainFile("rpc/ConferenceService.kt"))
            fns.first { it.name == "listActiveRooms" }.body shouldContain "notAnEventRoom()"
            fns.first { it.name == "reconcileActiveRooms" }.body shouldContain "notAnEventRoom()"
            fns.first { it.name == "reconcileRoomIfDue" }.body shouldContain "EventConferenceGuard.isEventRoom"
            source shouldContain "private fun notAnEventRoom()"
        }

        test("a recording, a stream and the return to the main room of a breakout all consult the event guard") {
            // V1.9.156: the recording and the stream no longer refuse an event room outright -- they ask the ONE decision function, and the plain
            // refusal (requireNotEventRoom) must be gone from exactly these two places.
            val recording =
                EncounterSourceScan
                    .functions(EncounterSourceScan.mainFile("rpc/ConferenceRecordingService.kt"))
                    .first { it.name == "startRecordingInTransaction" }
                    .body
            recording shouldContain "EventConferenceGuard.requireEventCaptureAllowed(row = room)"
            recording shouldNotContain "requireNotEventRoom"
            val stream =
                EncounterSourceScan
                    .functions(EncounterSourceScan.mainFile("rpc/ConferenceStreamingService.kt"))
                    .first { it.name == "startStreamInternal" }
                    .body
            stream shouldContain "EventConferenceGuard.requireEventCaptureAllowed(row = room)"
            stream shouldNotContain "requireNotEventRoom"
            EncounterSourceScan
                .functions(EncounterSourceScan.mainFile("rpc/ConferenceBreakoutService.kt"))
                .first { it.name == "rejoinMainRoomToken" }
                .body shouldContain "EventConferenceGuard.requireStillEntitled"
        }

        test(
            "requireEventCaptureAllowed is called from exactly startRecordingInTransaction and startStreamInternal (V1.9.156; startStream delegates to it)",
        ) {
            val callers =
                SourceScan
                    .mainFiles()
                    .flatMap { file ->
                        EncounterSourceScan.functions(file).filter { it.body.contains("requireEventCaptureAllowed(") }.map {
                            file.name to
                                it.name
                        }
                    }.sortedBy { it.second }
            callers shouldBe
                listOf(
                    "ConferenceRecordingService.kt" to "startRecordingInTransaction",
                    "ConferenceStreamingService.kt" to "startStreamInternal",
                )
            // and nothing else may bypass it: the plain refusal stays where it was for every other recording-adjacent or entering path
            val fenced =
                SourceScan
                    .mainFiles()
                    .filter { SourceScan.blank(it.readText()).contains("requireNotEventRoom(") }
                    .map { it.name }
                    .sorted()
            fenced shouldBe listOf("ConferenceService.kt", "EventConferenceGuard.kt", "MembershipGuards.kt")
        }

        test(
            "the follow-up functions of a recording and a stream (stop, pause, resume, recompute, retry, delete) neither refuse an event room nor re-decide (V1.9.156)",
        ) {
            // they only act on rows that exist; the release is frozen while the room is active, so a new decision would add nothing and a refusal would strand a running capture
            val recording = EncounterSourceScan.functions(EncounterSourceScan.mainFile("rpc/ConferenceRecordingService.kt"))
            listOf("stopRecording", "recomputeRecording", "retryRecordingComposition", "deleteRecording").forEach { name ->
                val body = recording.first { it.isOverride && it.name == name }.body
                (body.contains("requireNotEventRoom") || body.contains("requireEventCaptureAllowed")) shouldBe false
            }
            val stream = EncounterSourceScan.functions(EncounterSourceScan.mainFile("rpc/ConferenceStreamingService.kt"))
            listOf("stopStream", "pauseStream", "resumeStream").forEach { name ->
                val body = stream.first { it.isOverride && it.name == name }.body
                (body.contains("requireNotEventRoom") || body.contains("requireEventCaptureAllowed")) shouldBe false
            }
        }

        test("requireRoomEntryAuthorization fences event rooms by default; only the three known read-only callers opt out") {
            val fn =
                EncounterSourceScan.functions(EncounterSourceScan.mainFile("rpc/MembershipGuards.kt")).single {
                    it.name == "requireRoomEntryAuthorization"
                }
            fn.params shouldContain "allowEventRoom: Boolean = false"
            fn.body shouldContain "if (!allowEventRoom) EventConferenceGuard.requireNotEventRoom"
            val optOuts =
                SourceScan
                    .mainFiles()
                    .filter { it.readText().contains("allowEventRoom = true") }
                    .map { it.name }
                    .sorted()
            optOuts shouldBe listOf("ConferenceRecordingService.kt", "ConferenceService.kt", "ConferenceStreamingService.kt")
        }

        test("event_conference is written by EventConferenceStore only") {
            val writers =
                SourceScan
                    .mainFiles()
                    .filter { file ->
                        val code = SourceScan.blank(file.readText())
                        Regex("""EventConferenceTable\s*\.\s*(?:insert|update|deleteWhere|batchInsert|upsert)\b""").containsMatchIn(code) ||
                            Regex("""EventConferenceTable\.(?:update|deleteWhere)\(""").containsMatchIn(code)
                    }.map { it.name }
            writers shouldBe listOf("EventConferenceStore.kt")
        }

        test("the window is computed by EventOnlineWindow only (nobody else adds the 15-minute lead or the 60-minute trail)") {
            val offenders =
                SourceScan
                    .mainFiles()
                    .filter { it.name != "EventOnlineWindow.kt" }
                    .filter { file ->
                        val code = SourceScan.blank(file.readText())
                        code.contains("EventOnlineWindow.LEAD") || code.contains("EventOnlineWindow.TRAIL")
                    }.map { it.name }
            offenders.shouldBeEmpty()
        }
    })

private inline fun withClueName(
    name: String,
    block: () -> Unit,
) {
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$name: ${e.message}", e)
    }
}
