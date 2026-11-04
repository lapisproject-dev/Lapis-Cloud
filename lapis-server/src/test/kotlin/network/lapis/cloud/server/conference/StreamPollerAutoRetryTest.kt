package network.lapis.cloud.server.conference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.crypto.SecretBox
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
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceStreamFailureKind
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
import java.util.Collections
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private class UpdateCall(
    val egressId: String,
    val addUrls: List<String>,
    val removeUrls: List<String>,
)

private class RetryEgress : LiveKitEgressClient {
    val resultsByRoom = mutableMapOf<String, LiveKitEgressInfo>()
    val updates = Collections.synchronizedList(mutableListOf<UpdateCall>())
    var failAdd = false
    val adds: List<UpdateCall> get() = synchronized(updates) { updates.filter { it.addUrls.isNotEmpty() } }

    override suspend fun startTrackEgress(
        roomName: String,
        trackId: String,
        outputFilepathWithoutExtension: String,
    ): LiveKitEgressInfo = error("unused")

    override suspend fun stopEgress(
        roomName: String,
        egressId: String,
    ): LiveKitEgressInfo = LiveKitEgressInfo(egressId = egressId, status = "EGRESS_ENDING")

    override suspend fun listEgress(roomName: String): List<LiveKitEgressInfo> = listOfNotNull(resultsByRoom[roomName])

    override suspend fun startRoomCompositeEgress(
        roomName: String,
        layout: ConferenceStreamLayout,
        latencyMode: ConferenceStreamLatencyMode,
        rtmpUrls: List<String>,
    ): LiveKitEgressInfo = error("a retry must never start an egress")

    override suspend fun startParticipantEgress(
        roomName: String,
        identity: String,
        latencyMode: ConferenceStreamLatencyMode,
        rtmpUrls: List<String>,
    ): LiveKitEgressInfo = error("a retry must never start an egress")

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
    ): LiveKitEgressInfo {
        updates += UpdateCall(egressId = egressId, addUrls = addUrls, removeUrls = removeUrls)
        if (failAdd && addUrls.isNotEmpty()) throw LiveKitAdminException(message = "simulated refusal")
        return LiveKitEgressInfo(egressId = egressId, status = "EGRESS_ACTIVE")
    }
}

/**
 * V1.9.275 -- the automatic retry of a failed target of a LIVE stream: planning, the 45 second window, back-off, the limit of three, the refusal
 * that is never retried, results that predate the attempt, a refused `UpdateStream`, a stream that is no longer LIVE, two ticks at once, and the
 * honest ending. A fake clock and a fake egress: nothing sleeps, nothing leaves the process; every key is a dummy value.
 */
class StreamPollerAutoRetryTest :
    FunSpec({
        val zone = ServerClock.zone
        val keyBytes = ByteArray(32)
        val box = SecretBox(keyBytes)
        val config =
            ConferenceStreamingConfig.load { key ->
                when (key) {
                    "LAPIS_STREAMING_ENABLED" -> "true"
                    "LAPIS_SECRET_ENCRYPTION_KEY" -> Base64.getEncoder().encodeToString(keyBytes)
                    else -> null
                }
            }
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

        class Target(
            val id: Uuid,
            val url: String,
        ) {
            val fingerprint: String get() = StreamUrlFingerprint.of(url)
        }

        class Fixture(
            val roomName: String,
            val roomId: Uuid,
            val streamId: Uuid,
            val egressId: String,
            val targets: List<Target>,
            val base: LocalDateTime,
        )

        fun fixture(
            targetCount: Int = 2,
            streamStatus: ConferenceStreamStatus = ConferenceStreamStatus.LIVE,
            windowStartOffsetSeconds: Int = 0,
        ): Fixture {
            val memberId = Uuid.random()
            val roomId = Uuid.random()
            val streamId = Uuid.random()
            val egressId = "EG_retry_${streamId.toString().take(8)}"
            val base = DbClock.nowLocalDateTime()
            val windowStart = (base.toInstant(zone) + windowStartOffsetSeconds.seconds).toLocalDateTime(zone)
            val made = mutableListOf<Target>()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = memberId
                    it[displayName] = "Retry Test"
                    it[email] = "retry-$memberId@example.org"
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
                    it[title] = "Retry-Raum"
                    it[description] = ""
                    it[livekitRoomName] = "lc-retry-$roomId"
                    it[createdByMemberId] = memberId
                    it[createdAt] = base
                    it[endedAt] = null
                    it[maxParticipants] = 25
                }
                ConferenceStreamTable.insert {
                    it[ConferenceStreamTable.id] = streamId
                    it[ConferenceStreamTable.roomId] = roomId
                    it[startedByMemberId] = memberId
                    it[ConferenceStreamTable.status] = streamStatus
                    it[layout] = ConferenceStreamLayout.GRID
                    it[latencyMode] = ConferenceStreamLatencyMode.STANDARD
                    it[participantIdentity] = null
                    it[livekitEgressId] = egressId
                    it[startedAt] = base
                    it[restartCount] = 0
                }
                repeat(targetCount) { n ->
                    val destId = Uuid.random()
                    val targetId = Uuid.random()
                    val keyText = "dummy-key-$n-${destId.toString().take(6)}"
                    ConferenceStreamDestinationTable.insert {
                        it[ConferenceStreamDestinationTable.id] = destId
                        it[label] = "retry-dest-$destId"
                        it[platform] = ConferenceStreamPlatform.GENERIC_RTMP
                        it[rtmpUrl] = "rtmp://sink$n.example.org:1935/live"
                        it[keyKind] = ConferenceStreamKeyKind.PERSISTENT
                        it[streamKeyCiphertext] = box.seal(plaintext = keyText, aad = destId.toString())
                        it[streamKeySetAt] = base
                        it[createdByMemberId] = memberId
                        it[createdAt] = base
                        it[enabled] = true
                    }
                    destinationIds += destId
                    val url = "rtmp://sink$n.example.org:1935/live/$keyText"
                    ConferenceStreamTargetTable.insert {
                        it[ConferenceStreamTargetTable.id] = targetId
                        it[ConferenceStreamTargetTable.streamId] = streamId
                        it[destinationId] = destId
                        it[ConferenceStreamTargetTable.status] = ConferenceStreamTargetStatus.PENDING
                        it[urlFingerprint] = StreamUrlFingerprint.of(url)
                        it[retries] = 0
                        it[retryWindowStartedAt] = windowStart
                    }
                    made += Target(targetId, url)
                }
            }
            memberIds += memberId
            roomIds += roomId
            return Fixture("lc-retry-$roomId", roomId, streamId, egressId, made, base)
        }

        fun nanosAt(t: LocalDateTime): String = (t.toInstant(zone).toEpochMilliseconds() * 1_000_000L).toString()

        fun stream(
            f: Fixture,
            vararg results: LiveKitStreamInfo,
        ) = LiveKitEgressInfo(egressId = f.egressId, status = "EGRESS_ACTIVE", streamResults = results.toList())

        fun active(t: Target) = LiveKitStreamInfo(url = t.fingerprint, status = "ACTIVE", startedAtEpochNanos = "1")

        fun failed(
            t: Target,
            endedAt: LocalDateTime,
            error: String = "Failed to connect: Connection refused",
        ) = LiveKitStreamInfo(
            url = t.fingerprint,
            status = "FAILED",
            error = error,
            startedAtEpochNanos = nanosAt(endedAt),
            endedAtEpochNanos = nanosAt(endedAt),
        )

        fun row(t: Target) =
            transaction { ConferenceStreamTargetTable.selectAll().where { ConferenceStreamTargetTable.id eq t.id }.single() }

        fun plus(
            t: LocalDateTime,
            seconds: Int,
        ) = (t.toInstant(zone) + seconds.seconds).toLocalDateTime(zone)

        fun poller(
            egress: RetryEgress,
            now: () -> LocalDateTime,
        ) = StreamPoller(liveKitEgressClient = egress, streamingConfig = config, clock = now)

        test(
            "a target that fails right after the start is planned for a retry; the retry re-adds exactly its own output and starts no egress",
        ) {
            val f = fixture()
            val (fb, yt) = f.targets
            val egress = RetryEgress()
            var now = f.base
            val p = poller(egress) { now }
            // 0.2 seconds after the start LiveKit reports Facebook as failed, YouTube as active.
            now = plus(f.base, 1)
            egress.resultsByRoom[f.roomName] = stream(f, failed(fb, now), active(yt))
            p.tick()
            row(fb)[ConferenceStreamTargetTable.status] shouldBe ConferenceStreamTargetStatus.PENDING
            row(fb)[ConferenceStreamTargetTable.autoRetryAttempt] shouldBe 1
            row(fb)[ConferenceStreamTargetTable.failureKind] shouldBe ConferenceStreamFailureKind.CONNECTION
            row(fb)[ConferenceStreamTargetTable.autoRetryNextAt] shouldBe plus(f.base, 6)
            row(yt)[ConferenceStreamTargetTable.status] shouldBe ConferenceStreamTargetStatus.ACTIVE
            egress.updates.size shouldBe 0
            // Too early: nothing happens, the planned row stays as planned although LiveKit still reports the old failure.
            now = plus(f.base, 4)
            p.tick()
            egress.updates.size shouldBe 0
            row(fb)[ConferenceStreamTargetTable.status] shouldBe ConferenceStreamTargetStatus.PENDING
            row(fb)[ConferenceStreamTargetTable.autoRetryAttempt] shouldBe 1
            // Due: exactly one UpdateStream with the same URL removed and added, for this target only.
            now = plus(f.base, 7)
            p.tick()
            egress.adds.size shouldBe 1
            // The old output is removed (best effort), then the same URL is added -- both on the RUNNING egress, for this target only.
            egress.updates.map { it.egressId }.toSet() shouldBe setOf(f.egressId)
            egress.updates.flatMap { it.removeUrls } shouldBe listOf(fb.url)
            egress.updates.flatMap { it.addUrls } shouldBe listOf(fb.url)
            row(fb)[ConferenceStreamTargetTable.autoRetryIssuedAt] shouldNotBe null
            row(fb)[ConferenceStreamTargetTable.autoRetryNextAt] shouldBe null
        }

        test("a fresh ACTIVE result ends the retry; the counter stays for the display and the failure fields are cleared") {
            val f = fixture()
            val (fb, yt) = f.targets
            val egress = RetryEgress()
            var now = plus(f.base, 1)
            val p = poller(egress) { now }
            egress.resultsByRoom[f.roomName] = stream(f, failed(fb, now), active(yt))
            p.tick()
            now = plus(f.base, 7)
            p.tick() // issues
            now = plus(f.base, 9)
            egress.resultsByRoom[f.roomName] = stream(f, active(fb), active(yt))
            p.tick()
            row(fb)[ConferenceStreamTargetTable.status] shouldBe ConferenceStreamTargetStatus.ACTIVE
            row(fb)[ConferenceStreamTargetTable.autoRetryAttempt] shouldBe 1
            row(fb)[ConferenceStreamTargetTable.failureKind] shouldBe null
            row(fb)[ConferenceStreamTargetTable.autoRetryIssuedAt] shouldBe null
            row(fb)[ConferenceStreamTargetTable.failureReason] shouldBe null
        }

        test(
            "an old failure that LiveKit still reports after the attempt is ignored; with no fresh result the attempt times out after 30 seconds",
        ) {
            val f = fixture()
            val (fb, yt) = f.targets
            val egress = RetryEgress()
            var now = plus(f.base, 1)
            val p = poller(egress) { now }
            val old = failed(fb, now)
            egress.resultsByRoom[f.roomName] = stream(f, old, active(yt))
            p.tick()
            now = plus(f.base, 7)
            p.tick() // attempt 1 issued at +7
            now = plus(f.base, 20)
            p.tick() // stale result only, 13 seconds: nothing
            row(fb)[ConferenceStreamTargetTable.status] shouldBe ConferenceStreamTargetStatus.PENDING
            row(fb)[ConferenceStreamTargetTable.autoRetryAttempt] shouldBe 1
            egress.adds.size shouldBe 1
            // 31 seconds after the issue -> TIMEOUT; the window anchor is the last issue, so the next attempt is still planned
            now = plus(f.base, 38)
            p.tick()
            row(fb)[ConferenceStreamTargetTable.failureKind] shouldBe ConferenceStreamFailureKind.TIMEOUT
            row(fb)[ConferenceStreamTargetTable.autoRetryAttempt] shouldBe 2
            row(fb)[ConferenceStreamTargetTable.status] shouldBe ConferenceStreamTargetStatus.PENDING
        }

        test("three failed attempts end FAILED with the reason and attempt 3, and there is no fourth call") {
            val f = fixture()
            val (fb, yt) = f.targets
            val egress = RetryEgress()
            var now = plus(f.base, 1)
            val p = poller(egress) { now }
            egress.resultsByRoom[f.roomName] = stream(f, failed(fb, now), active(yt))
            p.tick() // planned #1 at +6
            repeat(3) { n ->
                now = plus(now, 10)
                p.tick() // issue #n+1
                now = plus(now, 2)
                egress.resultsByRoom[f.roomName] = stream(f, failed(fb, now), active(yt))
                p.tick() // fresh failure after the attempt
                if (n < 2) {
                    row(fb)[ConferenceStreamTargetTable.status] shouldBe ConferenceStreamTargetStatus.PENDING
                    now = plus(now, 40)
                }
            }
            egress.adds.size shouldBe 3
            row(fb)[ConferenceStreamTargetTable.status] shouldBe ConferenceStreamTargetStatus.FAILED
            row(fb)[ConferenceStreamTargetTable.autoRetryAttempt] shouldBe 3
            row(fb)[ConferenceStreamTargetTable.failureReason] shouldBe FAILURE_CONNECTION
            repeat(3) {
                now = plus(now, 60)
                p.tick()
            }
            egress.adds.size shouldBe 3
        }

        test("a refused key is FAILED at once and never retried") {
            val f = fixture()
            val (fb, yt) = f.targets
            val egress = RetryEgress()
            val now = plus(f.base, 1)
            egress.resultsByRoom[f.roomName] = stream(f, failed(fb, now, error = "403 Forbidden"), active(yt))
            poller(egress) { now }.tick()
            row(fb)[ConferenceStreamTargetTable.status] shouldBe ConferenceStreamTargetStatus.FAILED
            row(fb)[ConferenceStreamTargetTable.failureKind] shouldBe ConferenceStreamFailureKind.REJECTED
            row(fb)[ConferenceStreamTargetTable.failureReason] shouldBe FAILURE_REJECTED
            row(fb)[ConferenceStreamTargetTable.autoRetryAttempt] shouldBe 0
            egress.updates.size shouldBe 0
        }

        test("a failure outside the 45 second window is FAILED at once") {
            val f = fixture(windowStartOffsetSeconds = -100)
            val (fb, yt) = f.targets
            val egress = RetryEgress()
            val now = plus(f.base, 1)
            egress.resultsByRoom[f.roomName] = stream(f, failed(fb, now), active(yt))
            poller(egress) { now }.tick()
            row(fb)[ConferenceStreamTargetTable.status] shouldBe ConferenceStreamTargetStatus.FAILED
            row(fb)[ConferenceStreamTargetTable.autoRetryAttempt] shouldBe 0
        }

        test("an UpdateStream that is refused counts at once as a failed attempt (UNKNOWN) and the next attempt is planned") {
            val f = fixture()
            val (fb, yt) = f.targets
            val egress = RetryEgress()
            var now = plus(f.base, 1)
            val p = poller(egress) { now }
            egress.resultsByRoom[f.roomName] = stream(f, failed(fb, now), active(yt))
            p.tick()
            egress.failAdd = true
            now = plus(f.base, 8)
            p.tick()
            egress.updates.count { it.addUrls.isNotEmpty() } shouldBe 1
            row(fb)[ConferenceStreamTargetTable.failureKind] shouldBe ConferenceStreamFailureKind.UNKNOWN
            row(fb)[ConferenceStreamTargetTable.autoRetryAttempt] shouldBe 2
            row(fb)[ConferenceStreamTargetTable.status] shouldBe ConferenceStreamTargetStatus.PENDING
            row(fb)[ConferenceStreamTargetTable.autoRetryIssuedAt] shouldBe null
        }

        test("a stream that is no longer LIVE gets no call, even with an attempt that is due") {
            val f = fixture(streamStatus = ConferenceStreamStatus.PAUSED)
            val (fb, _) = f.targets
            transaction {
                ConferenceStreamTargetTable.update({ ConferenceStreamTargetTable.id eq fb.id }) {
                    it[autoRetryAttempt] = 1
                    it[autoRetryNextAt] = f.base
                }
            }
            val egress = RetryEgress()
            poller(egress) { plus(f.base, 30) }.tick()
            egress.updates.size shouldBe 0
        }

        test("the stream ending turns a target that never recovered into FAILED, not FINISHED") {
            val f = fixture()
            val (fb, yt) = f.targets
            val egress = RetryEgress()
            val now = plus(f.base, 1)
            egress.resultsByRoom[f.roomName] = stream(f, failed(fb, now), active(yt))
            poller(egress) { now }.tick()
            transaction {
                ConferenceRoomTable.update({ ConferenceRoomTable.id eq f.roomId }) { it[endedAt] = DbClock.nowLocalDateTime() }
            }
            poller(egress) { plus(now, 2) }.tick()
            row(fb)[ConferenceStreamTargetTable.status] shouldBe ConferenceStreamTargetStatus.FAILED
            row(fb)[ConferenceStreamTargetTable.failureReason] shouldBe FAILURE_CONNECTION
            row(yt)[ConferenceStreamTargetTable.status] shouldBe ConferenceStreamTargetStatus.FINISHED
            row(fb)[ConferenceStreamTargetTable.autoRetryNextAt] shouldBe null
        }

        test("two ticks at the same time issue a due attempt exactly once") {
            val f = fixture()
            val (fb, yt) = f.targets
            val egress = RetryEgress()
            var now = plus(f.base, 1)
            egress.resultsByRoom[f.roomName] = stream(f, failed(fb, now), active(yt))
            poller(egress) { now }.tick()
            now = plus(f.base, 8)
            val one = poller(egress) { now }
            val two = poller(egress) { now }
            coroutineScope {
                listOf(async(Dispatchers.IO) { one.tick() }, async(Dispatchers.IO) { two.tick() }).awaitAll()
            }
            egress.adds.size shouldBe 1
        }

        test("a server restart: a new poller executes the attempt that was due, and an issued attempt without a result converges") {
            val f = fixture()
            val (fb, yt) = f.targets
            val egress = RetryEgress()
            egress.resultsByRoom[f.roomName] = stream(f, active(yt), failed(fb, plus(f.base, 1)))
            transaction {
                ConferenceStreamTargetTable.update({ ConferenceStreamTargetTable.id eq fb.id }) {
                    it[status] = ConferenceStreamTargetStatus.PENDING
                    it[autoRetryAttempt] = 1
                    it[failureKind] = ConferenceStreamFailureKind.CONNECTION
                    it[autoRetryNextAt] = plus(f.base, 5)
                }
            }
            poller(egress) { plus(f.base, 10) }.tick()
            egress.adds.size shouldBe 1
            // The process died after the claim: the issue has no fresh result; 31 seconds later it is a TIMEOUT and the next attempt is planned.
            poller(egress) { plus(f.base, 41) }.tick()
            row(fb)[ConferenceStreamTargetTable.failureKind] shouldBe ConferenceStreamFailureKind.TIMEOUT
            row(fb)[ConferenceStreamTargetTable.autoRetryAttempt] shouldBe 2
        }

        test(
            "an issued attempt whose fingerprint LiveKit does not list at all (new key, crash before the call) still times out after 30 seconds",
        ) {
            val f = fixture()
            val (fb, yt) = f.targets
            val egress = RetryEgress()
            // stream_results holds no entry at all for the target (e.g. a manual reconnect with a new key whose UpdateStream never happened)
            egress.resultsByRoom[f.roomName] = stream(f, active(yt))
            transaction {
                ConferenceStreamTargetTable.update({ ConferenceStreamTargetTable.id eq fb.id }) {
                    it[status] = ConferenceStreamTargetStatus.PENDING
                    it[autoRetryAttempt] = 1
                    it[failureKind] = ConferenceStreamFailureKind.CONNECTION
                    it[autoRetryNextAt] = null
                    it[autoRetryIssuedAt] = plus(f.base, 7)
                }
            }
            poller(egress) { plus(f.base, 20) }.tick() // 13 seconds: nothing yet
            row(fb)[ConferenceStreamTargetTable.status] shouldBe ConferenceStreamTargetStatus.PENDING
            row(fb)[ConferenceStreamTargetTable.autoRetryAttempt] shouldBe 1
            poller(egress) { plus(f.base, 38) }.tick() // 31 seconds -> TIMEOUT, next attempt planned
            row(fb)[ConferenceStreamTargetTable.failureKind] shouldBe ConferenceStreamFailureKind.TIMEOUT
            row(fb)[ConferenceStreamTargetTable.autoRetryAttempt] shouldBe 2
        }

        test("a target without any listed result and no issued attempt stays untouched") {
            val f = fixture()
            val (fb, yt) = f.targets
            val egress = RetryEgress()
            egress.resultsByRoom[f.roomName] = stream(f, active(yt))
            poller(egress) { plus(f.base, 20) }.tick()
            row(fb)[ConferenceStreamTargetTable.status] shouldBe ConferenceStreamTargetStatus.PENDING
            row(fb)[ConferenceStreamTargetTable.autoRetryAttempt] shouldBe 0
            egress.adds.size shouldBe 0
        }
    })
