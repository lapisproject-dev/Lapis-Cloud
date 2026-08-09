package network.lapis.cloud.server.conference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.ConferenceStreamDestinationTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTargetTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode
import network.lapis.cloud.shared.domain.ConferenceStreamLayout
import network.lapis.cloud.shared.domain.ConferenceStreamPlatform
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
import network.lapis.cloud.shared.domain.ConferenceStreamTargetStatus
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private const val FAKE_ROOM_NAME_PREFIX = "lc-stream-poller-test-"

/**
 * Records every call for assertions -- [listEgress] returns a per-room canned response
 * ([egressListByRoom]), or throws [LiveKitAdminException] when [failListEgress] holds, or a plain
 * [RuntimeException] when [roomName] equals [throwUnexpectedForRoom] (used by the tick()
 * exception-safety test -- an UNCAUGHT throwable type, unlike the [LiveKitAdminException] every
 * other resilience test in this file exercises).
 */
private class StreamPollerFakeEgressClient(
    private val failListEgress: Boolean = false,
    private val throwUnexpectedForRoom: String? = null,
) : LiveKitEgressClient {
    val egressListByRoom = mutableMapOf<String, List<LiveKitEgressInfo>>()
    val stopCalls = mutableListOf<Pair<String, String>>()
    val listEgressCalls = AtomicInteger(0)

    override suspend fun startTrackEgress(
        roomName: String,
        trackId: String,
        outputFilepathWithoutExtension: String,
    ): LiveKitEgressInfo = error("not used by StreamPollerTest")

    override suspend fun stopEgress(
        roomName: String,
        egressId: String,
    ): LiveKitEgressInfo {
        stopCalls += roomName to egressId
        return LiveKitEgressInfo(egressId = egressId, status = "EGRESS_ENDING")
    }

    override suspend fun listEgress(roomName: String): List<LiveKitEgressInfo> {
        listEgressCalls.incrementAndGet()
        if (roomName == throwUnexpectedForRoom) throw RuntimeException("simulated unexpected failure")
        if (failListEgress) throw LiveKitAdminException("ListEgress failed (simulated outage)")
        return egressListByRoom[roomName] ?: emptyList()
    }

    override suspend fun startRoomCompositeEgress(
        roomName: String,
        layout: ConferenceStreamLayout,
        latencyMode: ConferenceStreamLatencyMode,
        rtmpUrls: List<String>,
    ): LiveKitEgressInfo = error("not used by StreamPollerTest")

    override suspend fun startParticipantEgress(
        roomName: String,
        identity: String,
        latencyMode: ConferenceStreamLatencyMode,
        rtmpUrls: List<String>,
    ): LiveKitEgressInfo = error("not used by StreamPollerTest")

    override suspend fun updateStream(
        roomName: String,
        egressId: String,
        addUrls: List<String>,
        removeUrls: List<String>,
    ): LiveKitEgressInfo = error("not used by StreamPollerTest")
}

/**
 * [StreamPoller.tick] end to end -- real H2 DB rows (mirrors [RecordingPollerTest]'s own house
 * style), a fake [LiveKitEgressClient], no real network/LiveKit involvement. Covers the
 * STARTING/LIVE/PAUSED/STOPPING branches, the `url_fingerprint` matching discipline, the sanitized
 * failure vocabulary, and `tick()`'s own exception-safety.
 */
class StreamPollerTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdRoomIds = mutableListOf<Uuid>()
        val createdDestinationIds = mutableListOf<Uuid>()
        val createdStreamIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        // [tick] scans EVERY non-terminal conference_stream row in the whole table, not just rows
        // this test itself created -- a stream left LIVE/STARTING/PAUSED/STOPPING by one test would
        // otherwise be picked up (and call the NEXT test's OWN fake LiveKitEgressClient instance,
        // for a room that client knows nothing about) by every later test's own tick() call in this
        // spec. Force every stream created SO FAR into a terminal status after each test so the
        // next test's tick() only ever sees its own rows.
        afterTest {
            transaction {
                ConferenceStreamTable.update({ ConferenceStreamTable.id inList createdStreamIds }) {
                    it[status] = ConferenceStreamStatus.ENDED
                }
            }
        }

        afterSpec {
            transaction {
                if (createdStreamIds.isNotEmpty()) {
                    ConferenceStreamTargetTable.deleteWhere { ConferenceStreamTargetTable.streamId inList createdStreamIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) {
                        it[actorMemberId] = null
                    }
                }
                if (createdStreamIds.isNotEmpty()) {
                    ConferenceStreamTable.deleteWhere { ConferenceStreamTable.id inList createdStreamIds }
                }
                if (createdDestinationIds.isNotEmpty()) {
                    ConferenceStreamDestinationTable.deleteWhere { ConferenceStreamDestinationTable.id inList createdDestinationIds }
                }
                if (createdRoomIds.isNotEmpty()) {
                    ConferenceRoomTable.deleteWhere { ConferenceRoomTable.id inList createdRoomIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
        }

        fun createMember(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "StreamPoller Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.AKTIV
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += id
            return id
        }

        fun createRoom(
            creatorId: Uuid,
            ended: Boolean = false,
        ): Pair<Uuid, String> {
            val id = Uuid.random()
            val livekitRoomName = "$FAKE_ROOM_NAME_PREFIX$id"
            val now = DbClock.nowLocalDateTime()
            transaction {
                ConferenceRoomTable.insert {
                    it[ConferenceRoomTable.id] = id
                    it[title] = "Poller-Test-Raum"
                    it[description] = ""
                    it[ConferenceRoomTable.livekitRoomName] = livekitRoomName
                    it[createdByMemberId] = creatorId
                    it[createdAt] = now
                    it[endedAt] = if (ended) now else null
                    it[maxParticipants] = 25
                }
            }
            createdRoomIds += id
            return id to livekitRoomName
        }

        fun createDestination(creatorId: Uuid): Uuid {
            val id = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            transaction {
                ConferenceStreamDestinationTable.insert {
                    it[ConferenceStreamDestinationTable.id] = id
                    it[label] = "Poller-Test-Ziel-$id"
                    it[platform] = ConferenceStreamPlatform.GENERIC_RTMP
                    it[rtmpUrl] = "rtmp://sink.example.org:1935/live"
                    it[streamKeyCiphertext] = "v1:unused:unused"
                    it[streamKeySetAt] = now
                    it[createdByMemberId] = creatorId
                    it[createdAt] = now
                    it[enabled] = true
                }
            }
            createdDestinationIds += id
            return id
        }

        fun createStream(
            roomId: Uuid,
            startedByMemberId: Uuid,
            status: ConferenceStreamStatus,
            startedAt: LocalDateTime = DbClock.nowLocalDateTime(),
            livekitEgressId: String? = null,
            restartCount: Int = 0,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                ConferenceStreamTable.insert {
                    it[ConferenceStreamTable.id] = id
                    it[ConferenceStreamTable.roomId] = roomId
                    it[ConferenceStreamTable.startedByMemberId] = startedByMemberId
                    it[ConferenceStreamTable.status] = status
                    it[layout] = ConferenceStreamLayout.GRID
                    it[latencyMode] = ConferenceStreamLatencyMode.STANDARD
                    it[participantIdentity] = null
                    it[ConferenceStreamTable.livekitEgressId] = livekitEgressId
                    it[ConferenceStreamTable.startedAt] = startedAt
                    it[pausedAt] = null
                    it[endedAt] = null
                    it[ConferenceStreamTable.restartCount] = restartCount
                    it[failureReason] = null
                }
            }
            createdStreamIds += id
            return id
        }

        fun createTarget(
            streamId: Uuid,
            destinationId: Uuid,
            urlFingerprint: String,
            status: ConferenceStreamTargetStatus = ConferenceStreamTargetStatus.PENDING,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                ConferenceStreamTargetTable.insert {
                    it[ConferenceStreamTargetTable.id] = id
                    it[ConferenceStreamTargetTable.streamId] = streamId
                    it[ConferenceStreamTargetTable.destinationId] = destinationId
                    it[ConferenceStreamTargetTable.status] = status
                    it[ConferenceStreamTargetTable.urlFingerprint] = urlFingerprint
                    it[startedAtEpochNanos] = null
                    it[endedAtEpochNanos] = null
                    it[retries] = 0
                    it[failureReason] = null
                }
            }
            return id
        }

        fun streamRow(id: Uuid) = transaction { ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq id }.single() }

        fun targetRow(id: Uuid) =
            transaction { ConferenceStreamTargetTable.selectAll().where { ConferenceStreamTargetTable.id eq id }.single() }

        fun buildPoller(
            egressClient: LiveKitEgressClient,
            maxDurationMinutes: Long = 480,
            startupTimeoutSeconds: Long = 60,
            clock: () -> LocalDateTime = { DbClock.nowLocalDateTime() },
        ): StreamPoller {
            val config =
                ConferenceStreamingConfig.load { key ->
                    when (key) {
                        "LAPIS_STREAMING_ENABLED" -> "true"
                        "LAPIS_SECRET_ENCRYPTION_KEY" ->
                            java.util.Base64
                                .getEncoder()
                                .encodeToString(ByteArray(32))
                        "LAPIS_STREAM_MAX_DURATION_MINUTES" -> maxDurationMinutes.toString()
                        "LAPIS_STREAM_STARTUP_TIMEOUT_SECONDS" -> startupTimeoutSeconds.toString()
                        else -> null
                    }
                }
            return StreamPoller(egressClient, config, clock)
        }

        // ── LIVE ─────────────────────────────────────────────────────────

        test("LIVE: refreshes target status/retries from ListEgress stream_results, matched via url_fingerprint -- stream stays LIVE") {
            val member = createMember("poller-live-refresh@example.org")
            val (roomId, roomName) = createRoom(member)
            val destId = createDestination(member)
            val streamId = createStream(roomId, member, ConferenceStreamStatus.LIVE, livekitEgressId = "EG_live1")
            val fingerprint = "rtmp://sink.example.org:1935/live/{tes...123}"
            val targetId = createTarget(streamId, destId, fingerprint)

            val egressClient = StreamPollerFakeEgressClient()
            egressClient.egressListByRoom[roomName] =
                listOf(
                    LiveKitEgressInfo(
                        egressId = "EG_live1",
                        status = "EGRESS_ACTIVE",
                        streamResults = listOf(LiveKitStreamInfo(url = fingerprint, status = "ACTIVE", retries = 2)),
                    ),
                )

            buildPoller(egressClient).tick()

            streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.LIVE
            targetRow(targetId)[ConferenceStreamTargetTable.status] shouldBe ConferenceStreamTargetStatus.ACTIVE
            targetRow(targetId)[ConferenceStreamTargetTable.retries] shouldBe 2
        }

        test(
            "LIVE: a terminal EgressInfo.status marks the STREAM and every non-terminal target FAILED with a SANITIZED reason -- never the raw LiveKit text",
        ) {
            val member = createMember("poller-live-terminal@example.org")
            val (roomId, roomName) = createRoom(member)
            val destId = createDestination(member)
            val streamId = createStream(roomId, member, ConferenceStreamStatus.LIVE, livekitEgressId = "EG_live2")
            val fingerprint = "rtmp://sink.example.org:1935/live/{tes...456}"
            val targetId = createTarget(streamId, destId, fingerprint)

            val rawError = "Failed to connect: Error resolving “nonexistent-bad-host-xyz”: Name or service not known"
            val egressClient = StreamPollerFakeEgressClient()
            egressClient.egressListByRoom[roomName] =
                listOf(
                    LiveKitEgressInfo(
                        egressId = "EG_live2",
                        status = "EGRESS_FAILED",
                        error = rawError,
                        streamResults = listOf(LiveKitStreamInfo(url = fingerprint, status = "FAILED", error = rawError, retries = 3)),
                    ),
                )

            buildPoller(egressClient).tick()

            val stream = streamRow(streamId)
            stream[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.FAILED
            stream[ConferenceStreamTable.failureReason] shouldBe "Die Verbindung zum Streaming-Ziel konnte nicht hergestellt werden."
            (stream[ConferenceStreamTable.failureReason]?.contains("nonexistent-bad-host-xyz") ?: true) shouldBe false

            val target = targetRow(targetId)
            target[ConferenceStreamTargetTable.status] shouldBe ConferenceStreamTargetStatus.FAILED
            target[ConferenceStreamTargetTable.failureReason] shouldBe "Die Verbindung zum Streaming-Ziel konnte nicht hergestellt werden."
        }

        test("LIVE: egress no longer reported by ListEgress -> FAILED immediately") {
            val member = createMember("poller-live-vanished@example.org")
            val (roomId, roomName) = createRoom(member)
            val destId = createDestination(member)
            val streamId = createStream(roomId, member, ConferenceStreamStatus.LIVE, livekitEgressId = "EG_gone")
            createTarget(streamId, destId, "rtmp://sink.example.org:1935/live/{gon...one}")

            val egressClient = StreamPollerFakeEgressClient()
            egressClient.egressListByRoom[roomName] = emptyList()

            buildPoller(egressClient).tick()

            streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.FAILED
        }

        test("LIVE: auto-stops (StopEgress + ENDED) once maxDurationMinutes has elapsed since startedAt") {
            val member = createMember("poller-live-maxduration@example.org")
            val (roomId, roomName) = createRoom(member)
            val destId = createDestination(member)
            val startedAt = DbClock.nowLocalDateTime()
            val streamId = createStream(roomId, member, ConferenceStreamStatus.LIVE, startedAt = startedAt, livekitEgressId = "EG_maxdur")
            createTarget(streamId, destId, "rtmp://sink.example.org:1935/live/{max...dur}", status = ConferenceStreamTargetStatus.ACTIVE)

            val fakeNow = startedAt.shiftedByMinutes(11)
            val egressClient = StreamPollerFakeEgressClient()
            val poller = buildPoller(egressClient, maxDurationMinutes = 10, clock = { fakeNow })

            poller.tick()

            egressClient.stopCalls shouldBe listOf(roomName to "EG_maxdur")
            val stream = streamRow(streamId)
            stream[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.ENDED
            (stream[ConferenceStreamTable.endedAt] != null) shouldBe true
        }

        test("LIVE: room has ENDED -> auto-stop regardless of duration/egress status") {
            val member = createMember("poller-live-roomended@example.org")
            val (roomId, roomName) = createRoom(member, ended = true)
            val destId = createDestination(member)
            val streamId = createStream(roomId, member, ConferenceStreamStatus.LIVE, livekitEgressId = "EG_roomended")
            createTarget(streamId, destId, "rtmp://sink.example.org:1935/live/{roo...ded}")

            val egressClient = StreamPollerFakeEgressClient()
            buildPoller(egressClient).tick()

            egressClient.stopCalls shouldBe listOf(roomName to "EG_roomended")
            streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.ENDED
        }

        // ── PAUSED ───────────────────────────────────────────────────────

        test("PAUSED: auto-finalizes to ENDED once maxDurationMinutes elapsed -- NO StopEgress call, nothing is running") {
            val member = createMember("poller-paused-maxduration@example.org")
            val (roomId, _) = createRoom(member)
            val startedAt = DbClock.nowLocalDateTime()
            val streamId =
                createStream(roomId, member, ConferenceStreamStatus.PAUSED, startedAt = startedAt, livekitEgressId = "EG_paused_stale")

            val fakeNow = startedAt.shiftedByMinutes(11)
            val egressClient = StreamPollerFakeEgressClient()
            val poller = buildPoller(egressClient, maxDurationMinutes = 10, clock = { fakeNow })

            poller.tick()

            egressClient.stopCalls shouldBe emptyList()
            streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.ENDED
        }

        test("PAUSED: within maxDurationMinutes -> left untouched") {
            val member = createMember("poller-paused-within@example.org")
            val (roomId, _) = createRoom(member)
            val streamId = createStream(roomId, member, ConferenceStreamStatus.PAUSED)

            buildPoller(StreamPollerFakeEgressClient(), maxDurationMinutes = 480).tick()

            streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSED
        }

        // ── STOPPING ─────────────────────────────────────────────────────

        test("STOPPING: completes an interrupted stop -- best-effort StopEgress, then ENDED") {
            val member = createMember("poller-stopping@example.org")
            val (roomId, roomName) = createRoom(member)
            val streamId = createStream(roomId, member, ConferenceStreamStatus.STOPPING, livekitEgressId = "EG_stopping")

            val egressClient = StreamPollerFakeEgressClient()
            buildPoller(egressClient).tick()

            egressClient.stopCalls shouldBe listOf(roomName to "EG_stopping")
            streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.ENDED
        }

        test("STOPPING: a StopEgress failure (LiveKitAdminException) is logged and swallowed -- still finalizes to ENDED") {
            val member = createMember("poller-stopping-failure@example.org")
            val (roomId, _) = createRoom(member)
            val streamId = createStream(roomId, member, ConferenceStreamStatus.STOPPING, livekitEgressId = "EG_stopping_fail")

            val egressClient =
                object : LiveKitEgressClient {
                    override suspend fun startTrackEgress(
                        roomName: String,
                        trackId: String,
                        outputFilepathWithoutExtension: String,
                    ) = error("not used")

                    override suspend fun stopEgress(
                        roomName: String,
                        egressId: String,
                    ): LiveKitEgressInfo = throw LiveKitAdminException("simulated StopEgress failure")

                    override suspend fun listEgress(roomName: String) = emptyList<LiveKitEgressInfo>()

                    override suspend fun startRoomCompositeEgress(
                        roomName: String,
                        layout: ConferenceStreamLayout,
                        latencyMode: ConferenceStreamLatencyMode,
                        rtmpUrls: List<String>,
                    ) = error("not used")

                    override suspend fun startParticipantEgress(
                        roomName: String,
                        identity: String,
                        latencyMode: ConferenceStreamLatencyMode,
                        rtmpUrls: List<String>,
                    ) = error("not used")

                    override suspend fun updateStream(
                        roomName: String,
                        egressId: String,
                        addUrls: List<String>,
                        removeUrls: List<String>,
                    ) = error("not used")
                }
            buildPoller(egressClient).tick()

            streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.ENDED
        }

        // ── STARTING -- orphan reconciliation ───────────────────────────

        test("STARTING: adopts a matching orphan egress once startupTimeoutSeconds has elapsed") {
            val member = createMember("poller-starting-adopt@example.org")
            val (roomId, roomName) = createRoom(member)
            val destId = createDestination(member)
            val startedAt = DbClock.nowLocalDateTime()
            val streamId = createStream(roomId, member, ConferenceStreamStatus.STARTING, startedAt = startedAt, livekitEgressId = null)
            val fingerprint = "rtmp://sink.example.org:1935/live/{ado...pte}"
            createTarget(streamId, destId, fingerprint)

            val egressClient = StreamPollerFakeEgressClient()
            egressClient.egressListByRoom[roomName] =
                listOf(
                    LiveKitEgressInfo(
                        egressId = "EG_adopted",
                        status = "EGRESS_ACTIVE",
                        streamResults = listOf(LiveKitStreamInfo(url = fingerprint, status = "ACTIVE")),
                    ),
                )
            val fakeNow = startedAt.shiftedBySeconds(90)
            buildPoller(egressClient, startupTimeoutSeconds = 60, clock = { fakeNow }).tick()

            val stream = streamRow(streamId)
            stream[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.LIVE
            stream[ConferenceStreamTable.livekitEgressId] shouldBe "EG_adopted"
        }

        test("STARTING: not yet past startupTimeoutSeconds -> left alone, no ListEgress call at all") {
            val member = createMember("poller-starting-tooearly@example.org")
            val (roomId, _) = createRoom(member)
            val destId = createDestination(member)
            val startedAt = DbClock.nowLocalDateTime()
            val streamId = createStream(roomId, member, ConferenceStreamStatus.STARTING, startedAt = startedAt, livekitEgressId = null)
            createTarget(streamId, destId, "rtmp://sink.example.org:1935/live/{too...rly}")

            val egressClient = StreamPollerFakeEgressClient()
            buildPoller(egressClient, startupTimeoutSeconds = 60, clock = { startedAt }).tick()

            streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.STARTING
            egressClient.listEgressCalls.get() shouldBe 0
        }

        test("STARTING: no matching egress after the timeout -> FAILED (Zeitueberschreitung)") {
            val member = createMember("poller-starting-timeout@example.org")
            val (roomId, roomName) = createRoom(member)
            val destId = createDestination(member)
            val startedAt = DbClock.nowLocalDateTime()
            val streamId = createStream(roomId, member, ConferenceStreamStatus.STARTING, startedAt = startedAt, livekitEgressId = null)
            createTarget(streamId, destId, "rtmp://sink.example.org:1935/live/{unm...tch}")

            val egressClient = StreamPollerFakeEgressClient()
            egressClient.egressListByRoom[roomName] = emptyList()
            val fakeNow = startedAt.shiftedBySeconds(90)
            buildPoller(egressClient, startupTimeoutSeconds = 60, clock = { fakeNow }).tick()

            val stream = streamRow(streamId)
            stream[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.FAILED
            stream[ConferenceStreamTable.failureReason] shouldBe "Der Stream wurde vom Server beendet (Zeitüberschreitung)."
        }

        test("STARTING: zero target rows -- fast-fails to FAILED without ever calling ListEgress") {
            val member = createMember("poller-starting-notargets@example.org")
            val (roomId, _) = createRoom(member)
            val startedAt = DbClock.nowLocalDateTime()
            val streamId = createStream(roomId, member, ConferenceStreamStatus.STARTING, startedAt = startedAt, livekitEgressId = null)

            val egressClient = StreamPollerFakeEgressClient()
            val fakeNow = startedAt.shiftedBySeconds(90)
            buildPoller(egressClient, startupTimeoutSeconds = 60, clock = { fakeNow }).tick()

            streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.FAILED
            egressClient.listEgressCalls.get() shouldBe 0
        }

        // ── tick() exception safety ──────────────────────────────────────

        test("tick(): an unexpected exception for one stream does not prevent another stream's own progress in the same tick") {
            val member = createMember("poller-exception-safety@example.org")
            val (healthyRoomId, healthyRoomName) = createRoom(member)
            val (brokenRoomId, brokenRoomName) = createRoom(member)
            val destId = createDestination(member)

            val healthyStreamId = createStream(healthyRoomId, member, ConferenceStreamStatus.LIVE, livekitEgressId = "EG_healthy")
            val brokenStreamId = createStream(brokenRoomId, member, ConferenceStreamStatus.LIVE, livekitEgressId = "EG_broken")
            val healthyFingerprint = "rtmp://sink.example.org:1935/live/{hea...thy}"
            createTarget(healthyStreamId, destId, healthyFingerprint)
            createTarget(brokenStreamId, destId, "rtmp://sink.example.org:1935/live/{bro...ken}")

            val egressClient = StreamPollerFakeEgressClient(throwUnexpectedForRoom = brokenRoomName)
            egressClient.egressListByRoom[healthyRoomName] =
                listOf(
                    LiveKitEgressInfo(
                        egressId = "EG_healthy",
                        status = "EGRESS_ACTIVE",
                        streamResults = listOf(LiveKitStreamInfo(url = healthyFingerprint, status = "ACTIVE")),
                    ),
                )
            buildPoller(egressClient).tick()

            // The broken stream's own ListEgress call throws an UNCAUGHT RuntimeException inside
            // handleLive -- tick() must swallow it per-row (see class KDoc "exception-safe at TWO
            // levels") and still refresh the healthy one in the SAME pass.
            streamRow(healthyStreamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.LIVE
            streamRow(brokenStreamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.LIVE
        }
    })

private val TZ = TimeZone.currentSystemDefault()

/** Test-only helper -- shifts a [LocalDateTime] by whole minutes using kotlinx-datetime's own Instant arithmetic, same conversion [StreamPoller] itself uses. */
private fun LocalDateTime.shiftedByMinutes(minutes: Long): LocalDateTime = this.toInstant(TZ).plus(minutes.minutes).toLocalDateTime(TZ)

/** Same as [shiftedByMinutes] but for sub-minute shifts -- [StreamPoller]'s STARTING-orphan timeout is seconds-granular, not minutes. */
private fun LocalDateTime.shiftedBySeconds(seconds: Long): LocalDateTime = this.toInstant(TZ).plus(seconds.seconds).toLocalDateTime(TZ)
