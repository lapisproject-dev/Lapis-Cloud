package network.lapis.cloud.server.e2e

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.conference.ConferenceStreamingConfig
import network.lapis.cloud.server.conference.DefaultSecretBallotStreamGuard
import network.lapis.cloud.server.conference.LiveKitAdminClient
import network.lapis.cloud.server.conference.LiveKitEgressClient
import network.lapis.cloud.server.conference.LiveKitEgressInfo
import network.lapis.cloud.server.conference.LiveKitParticipantInfo
import network.lapis.cloud.server.conference.LiveKitRoomInfo
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.ConferenceStreamDestinationTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTargetTable
import network.lapis.cloud.server.db.generated.ElectionBallotSelectionTable
import network.lapis.cloud.server.db.generated.ElectionBallotTable
import network.lapis.cloud.server.db.generated.ElectionBoardMemberTable
import network.lapis.cloud.server.db.generated.ElectionCandidacyTable
import network.lapis.cloud.server.db.generated.ElectionEligibleVoterTable
import network.lapis.cloud.server.db.generated.ElectionOptionTable
import network.lapis.cloud.server.db.generated.ElectionParticipationTable
import network.lapis.cloud.server.db.generated.ElectionTable
import network.lapis.cloud.server.db.generated.ElectionTallyApprovalTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.ResolutionTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.module
import network.lapis.cloud.server.rpc.ConferenceService
import network.lapis.cloud.server.rpc.ConferenceStreamingService
import network.lapis.cloud.server.rpc.ElectionService
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.ConferenceRoomInput
import network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode
import network.lapis.cloud.shared.domain.ConferenceStreamLayout
import network.lapis.cloud.shared.domain.ConferenceStreamPauseReason
import network.lapis.cloud.shared.domain.ConferenceStreamPlatform
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
import network.lapis.cloud.shared.domain.ElectionAnswer
import network.lapis.cloud.shared.domain.ElectionBallotInput
import network.lapis.cloud.shared.domain.ElectionOpenInput
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.ElectionType
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.ResolutionMode
import network.lapis.cloud.shared.domain.ResolutionStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * V1.0 Videokonferenzen, Wave 9 "Stream-Pause bei geheimen Abstimmungen" -- end-to-end journey
 * through the REAL, fully-wired [module] (mounted exactly like every other scenario in this
 * package, see [E2eSupport] KDoc), with small throwaway routes on top constructing
 * [ConferenceService]/[ConferenceStreamingService]/[ElectionService] directly with an ENABLED
 * config and fake LiveKit clients -- same "real middleware stack, elided Kilua JSON-RPC envelope"
 * idiom [ConferenceBreakoutJourneyTest]/[FederationGuestJourneyTest] already establish.
 *
 * Uses a REAL [DefaultSecretBallotStreamGuard] (backed by the SAME fake [LiveKitEgressClient]
 * instance [ConferenceStreamingService] itself uses), NOT [network.lapis.cloud.server.conference
 * .NoOpSecretBallotStreamGuard] -- the whole point of this journey is to observe the actual
 * pause/resume side effects on a real `conference_stream` row, which a no-op guard would make
 * vacuous.
 *
 * Covers the full chain: Gremium + WORKING_GROUP-scoped eligible voters -> a SCHEDULED Motion ->
 * a Konferenzraum bound to that Motion's Meeting via `setRoomMeeting` -> an ADMIN-created stream
 * destination -> `startStream` (LIVE) -> `openElection`(secret=true) + `appointElectionBoard` ->
 * `openVoting` (stream auto-PAUSES with `pauseReason == SECRET_BALLOT`) -> both eligible voters
 * cast a ballot (only possible BECAUSE the stream is verifiably quiesced,
 * `SecretBallotStreamLock.requireStreamQuiescedForBallot`) -> `closeVoting` (stream auto-RESUMES,
 * `restartCount == 1`) -> Vier-Augen `approveTally` + `tally` -> the resulting Resolution lands in
 * the Resolution Book as [ResolutionMode.DEMOCRATIC]/[ResolutionStatus.ADOPTED].
 *
 * **Production bug found + fixed while implementing this test (flagged here, not silently
 * worked around):** `network.lapis.cloud.server.rpc.restartEgressForStream` -- the shared
 * two-transaction kernel [ConferenceStreamingService.resumeStream] AND
 * [DefaultSecretBallotStreamGuard.resumeStreamsForMeeting] both delegate to -- used to call
 * `ConferenceStreamingConfig.load()` itself, reading REAL `System.getenv` regardless of which
 * (test-injected or otherwise) [ConferenceStreamingConfig] the calling instance was actually built
 * with. In this Gradle test JVM (no real `LAPIS_SECRET_ENCRYPTION_KEY` environment variable set --
 * every test injects it via [ConferenceStreamingConfig.load]'s own `env` lambda parameter instead,
 * see [ENABLED_STREAMING_CONFIG] below), that made `secretBox` silently resolve to `null` inside
 * `restartEgressForStream`, so it always WARN-logged and left the row `PAUSED`, never actually
 * restarting it -- breaking BOTH the manual moderator "resume" button and this wave's own
 * auto-resume path identically. Confirmed pre-existing and independent of this new test:
 * `network.lapis.cloud.server.rpc.ConferenceStreamingServiceTest`'s own "pauseStream then
 * resumeStream: resume mints a NEW egress id and bumps restartCount" test already failed against
 * this exact bug before the fix. Fixed by threading `streamingConfig` through as an explicit
 * parameter (every caller already has its own validated instance) instead of reloading it from
 * real env -- see `restartEgressForStream`'s own updated KDoc for the full trace.
 */
class SecretBallotStreamPauseJourneyTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdCommitteeIds = mutableListOf<Uuid>()
        val createdConferenceRoomIds = mutableListOf<Uuid>()
        val createdDestinationIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                // Conference streaming rows first -- conference_stream_target -> conference_stream
                // -> conference_room, then the destination row, mirroring
                // ConferenceStreamingServiceTest's own cleanUpConferenceStreamingTestData order.
                val streamIds =
                    ConferenceStreamTable
                        .selectAll()
                        .where { ConferenceStreamTable.roomId inList createdConferenceRoomIds }
                        .map { it[ConferenceStreamTable.id] }
                if (streamIds.isNotEmpty()) {
                    ConferenceStreamTargetTable.deleteWhere { ConferenceStreamTargetTable.streamId inList streamIds }
                    ConferenceStreamTable.deleteWhere { ConferenceStreamTable.id inList streamIds }
                }
                if (createdConferenceRoomIds.isNotEmpty()) {
                    ConferenceRoomTable.deleteWhere { ConferenceRoomTable.id inList createdConferenceRoomIds }
                }
                if (createdDestinationIds.isNotEmpty()) {
                    ConferenceStreamDestinationTable.deleteWhere { ConferenceStreamDestinationTable.id inList createdDestinationIds }
                }

                // Election family -- same cycle-breaking discipline ElectionServiceTest's own
                // cleanUpElectionTestData establishes (election<->resolution are mutually FK-linked).
                if (createdMemberIds.isNotEmpty()) {
                    AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) {
                        it[actorMemberId] = null
                    }
                }
                val meetingIds =
                    if (createdCommitteeIds.isEmpty()) {
                        emptyList()
                    } else {
                        MeetingTable.selectAll().where { MeetingTable.committeeId inList createdCommitteeIds }.map { it[MeetingTable.id] }
                    }
                val electionIds =
                    if (meetingIds.isEmpty()) {
                        emptyList()
                    } else {
                        ElectionTable.selectAll().where { ElectionTable.meetingId inList meetingIds }.map { it[ElectionTable.id] }
                    }
                if (electionIds.isNotEmpty()) {
                    ElectionTable.update({ ElectionTable.id inList electionIds }) { it[resolutionId] = null }
                    ResolutionTable.update({ ResolutionTable.electionId inList electionIds }) { it[ResolutionTable.electionId] = null }
                    val ballotIds =
                        ElectionBallotTable.selectAll().where { ElectionBallotTable.electionId inList electionIds }.map {
                            it[ElectionBallotTable.id]
                        }
                    if (ballotIds.isNotEmpty()) {
                        ElectionBallotSelectionTable.deleteWhere { ElectionBallotSelectionTable.ballotId inList ballotIds }
                    }
                    ElectionBallotTable.deleteWhere { ElectionBallotTable.electionId inList electionIds }
                    ElectionParticipationTable.deleteWhere { ElectionParticipationTable.electionId inList electionIds }
                    ElectionTallyApprovalTable.deleteWhere { ElectionTallyApprovalTable.electionId inList electionIds }
                    ElectionEligibleVoterTable.deleteWhere { ElectionEligibleVoterTable.electionId inList electionIds }
                    ElectionBoardMemberTable.deleteWhere { ElectionBoardMemberTable.electionId inList electionIds }
                    ElectionOptionTable.deleteWhere { ElectionOptionTable.electionId inList electionIds }
                    ElectionCandidacyTable.deleteWhere { ElectionCandidacyTable.electionId inList electionIds }
                    ElectionTable.deleteWhere { ElectionTable.id inList electionIds }
                }

                // Committee/Meeting/Motion/Resolution + member retirement -- shared E2E helper.
                hardDeleteGovernanceAndMembershipFixtures(committeeIds = createdCommitteeIds, memberIds = createdMemberIds)
            }
        }

        test(
            "secret Election opens -> the room's LIVE stream auto-pauses with pauseReason " +
                "SECRET_BALLOT -> ballots can only be cast once the stream is verifiably quiesced -> " +
                "closeVoting auto-resumes the stream (restartCount 1) -> Vier-Augen tally writes a " +
                "DEMOCRATIC/ADOPTED Resolution",
        ) {
            testApplication {
                val fakeEgressClient = E2e9FakeLiveKitEgressClient()
                val fakeAdminClient = E2e9FakeLiveKitAdminClient()
                val streamGuard =
                    DefaultSecretBallotStreamGuard(liveKitEgressClient = fakeEgressClient, streamingConfig = E2E9_ENABLED_STREAMING_CONFIG)

                application {
                    module()
                    routing {
                        fun conferenceService(call: ApplicationCall) =
                            ConferenceService(
                                call = call,
                                liveKitAdminClient = fakeAdminClient,
                                createRoomRateLimiter = LoginRateLimiter(),
                                config = E2E9_ENABLED_CONFERENCE_CONFIG,
                                conferenceMeetingBindRateLimiter = FederationInboxRateLimiter(),
                            )

                        fun streamingService(call: ApplicationCall) =
                            ConferenceStreamingService(
                                call = call,
                                liveKitEgressClient = fakeEgressClient,
                                config = E2E9_ENABLED_CONFERENCE_CONFIG,
                                streamingConfig = E2E9_ENABLED_STREAMING_CONFIG,
                                destinationRateLimiter = LoginRateLimiter(),
                                startStreamRateLimiter = LoginRateLimiter(),
                                mutateRateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes),
                                readRateLimiter = FederationInboxRateLimiter(maxRequests = 60, window = 1.minutes),
                            )

                        fun electionService(call: ApplicationCall) = ElectionService(call = call, streamGuard = streamGuard)

                        post("/e2e9/create-room") {
                            val room = conferenceService(call).createRoom(ConferenceRoomInput(title = "E2E Scenario 9 Sitzungsraum"))
                            call.respondText("${room.id}|${room.livekitRoomName}")
                        }
                        post("/e2e9/set-room-meeting/{roomId}/{meetingId}") {
                            conferenceService(
                                call,
                            ).setRoomMeeting(roomId = call.parameters["roomId"]!!, meetingId = call.parameters["meetingId"]!!)
                            call.respondText("OK")
                        }
                        post("/e2e9/create-destination") {
                            val dto =
                                streamingService(call).createDestination(
                                    label = "E2E9-Streaming-Ziel-${Uuid.random()}",
                                    platform = ConferenceStreamPlatform.GENERIC_RTMP,
                                    rtmpUrl = "rtmp://e2e9.example.org/live",
                                    streamKey = "e2e9-stream-key-totally-fake",
                                )
                            call.respondText(dto.id)
                        }
                        post("/e2e9/start-stream/{roomId}/{destinationId}") {
                            val dto =
                                streamingService(call).startStream(
                                    roomId = call.parameters["roomId"]!!,
                                    destinationIds = listOf(call.parameters["destinationId"]!!),
                                    layout = ConferenceStreamLayout.GRID,
                                    latencyMode = ConferenceStreamLatencyMode.STANDARD,
                                    participantIdentity = null,
                                )
                            call.respondText("${dto.id}|${dto.status}")
                        }
                        post("/e2e9/open-election/{motionId}") {
                            val w =
                                electionService(call).openElection(
                                    ElectionOpenInput(
                                        motionId = call.parameters["motionId"]!!,
                                        electionType = ElectionType.YES_NO,
                                        secret = true,
                                        tallyThreshold = 1,
                                    ),
                                )
                            call.respondText("${w.id}:${w.status}")
                        }
                        post("/e2e9/appoint-election-board/{electionId}") {
                            val memberIds = call.request.queryParameters["memberIds"]!!.split(",")
                            val list =
                                electionService(
                                    call,
                                ).appointElectionBoard(electionId = call.parameters["electionId"]!!, memberIds = memberIds)
                            call.respondText(list.size.toString())
                        }
                        post("/e2e9/open-voting/{electionId}") {
                            val w = electionService(call).openVoting(call.parameters["electionId"]!!)
                            call.respondText(w.status.name)
                        }
                        post("/e2e9/cast-ballot/{electionId}") {
                            val answer = ElectionAnswer.valueOf(call.request.queryParameters["answer"]!!)
                            val r =
                                electionService(call).castElectionBallot(
                                    ElectionBallotInput(
                                        electionId = call.parameters["electionId"]!!,
                                        answer = answer,
                                        selectedOptionIds = emptyList(),
                                    ),
                                )
                            call.respondText(r.id)
                        }
                        post("/e2e9/close-voting/{electionId}") {
                            val w = electionService(call).closeVoting(call.parameters["electionId"]!!)
                            call.respondText(w.status.name)
                        }
                        post("/e2e9/approve-tally/{electionId}") {
                            electionService(call).approveTally(call.parameters["electionId"]!!)
                            call.respondText("OK")
                        }
                        post("/e2e9/tally/{electionId}") {
                            val e = electionService(call).tally(call.parameters["electionId"]!!)
                            call.respondText("${e.winnerOptionIds.joinToString(",")}:${e.tie}:${e.majorityMet ?: ""}")
                        }
                    }
                }

                // ── Step 1: Gremium + eligible voters (a WORKING_GROUP, not GENERAL_ASSEMBLY -- ──
                // ── eligibility is exactly this Committee's own membership, deliberately excluding ──
                // ── every AKTIV member elsewhere in the shared test DB, same reasoning ────────────
                // ── ElectionServiceTest's own class KDoc gives). ──────────────────────────────────
                val voter1 = createRealMember(displayName = "E2E Scenario 9 Wähler A", email = "e2e9-voter-a-${Uuid.random()}@example.org")
                val voter2 = createRealMember(displayName = "E2E Scenario 9 Wähler B", email = "e2e9-voter-b-${Uuid.random()}@example.org")
                createdMemberIds += listOf(voter1, voter2)

                val committeeId = Uuid.random()
                val meetingId = Uuid.random()
                val motionId = Uuid.random()
                transaction {
                    CommitteeTable.insert {
                        it[id] = committeeId
                        it[name] = "E2E Scenario 9 Arbeitsgruppe"
                        it[type] = CommitteeType.WORKING_GROUP
                        it[description] = "E2E Scenario 9 -- secret Election + stream pause"
                        it[active] = true
                        it[quorumPercent] = 50
                        it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    }
                    listOf(voter1, voter2).forEach { memberId ->
                        CommitteeMembershipTable.insert {
                            it[id] = Uuid.random()
                            it[CommitteeMembershipTable.committeeId] = committeeId
                            it[CommitteeMembershipTable.memberId] = memberId
                            it[role] = CommitteeRole.MEMBER
                            it[since] = LocalDate(2020, 1, 1)
                            it[until] = null
                        }
                    }
                    // ── Step 2: a Sitzung with an already-SCHEDULED Antrag -- direct-DB shortcut, ──
                    // ── same as ElectionServiceTest's own createTerminierterMotion (openElection's ──
                    // ── own precondition is simply "a Motion in MotionStatus.SCHEDULED with a ───────
                    // ── scheduled Meeting", not a full submit/review/schedule walk). ────────────────
                    MeetingTable.insert {
                        it[id] = meetingId
                        it[MeetingTable.committeeId] = committeeId
                        it[title] = "E2E Scenario 9 Sitzung"
                        it[scheduledAt] = LocalDateTime(2026, 9, 1, 18, 0)
                        it[location] = "Vereinsheim"
                        it[format] = MeetingFormat.IN_PERSON
                        it[status] = MeetingStatus.PLANNED
                        it[calledBy] = null
                        it[calledAt] = null
                        it[chairMemberId] = null
                        it[minuteTakerMemberId] = null
                        it[protocolDocumentId] = null
                        it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    }
                    MotionTable.insert {
                        it[id] = motionId
                        it[targetCommitteeId] = committeeId
                        it[title] = "E2E Scenario 9 Geheime Abstimmung"
                        it[rationale] = "Rationale"
                        it[text] = "Motionstext"
                        it[submitterMemberId] = Uuid.parse(ADMIN_ID)
                        it[status] = MotionStatus.SCHEDULED
                        it[submittedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                        it[reviewedBy] = Uuid.parse(ADMIN_ID)
                        it[reviewedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                        it[reviewNote] = null
                        it[MotionTable.meetingId] = meetingId
                        it[agendaItemId] = null
                        it[resolutionId] = null
                        it[withdrawnAt] = null
                    }
                }
                createdCommitteeIds += committeeId

                // ── Step 3: a Konferenzraum, bound to the SAME Sitzung. ───────────────────────────
                val roomParts =
                    client.post("/e2e9/create-room") { header("X-Member-Id", ADMIN_ID) }.bodyAsText().split("|")
                val roomId = roomParts[0]
                createdConferenceRoomIds += Uuid.parse(roomId)
                client
                    .post("/e2e9/set-room-meeting/$roomId/$meetingId") { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe HttpStatusCode.OK

                // ── Step 4: ADMIN-created stream destination, then a real, LIVE stream. ──────────
                val destinationId = client.post("/e2e9/create-destination") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                createdDestinationIds += Uuid.parse(destinationId)
                val startParts =
                    client
                        .post("/e2e9/start-stream/$roomId/$destinationId") { header("X-Member-Id", ADMIN_ID) }
                        .bodyAsText()
                        .split("|")
                val streamId = Uuid.parse(startParts[0])
                startParts[1] shouldBe ConferenceStreamStatus.LIVE.name

                // ── Step 5: secret Election opens, 3-member election board appointed (ADMIN/ ─────
                // ── BOARD/TREASURER -- scene-partner privileged actors performing the ────────────
                // ── administrative Wahlvorstand role, not the journey's own protagonists, same ────
                // ── E2eSupport KDoc convention GovernanceStatusMachineJourneyTest already uses). ──
                val openResponse = client.post("/e2e9/open-election/$motionId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                val electionId = openResponse.substringBefore(":")
                openResponse.substringAfter(":") shouldBe ElectionStatus.PREPARATION.name
                client
                    .post("/e2e9/appoint-election-board/$electionId?memberIds=$ADMIN_ID,$BOARD_ID,$TREASURER_ID") {
                        header("X-Member-Id", ADMIN_ID)
                    }.bodyAsText() shouldBe "3"

                // ── Step 6: openVoting -- the D7 transaction/network-call split means the guard's ──
                // ── quiesceStreamsForMeeting call has ALREADY completed by the time this RPC ───────
                // ── response comes back (ElectionService.openVoting awaits it before returning), ───
                // ── so no polling is needed here -- see ElectionService.openVoting KDoc. ───────────
                client
                    .post("/e2e9/open-voting/$electionId") { header("X-Member-Id", ADMIN_ID) }
                    .bodyAsText() shouldBe ElectionStatus.OPEN.name
                val (statusAfterOpenVoting, pauseReasonAfterOpenVoting) = streamStatusAndPauseReasonOf(streamId)
                statusAfterOpenVoting shouldBe ConferenceStreamStatus.PAUSED
                pauseReasonAfterOpenVoting shouldBe ConferenceStreamPauseReason.SECRET_BALLOT

                // ── Step 7: both eligible voters cast a ballot -- only reachable BECAUSE the ──────
                // ── stream is verifiably PAUSED (SecretBallotStreamLock ──────────────────────────
                // ── .requireStreamQuiescedForBallot fails closed for STARTING/LIVE/PAUSING). ──────
                client
                    .post("/e2e9/cast-ballot/$electionId?answer=YES") { header("X-Member-Id", voter1.toString()) }
                    .status shouldBe HttpStatusCode.OK
                client
                    .post("/e2e9/cast-ballot/$electionId?answer=YES") { header("X-Member-Id", voter2.toString()) }
                    .status shouldBe HttpStatusCode.OK

                // ── Step 8: closeVoting -- auto-resume happens AFTER the transaction commits, ─────
                // ── again awaited synchronously before the RPC response returns. ─────────────────
                client
                    .post("/e2e9/close-voting/$electionId") { header("X-Member-Id", ADMIN_ID) }
                    .bodyAsText() shouldBe ElectionStatus.CLOSED.name
                val (statusAfterCloseVoting, restartCountAfterCloseVoting) = streamStatusAndRestartCountOf(streamId)
                statusAfterCloseVoting shouldBe ConferenceStreamStatus.LIVE
                restartCountAfterCloseVoting shouldBe 1
                streamStatusAndPauseReasonOf(streamId).second shouldBe null

                // ── Step 9: Vier-Augen tally (tallyThreshold=1 -- one named approval suffices) -- ─
                // ── 2 YES votes, no NO/ABSTAIN -> majority met, YES wins, no tie. ─────────────────
                client.post("/e2e9/approve-tally/$electionId") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.OK
                val tallyParts = client.post("/e2e9/tally/$electionId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText().split(":")
                tallyParts[1] shouldBe "false" // tie
                tallyParts[2] shouldBe "true" // majorityMet

                // ── Step 10: the resulting Resolution lands in the Resolution Book, tagged ────────
                // ── DEMOCRATIC and ADOPTED -- same assertion shape MembershipToGovernanceJourneyTest/
                // ── ElectionServiceTest already use for their own tally outcomes. ─────────────────
                val resolutionRow =
                    transaction {
                        ResolutionTable.selectAll().where { ResolutionTable.electionId eq Uuid.parse(electionId) }.single()
                    }
                transaction { resolutionRow[ResolutionTable.resolutionMode] } shouldBe ResolutionMode.DEMOCRATIC
                transaction { resolutionRow[ResolutionTable.status] } shouldBe ResolutionStatus.ADOPTED
            }
        }
    })

/** [ConferenceConfig] with `enabled=true` -- same injectable-`env` seam idiom every other conference E2E scenario in this package uses. */
private val E2E9_ENABLED_CONFERENCE_CONFIG =
    ConferenceConfig.load { key ->
        when (key) {
            "LAPIS_LIVEKIT_URL" -> "ws://localhost:7880"
            "LAPIS_LIVEKIT_API_KEY" -> "test-livekit-key"
            "LAPIS_LIVEKIT_API_SECRET" -> "test-livekit-secret-at-least-32-bytes-long!!"
            else -> null
        }
    }

/** Deterministic 32-byte test key -- never a real secret, same posture as [network.lapis.cloud.server.rpc.ConferenceStreamingServiceTest]'s own `TEST_ENCRYPTION_KEY_B64`. */
private val E2E9_TEST_ENCRYPTION_KEY_B64 =
    java.util.Base64
        .getEncoder()
        .encodeToString(ByteArray(32) { it.toByte() })

/** [ConferenceStreamingConfig] with `enabled=true` -- passed EXPLICITLY to both [ConferenceStreamingService] and [DefaultSecretBallotStreamGuard] in this file, closing exactly the `restartEgressForStream`-reloads-real-env gap this class's own KDoc documents. */
private val E2E9_ENABLED_STREAMING_CONFIG =
    ConferenceStreamingConfig.load { key ->
        when (key) {
            "LAPIS_STREAMING_ENABLED" -> "true"
            "LAPIS_SECRET_ENCRYPTION_KEY" -> E2E9_TEST_ENCRYPTION_KEY_B64
            "LAPIS_STREAM_MAX_DESTINATIONS" -> "2"
            else -> null
        }
    }

private fun streamStatusAndPauseReasonOf(streamId: Uuid): Pair<ConferenceStreamStatus, ConferenceStreamPauseReason?> =
    transaction {
        val row = ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq streamId }.single()
        row[ConferenceStreamTable.status] to row[ConferenceStreamTable.pauseReason]
    }

private fun streamStatusAndRestartCountOf(streamId: Uuid): Pair<ConferenceStreamStatus, Int> =
    transaction {
        val row = ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq streamId }.single()
        row[ConferenceStreamTable.status] to row[ConferenceStreamTable.restartCount]
    }

/**
 * Minimal, in-memory [LiveKitAdminClient] stand-in -- only [createRoom] is exercised by this
 * journey (no participant join/breakout mechanics needed), same "hermetic, no real LiveKit
 * container in this sandbox" posture [ConferenceBreakoutJourneyTest]'s own
 * `BreakoutE2eFakeLiveKitAdminClient` establishes, duplicated here rather than reused because that
 * class is `private` to its own file.
 */
private class E2e9FakeLiveKitAdminClient : LiveKitAdminClient {
    override suspend fun createRoom(
        name: String,
        maxParticipants: Int,
        emptyTimeoutSeconds: Int,
    ): LiveKitRoomInfo = LiveKitRoomInfo(sid = "RM_$name", name = name, maxParticipants = maxParticipants, numParticipants = 0)

    override suspend fun deleteRoom(name: String) = Unit

    override suspend fun listRooms(): List<LiveKitRoomInfo> = emptyList()

    override suspend fun listParticipants(room: String): List<LiveKitParticipantInfo> = emptyList()

    override suspend fun removeParticipant(
        room: String,
        identity: String,
    ) = Unit
}

/**
 * Minimal, in-memory [LiveKitEgressClient] stand-in -- same shape
 * [network.lapis.cloud.server.rpc.ConferenceStreamingServiceTest]'s own (file-private)
 * `FakeLiveKitEgressClient` establishes, duplicated here rather than reused for the same
 * `private`-to-its-own-file reason [E2e9FakeLiveKitAdminClient] documents. [listEgress] always
 * returns an empty list -- deliberately: [DefaultSecretBallotStreamGuard.quiesceStreamsForMeeting]'s
 * own confirmation loop (`awaitEgressStopped`) treats "the egress is no longer in the list" as proof
 * of a stopped egress and returns immediately, so this journey's `openVoting`/`closeVoting` calls
 * never have to wait out a real polling interval.
 */
private class E2e9FakeLiveKitEgressClient : LiveKitEgressClient {
    private var counter = 0

    override suspend fun startTrackEgress(
        roomName: String,
        trackId: String,
        outputFilepathWithoutExtension: String,
    ): LiveKitEgressInfo = error("not used by SecretBallotStreamPauseJourneyTest")

    override suspend fun stopEgress(
        roomName: String,
        egressId: String,
    ): LiveKitEgressInfo = LiveKitEgressInfo(egressId = egressId, status = "EGRESS_ENDING")

    override suspend fun listEgress(roomName: String): List<LiveKitEgressInfo> = emptyList()

    override suspend fun startRoomCompositeEgress(
        roomName: String,
        layout: ConferenceStreamLayout,
        latencyMode: ConferenceStreamLatencyMode,
        rtmpUrls: List<String>,
    ): LiveKitEgressInfo {
        counter += 1
        return LiveKitEgressInfo(egressId = "EG_e2e9_$counter", status = "EGRESS_STARTING")
    }

    override suspend fun startParticipantEgress(
        roomName: String,
        identity: String,
        latencyMode: ConferenceStreamLatencyMode,
        rtmpUrls: List<String>,
    ): LiveKitEgressInfo {
        counter += 1
        return LiveKitEgressInfo(egressId = "EG_e2e9_$counter", status = "EGRESS_STARTING")
    }

    override suspend fun updateStream(
        roomName: String,
        egressId: String,
        addUrls: List<String>,
        removeUrls: List<String>,
    ): LiveKitEgressInfo = error("not used by SecretBallotStreamPauseJourneyTest")
}
