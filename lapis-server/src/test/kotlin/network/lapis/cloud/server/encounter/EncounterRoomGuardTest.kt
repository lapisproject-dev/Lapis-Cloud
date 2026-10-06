package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.conference.ConferenceNotesState
import network.lapis.cloud.server.conference.ConferenceRecordingConfig
import network.lapis.cloud.server.conference.ConferenceStreamingConfig
import network.lapis.cloud.server.conference.ConferenceWhiteboardState
import network.lapis.cloud.server.conference.LiveKitAdminException
import network.lapis.cloud.server.conference.LiveKitEgressClient
import network.lapis.cloud.server.conference.LiveKitEgressInfo
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.ConferenceBreakoutAssignmentTable
import network.lapis.cloud.server.db.generated.ConferenceBreakoutRoomTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.ConferenceStreamDestinationTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTargetTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.ConferenceBreakoutService
import network.lapis.cloud.server.rpc.ConferenceNotesService
import network.lapis.cloud.server.rpc.ConferenceRecordingService
import network.lapis.cloud.server.rpc.ConferenceService
import network.lapis.cloud.server.rpc.ConferenceStreamingService
import network.lapis.cloud.server.rpc.ConferenceWhiteboardService
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceBreakoutPlanInput
import network.lapis.cloud.shared.domain.ConferenceGuestConsentAcknowledgmentInput
import network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode
import network.lapis.cloud.shared.domain.ConferenceStreamLayout
import network.lapis.cloud.shared.domain.ConferenceStreamPlatform
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.EncounterGuestPolicy
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import network.lapis.cloud.shared.domain.NoteBlockCreateWireDto
import network.lapis.cloud.shared.domain.WhiteboardPointDto
import network.lapis.cloud.shared.domain.WhiteboardStrokeWireDto
import network.lapis.cloud.shared.domain.WhiteboardTool
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import java.util.Base64
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** A minimal egress fake: records the starts, never touches a network. */
private class FakeEgress : LiveKitEgressClient {
    val participantStarts = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
    val compositeStarts = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var counter = 0

    override suspend fun startTrackEgress(
        roomName: String,
        trackId: String,
        outputFilepathWithoutExtension: String,
    ): LiveKitEgressInfo = error("not used")

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
        compositeStarts += roomName
        return LiveKitEgressInfo(egressId = "EG_${++counter}", status = "EGRESS_STARTING")
    }

    override suspend fun startParticipantEgress(
        roomName: String,
        identity: String,
        latencyMode: ConferenceStreamLatencyMode,
        rtmpUrls: List<String>,
    ): LiveKitEgressInfo {
        participantStarts += roomName to identity
        return LiveKitEgressInfo(egressId = "EG_${++counter}", status = "EGRESS_STARTING")
    }

    override suspend fun updateStream(
        roomName: String,
        egressId: String,
        addUrls: List<String>,
        removeUrls: List<String>,
    ): LiveKitEgressInfo = error("not used")
}

private val STREAMING_CONFIG =
    ConferenceStreamingConfig.load { key ->
        when (key) {
            "LAPIS_STREAMING_ENABLED" -> "true"
            "LAPIS_SECRET_ENCRYPTION_KEY" -> Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
            "LAPIS_STREAM_MAX_DESTINATIONS" -> "2"
            else -> null
        }
    }

private val RECORDING_CONFIG = ConferenceRecordingConfig.load { key -> if (key == "LAPIS_RECORDING_ENABLED") "true" else null }

/**
 * Welle V1.9.61 -- the ORDINARY conference RPC surface must never touch an encounter SESSION. The most important cases are the two
 * that would mint a token with `canPublish = true` ([ConferenceService.joinRoom], [ConferenceBreakoutService.rejoinMainRoomToken]):
 * a congregation member (who even HOLDS a presence row, so only the guard can stop him) must never obtain a publishing token for the
 * pulpit's LiveKit room. Everything that would leave a lasting record of attendance (participant list, recording, notes, whiteboard,
 * breakout rooms) is fenced off as well. See [EncounterRoomGuard].
 */
class EncounterRoomGuardTest :
    FunSpec({
        val fx = EncounterFixtures()
        val storage = File(System.getProperty("java.io.tmpdir"), "encounter-guard-${Uuid.random()}").also { it.mkdirs() }

        beforeSpec { DatabaseConfig.connect() }
        afterSpec {
            transaction {
                val rooms =
                    ConferenceRoomTable
                        .selectAll()
                        .where {
                            ConferenceRoomTable.encounterSpaceId inList fx.spaceIds
                        }.map { it[ConferenceRoomTable.id] }
                val streams =
                    ConferenceStreamTable
                        .selectAll()
                        .where {
                            ConferenceStreamTable.roomId inList rooms
                        }.map { it[ConferenceStreamTable.id] }
                ConferenceStreamTargetTable.deleteWhere { ConferenceStreamTargetTable.streamId inList streams }
                ConferenceStreamTable.deleteWhere { ConferenceStreamTable.id inList streams }
                ConferenceStreamDestinationTable.deleteWhere { ConferenceStreamDestinationTable.createdByMemberId inList fx.memberIds }
                val breakouts =
                    ConferenceBreakoutRoomTable
                        .selectAll()
                        .where {
                            ConferenceBreakoutRoomTable.parentRoomId inList rooms
                        }.map { it[ConferenceBreakoutRoomTable.id] }
                ConferenceBreakoutAssignmentTable.deleteWhere { ConferenceBreakoutAssignmentTable.breakoutRoomId inList breakouts }
                ConferenceBreakoutRoomTable.deleteWhere { ConferenceBreakoutRoomTable.id inList breakouts }
            }
            fx.cleanUp()
            storage.deleteRecursively()
        }

        /** One open session with a pulpit, a steward and a congregation member who ALL hold a presence row. */
        class Session(
            val rig: EncounterRig,
            val space: Uuid,
            val room: Uuid,
            val pulpit: Uuid,
            val steward: Uuid,
            val member: Uuid,
            val board: Uuid,
            val admin: Uuid,
        )

        suspend fun io.ktor.server.testing.ApplicationTestBuilder.openSession(): Session {
            val rig = EncounterRig()
            val boardId = fx.createMember(role = AccountRole.BOARD)
            val adminId = fx.createMember(role = AccountRole.ADMIN)
            val pulpit = fx.createMember()
            val steward = fx.createMember()
            val member = fx.createMember()
            val space = fx.createSpace(createdBy = boardId, guestPolicy = EncounterGuestPolicy.MEMBERS_AND_GUESTS)
            fx.setRole(spaceId = space, memberId = pulpit, role = EncounterSpaceRole.PULPIT)
            fx.setRole(spaceId = space, memberId = steward, role = EncounterSpaceRole.STEWARD)
            rig.asMember(client = client, member = steward) { it.openSpace(space.toString()) }.getOrThrow()
            listOf(pulpit, steward, member).forEach { m ->
                rig.asMember(client = client, member = m) { it.enterSpace(spaceId = space.toString(), consent = null) }.getOrThrow()
            }
            return Session(rig, space, fx.openSessionRoom(space)!!, pulpit, steward, member, boardId, adminId)
        }

        fun conference(
            call: ApplicationCall,
            rig: EncounterRig,
        ) = ConferenceService(
            call = call,
            liveKitAdminClient = rig.liveKit,
            createRoomRateLimiter = LoginRateLimiter(),
            config = ENCOUNTER_CONFIG,
            conferenceMeetingBindRateLimiter = FederationInboxRateLimiter(maxRequests = 100, window = 1.minutes),
            roomVotingStateRateLimiter = FederationInboxRateLimiter(maxRequests = 100, window = 1.minutes),
        )

        fun breakout(
            call: ApplicationCall,
            rig: EncounterRig,
        ) = ConferenceBreakoutService(call = call, liveKitAdminClient = rig.liveKit, config = ENCOUNTER_CONFIG)

        test("ConferenceService: every room-id method refuses an encounter session for everybody -- no token is ever returned") {
            encounterApp {
                val s = openSession()
                val roomId = s.room.toString()
                // joinRoom would mint canPublish=true: refused for the congregation, the pulpit, the steward AND BOARD/ADMIN
                listOf(s.member, s.pulpit, s.steward, s.board, s.admin).forEach { who ->
                    client
                        .runAs(
                            member = who,
                        ) { call -> conference(call, s.rig).joinRoom(roomId = roomId, guestConsent = null) }
                        .failure<ForbiddenException>()
                    client.runAs(member = who) { call -> conference(call, s.rig).getRoom(roomId) }.failure<ForbiddenException>()
                    client.runAs(member = who) { call -> conference(call, s.rig).getGuestJoinInfo(roomId) }.failure<ForbiddenException>()
                    client.runAs(member = who) { call -> conference(call, s.rig).listParticipants(roomId) }.failure<ForbiddenException>()
                    client.runAs(member = who) { call -> conference(call, s.rig).leaveRoom(roomId) }.failure<ForbiddenException>()
                    client.runAs(member = who) { call -> conference(call, s.rig).endRoom(roomId) }.failure<ForbiddenException>()
                    client
                        .runAs(
                            member = who,
                        ) { call -> conference(call, s.rig).removeParticipant(roomId = roomId, memberId = s.member.toString()) }
                        .failure<ForbiddenException>()
                    client
                        .runAs(
                            member = who,
                        ) { call -> conference(call, s.rig).renameRoom(roomId = roomId, title = "Umbenannt") }
                        .failure<ForbiddenException>()
                    client
                        .runAs(
                            member = who,
                        ) { call -> conference(call, s.rig).setRoomGuestAccess(roomId = roomId, allowFederationGuests = true) }
                        .failure<ForbiddenException>()
                    client
                        .runAs(
                            member = who,
                        ) { call -> conference(call, s.rig).setRoomMeeting(roomId = roomId, meetingId = null) }
                        .failure<ForbiddenException>()
                    client.runAs(member = who) { call -> conference(call, s.rig).getRoomVotingState(roomId) }.failure<ForbiddenException>()
                }
                // nothing changed: the session is open, everybody is still present, the room kept its name and its guest flag
                fx.openSessionRoom(s.space) shouldBe s.room
                fx.participationCount(s.room) shouldBe 3L
                s.rig.liveKit.deletedRooms
                    .shouldBeEmpty()
                transaction {
                    ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq s.room }.single()[ConferenceRoomTable.title]
                } shouldBe "Gottesdienst"
            }
        }

        test("ConferenceService: an encounter session is not listed and the lazy reconciliation never closes it by left_at") {
            encounterApp {
                val s = openSession()
                // make the session old and let LiveKit "forget" the room: an ordinary room would now be closed lazily
                transaction {
                    ConferenceRoomTable.update({ ConferenceRoomTable.id eq s.room }) {
                        it[createdAt] =
                            LocalDateTime.parse("2020-01-01T10:00:00")
                    }
                }
                s.rig.liveKit.forgetRoom(fx.livekitName(s.room))
                val listed = client.runAs(member = s.member) { call -> conference(call, s.rig).listActiveRooms() }.getOrThrow()
                listed.none { it.id == s.room.toString() } shouldBe true
                transaction {
                    ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq s.room }.single()[ConferenceRoomTable.endedAt]
                } shouldBe null
                fx.participationCount(s.room) shouldBe 3L
            }
        }

        test(
            "ConferenceBreakoutService: every method refuses an encounter session; rejoinMainRoomToken and a breakout token never come back",
        ) {
            encounterApp {
                val s = openSession()
                val roomId = s.room.toString()
                listOf(s.member, s.pulpit, s.steward, s.board).forEach { who ->
                    client.runAs(member = who) { call -> breakout(call, s.rig).rejoinMainRoomToken(roomId) }.failure<ForbiddenException>()
                    client
                        .runAs(
                            member = who,
                        ) { call -> breakout(call, s.rig).getMyBreakoutAssignment(roomId) }
                        .failure<ForbiddenException>()
                    client
                        .runAs(member = who) { call ->
                            breakout(call, s.rig).createBreakoutRooms(roomId = roomId, plan = ConferenceBreakoutPlanInput(roomCount = 2))
                        }.failure<ForbiddenException>()
                    client
                        .runAs(
                            member = who,
                        ) { call -> breakout(call, s.rig).assignParticipants(roomId = roomId, assignments = emptyList()) }
                        .failure<ForbiddenException>()
                    client.runAs(member = who) { call -> breakout(call, s.rig).recallAll(roomId) }.failure<ForbiddenException>()
                }
                // defence in depth: even a hand-inserted breakout room + assignment cannot yield a token (it would carry canPublish=true)
                val breakoutId = Uuid.random()
                transaction {
                    ConferenceBreakoutRoomTable.insert {
                        it[ConferenceBreakoutRoomTable.id] = breakoutId
                        it[parentRoomId] = s.room
                        it[livekitRoomName] = "lc-bo-${Uuid.random()}"
                        it[label] = "Forged"
                        it[createdByMemberId] = s.steward
                        it[createdAt] = LocalDateTime.parse("2026-10-06T10:00:00")
                        it[closedAt] = null
                    }
                    ConferenceBreakoutAssignmentTable.insert {
                        it[ConferenceBreakoutAssignmentTable.id] = Uuid.random()
                        it[breakoutRoomId] = breakoutId
                        it[memberId] = s.member
                        it[assignedAt] = LocalDateTime.parse("2026-10-06T10:00:00")
                        it[recalledAt] = null
                    }
                }
                client
                    .runAs(
                        member = s.member,
                    ) { call -> breakout(call, s.rig).requestBreakoutJoinToken(breakoutId.toString()) }
                    .failure<ForbiddenException>()
            }
        }

        test("ConferenceRecordingService: an encounter session can neither be recorded nor inspected through the recording API") {
            encounterApp {
                val s = openSession()

                fun recording(call: ApplicationCall) =
                    ConferenceRecordingService(
                        call = call,
                        ffmpegAvailable = true,
                        config = ENCOUNTER_CONFIG,
                        recordingConfig = RECORDING_CONFIG,
                    )
                listOf(s.steward, s.pulpit, s.board, s.admin).forEach { who ->
                    client
                        .runAs(
                            member = who,
                        ) { call ->
                            recording(
                                call,
                            ).startRecording(roomId = s.room.toString(), accessLevel = DocumentAccessLevel.BOARD_ONLY)
                        }.failure<ForbiddenException>()
                }
                client
                    .runAs(
                        member = s.member,
                    ) { call -> recording(call).getActiveRecording(s.room.toString()) }
                    .failure<ForbiddenException>()
            }
        }

        test(
            "ConferenceNotesService/ConferenceWhiteboardService: an encounter session has no shared notes and no whiteboard, even for a person who is present",
        ) {
            encounterApp {
                val s = openSession()

                fun notes(call: ApplicationCall) =
                    ConferenceNotesService(
                        call = call,
                        documentStorageRoot = storage,
                        notesState = ConferenceNotesState(),
                        config = ENCOUNTER_CONFIG,
                    )

                fun whiteboard(call: ApplicationCall) =
                    ConferenceWhiteboardService(
                        call = call,
                        documentStorageRoot = storage,
                        whiteboardState = ConferenceWhiteboardState(),
                        config = ENCOUNTER_CONFIG,
                    )
                val roomId = s.room.toString()
                listOf(s.member, s.pulpit, s.steward, s.board).forEach { who ->
                    client.runAs(member = who) { call -> notes(call).getNotesState(roomId) }.failure<ForbiddenException>()
                    client
                        .runAs(
                            member = who,
                        ) { call ->
                            notes(
                                call,
                            ).createBlock(roomId = roomId, block = NoteBlockCreateWireDto(blockId = "b1", content = "x", position = 0))
                        }.failure<ForbiddenException>()
                    client
                        .runAs(
                            member = who,
                        ) { call -> notes(call).deleteBlock(roomId = roomId, blockId = "b1") }
                        .failure<ForbiddenException>()
                    client
                        .runAs(
                            member = who,
                        ) { call -> notes(call).saveAsDocument(roomId = roomId, accessLevel = DocumentAccessLevel.BOARD_ONLY) }
                        .failure<ForbiddenException>()
                    client.runAs(member = who) { call -> whiteboard(call).getWhiteboardState(roomId) }.failure<ForbiddenException>()
                    client.runAs(member = who) { call -> whiteboard(call).clearBoard(roomId) }.failure<ForbiddenException>()
                    client
                        .runAs(member = who) { call ->
                            whiteboard(call).commitStroke(
                                roomId = roomId,
                                stroke =
                                    WhiteboardStrokeWireDto(
                                        strokeId = "s1",
                                        tool = WhiteboardTool.ERASER,
                                        color = "#000000",
                                        strokeWidth = 2.0,
                                        points = listOf(WhiteboardPointDto(x = 10.0, y = 10.0)),
                                    ),
                            )
                        }.failure<ForbiddenException>()
                }
            }
        }

        test("ConferenceStreamingService: a stream of an encounter session shows ONLY the pulpit (SINGLE_PARTICIPANT on a PULPIT holder)") {
            encounterApp {
                val s = openSession()
                val egress = FakeEgress()

                fun streaming(call: ApplicationCall) =
                    ConferenceStreamingService(
                        call = call,
                        liveKitEgressClient = egress,
                        config = ENCOUNTER_CONFIG,
                        streamingConfig = STREAMING_CONFIG,
                        destinationRateLimiter = LoginRateLimiter(),
                        startStreamRateLimiter = LoginRateLimiter(maxFailures = 100),
                    )
                val destination =
                    client
                        .runAs(member = s.admin) { call ->
                            streaming(call).createDestination(
                                label = "Kirchen-Kanal",
                                platform = ConferenceStreamPlatform.YOUTUBE,
                                rtmpUrl = "rtmp://a.rtmp.youtube.com/live2",
                                streamKey = "test-key",
                            )
                        }.getOrThrow()
                val roomId = s.room.toString()

                suspend fun start(
                    who: Uuid,
                    layout: ConferenceStreamLayout,
                    identity: String?,
                ) = client.runAs(member = who) { call ->
                    streaming(call).startStream(
                        roomId = roomId,
                        destinationIds = listOf(destination.id),
                        layout = layout,
                        latencyMode = ConferenceStreamLatencyMode.STANDARD,
                        participantIdentity = identity,
                    )
                }

                // the congregation cannot start anything
                start(s.member, ConferenceStreamLayout.SINGLE_PARTICIPANT, s.pulpit.toString()).failure<ForbiddenException>()
                // a gallery / speaker view would put the congregation on a public platform
                start(s.steward, ConferenceStreamLayout.GRID, null).failure<ConflictException>()
                start(s.steward, ConferenceStreamLayout.SPEAKER, null).failure<ConflictException>()
                // a single participant that is not the pulpit (the congregation, a steward, nobody) is refused
                start(s.steward, ConferenceStreamLayout.SINGLE_PARTICIPANT, s.member.toString()).failure<ForbiddenException>()
                start(s.steward, ConferenceStreamLayout.SINGLE_PARTICIPANT, s.steward.toString()).failure<ForbiddenException>()
                start(s.steward, ConferenceStreamLayout.SINGLE_PARTICIPANT, "not-a-member").failure<ForbiddenException>()
                egress.participantStarts.shouldBeEmpty()
                egress.compositeStarts.shouldBeEmpty()

                val started = start(s.steward, ConferenceStreamLayout.SINGLE_PARTICIPANT, s.pulpit.toString()).getOrThrow()
                started.roomId shouldBe roomId
                egress.participantStarts shouldHaveSize 1
                egress.participantStarts.single().second shouldBe s.pulpit.toString()

                // everybody in the room may see THAT a stream is running (legal right to know), nobody else can see more
                client.runAs(member = s.member) { call -> streaming(call).getActiveStream(roomId) }.getOrThrow() shouldHaveSize 1
                // listStreamTargets: an office holder may pick targets, a plain member may not
                client.runAs(member = s.steward) { call -> streaming(call).listStreamTargets() }.getOrThrow() shouldHaveSize 1
                client.runAs(member = s.member) { call -> streaming(call).listStreamTargets() }.failure<ForbiddenException>()
            }
        }

        test("a failing LiveKit does not matter for the guard: it fires before any LiveKit call") {
            encounterApp {
                val s = openSession()
                s.rig.liveKit.failAll = true
                client
                    .runAs(
                        member = s.member,
                    ) { call -> conference(call, s.rig).joinRoom(roomId = s.room.toString(), guestConsent = null) }
                    .failure<ForbiddenException>()
                client
                    .runAs(member = s.member) { call ->
                        conference(call, s.rig).joinRoom(
                            roomId = s.room.toString(),
                            guestConsent = ConferenceGuestConsentAcknowledgmentInput(consentVersion = "x", consentSha256 = "y"),
                        )
                    }.failure<ForbiddenException>()
                // (and no LiveKitAdminException leaks out as the failure)
                LiveKitAdminException::class.simpleName shouldBe "LiveKitAdminException"
            }
        }

        test("an ORDINARY room is unaffected: the creator still moderates it, joinRoom still works, and it is still listed") {
            encounterApp {
                val rig = EncounterRig()
                val creator = fx.createMember()
                val other = fx.createMember()
                val created =
                    client
                        .runAs(member = creator) { call ->
                            conference(
                                call,
                                rig,
                            ).createRoom(
                                network.lapis.cloud.shared.domain
                                    .ConferenceRoomInput(title = "Normale Besprechung"),
                            )
                        }.getOrThrow()
                val token =
                    client
                        .runAs(
                            member = other,
                        ) { call -> conference(call, rig).joinRoom(roomId = created.id, guestConsent = null) }
                        .getOrThrow()
                videoGrantOf(token.token)["canPublish"] shouldBe true
                videoGrantOf(token.token)["canPublishData"] shouldBe true
                client
                    .runAs(
                        member = other,
                    ) { call -> conference(call, rig).renameRoom(roomId = created.id, title = "x") }
                    .failure<ForbiddenException>()
                client
                    .runAs(
                        member = creator,
                    ) { call -> conference(call, rig).renameRoom(roomId = created.id, title = "Umbenannt") }
                    .getOrThrow()
                    .title shouldBe
                    "Umbenannt"
                client
                    .runAs(
                        member = other,
                    ) { call -> conference(call, rig).listActiveRooms() }
                    .getOrThrow()
                    .any { it.id == created.id } shouldBe
                    true
                client.runAs(member = creator) { call -> conference(call, rig).endRoom(created.id) }.getOrThrow().active shouldBe false
                transaction {
                    network.lapis.cloud.server.db.generated.ConferenceParticipationTable.deleteWhere {
                        network.lapis.cloud.server.db.generated.ConferenceParticipationTable.roomId eq Uuid.parse(created.id)
                    }
                    ConferenceRoomTable.deleteWhere { ConferenceRoomTable.id eq Uuid.parse(created.id) }
                }
            }
        }
    })
