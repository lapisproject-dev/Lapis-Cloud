package network.lapis.cloud.server.conference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.datetime.LocalDate
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
import network.lapis.cloud.shared.domain.ConferenceStreamKeyKind
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
import java.util.Base64
import kotlin.uuid.Uuid

private const val SEALED = "v1:dummy-iv:dummy-ciphertext"

private class PollerBkEgress : LiveKitEgressClient {
    val egressListByRoom = mutableMapOf<String, List<LiveKitEgressInfo>>()

    override suspend fun startTrackEgress(
        roomName: String,
        trackId: String,
        outputFilepathWithoutExtension: String,
    ): LiveKitEgressInfo = error("unused")

    override suspend fun stopEgress(
        roomName: String,
        egressId: String,
    ): LiveKitEgressInfo = LiveKitEgressInfo(egressId = egressId, status = "EGRESS_ENDING")

    override suspend fun listEgress(roomName: String): List<LiveKitEgressInfo> = egressListByRoom[roomName] ?: emptyList()

    override suspend fun startRoomCompositeEgress(
        roomName: String,
        layout: ConferenceStreamLayout,
        latencyMode: ConferenceStreamLatencyMode,
        rtmpUrls: List<String>,
    ): LiveKitEgressInfo = error("unused")

    override suspend fun startParticipantEgress(
        roomName: String,
        identity: String,
        latencyMode: ConferenceStreamLatencyMode,
        rtmpUrls: List<String>,
    ): LiveKitEgressInfo = error("unused")

    override suspend fun startParticipantFileEgress(
        roomName: String,
        identity: String,
        outputFilepath: String,
    ): LiveKitEgressInfo = error("unused")

    override suspend fun updateStream(
        roomName: String,
        egressId: String,
        addUrls: List<String>,
        removeUrls: List<String>,
    ): LiveKitEgressInfo = error("unused")
}

/**
 * V1.9.274 -- when does the StreamPoller delete a broadcast key: on `ENDED`/`FAILED` of the stream (every finalize path) and as the first step of
 * every tick (also a server restart), but never because ONE target of a running stream failed (Resume and the reconnect action need the key).
 */
class StreamPollerBroadcastKeyTest :
    FunSpec({
        val memberIds = mutableListOf<Uuid>()
        val roomIds = mutableListOf<Uuid>()
        val destinationIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec {
            transaction {
                AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList memberIds }) { it[actorMemberId] = null }
                val streams =
                    ConferenceStreamTable
                        .selectAll()
                        .where {
                            ConferenceStreamTable.roomId inList roomIds
                        }.map { it[ConferenceStreamTable.id] }
                streams.forEach { s -> ConferenceStreamTargetTable.deleteWhere { ConferenceStreamTargetTable.streamId eq s } }
                streams.forEach { s -> ConferenceStreamTable.deleteWhere { ConferenceStreamTable.id eq s } }
                ConferenceStreamDestinationTable.deleteWhere { ConferenceStreamDestinationTable.id inList destinationIds }
                roomIds.forEach { r -> ConferenceRoomTable.deleteWhere { ConferenceRoomTable.id eq r } }
                memberIds.forEach { m -> AccountTable.deleteWhere { AccountTable.memberId eq m } }
                memberIds.forEach { m -> MemberTable.deleteWhere { MemberTable.id eq m } }
            }
        }

        class Fixture(
            val roomName: String,
            val roomId: Uuid,
            val streamId: Uuid,
            val targetId: Uuid,
        )

        fun fixture(
            status: ConferenceStreamStatus,
            egressId: String?,
            fingerprint: String = "rtmp://sink.example.org:1935/live/{abc...xyz}",
        ): Fixture {
            val memberId = Uuid.random()
            val roomId = Uuid.random()
            val destId = Uuid.random()
            val streamId = Uuid.random()
            val targetId = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = memberId
                    it[displayName] = "Poller Schluessel"
                    it[email] = "pbk-$memberId@example.org"
                    it[MemberTable.status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[AccountTable.memberId] = memberId
                    it[role] = AccountRole.MEMBER
                }
                ConferenceRoomTable.insert {
                    it[ConferenceRoomTable.id] = roomId
                    it[title] = "Poller-Schluessel"
                    it[description] = ""
                    it[livekitRoomName] = "lc-pbk-$roomId"
                    it[createdByMemberId] = memberId
                    it[createdAt] = now
                    it[endedAt] = null
                    it[maxParticipants] = 25
                }
                ConferenceStreamDestinationTable.insert {
                    it[ConferenceStreamDestinationTable.id] = destId
                    it[label] = "pbk-$destId"
                    it[platform] = ConferenceStreamPlatform.GENERIC_RTMP
                    it[rtmpUrl] = "rtmp://sink.example.org:1935/live"
                    it[keyKind] = ConferenceStreamKeyKind.PER_BROADCAST
                    it[streamKeyCiphertext] = null
                    it[streamKeySetAt] = null
                    it[createdByMemberId] = memberId
                    it[createdAt] = now
                    it[enabled] = true
                }
                ConferenceStreamTable.insert {
                    it[ConferenceStreamTable.id] = streamId
                    it[ConferenceStreamTable.roomId] = roomId
                    it[startedByMemberId] = memberId
                    it[ConferenceStreamTable.status] = status
                    it[layout] = ConferenceStreamLayout.GRID
                    it[latencyMode] = ConferenceStreamLatencyMode.STANDARD
                    it[participantIdentity] = null
                    it[livekitEgressId] = egressId
                    it[startedAt] = now
                    it[restartCount] = 0
                }
                ConferenceStreamTargetTable.insert {
                    it[ConferenceStreamTargetTable.id] = targetId
                    it[ConferenceStreamTargetTable.streamId] = streamId
                    it[destinationId] = destId
                    it[ConferenceStreamTargetTable.status] = ConferenceStreamTargetStatus.PENDING
                    it[urlFingerprint] = fingerprint
                    it[broadcastKeyCiphertext] = SEALED
                    it[retries] = 0
                }
            }
            memberIds += memberId
            roomIds += roomId
            destinationIds += destId
            return Fixture("lc-pbk-$roomId", roomId, streamId, targetId)
        }

        fun keyOf(f: Fixture): String? =
            transaction {
                ConferenceStreamTargetTable.selectAll().where { ConferenceStreamTargetTable.id eq f.targetId }.single()
            }[ConferenceStreamTargetTable.broadcastKeyCiphertext]

        fun statusOf(f: Fixture) =
            transaction {
                ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq f.streamId }.single()
            }[ConferenceStreamTable.status]

        val config =
            ConferenceStreamingConfig.load { key ->
                when (key) {
                    "LAPIS_STREAMING_ENABLED" -> "true"
                    "LAPIS_SECRET_ENCRYPTION_KEY" -> Base64.getEncoder().encodeToString(ByteArray(32))
                    else -> null
                }
            }

        test(
            "the first step of a tick deletes a key left behind on an ENDED or FAILED stream (a server restart, a missed path) and leaves a running stream alone",
        ) {
            val ended = fixture(ConferenceStreamStatus.ENDED, egressId = null)
            val failed = fixture(ConferenceStreamStatus.FAILED, egressId = null)
            val live = fixture(ConferenceStreamStatus.LIVE, egressId = "EG_pbk_live")
            val egress = PollerBkEgress()
            egress.egressListByRoom[live.roomName] =
                listOf(LiveKitEgressInfo(egressId = "EG_pbk_live", status = "EGRESS_ACTIVE"))
            StreamPoller(liveKitEgressClient = egress, streamingConfig = config).tick()
            keyOf(ended) shouldBe null
            keyOf(failed) shouldBe null
            keyOf(live) shouldBe SEALED
            statusOf(live) shouldBe ConferenceStreamStatus.LIVE
        }

        test("a stream whose egress vanished from LiveKit becomes FAILED and its key is deleted in the same step") {
            val f = fixture(ConferenceStreamStatus.LIVE, egressId = "EG_pbk_gone")
            // ListEgress reports nothing for the room.
            StreamPoller(liveKitEgressClient = PollerBkEgress(), streamingConfig = config).tick()
            statusOf(f) shouldBe ConferenceStreamStatus.FAILED
            keyOf(f) shouldBe null
        }

        test("a stream whose room has ended is finalised to ENDED and its key is deleted") {
            val f = fixture(ConferenceStreamStatus.LIVE, egressId = "EG_pbk_room")
            transaction {
                ConferenceRoomTable.update({ ConferenceRoomTable.id eq f.roomId }) { it[endedAt] = DbClock.nowLocalDateTime() }
            }
            StreamPoller(liveKitEgressClient = PollerBkEgress(), streamingConfig = config).tick()
            statusOf(f) shouldBe ConferenceStreamStatus.ENDED
            keyOf(f) shouldBe null
        }

        test("a stop that is confirmed (STOPPING, egress gone) ends the stream and deletes the key") {
            val f = fixture(ConferenceStreamStatus.STOPPING, egressId = "EG_pbk_stop")
            StreamPoller(liveKitEgressClient = PollerBkEgress(), streamingConfig = config).tick()
            statusOf(f) shouldBe ConferenceStreamStatus.ENDED
            keyOf(f) shouldBe null
        }

        test("one FAILED target of a LIVE stream keeps its key (Resume and the reconnect action need it)") {
            val fingerprint = "rtmp://sink.example.org:1935/live/{abc...xyz}"
            val f = fixture(ConferenceStreamStatus.LIVE, egressId = "EG_pbk_part", fingerprint = fingerprint)
            val egress = PollerBkEgress()
            egress.egressListByRoom[f.roomName] =
                listOf(
                    LiveKitEgressInfo(
                        egressId = "EG_pbk_part",
                        status = "EGRESS_ACTIVE",
                        streamResults = listOf(LiveKitStreamInfo(url = fingerprint, status = "FAILED", retries = 3)),
                    ),
                )
            StreamPoller(liveKitEgressClient = egress, streamingConfig = config).tick()
            statusOf(f) shouldBe ConferenceStreamStatus.LIVE
            transaction {
                ConferenceStreamTargetTable.selectAll().where { ConferenceStreamTargetTable.id eq f.targetId }.single()
            }[ConferenceStreamTargetTable.status] shouldBe ConferenceStreamTargetStatus.FAILED
            keyOf(f) shouldNotBe null
        }
    })
