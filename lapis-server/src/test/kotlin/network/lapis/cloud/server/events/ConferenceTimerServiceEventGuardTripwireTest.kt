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
                "the tenders, the online counts, the image gallery, the dashboard, the party report, the LupaBB sync, the Protokoll draft, the Protokoll finalising, then the partner servers, then the activism module (Kilua numbers routes by declaration order)",
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
            val partnerServers = code.indexOf("registerService(IPartnerServerService::class)")
            val activism = code.indexOf("registerService(IActivismService::class)")
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
            (partnerServers > meetingProtocol) shouldBe true
            (activism > partnerServers) shouldBe true
            // a new service is always appended (V1.9.244: the partner servers after the Protokoll finalising): the timer stays thirteenth to last, the
            // agreement texts twelfth to last, the pulpit recording eleventh to last, the member finder tenth to last, the tenders ninth to
            // last, the online counts eighth to last, the image gallery seventh to last, the dashboard sixth to last, the party report fifth
            // to last, the LupaBB sync fourth to last, the Protokoll draft third to last, the Protokoll finalising second to last, the
            // partner servers last
            registrations[registrations.size - 14] shouldBe timer
            registrations[registrations.size - 13] shouldBe agreementTexts
            registrations[registrations.size - 12] shouldBe pulpitRecording
            registrations[registrations.size - 11] shouldBe memberFinder
            registrations[registrations.size - 10] shouldBe tenders
            registrations[registrations.size - 9] shouldBe onlineCounts
            registrations[registrations.size - 8] shouldBe attachedImages
            registrations[registrations.size - 7] shouldBe dashboard
            registrations[registrations.size - 6] shouldBe partyReport
            registrations[registrations.size - 5] shouldBe lupaBbSync
            registrations[registrations.size - 4] shouldBe protocolDraft
            registrations[registrations.size - 3] shouldBe meetingProtocol
            registrations[registrations.size - 2] shouldBe partnerServers
            registrations.last() shouldBe activism
        }
    })
