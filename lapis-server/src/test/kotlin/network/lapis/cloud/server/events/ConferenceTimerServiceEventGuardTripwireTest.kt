package network.lapis.cloud.server.events

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import network.lapis.cloud.server.db.SourceScan
import network.lapis.cloud.server.encounter.EncounterSourceScan

/**
 * V1.9.133 -- same shape as [ConferenceHandServiceEventGuardTripwireTest] for the conference-timer service: every `roomId` method decides
 * about event rooms (directly, or through the one `moderatorRoom` helper that calls the shared `authorize`), the moderator check comes
 * before the rate limit and before any state is touched, and the state / topic are used only by the expected files.
 */
class ConferenceTimerServiceEventGuardTripwireTest :
    FunSpec({
        val controlMethods = listOf("startTimer", "pauseTimer", "resumeTimer", "stopTimer", "extendTimer")

        test("all six roomId methods authorize through the shared function that carries the event guard") {
            val fns = EncounterSourceScan.functions(EncounterSourceScan.mainFile("rpc/ConferenceTimerService.kt"))
            val roomIdMethods = fns.filter { it.isOverride && Regex("""\broomId\b""").containsMatchIn(it.params) }
            roomIdMethods.size shouldBe 6
            val moderatorRoom = fns.first { it.name == "moderatorRoom" }
            moderatorRoom.body shouldContain "authorize(roomId = roomId"
            roomIdMethods
                .filter { !it.body.contains("authorize(roomId = roomId") && !it.body.contains("moderatorRoom(roomId)") }
                .map { it.name }
                .shouldBeEmpty()
            val authorize = fns.first { it.name == "authorize" }
            authorize.body shouldContain "EventConferenceGuard.requireParticipantOrModerator"
            authorize.body shouldContain "EncounterRoomGuard.isEncounterRoom"
            authorize.body shouldContain "requireConferenceEligibleMembership"
            authorize.body shouldContain "ConferenceModeratorAuthority.isModerator"
        }

        test("the moderator check comes before the rate limit, before the range checks and before any mutation") {
            val fns = EncounterSourceScan.functions(EncounterSourceScan.mainFile("rpc/ConferenceTimerService.kt"))
            fns.first { it.name == "moderatorRoom" }.body shouldContain "!room.isModerator"
            controlMethods.forEach { name ->
                val body = fns.first { it.name == name }.body
                val moderator = body.indexOf("moderatorRoom(roomId)")
                (moderator >= 0) shouldBe true
                (moderator < body.indexOf("requireWithinRate(")) shouldBe true
                (body.indexOf("requireWithinRate(") < body.indexOf("mutate(")) shouldBe true
                if ("BadRequestException" in body) (moderator < body.indexOf("BadRequestException")) shouldBe true
            }
            // reading is for present members and moderators, never a mutation
            fns.first { it.name == "timerState" }.body.contains("mutate(") shouldBe false
        }

        test(
            "the timer state is used by the service, the reconciler, the state file and Application only; the topic by the two senders and the limit",
        ) {
            val stateUsers =
                SourceScan
                    .mainFiles()
                    .filter { SourceScan.blank(it.readText()).contains("ConferenceTimerState") }
                    .map { it.name }
                    .toSet()
            stateUsers shouldBe
                setOf(
                    "ConferenceTimerState.kt",
                    "ConferenceTimerReconciler.kt",
                    "ConferenceTimerService.kt",
                    "Application.kt",
                )
            val topicUsers =
                SourceScan
                    .mainFiles()
                    .filter { SourceScan.blank(it.readText()).contains("CONFERENCE_TIMER_TOPIC") }
                    .map { it.name }
                    .toSet()
            topicUsers shouldBe setOf("ConferenceTimerReconciler.kt", "ConferenceTimerService.kt", "LiveKitAdminClient.kt")
            // the packet builder is used only by the two senders
            SourceScan
                .mainFiles()
                .filter { SourceScan.blank(it.readText()).contains("ConferenceTimerPackets") }
                .map { it.name }
                .toSet() shouldBe setOf("ConferenceTimerState.kt", "ConferenceTimerReconciler.kt", "ConferenceTimerService.kt")
        }

        test(
            "the tail of the registrations in Application.kt is pinned: timer, agreement texts, pulpit recording, then the member finder (Kilua numbers routes by declaration order)",
        ) {
            val code = SourceScan.blank(EncounterSourceScan.mainFile("Application.kt").readText())
            val registrations = Regex("""registerService\(""").findAll(code).map { it.range.first }.toList()
            val timer = code.indexOf("registerService(IConferenceTimerService::class)")
            val agreementTexts = code.indexOf("registerService(IAgreementTextAdminService::class)")
            val pulpitRecording = code.indexOf("registerService(IPulpitRecordingService::class)")
            val memberFinder = code.indexOf("registerService(IMemberFinderService::class)")
            (timer >= 0) shouldBe true
            (agreementTexts > timer) shouldBe true
            (pulpitRecording > agreementTexts) shouldBe true
            (memberFinder > pulpitRecording) shouldBe true
            // a new service is always appended: the timer stays fourth to last, the agreement texts third to last, the pulpit recording second to
            // last, the member finder last
            registrations[registrations.size - 4] shouldBe timer
            registrations[registrations.size - 3] shouldBe agreementTexts
            registrations[registrations.size - 2] shouldBe pulpitRecording
            registrations.last() shouldBe memberFinder
        }
    })
