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
            "the tail of the registrations in Application.kt is pinned: timer, agreement texts, pulpit recording, member finder, " +
                "the tenders, the online counts, the image gallery, the dashboard, the party report, the LupaBB sync, then the Protokoll draft (Kilua numbers routes by declaration order)",
        ) {
            val code = SourceScan.blank(EncounterSourceScan.mainFile("Application.kt").readText())
            val registrations = Regex("""registerService\(""").findAll(code).map { it.range.first }.toList()
            val timer = code.indexOf("registerService(IConferenceTimerService::class)")
            val agreementTexts = code.indexOf("registerService(IAgreementTextAdminService::class)")
            val pulpitRecording = code.indexOf("registerService(IPulpitRecordingService::class)")
            val memberFinder = code.indexOf("registerService(IMemberFinderService::class)")
            val tenders = code.indexOf("registerService(ITenderService::class)")
            val onlineCounts = code.indexOf("registerService(IOnlineCountsService::class)")
            val attachedImages = code.indexOf("registerService(IAttachedImageService::class)")
            val dashboard = code.indexOf("registerService(IDashboardService::class)")
            val partyReport = code.indexOf("registerService(IPartyReportService::class)")
            val lupaBbSync = code.indexOf("registerService(ILupaBbSyncService::class)")
            val protocolDraft = code.indexOf("registerService(IProtocolDraftService::class)")
            val meetingProtocol = code.indexOf("registerService(IMeetingProtocolService::class)")
            (timer >= 0) shouldBe true
            (agreementTexts > timer) shouldBe true
            (pulpitRecording > agreementTexts) shouldBe true
            (memberFinder > pulpitRecording) shouldBe true
            (tenders > memberFinder) shouldBe true
            (onlineCounts > tenders) shouldBe true
            (attachedImages > onlineCounts) shouldBe true
            (dashboard > attachedImages) shouldBe true
            (partyReport > dashboard) shouldBe true
            (lupaBbSync > partyReport) shouldBe true
            (protocolDraft > lupaBbSync) shouldBe true
            (meetingProtocol > protocolDraft) shouldBe true
            // a new service is always appended (V1.9.240: the Protokoll finalising after the Protokoll draft): the timer stays twelfth to last, the
            // agreement texts tenth to last, the pulpit recording ninth to last, the member finder eighth to last, the tenders seventh to
            // last, the online counts sixth to last, the image gallery fifth to last, the dashboard fourth to last, the party report third
            // to last, the LupaBB sync second to last, the Protokoll draft second to last, the Protokoll finalising last
            registrations[registrations.size - 12] shouldBe timer
            registrations[registrations.size - 11] shouldBe agreementTexts
            registrations[registrations.size - 10] shouldBe pulpitRecording
            registrations[registrations.size - 9] shouldBe memberFinder
            registrations[registrations.size - 8] shouldBe tenders
            registrations[registrations.size - 7] shouldBe onlineCounts
            registrations[registrations.size - 6] shouldBe attachedImages
            registrations[registrations.size - 5] shouldBe dashboard
            registrations[registrations.size - 4] shouldBe partyReport
            registrations[registrations.size - 3] shouldBe lupaBbSync
            registrations[registrations.size - 2] shouldBe protocolDraft
            registrations.last() shouldBe meetingProtocol
        }
    })
