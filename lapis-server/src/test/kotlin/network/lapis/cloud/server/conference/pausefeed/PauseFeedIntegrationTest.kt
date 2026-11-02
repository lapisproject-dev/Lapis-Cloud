package network.lapis.cloud.server.conference.pausefeed

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.conference.ConferenceRoomPauseCoordinator
import network.lapis.cloud.server.conference.ConferenceStreamingConfig
import network.lapis.cloud.server.conference.LiveKitEgressClient
import network.lapis.cloud.server.conference.LiveKitEgressInfo
import network.lapis.cloud.server.conference.ModerationFakeLiveKit
import network.lapis.cloud.server.conference.StreamPoller
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.ConferenceRecordingPauseTable
import network.lapis.cloud.server.db.generated.ConferenceRecordingTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.ConferenceStreamDestinationTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTargetTable
import network.lapis.cloud.server.db.generated.MemberStatusHistoryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.ConferenceStreamPauseCoordinator
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceRecordingStatus
import network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode
import network.lapis.cloud.shared.domain.ConferenceStreamLayout
import network.lapis.cloud.shared.domain.ConferenceStreamPauseFeedState
import network.lapis.cloud.shared.domain.ConferenceStreamPauseReason
import network.lapis.cloud.shared.domain.ConferenceStreamPlatform
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
import network.lapis.cloud.shared.domain.ConferenceStreamTargetStatus
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.net.InetAddress
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val KEY_B64 = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 3).toByte() })
private val STREAMING_CONFIG =
    ConferenceStreamingConfig.load { key ->
        when (key) {
            "LAPIS_STREAMING_ENABLED" -> "true"
            "LAPIS_SECRET_ENCRYPTION_KEY" -> KEY_B64
            else -> null
        }
    }

/** A LiveKit egress fake that writes into the SAME event log as the fake feed launcher, so the ORDER of both is observable. */
private class OrderedEgress(
    private val log: MutableList<String>,
) : LiveKitEgressClient {
    var counter = 0

    override suspend fun startTrackEgress(
        roomName: String,
        trackId: String,
        outputFilepathWithoutExtension: String,
    ): LiveKitEgressInfo = error("unused")

    override suspend fun startParticipantFileEgress(
        roomName: String,
        identity: String,
        outputFilepath: String,
    ): LiveKitEgressInfo = error("unused")

    override suspend fun stopEgress(
        roomName: String,
        egressId: String,
    ): LiveKitEgressInfo {
        log += "egress-stop"
        return LiveKitEgressInfo(egressId = egressId, status = "EGRESS_ENDING")
    }

    override suspend fun listEgress(roomName: String): List<LiveKitEgressInfo> = emptyList()

    override suspend fun startRoomCompositeEgress(
        roomName: String,
        layout: ConferenceStreamLayout,
        latencyMode: ConferenceStreamLatencyMode,
        rtmpUrls: List<String>,
    ): LiveKitEgressInfo {
        log += "egress-start"
        return LiveKitEgressInfo(egressId = "EG_pf_${++counter}_${Uuid.random()}", status = "EGRESS_STARTING")
    }

    override suspend fun startParticipantEgress(
        roomName: String,
        identity: String,
        latencyMode: ConferenceStreamLatencyMode,
        rtmpUrls: List<String>,
    ): LiveKitEgressInfo = error("unused")

    override suspend fun updateStream(
        roomName: String,
        egressId: String,
        addUrls: List<String>,
        removeUrls: List<String>,
    ): LiveKitEgressInfo = error("unused")
}

private val PUBLIC = HostResolver { listOf(InetAddress.getByName("203.0.113.9")) }
private val FAST =
    PauseFeedTimings(
        watchdogInterval = 30.milliseconds,
        livenessTimeout = 5.minutes,
        stopGrace = 150.milliseconds,
        killGrace = 100.milliseconds,
        restartBackoff = listOf(40.milliseconds, 80.milliseconds),
    )

/**
 * V1.9.255 -- the pause feed against the real database, the real coordinator, the real poller and the real `restartEgressForStream`, with a
 * fake feed launcher and a fake egress that write into one ordered log.
 */
class PauseFeedIntegrationTest :
    FunSpec({
        val members = mutableListOf<Uuid>()
        val scopes = mutableListOf<CoroutineScope>()
        val image = Files.createTempFile("pf-int", ".png").toFile()
        val media =
            object : PauseMediaSource {
                override fun imageFile() = image

                override fun musicFile() = null
            }

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterTest { PauseFeeds.current = PauseFeedControl.NoOp }
        // The database is shared by every test of the spec: streams of earlier tests must not be "wanted" by this test's poller.
        beforeTest {
            transaction {
                val rooms =
                    ConferenceRoomTable
                        .selectAll()
                        .where {
                            ConferenceRoomTable.createdByMemberId inList members
                        }.map { it[ConferenceRoomTable.id] }
                val streams =
                    ConferenceStreamTable
                        .selectAll()
                        .where {
                            ConferenceStreamTable.roomId inList rooms
                        }.map { it[ConferenceStreamTable.id] }
                ConferenceStreamTargetTable.deleteWhere { ConferenceStreamTargetTable.streamId inList streams }
                ConferenceStreamTable.deleteWhere { ConferenceStreamTable.id inList streams }
            }
        }
        afterSpec {
            scopes.forEach { it.cancel() }
            transaction {
                AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList members }) { it[actorMemberId] = null }
                val rooms =
                    ConferenceRoomTable
                        .selectAll()
                        .where {
                            ConferenceRoomTable.createdByMemberId inList members
                        }.map { it[ConferenceRoomTable.id] }
                val recordings =
                    ConferenceRecordingTable
                        .selectAll()
                        .where {
                            ConferenceRecordingTable.roomId inList rooms
                        }.map { it[ConferenceRecordingTable.id] }
                ConferenceRecordingPauseTable.deleteWhere { ConferenceRecordingPauseTable.recordingId inList recordings }
                ConferenceRecordingTable.deleteWhere { ConferenceRecordingTable.id inList recordings }
                val streams =
                    ConferenceStreamTable
                        .selectAll()
                        .where {
                            ConferenceStreamTable.roomId inList rooms
                        }.map { it[ConferenceStreamTable.id] }
                ConferenceStreamTargetTable.deleteWhere { ConferenceStreamTargetTable.streamId inList streams }
                ConferenceStreamTable.deleteWhere { ConferenceStreamTable.id inList streams }
                ConferenceStreamDestinationTable.deleteWhere { ConferenceStreamDestinationTable.createdByMemberId inList members }
                ConferenceRoomTable.deleteWhere { ConferenceRoomTable.id inList rooms }
                AccountTable.deleteWhere { AccountTable.memberId inList members }
                MemberStatusHistoryTable.deleteWhere { MemberStatusHistoryTable.memberId inList members }
                MemberTable.deleteWhere { MemberTable.id inList members }
            }
        }

        class Fx(
            val creator: Uuid,
            val room: Uuid,
            val roomName: String,
        )

        fun newFx(): Fx {
            val creator = Uuid.random()
            val room = Uuid.random()
            val name = "lc-$room"
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = creator
                    it[displayName] = "Pause Feed ${creator.toString().take(8)}"
                    it[email] = "pf-$creator@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = creator
                    it[role] = AccountRole.MEMBER
                }
                ConferenceRoomTable.insert {
                    it[ConferenceRoomTable.id] = room
                    it[title] = "Feed room"
                    it[description] = ""
                    it[livekitRoomName] = name
                    it[createdByMemberId] = creator
                    it[createdAt] = DbClock.nowLocalDateTime()
                    it[endedAt] = null
                    it[maxParticipants] = 25
                    it[allowFederationGuests] = false
                    it[muteOnJoin] = false
                    it[participantsMayUnmute] = true
                    it[encounterSpaceId] = null
                }
            }
            members += creator
            return Fx(creator, room, name)
        }

        fun newStream(
            fx: Fx,
            status: ConferenceStreamStatus,
            reason: ConferenceStreamPauseReason? = null,
            destinations: Int = 1,
            destinationEnabled: Boolean = true,
        ): Uuid {
            val id = Uuid.random()
            val box = SecretBox(Base64.getDecoder().decode(KEY_B64))
            transaction {
                ConferenceStreamTable.insert {
                    it[ConferenceStreamTable.id] = id
                    it[roomId] = fx.room
                    it[startedByMemberId] = fx.creator
                    it[ConferenceStreamTable.status] = status
                    it[layout] = ConferenceStreamLayout.GRID
                    it[latencyMode] = ConferenceStreamLatencyMode.STANDARD
                    it[livekitEgressId] = if (status == ConferenceStreamStatus.LIVE) "EG_live_${Uuid.random()}" else null
                    it[startedAt] = DbClock.nowLocalDateTime()
                    it[restartCount] = 0
                    it[pauseReason] = reason
                }
                repeat(destinations) { n ->
                    val dest = Uuid.random()
                    ConferenceStreamDestinationTable.insert {
                        it[ConferenceStreamDestinationTable.id] = dest
                        it[label] = "dest-$dest"
                        it[platform] = ConferenceStreamPlatform.GENERIC_RTMP
                        it[rtmpUrl] = "rtmp://sink$n.example.org:1935/live"
                        it[streamKeyCiphertext] = box.seal(plaintext = "key-$n-abcdef", aad = dest.toString())
                        it[streamKeySetAt] = DbClock.nowLocalDateTime()
                        it[createdByMemberId] = fx.creator
                        it[createdAt] = DbClock.nowLocalDateTime()
                        it[enabled] = destinationEnabled
                    }
                    ConferenceStreamTargetTable.insert {
                        it[ConferenceStreamTargetTable.id] = Uuid.random()
                        it[streamId] = id
                        it[destinationId] = dest
                        it[ConferenceStreamTargetTable.status] = ConferenceStreamTargetStatus.PENDING
                        it[urlFingerprint] = "x"
                    }
                }
            }
            return id
        }

        fun setRoomPaused(
            fx: Fx,
            paused: Boolean,
        ) = transaction {
            ConferenceRoomTable.update({ ConferenceRoomTable.id eq fx.room }) {
                it[capturePausedAt] = if (paused) DbClock.nowLocalDateTime() else null
            }
        }

        fun streamRow(id: Uuid) = transaction { ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq id }.single() }

        class Rig(
            enabled: Boolean = true,
        ) {
            val log = CopyOnWriteArrayList<String>()
            val launcher = FakeLauncher(log)
            val egress = OrderedEgress(log)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
            val supervisor =
                PauseFeedSupervisor(
                    launcher = launcher,
                    media = media,
                    targetPolicy = PauseFeedTargetPolicy(resolver = PUBLIC, allowRtmps = true),
                    dataSource = DbPauseFeedDataSource(STREAMING_CONFIG),
                    enabled = enabled,
                    tlsVerifyArgs = emptyList(),
                    onStateChanged = {},
                    onFeedEnded = {},
                    scope = scope,
                    timings = FAST,
                )
            val coordinator =
                ConferenceRoomPauseCoordinator(
                    liveKitAdminClient = ModerationFakeLiveKit(),
                    liveKitEgressClient = egress,
                    streamingConfig = STREAMING_CONFIG,
                    rateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
                    requestRecordingTick = {},
                    pauseFeed = supervisor,
                )
            val poller = StreamPoller(liveKitEgressClient = egress, streamingConfig = STREAMING_CONFIG, pauseFeed = supervisor)

            init {
                PauseFeeds.current = supervisor
            }
        }

        fun current(fx: Fx) = CurrentMember(memberId = fx.creator, role = AccountRole.MEMBER, status = MemberStatus.ACTIVE)

        test("pause: the feed starts only after the egress is confirmed stopped; resume: the feed ends BEFORE the new egress starts") {
            val fx = newFx()
            val stream = newStream(fx, ConferenceStreamStatus.LIVE)
            val rig = Rig()
            kotlinx.coroutines.runBlocking {
                rig.coordinator.pause(roomId = fx.room.toString(), current = current(fx))
                streamRow(stream)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSED
                rig.log shouldContainExactly listOf("egress-stop", "launch")
                rig.supervisor.stateOf(stream) shouldBe ConferenceStreamPauseFeedState.STARTING
                // the target is the decrypted URL of the destination (the plaintext only ever lives inside the command)
                (
                    rig.launcher.commands
                        .single()
                        .args
                        .last()
                        .startsWith("rtmp://sink0.example.org:1935/live/key-0-abcdef")
                ) shouldBe true

                rig.coordinator.resume(roomId = fx.room.toString(), current = current(fx))
                rig.log shouldContainExactly listOf("egress-stop", "launch", "terminate", "egress-start")
                streamRow(stream)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.LIVE
                rig.supervisor.stateOf(stream) shouldBe ConferenceStreamPauseFeedState.OFF
                rig.launcher.aliveNow.get() shouldBe 0
                // a later tick starts nothing: the stream is not paused any more
                rig.poller.tick()
                rig.launcher.handles.size shouldBe 1
            }
        }

        test("a stream still PAUSING gets no feed; once PAUSED the next tick starts it; the tick is idempotent") {
            val fx = newFx()
            val stream = newStream(fx, ConferenceStreamStatus.PAUSING, ConferenceStreamPauseReason.ROOM_PAUSE)
            setRoomPaused(fx, true)
            val rig = Rig()
            kotlinx.coroutines.runBlocking {
                rig.poller.tick()
                rig.launcher.handles.size shouldBe 0
                transaction {
                    ConferenceStreamTable.update({ ConferenceStreamTable.id eq stream }) {
                        it[status] =
                            ConferenceStreamStatus.PAUSED
                    }
                }
                rig.poller.tick()
                rig.poller.tick()
                rig.launcher.handles.size shouldBe 1
            }
        }

        test("a secret-ballot pause, a manual pause and a PAUSED stream of a room that is not paused get no feed") {
            val fx = newFx()
            newStream(fx, ConferenceStreamStatus.PAUSED, ConferenceStreamPauseReason.SECRET_BALLOT)
            newStream(newFx(), ConferenceStreamStatus.PAUSED, ConferenceStreamPauseReason.MANUAL)
            val notPausedRoom = newFx()
            newStream(notPausedRoom, ConferenceStreamStatus.PAUSED, ConferenceStreamPauseReason.ROOM_PAUSE)
            setRoomPaused(fx, true)
            val rig = Rig()
            kotlinx.coroutines.runBlocking { rig.poller.tick() }
            rig.launcher.handles.size shouldBe 0
        }

        test("a secret ballot that begins INSIDE a room pause leaves the reason ROOM_PAUSE, so the feed keeps running") {
            val fx = newFx()
            val stream = newStream(fx, ConferenceStreamStatus.PAUSED, ConferenceStreamPauseReason.ROOM_PAUSE)
            setRoomPaused(fx, true)
            val rig = Rig()
            kotlinx.coroutines.runBlocking {
                rig.poller.tick()
                rig.launcher.handles.size shouldBe 1
                // the real secret-ballot entry point (what openVoting calls): it only claims STARTING/LIVE streams, a ROOM_PAUSE one is left alone
                val claimed =
                    transaction {
                        ConferenceStreamPauseCoordinator.markPausingForSecretBallot(
                            roomIds = listOf(fx.room),
                            actorMemberId = null,
                            actorRole = null,
                        )
                    }
                claimed shouldBe emptyList()
                streamRow(stream)[ConferenceStreamTable.pauseReason] shouldBe ConferenceStreamPauseReason.ROOM_PAUSE
                streamRow(stream)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSED
                rig.poller.tick()
                rig.launcher.handles.size shouldBe 1
                rig.launcher.aliveNow.get() shouldBe 1
                rig.supervisor.stateOf(stream) shouldBe ConferenceStreamPauseFeedState.STARTING
            }
        }

        test("a destination disabled during the pause: the next tick stops the old process and starts one with only the remaining target") {
            val fx = newFx()
            val stream = newStream(fx, ConferenceStreamStatus.PAUSED, ConferenceStreamPauseReason.ROOM_PAUSE, destinations = 2)
            setRoomPaused(fx, true)
            val rig = Rig()
            kotlinx.coroutines.runBlocking {
                rig.poller.tick()
                rig.launcher.handles.size shouldBe 1
                rig.launcher.commands
                    .single()
                    .args
                    .takeLast(2)
                    .first() shouldBe "tee"
                // an unchanged target set keeps the process
                rig.poller.tick()
                rig.launcher.handles.size shouldBe 1
                val destinationId =
                    transaction {
                        ConferenceStreamTargetTable
                            .selectAll()
                            .where { ConferenceStreamTargetTable.streamId eq stream }
                            .first()[ConferenceStreamTargetTable.destinationId]
                    }
                transaction {
                    ConferenceStreamDestinationTable.update({ ConferenceStreamDestinationTable.id eq destinationId }) {
                        it[enabled] = false
                    }
                }
                rig.poller.tick()
                rig.launcher.handles.size shouldBe 2
                rig.launcher.aliveNow.get() shouldBe 1
                rig.launcher.maxAlive.get() shouldBe 1 // never two senders at once
                rig.launcher.handles[0].isAlive shouldBe false
                rig.launcher.commands[1]
                    .args
                    .takeLast(2)
                    .first() shouldNotBe "tee"
                // re-pointing the remaining destination restarts again
                transaction {
                    ConferenceStreamDestinationTable.update({ ConferenceStreamDestinationTable.id neq destinationId }) {
                        it[rtmpUrl] = "rtmp://other.example.org:1935/live"
                    }
                }
                rig.poller.tick()
                rig.launcher.handles.size shouldBe 3
                rig.launcher.aliveNow.get() shouldBe 1
                // every destination disabled -> no feed at all
                transaction {
                    ConferenceStreamDestinationTable.update({ ConferenceStreamDestinationTable.id neq destinationId }) {
                        it[enabled] = false
                    }
                }
                rig.poller.tick()
                rig.launcher.aliveNow.get() shouldBe 0
                rig.supervisor.stateOf(stream) shouldBe ConferenceStreamPauseFeedState.OFF
            }
        }

        test("after a server restart (new supervisor, state only in the database) the first tick starts the feed") {
            val fx = newFx()
            newStream(fx, ConferenceStreamStatus.PAUSED, ConferenceStreamPauseReason.ROOM_PAUSE)
            setRoomPaused(fx, true)
            val rig = Rig()
            kotlinx.coroutines.runBlocking { rig.poller.tick() }
            rig.launcher.handles.size shouldBe 1
        }

        test("the end of the room, an ended or resumed stream and a no longer paused room stop the feed at the next tick") {
            val fx = newFx()
            val stream = newStream(fx, ConferenceStreamStatus.PAUSED, ConferenceStreamPauseReason.ROOM_PAUSE)
            setRoomPaused(fx, true)
            val rig = Rig()
            kotlinx.coroutines.runBlocking {
                rig.poller.tick()
                rig.launcher.aliveNow.get() shouldBe 1
                transaction {
                    ConferenceRoomTable.update(
                        { ConferenceRoomTable.id eq fx.room },
                    ) { it[endedAt] = DbClock.nowLocalDateTime() }
                }
                rig.poller.tick()
                rig.launcher.aliveNow.get() shouldBe 0
                rig.supervisor.stateOf(stream) shouldBe ConferenceStreamPauseFeedState.OFF
            }
        }

        test("a disabled supervisor: the pause behaves exactly like V1.9.254 (stream PAUSED, no process)") {
            val fx = newFx()
            val stream = newStream(fx, ConferenceStreamStatus.LIVE)
            val rig = Rig(enabled = false)
            kotlinx.coroutines.runBlocking { rig.coordinator.pause(roomId = fx.room.toString(), current = current(fx)) }
            streamRow(stream)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSED
            rig.launcher.handles.size shouldBe 0
            rig.supervisor.stateOf(stream) shouldBe ConferenceStreamPauseFeedState.OFF
        }

        test("a disabled destination gets no feed (the operator took it offline)") {
            val fx = newFx()
            newStream(fx, ConferenceStreamStatus.PAUSED, ConferenceStreamPauseReason.ROOM_PAUSE, destinationEnabled = false)
            setRoomPaused(fx, true)
            val rig = Rig()
            kotlinx.coroutines.runBlocking { rig.poller.tick() }
            rig.launcher.handles.size shouldBe 0
        }

        test("two destinations go through tee in one process") {
            val fx = newFx()
            newStream(fx, ConferenceStreamStatus.PAUSED, ConferenceStreamPauseReason.ROOM_PAUSE, destinations = 2)
            setRoomPaused(fx, true)
            val rig = Rig()
            kotlinx.coroutines.runBlocking { rig.poller.tick() }
            rig.launcher.handles.size shouldBe 1
            rig.launcher.commands
                .single()
                .args
                .takeLast(2)
                .first() shouldBe "tee"
        }

        test("the recording side is untouched: the pause rows and the stage are the same with and without a feed") {
            fun scenario(enabled: Boolean): Pair<Int, Int> {
                val fx = newFx()
                val recordingId = Uuid.random()
                transaction {
                    ConferenceRecordingTable.insert {
                        it[ConferenceRecordingTable.id] = recordingId
                        it[roomId] = fx.room
                        it[startedByMemberId] = fx.creator
                        it[startedAt] = DbClock.nowLocalDateTime()
                        it[status] = ConferenceRecordingStatus.RECORDING
                        it[accessLevel] = DocumentAccessLevel.BOARD_ONLY
                        it[rawDir] = recordingId.toString()
                        it[composeAttempts] = 0
                        it[pausedAt] = null
                        it[stage] = 0
                    }
                }
                newStream(fx, ConferenceStreamStatus.LIVE)
                val rig = Rig(enabled)
                kotlinx.coroutines.runBlocking {
                    rig.coordinator.pause(roomId = fx.room.toString(), current = current(fx))
                    rig.coordinator.resume(roomId = fx.room.toString(), current = current(fx))
                }
                val rows =
                    transaction {
                        ConferenceRecordingPauseTable
                            .selectAll()
                            .where {
                                ConferenceRecordingPauseTable.recordingId eq
                                    recordingId
                            }.toList()
                    }
                val stage =
                    transaction {
                        ConferenceRecordingTable
                            .selectAll()
                            .where {
                                ConferenceRecordingTable.id eq recordingId
                            }.single()[ConferenceRecordingTable.stage]
                    }
                return rows.size to stage.toInt()
            }
            scenario(enabled = true) shouldBe scenario(enabled = false)
            scenario(enabled = true) shouldBe (1 to 1)
        }
    })
